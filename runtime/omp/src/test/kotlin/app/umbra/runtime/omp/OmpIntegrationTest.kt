package app.umbra.runtime.omp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/**
 * Drives the real `omp --mode rpc-ui` binary (the one the product ports) against a scripted
 * OpenAI-compatible server standing in for the model. Skipped when `omp` is not installed, so CI and
 * other machines are unaffected; run locally to verify the protocol assumptions against reality.
 *
 * Everything is isolated: a throwaway HOME, config and workspace, and no network beyond loopback.
 */
class OmpIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val ompBinary: File? = listOf(
        System.getenv("OMP_BIN"),
        *System.getenv("PATH").orEmpty().split(File.pathSeparator).map { "$it/omp" }.toTypedArray(),
        "${System.getProperty("user.home")}/.bun/bin/omp",
    ).filterNotNull().map(::File).firstOrNull { it.canExecute() }

    // ---- scripted model ----

    private fun chunk(delta: String, finish: String? = null) =
        """{"id":"chatcmpl-it","object":"chat.completion.chunk","created":0,"model":"fake-model","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun quote(s: String) = JsonPrimitive(s).toString()

    private fun toolCall(name: String, arguments: String, id: String = "call_1") = sse(
        chunk("""{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"$id","type":"function","function":{"name":"$name","arguments":""}}]}"""),
        chunk("""{"tool_calls":[{"index":0,"function":{"arguments":${quote(arguments)}}}]}"""),
        chunk("{}", "tool_calls"),
        """{"id":"chatcmpl-it","object":"chat.completion.chunk","created":0,"model":"fake-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
    )

    private fun text(content: String) = sse(
        chunk("""{"role":"assistant","content":${quote(content)}}"""),
        chunk("{}", "stop"),
        """{"id":"chatcmpl-it","object":"chat.completion.chunk","created":0,"model":"fake-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
    )

    private fun sseResponse(body: String, delayMs: Long = 0) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).apply {
            if (delayMs > 0) headersDelay(delayMs, TimeUnit.MILLISECONDS)
        }.build()

    /** What the model was sent, for assertions. */
    private class ModelTraffic {
        val requests = CopyOnWriteArrayList<JsonObject>()
        val toolNamesOffered: Set<String>
            get() = requests.flatMap { r -> r["tools"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["function"]?.jsonObject?.str("name") } }.toSet()
    }

    private fun lastToolResult(request: JsonObject): String? =
        request["messages"]?.jsonArray?.lastOrNull()?.jsonObject?.takeIf { it.str("role") == "tool" }?.let {
            when (val c = it["content"]) {
                is JsonPrimitive -> c.content
                is JsonArray -> c.joinToString("") { b -> b.jsonObject.str("text").orEmpty() }
                else -> ""
            }
        }

    // ---- harness ----

    private class Session(
        val client: OmpClient,
        val events: MutableList<OmpEvent>,
        val traffic: ModelTraffic,
        val work: Path,
        val scope: CoroutineScope,
    )

    private fun withOmp(
        approvalMode: OmpApprovalMode,
        allowedHostTools: List<String>,
        script: (ModelTraffic, JsonObject) -> MockResponse,
        uiHandler: UiRequestHandler = UiRequestHandler.DenyAll,
        bashPatterns: List<BashPattern> = emptyList(),
        block: suspend Session.() -> Unit,
    ): Unit {
        assumeTrue(ompBinary != null, "omp is not installed; skipping the real-omp integration test")
        val home = Files.createDirectories(dir.resolve("home"))
        val work = Files.createDirectories(dir.resolve("work"))
        val traffic = ModelTraffic()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.encodedPath.endsWith("/chat/completions")) {
                    val body = WireJson.parseToJsonElement(request.body!!.utf8()).jsonObject
                    traffic.requests += body
                    return script(traffic, body)
                }
                return MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
            }
        }
        server.start()
        try {
            val settings = OmpSettings(
                approvalMode = approvalMode,
                toolPolicies = allowedHostTools.associateWith { ToolPolicy.ALLOW },
                bashPatterns = bashPatterns,
            )
            val provider = OmpProvider(
                "fake", "http://127.0.0.1:${server.port}/v1", auth = "none",
                models = listOf(OmpModel("fake-model", "Fake Model")),
            )
            val launch = OmpLaunch(
                executable = listOf(ompBinary!!.path),
                workingDirectory = work.toString(),
                model = "fake/fake-model",
                noSession = true,
                loadSkills = false,
                loadRules = false,
            )
            val env = mapOf(
                "HOME" to home.toString(),
                "OTEL_SDK_DISABLED" to "true",
                "PATH" to listOfNotNull(
                    "${System.getProperty("user.home")}/.bun/bin", "/opt/homebrew/bin", "/usr/local/bin", System.getenv("PATH"),
                ).joinToString(File.pathSeparator),
            )
            runBlocking<Unit> {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val started = OmpProcess.start(
                    launch, settings, listOf(provider), files = LocalAgentFiles(home.resolve(".omp/agent")), scope = scope,
                    environment = env, clientConfig = OmpClientConfig(readyTimeoutMs = 60_000, requestTimeoutMs = 30_000),
                )
                val client = started.client
                val transport = started.transport
                client.uiHandler = uiHandler
                val events = CopyOnWriteArrayList<OmpEvent>()
                scope.launch(start = CoroutineStart.UNDISPATCHED) { client.events.collect { events += it } }
                try {
                    client.start()
                    Session(client, events, traffic, work, scope).block()
                } catch (t: Throwable) {
                    System.err.println("--- omp events ---\n" + events.joinToString("\n") { it.toString().take(400) })
                    System.err.println("--- omp stderr ---\n" + transport.stderrTail.joinToString("\n"))
                    throw t
                } finally {
                    withTimeoutOrNull(15_000) { client.close(graceMs = 8_000) }
                    transport.destroy()
                    scope.cancel()
                }
            }
        } finally {
            server.close()
        }
    }

    private suspend fun Session.awaitPrompt(handle: PromptHandle, timeoutMs: Long = 60_000): PromptResult =
        withTimeout(timeoutMs) { handle.result.await() }

    // ---- tests ----

    @Test
    fun theProtocolHandshakeAndSessionStateWorkAgainstTheRealBinary(): Unit =
        withOmp(OmpApprovalMode.YOLO, emptyList(), script = { _, _ -> sseResponse(text("hi")) }) {
            val info = client.readyInfo!!
            assertTrue(info.supportsV2, "omp must offer protocol v2: $info")
            assertEquals(2, client.protocolVersion)

            val state = client.getState()
            assertEquals("fake-model", state.obj("model")?.str("id"), "the --model flag selected the scripted provider: $state")
            assertFalse(state.bool("isStreaming") ?: false)
            assertTrue(client.availableModels().any { it.provider == "fake" && it.id == "fake-model" })
        }

    @Test
    fun hostToolsReachTheModelAndRoundTrip(): Unit {
        val seenArgs = CompletableDeferred<JsonObject>()
        withOmp(
            approvalMode = OmpApprovalMode.WRITE,
            allowedHostTools = listOf("android_observe"),
            script = { _, request ->
                val result = lastToolResult(request)
                if (result == null) {
                    sseResponse(toolCall("android_observe", """{"level":"brief"}"""))
                } else {
                    sseResponse(text("The foreground app is: ${result.take(80)}"))
                }
            },
        ) {
            val registered = client.setHostTools(
                listOf(
                    HostToolDefinition(
                        "android_observe", "Observe the Android screen",
                        objectSchema { enum("level", listOf("brief", "tree"), "How much to return", required = true) },
                    ),
                ),
            ) { call, _ ->
                seenArgs.complete(call.arguments)
                HostToolPayload.text("com.example.crashy / LoginActivity")
            }
            assertEquals(listOf("android_observe"), registered)

            val handle = client.prompt("What is on the screen?")
            val result = awaitPrompt(handle)
            assertEquals(PromptStatus.COMPLETED, result.status, "prompt result: $result")

            assertTrue("android_observe" in traffic.toolNamesOffered, "the host tool must be in the model's tool list: ${traffic.toolNamesOffered}")
            assertEquals("brief", seenArgs.await().str("level"))
            val finalText = events.filterIsInstance<OmpEvent.MessageEnd>().filter { it.role == "assistant" }.last().text
            assertTrue("com.example.crashy / LoginActivity" in finalText, finalText)
            val end = events.filterIsInstance<OmpEvent.ToolEnd>().single { it.toolName == "android_observe" }
            assertFalse(end.isError)
        }
    }

    @Test
    fun aDeniedApprovalStopsTheToolAndTheModelSeesTheDenial(): Unit {
        val dialogs = CopyOnWriteArrayList<UiRequest>()
        val marker by lazy { dir.resolve("work/marker-denied") }
        withOmp(
            approvalMode = OmpApprovalMode.ALWAYS_ASK,
            allowedHostTools = emptyList(),
            script = { _, request ->
                val result = lastToolResult(request)
                if (result == null) {
                    sseResponse(toolCall("bash", """{"command":"touch marker-denied"}"""))
                } else {
                    sseResponse(text("I could not run it: ${result.take(100)}"))
                }
            },
            uiHandler = UiRequestHandler { req ->
                dialogs += req
                if (req is UiRequest.Select) UiResponse.Value("Deny") else UiResponse.Cancelled()
            },
        ) {
            val handle = client.prompt("create the marker file")
            val result = awaitPrompt(handle)
            assertEquals(PromptStatus.COMPLETED, result.status, "prompt result: $result")

            val select = dialogs.filterIsInstance<UiRequest.Select>().single()
            assertEquals(listOf("Approve", "Deny"), select.options)
            assertTrue("Allow tool: bash" in select.title, select.title)
            assertTrue("touch marker-denied" in select.title, "the command must be visible for classification: ${select.title}")
            assertFalse(marker.exists(), "a denied command must not run")
            val toolEnd = events.filterIsInstance<OmpEvent.ToolEnd>().single { it.toolName == "bash" }
            assertTrue(toolEnd.isError)
        }
    }

    @Test
    fun anApprovedApprovalRunsTheCommand(): Unit {
        val marker by lazy { dir.resolve("work/marker-approved") }
        withOmp(
            approvalMode = OmpApprovalMode.ALWAYS_ASK,
            allowedHostTools = emptyList(),
            script = { _, request ->
                if (lastToolResult(request) == null) sseResponse(toolCall("bash", """{"command":"touch marker-approved && echo created"}"""))
                else sseResponse(text("done"))
            },
            uiHandler = UiRequestHandler { req -> if (req is UiRequest.Select) UiResponse.Value("Approve") else UiResponse.Cancelled() },
        ) {
            val result = awaitPrompt(client.prompt("create the marker file"))
            assertEquals(PromptStatus.COMPLETED, result.status, "prompt result: $result")
            assertTrue(marker.exists(), "an approved command runs in the workspace")
        }
    }

    @Test
    fun withoutAnApprovalHandlerOmpFailsClosed(): Unit {
        val marker by lazy { dir.resolve("work/marker-closed") }
        withOmp(
            approvalMode = OmpApprovalMode.ALWAYS_ASK,
            allowedHostTools = emptyList(),
            script = { _, request ->
                if (lastToolResult(request) == null) sseResponse(toolCall("bash", """{"command":"touch marker-closed"}"""))
                else sseResponse(text("could not run"))
            },
            // The default handler cancels every dialog.
        ) {
            val result = awaitPrompt(client.prompt("create the marker file"))
            assertEquals(PromptStatus.COMPLETED, result.status)
            assertFalse(marker.exists(), "an unanswered approval must never run the command")
        }
    }

    @Test
    fun desktopOnlyBuiltinsAreNotOfferedToTheModel(): Unit =
        withOmp(OmpApprovalMode.WRITE, emptyList(), script = { _, _ -> sseResponse(text("ok")) }) {
            assertEquals(PromptStatus.COMPLETED, awaitPrompt(client.prompt("hello")).status)
            val offered = traffic.toolNamesOffered
            assertTrue("bash" in offered, "the shell stays available for the Linux CLI side: $offered")
            assertTrue("read" in offered, "file tools stay available: $offered")
            assertFalse("browser" in offered, "the desktop browser tool must be replaced by host tools: $offered")
            assertFalse("computer" in offered, "the desktop computer-use tool must be replaced by host tools: $offered")
        }

    @Test
    fun aDenyPatternBlocksTheCommandEvenInYoloMode(): Unit {
        val marker by lazy { dir.resolve("work/forbidden-file") }
        withOmp(
            approvalMode = OmpApprovalMode.YOLO,
            allowedHostTools = emptyList(),
            bashPatterns = OmpSettings.PRODUCT_BASH_PATTERNS + BashPattern("touch forbidden*", ToolPolicy.DENY),
            script = { _, request ->
                if (lastToolResult(request) == null) sseResponse(toolCall("bash", """{"command":"touch forbidden-file"}"""))
                else sseResponse(text("blocked"))
            },
        ) {
            assertEquals(PromptStatus.COMPLETED, awaitPrompt(client.prompt("make the forbidden file")).status)
            assertFalse(marker.exists(), "a deny pattern is absolute: yolo mode must not run the command")
            assertTrue(events.filterIsInstance<OmpEvent.ToolEnd>().single { it.toolName == "bash" }.isError)
        }
    }

    @Test
    fun bashPatternsMatchWholeCommandsAndAStarMatchesAnythingIncludingSlashes(): Unit {
        // The product patterns are written for this behaviour (OmpConfigTest models it); harmless stand-ins prove it.
        val expectations = listOf(
            "echo blocked-exact" to true,
            "echo blocked-exact extra" to false,
            "echo blocked-exactX" to false,
            "echo wild/deeper/path" to true,
            "echo 1; echo blocked-exact" to true,
            "cd . && echo blocked-exact" to true,
            "true; echo wild-a" to true,
            "echo ok-control" to false,
        )
        withOmp(
            approvalMode = OmpApprovalMode.YOLO,
            allowedHostTools = emptyList(),
            bashPatterns = listOf(BashPattern("echo blocked-exact", ToolPolicy.DENY), BashPattern("echo wild*", ToolPolicy.DENY)),
            script = { traffic, _ ->
                val done = traffic.requests.count { r -> lastToolResult(r) != null }
                if (done < expectations.size) sseResponse(toolCall("bash", """{"command":${quote(expectations[done].first)}}""", id = "call_$done"))
                else sseResponse(text("finished"))
            },
        ) {
            assertEquals(PromptStatus.COMPLETED, awaitPrompt(client.prompt("probe"), timeoutMs = 120_000).status)
            val ends = events.filterIsInstance<OmpEvent.ToolEnd>().filter { it.toolName == "bash" }
            assertEquals(expectations.size, ends.size)
            expectations.zip(ends).forEach { (expected, end) -> assertEquals(expected.second, end.isError, "blocked? '${expected.first}'") }
        }
    }

    @Test
    fun abortEndsARunningTurnWithAnAbortedPromptResult(): Unit =
        withOmp(
            approvalMode = OmpApprovalMode.WRITE,
            allowedHostTools = listOf("android_observe"),
            script = { _, _ -> sseResponse(text("never delivered"), delayMs = 60_000) },
        ) {
            val handle = client.prompt("think very hard")
            // Wait until the model request is actually in flight.
            val deadline = System.currentTimeMillis() + 20_000
            while (traffic.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(50)
            assertTrue(traffic.requests.isNotEmpty(), "omp never called the model")

            client.abort()
            val result = awaitPrompt(handle, timeoutMs = 30_000)
            assertEquals(PromptStatus.ABORTED, result.status, "prompt result: $result")
        }

    @Test
    fun aSlowHostToolIsCancelledWhenTheTurnIsAborted(): Unit {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        withOmp(
            approvalMode = OmpApprovalMode.WRITE,
            allowedHostTools = listOf("android_wait"),
            script = { _, _ -> sseResponse(toolCall("android_wait", "{}")) },
        ) {
            client.setHostTools(listOf(HostToolDefinition("android_wait", "Waits", objectSchema { }))) { _, _ ->
                started.complete(Unit)
                try {
                    delay(120_000)
                    HostToolPayload.text("never")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.complete(true)
                    throw e
                }
            }
            val handle = client.prompt("wait for the screen")
            withTimeout(30_000) { started.await() }
            assertEquals(1, client.hostToolsInFlight)
            client.abort()
            assertTrue(withTimeout(20_000) { cancelled.await() }, "omp sends host_tool_cancel when the turn is aborted")
            assertEquals(PromptStatus.ABORTED, awaitPrompt(handle, 30_000).status)
        }
    }

    @Test
    fun closingStdinShutsOmpDownCleanly(): Unit =
        withOmp(OmpApprovalMode.YOLO, emptyList(), script = { _, _ -> sseResponse(text("hi")) }) {
            val code = client.close(graceMs = 15_000)
            assertEquals(0, code, "omp must exit 0 when the host closes its stdin")
            // The Closed event travels through a channel to the collector; wait for it.
            val closed = withTimeout(5_000) {
                var hit: OmpEvent.Closed? = null
                while (hit == null) {
                    hit = events.filterIsInstance<OmpEvent.Closed>().firstOrNull()
                    if (hit == null) delay(20)
                }
                hit
            }
            assertEquals(0, closed.exitCode)
        }
}
