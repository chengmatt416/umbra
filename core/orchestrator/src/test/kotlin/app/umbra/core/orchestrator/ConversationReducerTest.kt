package app.umbra.core.orchestrator

import app.umbra.runtime.omp.OmpEvent
import app.umbra.runtime.omp.OmpEventParsing
import app.umbra.runtime.omp.OmpSessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Frames in the shapes omp 18.6.1 writes (src/modes/rpc/wire), fed through the reducer the GUI renders from. */
class ConversationReducerTest {
    private fun frame(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun event(json: String): OmpEvent = OmpEventParsing.parse(frame(json))

    private fun ConversationState.feed(vararg frames: String, now: Long = 1_000): Pair<ConversationState, List<ConversationEffect>> {
        var s = this
        val effects = ArrayList<ConversationEffect>()
        for (f in frames) {
            val (next, effect) = ConversationReducer.reduce(s, event(f), now)
            s = next
            effect?.let(effects::add)
        }
        return s to effects
    }

    @Test
    fun aStreamedAnswerWithAToolCallBecomesOneAssistantItemAndAToolRun() {
        val (s, _) = ConversationState().feed(
            """{"type":"agent_start"}""",
            """{"type":"message_start","messageId":"u1","message":{"role":"user","content":"list files","timestamp":10}}""",
            """{"type":"message_end","messageId":"u1","message":{"role":"user","content":"list files","timestamp":10}}""",
            """{"type":"message_start","messageId":"a1","message":{"role":"assistant","content":[],"api":"x","provider":"p","model":"m","usage":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"totalTokens":0,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0}},"stopReason":"stop","timestamp":20}}""",
            """{"type":"message_update","messageId":"a1","assistantMessageEvent":{"type":"thinking_delta","delta":"Hmm"},"message":{"role":"assistant","content":[{"type":"thinking","thinking":"Hmm"}],"api":"x","provider":"p","model":"m","usage":{"input":0,"output":0},"stopReason":"stop","timestamp":20}}""",
            """{"type":"message_update","messageId":"a1","assistantMessageEvent":{"type":"text_delta","delta":"Let me look"},"message":{"role":"assistant","content":[{"type":"thinking","thinking":"Hmm"},{"type":"text","text":"Let me look"}],"api":"x","provider":"p","model":"m","usage":{"input":0,"output":0},"stopReason":"stop","timestamp":20}}""",
        )
        val streaming = s.items.last()
        assertIs<ChatItem.Assistant>(streaming)
        assertTrue(streaming.streaming)
        assertEquals("Let me look", streaming.text)
        assertTrue(s.working)

        val (done, _) = s.feed(
            """{"type":"message_end","messageId":"a1","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Hmm"},{"type":"text","text":"Let me look"},{"type":"toolCall","id":"t1","name":"bash","arguments":{"command":"ls"},"intent":"List the folder"}],"api":"x","provider":"p","model":"m","usage":{"input":120,"output":30,"cost":{"total":0.0042}},"stopReason":"toolUse","timestamp":20}}""",
            """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"ls"},"intent":"List the folder"}""",
            """{"type":"tool_execution_update","toolCallId":"t1","toolName":"bash","partialResult":{"content":[{"type":"text","text":"a.txt\n"}]}}""",
        )
        assertEquals(2, done.items.size, "the user message and one assistant message: ${done.items}")
        val answer = done.items.last() as ChatItem.Assistant
        assertFalse(answer.streaming)
        assertEquals(listOf("Thinking", "Text", "ToolCall"), answer.blocks.map { it::class.simpleName })
        assertEquals(0.0042, answer.cost)
        assertEquals("a.txt\n", done.tools["t1"]?.partial)
        assertTrue(done.tools["t1"]!!.running)

        val (finished, _) = done.feed(
            """{"type":"tool_execution_end","toolCallId":"t1","toolName":"bash","result":{"content":[{"type":"text","text":"a.txt\nb.txt"}]},"isError":false}""",
            """{"type":"message_end","messageId":"r1","message":{"role":"toolResult","toolCallId":"t1","toolName":"bash","content":[{"type":"text","text":"a.txt\nb.txt"}],"isError":false,"timestamp":30}}""",
            """{"type":"agent_end","messages":[],"isTerminal":true}""",
        )
        assertFalse(finished.tools["t1"]!!.running)
        assertEquals("a.txt\nb.txt", finished.tools["t1"]!!.result)
        assertFalse(finished.working)
        assertIs<ChatItem.ToolResult>(finished.items.last())
    }

    @Test
    fun aReloadedTranscriptKeepsTheClientsOwnNotesAfterIt() {
        val (s, _) = ConversationState().feed("""{"type":"command_output","text":"Session: abc"}""", now = 50)
        val reloaded = ConversationReducer.withMessages(
            s,
            listOf(
                frame("""{"role":"user","content":[{"type":"text","text":"hi"},{"type":"image","data":"AAAA","mimeType":"image/png"}],"timestamp":10}"""),
                frame("""{"role":"assistant","content":[{"type":"toolCall","id":"t9","name":"read","arguments":{"path":"x"}}],"provider":"p","model":"m","usage":{},"stopReason":"toolUse","timestamp":20}"""),
                frame("""{"role":"toolResult","toolCallId":"t9","toolName":"read","content":[{"type":"text","text":"contents"}],"isError":false,"timestamp":30}"""),
                frame("""{"role":"compactionSummary","summary":"long","shortSummary":"short","tokensBefore":9000,"timestamp":40}"""),
                frame("""{"role":"developer","content":"hidden","timestamp":41}"""),
            ),
        )
        assertEquals(listOf("User", "Assistant", "ToolResult", "Summary", "CommandOutput"), reloaded.items.map { it::class.simpleName })
        assertEquals(1, (reloaded.items[0] as ChatItem.User).images.size)
        assertEquals("contents", reloaded.tools["t9"]?.result, "tool runs come back from the transcript")
        assertEquals("short", (reloaded.items[3] as ChatItem.Summary).summary)
    }

    @Test
    fun extensionStatusWidgetsTitleQueueAndRetryAreTracked() {
        val (s, effects) = ConversationState().feed(
            """{"type":"extension_ui_request","id":"1","method":"setStatus","statusKey":"lsp","statusText":"LSP: ok"}""",
            """{"type":"extension_ui_request","id":"2","method":"setWidget","widgetKey":"plan","widgetLines":["1. read","2. edit"],"widgetPlacement":"aboveEditor"}""",
            """{"type":"extension_ui_request","id":"3","method":"setTitle","title":"Fix login"}""",
            """{"type":"extension_ui_request","id":"4","method":"set_editor_text","text":"/review"}""",
            """{"type":"extension_ui_request","id":"5","method":"open_url","url":"https://x/auth?a=1","launchUrl":"http://127.0.0.1:1455/l"}""",
            """{"type":"queue_update","steering":["stop that"],"followUp":["then commit"]}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"overloaded"}""",
        )
        assertEquals(mapOf("lsp" to "LSP: ok"), s.statuses)
        assertEquals(listOf("1. read", "2. edit"), s.widgets["plan"]?.lines)
        assertEquals("Fix login", s.title)
        assertEquals(listOf("stop that"), s.queuedSteering)
        assertEquals(listOf("then commit"), s.queuedFollowUp)
        assertEquals(3, s.retry?.maxAttempts)
        assertEquals(ConversationEffect.SetComposer("/review"), effects[0])
        assertEquals(ConversationEffect.OpenUrl("http://127.0.0.1:1455/l", null), effects[1], "the short loopback link is the one to open")

        val (cleared, _) = s.feed(
            """{"type":"extension_ui_request","id":"6","method":"setStatus","statusKey":"lsp"}""",
            """{"type":"extension_ui_request","id":"7","method":"setWidget","widgetKey":"plan"}""",
            """{"type":"auto_retry_end","success":true,"attempt":1}""",
        )
        assertTrue(cleared.statuses.isEmpty() && cleared.widgets.isEmpty())
        assertNull(cleared.retry)
    }

    @Test
    fun theStateFromGetStateFillsModelQueueTodosAndGoal() {
        val state = OmpSessionState.from(
            frame(
                """{"sessionId":"s1","model":{"id":"claude-sonnet-5-5","name":"Sonnet","api":"anthropic-messages","provider":"anthropic","baseUrl":"","reasoning":true,"input":["text","image"],"cost":{"input":3,"output":15,"cacheRead":0,"cacheWrite":0},"contextWindow":200000,"maxTokens":64000,"thinking":{"mode":"effort","efforts":["low","medium","high"]}},
                   "thinkingLevel":"medium","isStreaming":false,"isCompacting":false,"steeringMode":"all","followUpMode":"one-at-a-time","interruptMode":"wait",
                   "sessionName":"Login bug","autoCompactionEnabled":true,"fastModeEnabled":false,"fastModeActive":false,"messageCount":12,"queuedMessages":{"steering":[],"followUp":["later"]},
                   "todoPhases":[{"name":"Fix","tasks":[{"content":"find crash","status":"completed"},{"content":"patch","status":"in_progress"}]}],
                   "contextUsage":{"tokens":50000,"contextWindow":200000,"percent":25},"goal":{"enabled":true,"mode":"active","goal":{"id":"g","objective":"ship it","status":"active","tokensUsed":10,"timeUsedSeconds":5,"createdAt":0,"updatedAt":0}}}""",
            ),
        )
        val s = ConversationReducer.withSession(ConversationState(), state)
        assertEquals("anthropic/claude-sonnet-5-5", s.modelKey)
        assertTrue(state.model!!.takesImages)
        assertEquals(listOf("low", "medium", "high"), state.model!!.efforts)
        assertEquals("Login bug", s.title)
        assertEquals(listOf("later"), s.queuedFollowUp)
        assertEquals("in_progress", state.todos.single().tasks[1].status)
        assertEquals(25.0, state.contextUsage!!.percent)
        assertEquals("ship it", state.goal!!.objective)
        assertEquals("wait", state.interruptMode)
    }

    @Test
    fun aProcessThatEndsLeavesNothingRunning() {
        val (s, _) = ConversationState().feed(
            """{"type":"agent_start"}""",
            """{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{}}""",
        )
        val (after, _) = ConversationReducer.reduce(s, OmpEvent.Closed(1, emptyList()), 2_000)
        assertFalse(after.working)
        assertFalse(after.tools["t1"]!!.running)
        assertFalse(after.live)
    }
}
