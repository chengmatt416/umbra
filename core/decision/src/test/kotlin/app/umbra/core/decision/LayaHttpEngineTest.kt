package app.umbra.core.decision

import app.umbra.core.model.UmbraJson
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class LayaHttpEngineTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun stop() {
        runCatching { server.close() }
    }

    private fun engine(
        client: OkHttpClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(),
        model: String? = null,
        minConfidence: Double? = null,
        maxStateChars: Int = 3_000,
    ) = LayaHttpEngine(server.url("/").toString(), client, model, minConfidence, maxStateChars)

    private val options = listOf(Option("billing", "refunds"), Option("tech", "bugs"))
    private fun choice(state: String = "billed twice, refund please") =
        DecisionRequest(state, mapOf("dept" to Question.Choice(options, "which team?")))

    private fun reply(body: String, code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build())
    }

    private fun recorded(): JsonObject {
        val r = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/v1/systemone", r.url.encodedPath)
        return UmbraJson.parseToJsonElement(r.body!!.utf8()).jsonObject
    }

    @Test
    fun sendsTheDocumentedWireFormat() = runBlocking<Unit> {
        reply("""{"answers":{"dept":{"choice":"billing","probabilities":{"billing":0.9,"tech":0.1},"confidence":0.9}}}""")
        engine(model = "typed-decisions", minConfidence = 0.7).decide(choice())

        val body = recorded()
        assertEquals("billed twice, refund please", body["state"]!!.jsonObject["body"]!!.jsonPrimitive.content)
        val q = body["questions"]!!.jsonObject["dept"]!!.jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals("which team?", q["instructions"]!!.jsonPrimitive.content)
        val criteria = q["criteria"]!!.jsonObject
        assertEquals("refunds", criteria["billing"]!!.jsonPrimitive.content)
        assertEquals("bugs", criteria["tech"]!!.jsonPrimitive.content)
        assertEquals("typed-decisions", body["model"]!!.jsonPrimitive.content)
        assertEquals("0.7", body["min_confidence"]!!.jsonPrimitive.content)
    }

    @Test
    fun noulAndScoreQuestionsUseTheirTypes() = runBlocking<Unit> {
        reply("""{"answers":{"ok":{"noul":0.9},"urg":{"score":1.5,"distribution":[0.1,0.3,0.6],"confidence":0.6}}}""")
        val levels = listOf(Option("low", "minor"), Option("mid", "some"), Option("high", "urgent"))
        engine().decide(DecisionRequest("s", mapOf("ok" to Question.Noul("it worked"), "urg" to Question.Score(levels, "how urgent?"))))
        val q = recorded()["questions"]!!.jsonObject
        assertEquals("noul", q["ok"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("it worked", q["ok"]!!.jsonObject["instructions"]!!.jsonPrimitive.content)
        // The documented noul example names both sides; the request does too, rather than rely on a default.
        assertEquals(setOf("false", "true"), q["ok"]!!.jsonObject["criteria"]!!.jsonObject.keys)
        // Laya refuses a score whose criteria are a map (422): levels go as a list, lowest first.
        assertEquals(listOf("minor", "some", "urgent"), q["urg"]!!.jsonObject["criteria"]!!.jsonArray.map { it.jsonPrimitive.content }, "scale order is preserved")
    }

    @Test
    fun longStatesAreTruncated() = runBlocking<Unit> {
        reply("""{"answers":{"dept":{"choice":"tech"}}}""")
        engine(maxStateChars = 100).decide(choice("x".repeat(5_000)))
        assertEquals(100, recorded()["state"]!!.jsonObject["body"]!!.jsonPrimitive.content.length)
    }

    @Test
    fun parsesAnAnswersWrapperWithProbabilitiesAndConfidence() = runBlocking<Unit> {
        reply("""{"answers":{"dept":{"choice":"billing","probabilities":{"billing":0.9,"tech":0.1},"confidence":0.84}},"usage":{"input_tokens":12}}""")
        val r = engine().decide(choice())
        val a = assertIs<Answer.ChoiceAnswer>(r.answers.getValue("dept"))
        assertEquals("billing", a.choice)
        assertEquals(0.9, a.probabilities.getValue("billing"), 1e-9)
        assertEquals(0.84, a.confidence, 1e-9)
        assertEquals("laya", r.engine)
    }

    @Test
    fun toleratesAlternativeResponseShapes() = runBlocking<Unit> {
        // Top-level answers, a bare string answer, no confidence: falls back to the top probability.
        reply("""{"dept":{"answer":"TECH","probs":{"billing":0.2,"tech":0.8}}}""")
        val a = assertIs<Answer.ChoiceAnswer>(engine().decide(choice()).answers.getValue("dept"))
        assertEquals("tech", a.choice, "label matching is case-insensitive and canonicalised")
        assertEquals(0.8, a.confidence, 1e-9)

        reply("""{"answers":{"dept":"billing"}}""")
        assertEquals("billing", (engine().decide(choice()).answers.getValue("dept") as Answer.ChoiceAnswer).choice)

        // Probabilities as an ordered array.
        reply("""{"answers":{"dept":{"choice":"tech","probabilities":[0.3,0.7]}}}""")
        val arr = engine().decide(choice()).answers.getValue("dept") as Answer.ChoiceAnswer
        assertEquals(0.7, arr.probabilities.getValue("tech"), 1e-9)
    }

    @Test
    fun parsesNoulAsNumberOrBoolean() = runBlocking<Unit> {
        reply("""{"answers":{"ok":{"noul":0.892,"confidence":0.78}}}""")
        val req = DecisionRequest("s", mapOf("ok" to Question.Noul("it worked")))
        val a = engine().decide(req).answers.getValue("ok") as Answer.NoulAnswer
        assertEquals(0.892, a.probability, 1e-9)
        assertEquals(0.78, a.confidence, 1e-9)

        reply("""{"answers":{"ok":true}}""")
        assertEquals(1.0, (engine().decide(req).answers.getValue("ok") as Answer.NoulAnswer).probability, 1e-9)
        reply("""{"answers":{"ok":{"answer":false}}}""")
        assertEquals(0.0, (engine().decide(req).answers.getValue("ok") as Answer.NoulAnswer).probability, 1e-9)
    }

    @Test
    fun parsesScoresWithADistribution() = runBlocking<Unit> {
        reply("""{"answers":{"urg":{"score":1.84,"distribution":[0.05,0.1,0.85],"confidence":0.85}}}""")
        val levels = listOf(Option("low", "minor"), Option("mid", "some"), Option("high", "urgent"))
        val a = engine().decide(DecisionRequest("s", mapOf("urg" to Question.Score(levels, "?")))).answers.getValue("urg") as Answer.ScoreAnswer
        assertEquals(1.84, a.score, 1e-9)
        assertEquals(listOf(0.05, 0.1, 0.85), a.distribution)
    }

    @Test
    fun rejectsAnswersThatDoNotMatchTheQuestion() = runBlocking<Unit> {
        reply("""{"answers":{"dept":{"choice":"legal"}}}""")
        assertTrue(assertFailsWith<DecisionEngineException> { engine().decide(choice()) }.message!!.contains("not one of"))
        reply("""{"answers":{}}""")
        assertTrue(assertFailsWith<DecisionEngineException> { engine().decide(choice()) }.message!!.contains("no answer for 'dept'"))
        reply("""[1,2,3]""")
        assertFailsWith<DecisionEngineException> { engine().decide(choice()) }
    }

    @Test
    fun serverErrorsInvalidJsonAndUnreachableServersAreEngineExceptions() = runBlocking<Unit> {
        reply("""{"detail":"model not loaded"}""", code = 503)
        assertTrue(assertFailsWith<DecisionEngineException> { engine().decide(choice()) }.message!!.contains("HTTP 503"))

        reply("<html>not json</html>")
        assertTrue(assertFailsWith<DecisionEngineException> { engine().decide(choice()) }.message!!.contains("invalid JSON"))

        val e = engine()
        server.close()
        assertTrue(assertFailsWith<DecisionEngineException> { e.decide(choice()) }.message!!.contains("unreachable"))
    }

    @Test
    fun aSlowServerTimesOutAsAnEngineException() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("""{"answers":{}}""").headersDelay(3, TimeUnit.SECONDS).build())
        val fast = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        assertFailsWith<DecisionEngineException> { engine(client = fast).decide(choice()) }
    }

    @Test
    fun healthCheckReflectsTheServer() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok"}""").build())
        assertTrue(engine().isAvailable())
        assertEquals("/health", server.takeRequest(2, TimeUnit.SECONDS)!!.url.encodedPath)

        server.enqueue(MockResponse.Builder().code(503).build())
        assertFalse(engine().isAvailable())

        val e = engine()
        server.close()
        assertFalse(e.isAvailable())
    }

    @Test
    fun otherServersAndUnauthorizedAnswersAreNotLaya() = runBlocking<Unit> {
        // 401, 404 and a non-Laya page on the port all used to count as "up" (security review G21).
        for (response in listOf(MockResponse.Builder().code(401).build(), MockResponse.Builder().code(404).build(),
            MockResponse.Builder().code(200).body("<html>router</html>").build(), MockResponse.Builder().code(200).body("""{"status":"starting"}""").build())) {
            server.enqueue(response)
            assertFalse(engine().isAvailable())
        }
    }

    @Test
    fun withAKeyEveryRequestIsAuthorizedAndOnlyTheAuthorizedHealthDetailCounts() = runBlocking<Unit> {
        val keyed = LayaHttpEngine(server.url("/").toString(), OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build(), apiKey = { "k-123" })
        // Liveness alone is what laya-serve tells a caller without the right key: a different server instance.
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok"}""").build())
        assertFalse(keyed.isAvailable())
        server.enqueue(MockResponse.Builder().code(200).body("""{"status":"ok","loaded":["laya-typed-decisions"]}""").build())
        assertTrue(keyed.isAvailable())
        repeat(2) { assertEquals("Bearer k-123", server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Authorization"]) }
        reply("""{"answers":{"dept":{"choice":"billing","confidence":0.9}}}""")
        runCatching { keyed.decide(choice()) }
        assertEquals("Bearer k-123", server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Authorization"])
    }

    @Test
    fun cancellingTheCallerCancelsTheHttpRequestPromptly() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(200).body("""{"answers":{}}""").headersDelay(10, TimeUnit.SECONDS).build())
        val e = engine(client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build())
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { e.decide(choice()) }
        delay(300)
        val started = System.nanoTime()
        job.cancelAndJoin()
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2_000, "cancel took ${ms}ms")
    }
}
