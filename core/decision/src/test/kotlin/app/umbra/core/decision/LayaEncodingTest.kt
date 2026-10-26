package app.umbra.core.decision

import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Kotlin port against fixtures written by `runtime-build/laya/convert.py golden` from Laya 0.4.15's own Python
 * tokenizer, `build_sequence` and `ONNXAgent._decode_answers` on the typed-decisions checkpoint.
 */
class LayaEncodingTest {
    private val golden = Json.parseToJsonElement(resource("laya/golden.json").decodeToString()).jsonObject
    private val tokenizer = LayaTokenizer.fromJson(GZIPInputStream(resource("laya/tokenizer.json.gz").inputStream()).readBytes().decodeToString())
    private val config = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(LayaConfig.serializer(), golden["config"]!!)
    private val encoding = LayaEncoding(tokenizer, config)

    private fun resource(path: String) = javaClass.classLoader.getResourceAsStream(path)!!.readBytes()

    private fun ints(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { it.jsonPrimitive.int }

    private val requests get() = golden["requests"]!!.jsonArray.map { it.jsonObject }

    private fun question(wire: JsonObject): Question {
        val instructions = wire["instructions"]!!.jsonPrimitive.content
        return when (wire["type"]!!.jsonPrimitive.content) {
            "choice" -> Question.Choice(wire["criteria"]!!.jsonObject.map { (k, v) -> Option(k, v.jsonPrimitive.content) }, instructions)
            "score" -> Question.Score(wire["criteria"]!!.jsonArray.mapIndexed { i, v -> Option("$i", v.jsonPrimitive.content) }, instructions)
            else -> Question.Noul(instructions)
        }
    }

    private fun request(fx: JsonObject) = DecisionRequest(
        fx["state"]!!.jsonPrimitive.content,
        fx["questions"]!!.jsonObject.mapValues { question(it.value.jsonObject) },
    )

    @Test fun tokenizerMatchesThePythonTokenizer() {
        for (case in golden["tokenizer"]!!.jsonArray.map { it.jsonObject }) {
            val text = case["text"]!!.jsonPrimitive.content
            assertEquals(ints(case["ids"]!!), tokenizer.encode(text), "tokens of ${text.take(40)}")
        }
    }

    @Test fun stateIsSerializedAndTokenizedAsTheSidecarDoes() {
        for (fx in requests) {
            val state = LayaEncoding.pythonJson(mapOf("body" to fx["state"]!!.jsonPrimitive.content))
            assertEquals(ints(fx["state_ids"]!!), tokenizer.encode(state.replace("[MASK]", " ")), "state of ${fx["id"]}")
        }
    }

    @Test fun sequencesAndMarkersMatchBuildSequence() {
        for (fx in requests) {
            val rows = encoding.encode(request(fx), Int.MAX_VALUE)
            for (r in fx["rows"]!!.jsonArray.map { it.jsonObject }) {
                val row = rows.getValue(r["qid"]!!.jsonPrimitive.content)
                assertEquals(ints(r["ids"]!!), row.ids, "ids of ${fx["id"]}/${r["qid"]}")
                assertEquals(ints(r["markers"]!!), row.markers, "markers of ${fx["id"]}/${r["qid"]}")
                assertEquals(r["qtype"]!!.jsonPrimitive.int, row.qtype)
            }
        }
    }

    @Test fun answersDecodeAsLayaDecodesThem() {
        for (fx in requests) {
            val req = request(fx)
            for (r in fx["rows"]!!.jsonArray.map { it.jsonObject }) {
                val qid = r["qid"]!!.jsonPrimitive.content
                val logits = r["logits"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
                val want = r["answer"]!!.jsonObject
                val got = encoding.decode(req.questions.getValue(qid), logits, logits.size)
                val where = "${fx["id"]}/$qid"
                fun close(a: Double, b: Double) = assertTrue(abs(a - b) <= 1.5e-4, "$where: $a vs $b")
                close(want["confidence"]!!.jsonPrimitive.double, got.confidence)
                when (got) {
                    is Answer.ChoiceAnswer -> {
                        assertEquals(want["choice"]!!.jsonPrimitive.content, got.choice, where)
                        want["probabilities"]!!.jsonObject.forEach { (k, v) -> close(v.jsonPrimitive.double, got.probabilities.getValue(k)) }
                    }
                    is Answer.ScoreAnswer -> {
                        close(want["score"]!!.jsonPrimitive.double, got.score)
                        want["probabilities"]!!.jsonObject.forEach { (k, v) -> close(v.jsonPrimitive.double, got.distribution[k.toInt()]) }
                    }
                    is Answer.NoulAnswer -> close(want["noul"]!!.jsonPrimitive.double, got.probability)
                }
            }
        }
    }

    @Test fun theEnginePadsEachRowToItsBucketAndAnswersEveryQuestion() = runBlocking {
        val byIds = requests.flatMap { fx -> fx["rows"]!!.jsonArray.map { it.jsonObject } }
            .associate { ints(it["ids"]!!) to it["logits"]!!.jsonArray.map { v -> v.jsonPrimitive.double.toFloat() } }
        val lengths = mutableListOf<Int>()
        val graph = object : LayaGraph {
            override val backend = "cpu"
            override fun run(length: Int, inputIds: IntArray, keyMask: FloatArray, markers: FloatArray, qtype: FloatArray): FloatArray {
                lengths += length
                assertEquals(length, inputIds.size)
                assertEquals(config.max_options * length, markers.size)
                val real = keyMask.count { it == 0f }
                assertTrue(inputIds.drop(real).all { it == config.pad } && keyMask.drop(real).all { it == LayaInputs.MASKED })
                assertEquals(1f, qtype.sum())
                val logits = byIds.getValue(inputIds.take(real))
                // Each marker row is one-hot at an option's [MASK] position inside the real tokens.
                for (slot in logits.indices) assertEquals(config.mask, inputIds[(0 until length).single { markers[slot * length + it] == 1f }])
                return FloatArray(config.max_options) { if (it < logits.size) logits[it] else -1e4f }
            }
            override fun close() {}
        }
        val engine = LayaNativeEngine(encoding, graph, maxStateChars = Int.MAX_VALUE)
        for (fx in requests) {
            val response = engine.decide(request(fx))
            assertEquals("laya-cpu", response.engine)
            for (r in fx["rows"]!!.jsonArray.map { it.jsonObject }) {
                val want = r["answer"]!!.jsonObject
                when (val got = response.answers.getValue(r["qid"]!!.jsonPrimitive.content)) {
                    is Answer.ChoiceAnswer -> assertEquals(want["choice"]!!.jsonPrimitive.content, got.choice)
                    is Answer.NoulAnswer -> assertTrue(abs(want["noul"]!!.jsonPrimitive.double - got.probability) < 2e-4)
                    is Answer.ScoreAnswer -> assertTrue(abs(want["score"]!!.jsonPrimitive.double - got.score) < 2e-4)
                }
            }
        }
        assertTrue(256 in lengths && 1024 in lengths, "short requests use the 256 bucket, the long one 1024: $lengths")
    }

    @Test fun pythonJsonEscapesLikeJsonDumpsWithoutAsciiEscaping() {
        assertEquals("{\"body\": \"a\\\"b\\\\c\\nd\\te\\u0001 é 👍\"}", LayaEncoding.pythonJson(mapOf("body" to "a\"b\\c\nd\te\u0001 é 👍")))
    }

    @Test fun tooManyOptionsAreRefusedSoTheRouterFallsBack() {
        val many = Question.Choice((0..40).map { Option("o$it", "option $it") }, "Pick one")
        val e = runCatching { encoding.encode(DecisionRequest("s", mapOf("q" to many)), 3_000) }.exceptionOrNull()
        assertTrue(e is DecisionEngineException)
    }
}
