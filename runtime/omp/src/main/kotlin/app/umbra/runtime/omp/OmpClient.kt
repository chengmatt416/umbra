package app.umbra.runtime.omp

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class OmpClientConfig(
    /**
     * omp loads Bun, its natives and its tool and plugin discovery before `ready`, and finishes starting
     * after it; a slow or busy device (an emulator measured at about two and a half minutes) needs room.
     */
    val readyTimeoutMs: Long = 240_000,
    val requestTimeoutMs: Long = 90_000,
    /** `prompt` is acknowledged after admission, which can include vision description (capped at 20 s). */
    val promptAckTimeoutMs: Long = 120_000,
    val negotiateV2: Boolean = true,
)

/**
 * Host side of the omp RPC protocol (`omp --mode rpc-ui`, ADR 0001). One client drives one omp process
 * and one live session.
 *
 * Threading model: a reader coroutine owns stdout and never blocks on user code. Host-tool calls and
 * dialogs run in their own coroutines, so a slow tool or an unanswered approval never stops events,
 * responses, or the `abort` that may be needed to end them.
 *
 * Collect [events] before [start]; events emitted while nobody collects are not replayed.
 */
class OmpClient(
    private val transport: OmpTransport,
    private val scope: CoroutineScope,
    private val config: OmpClientConfig = OmpClientConfig(),
) {
    private val handlers = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    private val idCounter = AtomicLong()
    private val writeMutex = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val promptWaiters = ConcurrentHashMap<String, CompletableDeferred<PromptResult>>()
    private val hostToolJobs = ConcurrentHashMap<String, Job>()
    private val uiJobs = ConcurrentHashMap<String, Job>()
    private val decoder = RpcFrameDecoder()
    private val ready = CompletableDeferred<ReadyInfo>()
    private val eventChannel = Channel<OmpEvent>(Channel.UNLIMITED)

    /** Frames that need no reply (tool results and updates, dialog answers), sent in order by one writer. */
    private val outbound = Channel<JsonObject>(Channel.UNLIMITED)
    private val mutableEvents = MutableSharedFlow<OmpEvent>(extraBufferCapacity = 1024)
    private val closing = AtomicBoolean(false)
    private val disconnected = AtomicBoolean(false)

    @Volatile private var hostToolHandler: HostToolHandler? = null

    @Volatile private var hostToolNames: Set<String> = emptySet()

    @Volatile private var maxFrameBytes: Int = 1024 * 1024

    /** Answers dialogs, including tool approvals. Defaults to cancelling everything (fail closed). */
    @Volatile var uiHandler: UiRequestHandler = UiRequestHandler.DenyAll

    @Volatile var readyInfo: ReadyInfo? = null
        private set

    @Volatile var protocolVersion: Int = 1
        private set

    val events: SharedFlow<OmpEvent> = mutableEvents.asSharedFlow()

    val isClosed: Boolean get() = disconnected.get()

    /** Host-tool calls currently executing. */
    val hostToolsInFlight: Int get() = hostToolJobs.size

    // ---- lifecycle ----

    /** Starts reading, waits for `ready`, and switches to protocol v2 when the server offers it. */
    suspend fun start(): ReadyInfo {
        scope.launch(Dispatchers.Default) { for (e in eventChannel) mutableEvents.emit(e) }
        scope.launch(Dispatchers.IO) { writeLoop() }
        scope.launch(Dispatchers.IO) { readLoop() }
        val info = withTimeoutOrNull(config.readyTimeoutMs) { ready.await() }
            ?: run {
                transport.destroy()
                throw OmpTimeoutException("omp sent no ready frame within ${config.readyTimeoutMs}ms; stderr: ${summarizeStderr(transport.stderrTail)}")
            }
        readyInfo = info
        maxFrameBytes = info.maxFrameBytes
        if (config.negotiateV2 && info.supportsV2) {
            requestOk("negotiate_protocol", buildJsonObject { put("protocolVersion", 2) })
            protocolVersion = 2
        }
        return info
    }

    /**
     * Ends the session: closes stdin (omp's "host is gone" signal), keeps reading until it exits,
     * and kills it if it does not within [graceMs]. Returns the exit code, or null if it had to be killed.
     */
    suspend fun close(graceMs: Long = 5_000): Int? {
        if (closing.compareAndSet(false, true)) transport.closeInput()
        var exit = transport.awaitExit(graceMs)
        var killed = false
        if (exit == null) {
            killed = true
            transport.destroy()
            exit = transport.awaitExit(2_000)
        }
        onDisconnected(exit)
        return if (killed) null else exit
    }

    // ---- requests ----

    fun nextId(): String = "c${idCounter.incrementAndGet()}"

    /** Sends one command and waits for its response. Failures come back as `success = false`. */
    suspend fun request(
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = config.requestTimeoutMs,
        id: String = nextId(),
    ): RpcResponse {
        if (disconnected.get()) throw OmpDisconnectedException("omp has exited")
        val deferred = CompletableDeferred<RpcResponse>()
        pending[id] = deferred
        try {
            send(
                buildJsonObject {
                    put("id", id)
                    put("type", command)
                    for ((k, v) in params) put(k, v)
                },
            )
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: throw OmpTimeoutException("omp did not answer '$command' within ${timeoutMs}ms")
        } finally {
            pending.remove(id)
        }
    }

    /** Like [request] but throws [OmpCommandException] on failure and returns `data`. */
    suspend fun requestOk(
        command: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = config.requestTimeoutMs,
    ): JsonElement? {
        val response = request(command, params, timeoutMs)
        if (!response.success) throw OmpCommandException(command, response.error ?: "unknown error", response.code)
        return response.data
    }

    // ---- typed commands ----

    suspend fun newSession(): Boolean =
        !((requestOk("new_session") as? JsonObject)?.bool("cancelled") ?: false)

    /** Continues the newest non-empty session in [sessionDir], or starts a fresh one there. */
    suspend fun openSession(sessionDir: String): OpenSession {
        val data = requestOk("open_session", buildJsonObject { put("sessionDir", sessionDir) }) as? JsonObject
            ?: throw OmpCommandException("open_session", "no data")
        return OpenSession(
            cancelled = data.bool("cancelled") ?: false,
            resumed = data.bool("resumed") ?: false,
            sessionId = data.str("sessionId").orEmpty(),
            sessionFile = data.str("sessionFile"),
        )
    }

    suspend fun getState(): JsonObject = requestOk("get_state") as? JsonObject ?: JsonObject(emptyMap())

    suspend fun setModel(provider: String, modelId: String): JsonObject? =
        requestOk("set_model", buildJsonObject { put("provider", provider); put("modelId", modelId) }) as? JsonObject

    suspend fun availableModels(): List<ModelInfo> =
        ((requestOk("get_available_models") as? JsonObject)?.arr("models") ?: JsonArray(emptyList())).mapNotNull {
            (it as? JsonObject)?.let { m -> ModelInfo(m.str("provider").orEmpty(), m.str("id").orEmpty(), m.str("name")) }
        }

    suspend fun setThinkingLevel(level: String) {
        requestOk("set_thinking_level", buildJsonObject { put("level", level) })
    }

    /** Opts in to structured `ask` dialogs (otherwise omp falls back to select/editor questions). */
    suspend fun setAskDialog(enabled: Boolean): Boolean =
        (requestOk("set_ask_dialog", buildJsonObject { put("enabled", enabled) }) as? JsonObject)?.bool("enabled") ?: enabled

    /** `off`, `progress` or `events`. */
    suspend fun setSubagentSubscription(level: String) {
        requestOk("set_subagent_subscription", buildJsonObject { put("level", level) })
    }

    /** Forward only the listed event types; null forwards everything. */
    suspend fun setEventFilter(types: List<String>?) {
        requestOk(
            "set_event_filter",
            buildJsonObject { put("events", if (types == null) JsonNull else JsonArray(types.map(::JsonPrimitive))) },
        )
    }

    /**
     * Sends a prompt and returns once omp has admitted it. While the agent is streaming a
     * [streamingBehavior] is required. Await [PromptHandle.result] for the outcome.
     */
    suspend fun prompt(
        message: String,
        images: List<ContentBlock.Image> = emptyList(),
        streamingBehavior: StreamingBehavior? = null,
    ): PromptHandle = admit("prompt", message, images, streamingBehavior)

    /** Aborts the running turn, then submits [message]. */
    suspend fun abortAndPrompt(message: String, images: List<ContentBlock.Image> = emptyList()): PromptHandle =
        admit("abort_and_prompt", message, images, null)

    suspend fun steer(message: String) {
        requestOk("steer", buildJsonObject { put("message", message) })
    }

    suspend fun followUp(message: String) {
        requestOk("follow_up", buildJsonObject { put("message", message) })
    }

    suspend fun abort() {
        requestOk("abort")
    }

    private suspend fun admit(
        command: String,
        message: String,
        images: List<ContentBlock.Image>,
        streamingBehavior: StreamingBehavior?,
    ): PromptHandle {
        val id = nextId()
        val waiter = CompletableDeferred<PromptResult>()
        // Registered first: `prompt_result` is written after the response but may be read before we resume.
        promptWaiters[id] = waiter
        val response = try {
            request(
                command,
                buildJsonObject {
                    put("message", message)
                    if (images.isNotEmpty()) put("images", JsonArray(images.map { it.toJson() }))
                    streamingBehavior?.let { put("streamingBehavior", it.wire) }
                },
                timeoutMs = config.promptAckTimeoutMs,
                id = id,
            )
        } catch (e: Throwable) {
            promptWaiters.remove(id)
            throw e
        }
        if (!response.success) {
            promptWaiters.remove(id)
            throw OmpCommandException(command, response.error ?: "unknown error", response.code)
        }
        if ((response.data as? JsonObject)?.bool("agentInvoked") == false) {
            // Finished locally (a builtin slash command): no `prompt_result` will follow.
            promptWaiters.remove(id)
            waiter.complete(PromptResult(id, agentInvoked = false, status = PromptStatus.COMPLETED, error = null, sessionSettled = true))
        }
        return PromptHandle(id, waiter)
    }

    // ---- host tools ----

    /**
     * Replaces the tools the agent can call on the host. [handler] runs each call in its own
     * coroutine; cancel means omp aborted that call.
     */
    suspend fun setHostTools(tools: List<HostToolDefinition>, handler: HostToolHandler): List<String> {
        require(tools.map { it.name }.toSet().size == tools.size) { "duplicate host tool names" }
        hostToolHandler = handler
        hostToolNames = tools.map { it.name }.toSet()
        val data = requestOk("set_host_tools", buildJsonObject { put("tools", JsonArray(tools.map { it.toJson() })) }) as? JsonObject
        return data?.strList("toolNames").orEmpty()
    }

    // ---- reading ----

    private suspend fun readLoop() {
        try {
            while (true) {
                val line = transport.readLine() ?: break
                if (line.isBlank()) continue
                val element = try {
                    WireJson.parseToJsonElement(line)
                } catch (e: Exception) {
                    emit(OmpEvent.FrameError("unparseable line from omp: ${line.take(200)}"))
                    continue
                }
                val obj = element as? JsonObject ?: continue
                val frame = try {
                    decoder.push(obj) ?: continue
                } catch (e: RpcProtocolException) {
                    decoder.reset()
                    emit(OmpEvent.FrameError(e.message ?: "protocol error"))
                    continue
                }
                dispatch(frame)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(OmpEvent.FrameError("reader failed: ${e.message}"))
        } finally {
            // Runs even when this coroutine is being cancelled, so waiters are always released.
            withContext(NonCancellable) { onDisconnected(transport.awaitExit(if (closing.get()) 2_000 else 500)) }
        }
    }

    private suspend fun writeLoop() {
        for (frame in outbound) {
            try {
                send(frame)
            } catch (e: OmpDisconnectedException) {
                // omp is gone; nobody is waiting for this frame.
            } catch (e: OmpFrameTooLargeException) {
                emit(OmpEvent.FrameError("dropped an outgoing frame: ${e.message}"))
            }
        }
    }

    private fun dispatch(frame: JsonObject) {
        when (val type = frame.str("type")) {
            "ready" -> ready.complete(ReadyInfo.from(frame))
            "response" -> onResponse(frame)
            "prompt_result" -> {
                val result = OmpEvent.promptResult(frame)
                promptWaiters.remove(result.id)?.complete(result)
                emit(OmpEvent.Prompt(result))
            }
            "extension_ui_request" -> onUiRequest(frame)
            "host_tool_call" -> onHostToolCall(frame)
            "host_tool_cancel" -> frame.str("targetId")?.let { hostToolJobs[it]?.cancel() }
            "host_uri_request" -> onHostUriRequest(frame)
            "host_uri_cancel" -> Unit
            else -> emit(OmpEvent.from(frame))
        }
    }

    private fun onResponse(frame: JsonObject) {
        val response = RpcResponse.from(frame)
        val waiter = response.id?.let { pending[it] }
        if (waiter != null) {
            waiter.complete(response)
        } else if (!response.success) {
            // Uncorrelated failures (`command: "parse"`) and late errors after an accepted prompt.
            emit(OmpEvent.FrameError("omp rejected '${response.command}': ${response.error}"))
        }
    }

    // ---- dialogs ----

    private fun onUiRequest(frame: JsonObject) {
        val id = frame.str("id") ?: return
        val request: UiRequest? = when (val method = frame.str("method")) {
            "select" -> UiRequest.Select(
                id,
                frame.str("title").orEmpty(),
                frame.strList("options"),
                frame.arr("optionDetails")?.map { (it as? JsonObject)?.str("description") }.orEmpty(),
                frame.long("timeout"),
            )
            "confirm" -> UiRequest.Confirm(id, frame.str("title").orEmpty(), frame.str("message").orEmpty(), frame.long("timeout"))
            "input" -> UiRequest.Input(id, frame.str("title").orEmpty(), frame.str("placeholder"), frame.long("timeout"))
            "editor" -> UiRequest.Editor(id, frame.str("title").orEmpty(), frame.str("prefill"))
            "ask" -> UiRequest.Ask(id, frame.arr("questions")?.mapNotNull(::askQuestion).orEmpty(), frame.long("timeout"))
            "cancel" -> {
                frame.str("targetId")?.let { uiJobs[it]?.cancel() }
                return
            }
            else -> {
                emit(OmpEvent.Ui(UiNotification(method.orEmpty(), frame)))
                return
            }
        }
        request ?: return
        val job = handlers.launch {
            val response = try {
                uiHandler.handle(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                UiResponse.Cancelled()
            }
            trySend(uiResponseFrame(request, response))
        }
        uiJobs[id] = job
        job.invokeOnCompletion { uiJobs.remove(id) }
    }

    private fun askQuestion(element: JsonElement): UiRequest.AskQuestion? {
        val q = element as? JsonObject ?: return null
        return UiRequest.AskQuestion(
            id = q.str("id") ?: return null,
            question = q.str("question").orEmpty(),
            header = q.str("header"),
            options = q.arr("options")?.mapNotNull { o ->
                (o as? JsonObject)?.let { UiRequest.AskOption(it.str("label").orEmpty(), it.str("description")) }
            }.orEmpty(),
            multi = q.bool("multi") ?: false,
            recommended = q.int("recommended"),
        )
    }

    private fun uiResponseFrame(request: UiRequest, response: UiResponse): JsonObject = buildJsonObject {
        put("type", "extension_ui_response")
        put("id", request.id)
        when {
            response is UiResponse.Cancelled -> {
                put("cancelled", true)
                if (response.timedOut) put("timedOut", true)
            }
            (request is UiRequest.Select || request is UiRequest.Input || request is UiRequest.Editor) && response is UiResponse.Value ->
                put("value", response.value)
            request is UiRequest.Confirm && response is UiResponse.Confirmed -> put("confirmed", response.confirmed)
            request is UiRequest.Ask && response is UiResponse.Answers -> put(
                "answers",
                JsonArray(
                    response.answers.map { a ->
                        buildJsonObject {
                            put("id", a.id)
                            put("selectedOptions", JsonArray(a.selectedOptions.map(::JsonPrimitive)))
                            a.customInput?.let { put("customInput", it) }
                        }
                    },
                ),
            )
            // A reply of the wrong kind must never be read as consent.
            else -> put("cancelled", true)
        }
    }

    // ---- host tools ----

    private fun onHostToolCall(frame: JsonObject) {
        val id = frame.str("id") ?: return
        val call = HostToolCall(
            id = id,
            toolCallId = frame.str("toolCallId").orEmpty(),
            toolName = frame.str("toolName").orEmpty(),
            arguments = frame.obj("arguments") ?: JsonObject(emptyMap()),
        )
        val handler = hostToolHandler
        val job = handlers.launch {
            val payload = try {
                if (handler == null || call.toolName !in hostToolNames) {
                    HostToolPayload.error("Unknown host tool '${call.toolName}'")
                } else {
                    handler.execute(call) { partial -> trySend(hostToolFrame("host_tool_update", id, partial, partialKey = true)) }
                }
            } catch (e: CancellationException) {
                throw e // omp aborted the call; it no longer waits for a result
            } catch (e: Throwable) {
                HostToolPayload.error("${call.toolName} failed: ${e.message ?: e::class.simpleName}")
            }
            trySend(fitToLimit(id, payload))
        }
        hostToolJobs[id] = job
        job.invokeOnCompletion { hostToolJobs.remove(id) }
    }

    private fun hostToolFrame(type: String, id: String, payload: HostToolPayload, partialKey: Boolean = false): JsonObject = buildJsonObject {
        put("type", type)
        put("id", id)
        put(if (partialKey) "partialResult" else "result", payload.toResultJson())
        if (!partialKey && payload.isError) put("isError", true)
    }

    /** A result that does not fit the transport frame becomes a smaller one rather than a dead call. */
    private fun fitToLimit(id: String, payload: HostToolPayload): JsonObject {
        val full = hostToolFrame("host_tool_result", id, payload)
        if (frameSize(full) <= maxFrameBytes) return full
        val withoutImages = payload.copy(
            content = payload.content.filterIsInstance<ContentBlock.Text>() +
                ContentBlock.Text("[image omitted: the tool output exceeded the ${maxFrameBytes / 1024} KiB transport limit]"),
        )
        val slim = hostToolFrame("host_tool_result", id, withoutImages)
        if (frameSize(slim) <= maxFrameBytes) return slim
        return hostToolFrame(
            "host_tool_result",
            id,
            HostToolPayload.error("Tool output was ${frameSize(full)} bytes, over the ${maxFrameBytes} byte transport limit. Return less data."),
        )
    }

    private fun onHostUriRequest(frame: JsonObject) {
        val id = frame.str("id") ?: return
        trySend(
            buildJsonObject {
                put("type", "host_uri_result")
                put("id", id)
                put("isError", true)
                put("error", "This host does not serve URI schemes")
            },
        )
    }

    // ---- writing ----

    private fun frameSize(frame: JsonObject): Int = WireJson.encodeToString(JsonObject.serializer(), frame).toByteArray(Charsets.UTF_8).size + 1

    private suspend fun send(frame: JsonObject) {
        val line = WireJson.encodeToString(JsonObject.serializer(), frame)
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1
        if (bytes > maxFrameBytes) throw OmpFrameTooLargeException(bytes, maxFrameBytes)
        writeMutex.withLock { transport.writeLine(line) }
    }

    /** Queues a frame that needs no reply; one writer sends them in order. */
    private fun trySend(frame: JsonObject) {
        outbound.trySend(frame)
    }

    private fun emit(event: OmpEvent) {
        eventChannel.trySend(event)
    }

    private fun onDisconnected(exitCode: Int?) {
        if (!disconnected.compareAndSet(false, true)) return
        val gone = OmpDisconnectedException("omp exited" + (exitCode?.let { " with code $it" } ?: "") + "; " + summarizeStderr(transport.stderrTail))
        ready.completeExceptionally(gone)
        for ((_, d) in pending) d.completeExceptionally(gone)
        for ((_, w) in promptWaiters) w.completeExceptionally(gone)
        promptWaiters.clear()
        hostToolJobs.values.forEach { it.cancel() }
        uiJobs.values.forEach { it.cancel() }
        emit(OmpEvent.Closed(exitCode, transport.stderrTail))
        eventChannel.close()
        outbound.close()
    }
}

data class ModelInfo(val provider: String, val id: String, val name: String?)

data class OpenSession(val cancelled: Boolean, val resumed: Boolean, val sessionId: String, val sessionFile: String?)

private fun ContentBlock.Image.toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("data", base64)
    put("mimeType", mimeType)
}
