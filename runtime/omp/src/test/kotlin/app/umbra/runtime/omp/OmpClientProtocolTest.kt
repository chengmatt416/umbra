package app.umbra.runtime.omp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class OmpClientProtocolTest {
    // ---- handshake ----

    @Test
    fun negotiatesProtocolV2WhenOffered(): Unit = harness {
        val info = handshake(v2 = true)
        assertTrue(info.supportsV2)
        assertEquals(2, client.protocolVersion)
        assertEquals(1_048_576, info.maxFrameBytes)
    }

    @Test
    fun staysOnV1WhenTheServerDoesNotOfferV2(): Unit = harness {
        val info = handshake(v2 = false)
        assertFalse(info.supportsV2)
        assertEquals(1, client.protocolVersion)
        assertTrue(transport.sent.isEmpty(), "nothing is sent when there is nothing to negotiate")
    }

    @Test
    fun startFailsClearlyWhenNoReadyFrameArrives(): Unit = harness(OmpClientConfig(readyTimeoutMs = 150)) {
        val e = assertFailsWith<OmpTimeoutException> { client.start() }
        assertTrue("ready" in e.message!!, e.message)
    }

    // ---- requests ----

    @Test
    fun responsesAreCorrelatedByIdEvenWhenAnsweredOutOfOrder(): Unit = harness {
        handshake()
        val a = async(Dispatchers.Default) { client.request("get_state") }
        val b = async(Dispatchers.Default) { client.request("get_available_commands") }
        val first = fake.nextCommand()
        val second = fake.nextCommand()
        // Answer in the opposite order.
        fake.respond(second.str("id"), second.str("type")!!, """{"which":"${second.str("type")}"}""")
        fake.respond(first.str("id"), first.str("type")!!, """{"which":"${first.str("type")}"}""")
        val ra = a.await()
        val rb = b.await()
        assertEquals("get_state", (ra.data as JsonObject).str("which"))
        assertEquals("get_available_commands", (rb.data as JsonObject).str("which"))
    }

    @Test
    fun failedCommandsBecomeTypedExceptionsWithTheCode(): Unit = harness {
        handshake()
        val call = async(Dispatchers.Default) { runCatching { client.setModel("nope", "x") } }
        val cmd = fake.nextCommand()
        assertEquals("set_model", cmd.str("type"))
        assertEquals("nope", cmd.str("provider"))
        fake.fail(cmd.str("id"), "set_model", "Model not found: nope/x", code = "model_not_found")
        val e = call.await().exceptionOrNull()
        assertIs<OmpCommandException>(e)
        assertEquals("model_not_found", e.code)
        assertTrue("Model not found" in e.message)
    }

    @Test
    fun anUnansweredRequestTimesOut(): Unit = harness {
        handshake()
        val e = assertFailsWith<OmpTimeoutException> { client.request("get_state", timeoutMs = 100) }
        assertTrue("get_state" in e.message!!)
    }

    @Test
    fun uncorrelatedFailuresSurfaceAsFrameErrors(): Unit = harness {
        handshake()
        fake.fail(null, "parse", "Failed to parse command: Unexpected token")
        val e = awaitEvent<OmpEvent.FrameError>()
        assertTrue("parse" in e.message, e.message)
    }

    @Test
    fun typedHelpersSendTheDocumentedParameters(): Unit = harness {
        handshake()
        val open = async(Dispatchers.Default) { client.openSession("/work/.sessions") }
        val cmd = fake.nextCommand()
        assertEquals("open_session", cmd.str("type"))
        assertEquals("/work/.sessions", cmd.str("sessionDir"))
        fake.respond(cmd.str("id"), "open_session", """{"cancelled":false,"resumed":true,"sessionId":"abc","sessionFile":"/work/.sessions/abc.jsonl"}""")
        assertEquals(OpenSession(false, true, "abc", "/work/.sessions/abc.jsonl"), open.await())

        val models = async(Dispatchers.Default) { client.availableModels() }
        fake.ack("""{"models":[{"provider":"anthropic","id":"claude-x","name":"Claude X"},{"provider":"local","id":"m"}]}""")
        assertEquals(listOf(ModelInfo("anthropic", "claude-x", "Claude X"), ModelInfo("local", "m", null)), models.await())

        val filter = async(Dispatchers.Default) { client.setEventFilter(listOf("agent_end", "tool_execution_end")) }
        val f = fake.ack()
        assertEquals(listOf("agent_end", "tool_execution_end"), f["events"]!!.jsonArray.map { (it as JsonPrimitive).content })
        filter.await()
        val clear = async(Dispatchers.Default) { client.setEventFilter(null) }
        assertEquals("null", fake.ack()["events"].toString())
        clear.await()
    }

    // ---- prompts ----

    @Test
    fun aPromptIsAdmittedThenCompletedByItsPromptResult(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { client.prompt("fix the login crash") }
        val cmd = fake.nextCommand()
        assertEquals("prompt", cmd.str("type"))
        assertEquals("fix the login crash", cmd.str("message"))
        assertNull(cmd["streamingBehavior"])
        fake.respond(cmd.str("id"), "prompt")
        val handle = admitted.await()
        assertEquals(cmd.str("id"), handle.id)
        assertFalse(handle.result.isCompleted)

        fake.send("""{"type":"prompt_result","id":"${handle.id}","agentInvoked":true,"status":"completed","sessionSettled":false}""")
        val result = withTimeout(2_000) { handle.result.await() }
        assertEquals(PromptResult(handle.id, true, PromptStatus.COMPLETED, null, false), result)
        assertIs<OmpEvent.Prompt>(awaitEvent<OmpEvent.Prompt>())
    }

    @Test
    fun aPromptResultThatArrivesRightAfterTheAckIsNotLost(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { client.prompt("go") }
        val cmd = fake.nextCommand()
        fake.respond(cmd.str("id"), "prompt")
        fake.send("""{"type":"prompt_result","id":"${cmd.str("id")}","agentInvoked":true,"status":"aborted","sessionSettled":true}""")
        val handle = admitted.await()
        assertEquals(PromptStatus.ABORTED, withTimeout(2_000) { handle.result.await() }.status)
    }

    @Test
    fun aLocalSlashCommandCompletesOnTheAckWithoutAPromptResult(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { client.prompt("/help") }
        val cmd = fake.nextCommand()
        fake.respond(cmd.str("id"), "prompt", """{"agentInvoked":false}""")
        val handle = admitted.await()
        val result = withTimeout(2_000) { handle.result.await() }
        assertFalse(result.agentInvoked)
        assertTrue(result.sessionSettled)
    }

    @Test
    fun aRejectedPromptThrowsAndLeavesNothingPending(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { runCatching { client.prompt("go") } }
        val cmd = fake.nextCommand()
        fake.fail(cmd.str("id"), "prompt", "Agent is streaming; specify streamingBehavior")
        val e = admitted.await().exceptionOrNull()
        assertIs<OmpCommandException>(e)
        assertTrue("streamingBehavior" in e.message)
    }

    @Test
    fun streamingBehaviorAndImagesAreSent(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { client.prompt("look", listOf(ContentBlock.Image("QUJD", "image/png")), StreamingBehavior.STEER) }
        val cmd = fake.nextCommand()
        assertEquals("steer", cmd.str("streamingBehavior"))
        val image = cmd["images"]!!.jsonArray.single().jsonObject
        assertEquals("image", image.str("type"))
        assertEquals("QUJD", image.str("data"))
        assertEquals("image/png", image.str("mimeType"))
        fake.respond(cmd.str("id"), "prompt")
        admitted.await()
    }

    @Test
    fun aPromptErrorCarriesProviderDetails(): Unit = harness {
        handshake()
        val admitted = async(Dispatchers.Default) { client.prompt("go") }
        val cmd = fake.nextCommand()
        fake.respond(cmd.str("id"), "prompt")
        val handle = admitted.await()
        fake.send(
            """{"type":"prompt_result","id":"${handle.id}","agentInvoked":true,"status":"error","sessionSettled":true,""" +
                """"error":{"message":"rate limited","provider":"anthropic","model":"claude-x","httpStatus":429,"retryable":true}}""",
        )
        val result = withTimeout(2_000) { handle.result.await() }
        assertEquals(PromptStatus.ERROR, result.status)
        assertEquals(PromptError("rate limited", "anthropic", "claude-x", 429, true), result.error)
    }

    // ---- host tools ----

    private val tapTool = HostToolDefinition(
        "android_tap", "Tap an element",
        objectSchema {
            string("element", "node id", required = true)
            integer("x"); integer("y")
        },
    )

    @Test
    fun hostToolDefinitionsAreSentAsSchemas(): Unit = harness {
        handshake()
        val registered = async(Dispatchers.Default) { client.setHostTools(listOf(tapTool.copy(loadMode = ToolLoadMode.DISCOVERABLE, label = "Tap"))) { _, _ -> HostToolPayload.text("ok") } }
        val cmd = fake.nextCommand()
        assertEquals("set_host_tools", cmd.str("type"))
        val tool = cmd["tools"]!!.jsonArray.single().jsonObject
        assertEquals("android_tap", tool.str("name"))
        assertEquals("discoverable", tool.str("loadMode"))
        assertEquals("Tap", tool.str("label"))
        val schema = tool["parameters"]!!.jsonObject
        assertEquals("object", schema.str("type"))
        assertEquals(listOf("element"), schema["required"]!!.jsonArray.map { (it as JsonPrimitive).content })
        assertEquals(setOf("element", "x", "y"), schema["properties"]!!.jsonObject.keys)
        assertEquals("false", schema["additionalProperties"].toString())
        fake.respond(cmd.str("id"), "set_host_tools", """{"toolNames":["android_tap"]}""")
        assertEquals(listOf("android_tap"), registered.await())
    }

    @Test
    fun aHostToolCallRunsTheHandlerAndSendsItsResult(): Unit = harness {
        handshake()
        val seen = CompletableDeferred<HostToolCall>()
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { call, _ -> seen.complete(call); HostToolPayload.text("tapped ${call.arguments.str("element")}", buildJsonObject { put("ok", true) }) }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()

        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"toolu_9","toolName":"android_tap","arguments":{"element":"1:3"}}""")
        val reply = fake.nextCommand()
        assertEquals("host_tool_result", reply.str("type"))
        assertEquals("h1", reply.str("id"))
        assertNull(reply["isError"])
        val result = reply["result"]!!.jsonObject
        assertEquals("text", result["content"]!!.jsonArray.single().jsonObject.str("type"))
        assertEquals("tapped 1:3", result["content"]!!.jsonArray.single().jsonObject.str("text"))
        assertEquals("true", result["details"]!!.jsonObject["ok"].toString())
        val call = seen.await()
        assertEquals("toolu_9", call.toolCallId)
        assertEquals("android_tap", call.toolName)
    }

    @Test
    fun aFailingHandlerBecomesAToolErrorNotACrash(): Unit = harness {
        handshake()
        val reg = async(Dispatchers.Default) { client.setHostTools(listOf(tapTool)) { _, _ -> error("accessibility service is not connected") } }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        val reply = fake.nextCommand()
        assertEquals("true", reply["isError"].toString())
        assertTrue("accessibility service is not connected" in reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text")!!)
        assertFalse(client.isClosed, "a handler failure must not take the client down")
    }

    @Test
    fun aToolErrorPayloadIsMarkedAsAnError(): Unit = harness {
        handshake()
        val reg = async(Dispatchers.Default) { client.setHostTools(listOf(tapTool)) { _, _ -> HostToolPayload.error("Element 1:3 is no longer on screen") } }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        val reply = fake.nextCommand()
        assertEquals("true", reply["isError"].toString())
    }

    @Test
    fun anUnknownToolIsAnErrorResult(): Unit = harness {
        handshake()
        val reg = async(Dispatchers.Default) { client.setHostTools(listOf(tapTool)) { _, _ -> HostToolPayload.text("x") } }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"rm_rf","arguments":{}}""")
        val reply = fake.nextCommand()
        assertEquals("true", reply["isError"].toString())
        assertTrue("Unknown host tool" in reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text")!!)
    }

    @Test
    fun cancellingACallCancelsTheHandlerAndSendsNoResult(): Unit = harness {
        handshake()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { _, _ ->
                started.complete(Unit)
                try {
                    delay(60_000)
                    HostToolPayload.text("never")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.complete(true)
                    throw e
                }
            }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()

        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        withTimeout(2_000) { started.await() }
        fake.send("""{"type":"host_tool_cancel","id":"x","targetId":"h1"}""")
        assertTrue(withTimeout(2_000) { cancelled.await() })
        delay(100)
        assertTrue(transport.sent.none { "host_tool_result" in it }, "a cancelled call must not answer: ${transport.sent}")
    }

    @Test
    fun progressUpdatesAreStreamedInOrderBeforeTheResult(): Unit = harness {
        handshake()
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { _, progress ->
                progress.update(HostToolPayload.text("step 1"))
                progress.update(HostToolPayload.text("step 2"))
                HostToolPayload.text("done")
            }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        val frames = (1..3).map { fake.nextCommand() }
        assertEquals(listOf("host_tool_update", "host_tool_update", "host_tool_result"), frames.map { it.str("type") })
        assertEquals("step 1", frames[0]["partialResult"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text"))
        assertEquals("step 2", frames[1]["partialResult"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text"))
    }

    @Test
    fun concurrentCallsRunConcurrently(): Unit = harness {
        handshake()
        val gate = CompletableDeferred<Unit>()
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { call, _ ->
                if (call.id == "slow") gate.await()
                HostToolPayload.text("done ${call.id}")
            }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"slow","toolCallId":"t1","toolName":"android_tap","arguments":{}}""")
        fake.send("""{"type":"host_tool_call","id":"fast","toolCallId":"t2","toolName":"android_tap","arguments":{}}""")
        val first = fake.nextCommand()
        assertEquals("fast", first.str("id"), "a slow call must not block a fast one")
        gate.complete(Unit)
        assertEquals("slow", fake.nextCommand().str("id"))
    }

    @Test
    fun anOversizedResultIsReplacedByASmallerOne(): Unit = harness {
        handshake(maxFrameBytes = 4_096)
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { _, _ ->
                HostToolPayload(listOf(ContentBlock.Text("screen captured"), ContentBlock.Image("A".repeat(10_000))))
            }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        val reply = fake.nextCommand()
        val blocks = reply["result"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "text"), blocks.map { it.str("type") }, "the image is dropped with a note")
        assertTrue("image omitted" in blocks.last().str("text")!!)
        assertNull(reply["isError"])
    }

    @Test
    fun aResultThatCannotBeShrunkBecomesAnErrorTheModelCanReadAndFix(): Unit = harness {
        handshake(maxFrameBytes = 2_048)
        val reg = async(Dispatchers.Default) { client.setHostTools(listOf(tapTool)) { _, _ -> HostToolPayload.text("x".repeat(50_000)) } }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        val reply = fake.nextCommand()
        assertEquals("true", reply["isError"].toString())
        assertTrue("Return less data" in reply["result"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject.str("text")!!)
    }

    @Test
    fun uriRequestsAreAnsweredWithAnError(): Unit = harness {
        handshake()
        fake.send("""{"type":"host_uri_request","id":"u1","operation":"read","url":"skill://x"}""")
        val reply = fake.nextCommand()
        assertEquals("host_uri_result", reply.str("type"))
        assertEquals("u1", reply.str("id"))
        assertEquals("true", reply["isError"].toString())
    }

    // ---- dialogs and approvals ----

    @Test
    fun anApprovalSelectIsAnsweredWithTheChosenOption(): Unit = harness {
        handshake()
        client.uiHandler = UiRequestHandler { req ->
            assertIs<UiRequest.Select>(req)
            assertEquals(listOf("Approve", "Deny"), req.options)
            assertTrue("Allow tool: bash" in req.title)
            UiResponse.Value("Approve")
        }
        fake.send("""{"type":"extension_ui_request","id":"ui1","method":"select","title":"Allow tool: bash\nCommand: ls -la","options":["Approve","Deny"]}""")
        val reply = fake.nextCommand()
        assertEquals("extension_ui_response", reply.str("type"))
        assertEquals("ui1", reply.str("id"))
        assertEquals("Approve", reply.str("value"))
    }

    @Test
    fun withoutAHandlerEveryDialogIsCancelled(): Unit = harness {
        handshake()
        for ((i, method) in listOf("select", "confirm", "input", "editor").withIndex()) {
            val extra = when (method) {
                "select" -> ""","options":["Approve","Deny"]"""
                "confirm" -> ""","message":"m""""
                else -> ""
            }
            fake.send("""{"type":"extension_ui_request","id":"d$i","method":"$method","title":"t"$extra}""")
            val reply = fake.nextCommand()
            assertEquals("d$i", reply.str("id"))
            assertEquals("true", reply["cancelled"].toString(), "$method must fail closed")
        }
    }

    @Test
    fun confirmInputEditorAndAskAreAnsweredInTheirOwnFormat(): Unit = harness {
        handshake()
        client.uiHandler = UiRequestHandler { req ->
            when (req) {
                is UiRequest.Confirm -> UiResponse.Confirmed(true)
                is UiRequest.Input -> UiResponse.Value("typed")
                is UiRequest.Editor -> UiResponse.Value("edited")
                is UiRequest.Ask -> UiResponse.Answers(req.questions.map { AskAnswer(it.id, it.options.take(1).map { o -> o.label }, null) })
                else -> UiResponse.Cancelled()
            }
        }
        fake.send("""{"type":"extension_ui_request","id":"c","method":"confirm","title":"t","message":"m"}""")
        assertEquals("true", fake.nextCommand()["confirmed"].toString())
        fake.send("""{"type":"extension_ui_request","id":"i","method":"input","title":"t"}""")
        assertEquals("typed", fake.nextCommand().str("value"))
        fake.send("""{"type":"extension_ui_request","id":"e","method":"editor","title":"t"}""")
        assertEquals("edited", fake.nextCommand().str("value"))
        fake.send(
            """{"type":"extension_ui_request","id":"a","method":"ask","questions":[{"id":"db","question":"Which?","options":[{"label":"Postgres"},{"label":"SQLite"}],"recommended":1,"multi":false}]}""",
        )
        val answers = fake.nextCommand()["answers"]!!.jsonArray.single().jsonObject
        assertEquals("db", answers.str("id"))
        assertEquals(listOf("Postgres"), answers["selectedOptions"]!!.jsonArray.map { (it as JsonPrimitive).content })
    }

    @Test
    fun aHandlerReplyOfTheWrongKindIsNeverTreatedAsConsent(): Unit = harness {
        handshake()
        client.uiHandler = UiRequestHandler { UiResponse.Confirmed(true) } // wrong kind for a select
        fake.send("""{"type":"extension_ui_request","id":"s","method":"select","title":"t","options":["Approve","Deny"]}""")
        val reply = fake.nextCommand()
        assertEquals("true", reply["cancelled"].toString())
        assertNull(reply["value"])
        assertNull(reply["confirmed"])
    }

    @Test
    fun aThrowingHandlerFailsClosed(): Unit = harness {
        handshake()
        client.uiHandler = UiRequestHandler { error("ui crashed") }
        fake.send("""{"type":"extension_ui_request","id":"s","method":"select","title":"t","options":["Approve","Deny"]}""")
        assertEquals("true", fake.nextCommand()["cancelled"].toString())
    }

    @Test
    fun aCancelRequestClosesTheOpenDialog(): Unit = harness {
        handshake()
        val open = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Boolean>()
        client.uiHandler = UiRequestHandler {
            open.complete(Unit)
            try {
                delay(60_000)
                UiResponse.Value("Approve")
            } catch (e: kotlinx.coroutines.CancellationException) {
                closed.complete(true)
                throw e
            }
        }
        fake.send("""{"type":"extension_ui_request","id":"s","method":"select","title":"t","options":["Approve","Deny"]}""")
        withTimeout(2_000) { open.await() }
        fake.send("""{"type":"extension_ui_request","id":"x","method":"cancel","targetId":"s"}""")
        assertTrue(withTimeout(2_000) { closed.await() })
        delay(100)
        assertTrue(transport.sent.none { "extension_ui_response" in it }, "a dialog omp closed must not be answered")
    }

    @Test
    fun presentationFramesBecomeUiEventsAndNeedNoReply(): Unit = harness {
        handshake()
        fake.send("""{"type":"extension_ui_request","id":"n1","method":"notify","message":"Build finished","notifyType":"info"}""")
        fake.send("""{"type":"extension_ui_request","id":"n2","method":"open_url","url":"https://example.com/oauth?x=1","instructions":"Sign in"}""")
        val notify = awaitEvent { it is OmpEvent.Ui && it.notification.method == "notify" } as OmpEvent.Ui
        assertEquals("Build finished", notify.notification.message)
        val open = awaitEvent { it is OmpEvent.Ui && it.notification.method == "open_url" } as OmpEvent.Ui
        assertEquals("https://example.com/oauth?x=1", open.notification.url)
        delay(100)
        assertTrue(transport.sent.none { "extension_ui_response" in it })
    }

    // ---- events ----

    @Test
    fun sessionEventsAreMappedAndUnknownOnesAreKept(): Unit = harness {
        handshake()
        fake.send("""{"type":"agent_start"}""")
        fake.send("""{"type":"message_start","messageId":"m1","message":{"role":"assistant","content":[]}}""")
        fake.send("""{"type":"message_update","messageId":"m1","message":{"role":"assistant"},"assistantMessageEvent":{"type":"text_delta","delta":"Hel"}}""")
        fake.send("""{"type":"message_end","messageId":"m1","message":{"role":"assistant","content":[{"type":"text","text":"Hello "},{"type":"toolCall","id":"c","name":"x","arguments":{}},{"type":"text","text":"world"}]}}""")
        fake.send("""{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"ls"},"intent":"list files"}""")
        fake.send("""{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","isError":true,"result":{"content":[]}}""")
        fake.send("""{"type":"queue_update","steering":["a"],"followUp":["b","c"]}""")
        fake.send("""{"type":"notice","level":"warning","message":"retrying","source":"retry"}""")
        fake.send("""{"type":"agent_end","messages":[],"isTerminal":true,"yielded":true,"messageCount":7}""")
        fake.send("""{"type":"session_settled"}""")
        fake.send("""{"type":"subagent_lifecycle","payload":{"id":"s1","status":"started"}}""")
        fake.send("""{"type":"some_future_event","x":1}""")
        awaitEvent<OmpEvent.SessionSettled>()
        awaitEvent { it is OmpEvent.Other }
        awaitEvent<OmpEvent.Subagent>()

        val all = eventsOf()
        assertTrue(all.any { it == OmpEvent.AgentStart })
        val delta = all.filterIsInstance<OmpEvent.MessageUpdate>().single()
        assertEquals("Hel", delta.textDelta)
        assertEquals("text_delta", delta.kind)
        assertEquals("Hello world", all.filterIsInstance<OmpEvent.MessageEnd>().single().text)
        val start = all.filterIsInstance<OmpEvent.ToolStart>().single()
        assertEquals("bash", start.toolName)
        assertEquals("list files", start.intent)
        assertTrue(all.filterIsInstance<OmpEvent.ToolEnd>().single().isError)
        assertEquals(OmpEvent.QueueUpdate(listOf("a"), listOf("b", "c")), all.filterIsInstance<OmpEvent.QueueUpdate>().single())
        assertEquals(OmpEvent.Notice("warning", "retrying", "retry"), all.filterIsInstance<OmpEvent.Notice>().single())
        val end = all.filterIsInstance<OmpEvent.AgentEnd>().single()
        assertEquals(true, end.yielded)
        assertEquals(7, end.messageCount)
        assertEquals("lifecycle", all.filterIsInstance<OmpEvent.Subagent>().single().kind)
        assertEquals("some_future_event", all.filterIsInstance<OmpEvent.Other>().single().type)
    }

    @Test
    fun garbageOnStdoutDoesNotStopTheReader(): Unit = harness {
        handshake()
        fake.send("this is not json")
        fake.send("""["an","array"]""")
        fake.send("")
        fake.send("""{"type":"agent_start"}""")
        awaitEvent<OmpEvent.FrameError>()
        awaitEvent { it == OmpEvent.AgentStart }
    }

    @Test
    fun aChunkedResponseIsReassembledAndDelivered(): Unit = harness {
        handshake()
        val req = async(Dispatchers.Default) { client.request("get_messages") }
        val cmd = fake.nextCommand()
        val big = "x".repeat(1_500_000)
        fake.sendChunked("""{"id":"${cmd.str("id")}","type":"response","command":"get_messages","success":true,"data":{"blob":"$big"}}""")
        val response = req.await()
        assertEquals(1_500_000, (response.data as JsonObject).str("blob")!!.length)
    }

    @Test
    fun aBrokenChunkSequenceIsReportedAndTheStreamRecovers(): Unit = harness {
        handshake()
        fake.send("""{"type":"rpc_chunk","chunkId":"a","index":0,"count":2,"byteLength":2000000,"data":"AAAA"}""")
        fake.send("""{"type":"agent_start"}""") // interrupts the sequence
        awaitEvent<OmpEvent.FrameError>()
        fake.send("""{"type":"agent_end","messages":[]}""")
        awaitEvent<OmpEvent.AgentEnd>()
    }

    // ---- sending ----

    @Test
    fun aCommandLargerThanTheTransportLimitIsRefusedLocally(): Unit = harness {
        handshake(maxFrameBytes = 2_048)
        val e = assertFailsWith<OmpFrameTooLargeException> { client.prompt("x".repeat(10_000)) }
        assertEquals(2_048, e.limit)
        assertTrue(transport.sent.none { "xxxxxxxx" in it })
    }

    @Test
    fun concurrentWritersNeverInterleaveLines(): Unit = harness {
        handshake()
        val calls = (1..40).map { i -> async(Dispatchers.Default) { runCatching { client.request("get_state", timeoutMs = 300) } } }
        calls.awaitAll()
        val lines = transport.sent.drop(1) // the first line is the protocol negotiation
        assertEquals(40, lines.size)
        for (l in lines) assertEquals("get_state", WireJson.parseToJsonElement(l).jsonObject.str("type"), "every line is one intact frame")
    }

    // ---- disconnect and shutdown ----

    @Test
    fun whenOmpExitsPendingWorkFailsAndTheLastEventIsClosed(): Unit = harness {
        handshake()
        val request = async(Dispatchers.Default) { runCatching { client.request("get_state", timeoutMs = 10_000) } }
        val prompt = async(Dispatchers.Default) { runCatching { client.prompt("go") } }
        fake.nextCommand(); fake.nextCommand()
        fake.exit(code = 137)
        assertIs<OmpDisconnectedException>(request.await().exceptionOrNull())
        assertIs<OmpDisconnectedException>(prompt.await().exceptionOrNull())
        val closed = awaitEvent<OmpEvent.Closed>()
        assertEquals(137, closed.exitCode)
        assertTrue(client.isClosed)
        assertFailsWith<OmpDisconnectedException> { client.request("get_state") }
        assertIs<OmpEvent.Closed>(eventsOf().last())
    }

    @Test
    fun anExitDuringAHostToolCallCancelsTheHandler(): Unit = harness {
        handshake()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Boolean>()
        val reg = async(Dispatchers.Default) {
            client.setHostTools(listOf(tapTool)) { _, _ ->
                started.complete(Unit)
                try { delay(60_000); HostToolPayload.text("never") } catch (e: kotlinx.coroutines.CancellationException) { cancelled.complete(true); throw e }
            }
        }
        fake.ack("""{"toolNames":["android_tap"]}""")
        reg.await()
        fake.send("""{"type":"host_tool_call","id":"h1","toolCallId":"t","toolName":"android_tap","arguments":{}}""")
        withTimeout(2_000) { started.await() }
        fake.exit()
        assertTrue(withTimeout(2_000) { cancelled.await() })
    }

    @Test
    fun closeEndsTheSessionByClosingStdinAndReportsTheExitCode(): Unit = harness {
        handshake()
        val code = client.close(graceMs = 2_000)
        assertEquals(0, code)
        assertTrue(transport.inputClosed)
        awaitEvent<OmpEvent.Closed>()
    }

    @Test
    fun closeKillsAProcessThatIgnoresStdin(): Unit = harness {
        handshake()
        val stubborn = object : OmpTransport by transport {
            override suspend fun closeInput() {}
            override suspend fun awaitExit(timeoutMs: Long): Int? = if (transport.exitCode == null) null else transport.exitCode
            override suspend fun destroy() = transport.serverExit(137)
        }
        val client2 = OmpClient(stubborn, scope)
        // Drive the real client against the stubborn transport.
        val code = client2.close(graceMs = 100)
        assertNull(code, "a killed process has no clean exit code")
    }

    @Test
    fun theStderrTailIsPartOfTheClosedEvent(): Unit = harness {
        handshake()
        fake.exit(1)
        val closed = awaitEvent<OmpEvent.Closed>()
        assertEquals(1, closed.exitCode)
        assertNotNull(closed.stderrTail)
        assertContentEquals(emptyList(), closed.stderrTail)
    }
}
