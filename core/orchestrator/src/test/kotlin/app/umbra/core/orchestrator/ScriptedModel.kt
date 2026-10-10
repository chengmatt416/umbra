package app.umbra.core.orchestrator

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * An OpenAI-compatible chat endpoint that answers from a script. It stands in for the language model
 * only, so the orchestrator and the real omp binary can be exercised without a key or a network:
 * [reply] sees each request and returns the next streamed answer.
 */
class ScriptedModel(private val reply: (Request) -> MockResponse) : AutoCloseable {
    class Request(val body: JsonObject) {
        /** Text of the last message if it is a tool result (the model has just seen a tool's output). */
        val lastToolResult: String?
            get() = body["messages"]?.jsonArray?.lastOrNull()?.jsonObject?.takeIf { it.str("role") == "tool" }?.let {
                when (val c = it["content"]) {
                    is JsonPrimitive -> c.content
                    is JsonArray -> c.joinToString("") { b -> b.jsonObject.str("text").orEmpty() }
                    else -> ""
                }
            }

        val lastUserText: String?
            get() = body["messages"]?.jsonArray?.lastOrNull { it.jsonObject.str("role") == "user" }?.jsonObject?.get("content")?.let {
                when (it) {
                    is JsonPrimitive -> it.content
                    is JsonArray -> it.joinToString("") { b -> b.jsonObject.str("text").orEmpty() }
                    else -> null
                }
            }

        /** The system prompt omp sent, to check what the agent was told. */
        val systemText: String
            get() = body["messages"]?.jsonArray.orEmpty().filter { it.jsonObject.str("role") == "system" }.joinToString("\n") {
                when (val c = it.jsonObject["content"]) {
                    is JsonPrimitive -> c.content
                    is JsonArray -> c.joinToString("") { b -> b.jsonObject.str("text").orEmpty() }
                    else -> ""
                }
            }

        val toolNames: Set<String>
            get() = body["tools"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["function"]?.jsonObject?.str("name") }.toSet()
    }

    val requests = CopyOnWriteArrayList<Request>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.url.encodedPath.endsWith("/chat/completions")) {
                    return MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
                }
                val parsed = Request(Json.parseToJsonElement(request.body!!.utf8()).jsonObject)
                requests += parsed
                return reply(parsed)
            }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.port}/v1"

    override fun close() = server.close()

    companion object {
        private fun chunk(delta: String, finish: String? = null) =
            """{"id":"chatcmpl-t","object":"chat.completion.chunk","created":0,"model":"fake-model","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

        private fun sse(vararg events: String) = events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

        private fun quote(s: String) = JsonPrimitive(s).toString()

        private const val USAGE = """{"id":"chatcmpl-t","object":"chat.completion.chunk","created":0,"model":"fake-model","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""

        fun text(content: String, delayMs: Long = 0): MockResponse = response(
            sse(chunk("""{"role":"assistant","content":${quote(content)}}"""), chunk("{}", "stop"), USAGE), delayMs,
        )

        fun toolCall(name: String, arguments: String, id: String = "call_1", delayMs: Long = 0): MockResponse = response(
            sse(
                chunk("""{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"$id","type":"function","function":{"name":"$name","arguments":""}}]}"""),
                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":${quote(arguments)}}}]}"""),
                chunk("{}", "tool_calls"),
                USAGE,
            ),
            delayMs,
        )

        fun error(status: Int, message: String): MockResponse =
            MockResponse.Builder().code(status).addHeader("Content-Type", "application/json").body("""{"error":{"message":${quote(message)},"type":"server_error"}}""").build()

        private fun response(body: String, delayMs: Long) =
            MockResponse.Builder().code(200).addHeader("Content-Type", "text/event-stream").body(body).apply {
                if (delayMs > 0) headersDelay(delayMs, TimeUnit.MILLISECONDS)
            }.build()

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.content
        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()
    }
}
