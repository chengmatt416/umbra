package app.umbra.core.decision

import app.umbra.core.model.UmbraJson
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Talks to a `laya-serve`-compatible sidecar (ADR 0005). The request follows the documented wire
 * format: `state: {"body": ...}` and, per question, `type`, `instructions` and a `criteria` map of
 * label to description. The response parser is deliberately tolerant because the response schema is
 * not documented in the Laya README; `scripts/laya-contract-check.sh` prints a real response so the
 * parser can be tightened once a live server has been seen.
 */
class LayaHttpEngine(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val model: String? = null,
    private val minConfidence: Double? = null,
    /** Laya reads ~512-1024 tokens; keep the state well inside that. */
    private val maxStateChars: Int = 3_000,
    private val json: Json = UmbraJson,
) : DecisionEngine {
    override val name: String = "laya"

    private val base = baseUrl.trimEnd('/').toHttpUrl()

    override suspend fun isAvailable(): Boolean =
        try {
            // Any answer below 500 means a server is there: the README does not promise a /health route, and a 404 or 401 still proves it is up.
            client.newCall(Request.Builder().url(base.newBuilder().addPathSegments("health").build()).get().build()).await().use { it.code < 500 }
        } catch (e: IOException) {
            false
        } catch (e: IllegalStateException) {
            false
        }

    override suspend fun decide(request: DecisionRequest): DecisionResponse {
        val started = System.nanoTime()
        val body = json.encodeToString(JsonObject.serializer(), wireRequest(request)).toRequestBody(JSON)
        val http = Request.Builder().url(base.newBuilder().addPathSegments("v1/systemone").build()).post(body).build()
        val text = try {
            client.newCall(http).await().use { response ->
                val payload = withContext(Dispatchers.IO) { response.body.string() }
                if (!response.isSuccessful) {
                    throw DecisionEngineException("laya returned HTTP ${response.code}: ${payload.take(200)}")
                }
                payload
            }
        } catch (e: DecisionEngineException) {
            throw e
        } catch (e: IOException) {
            throw DecisionEngineException("laya is unreachable: ${e.message}", e)
        }
        val parsed = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw DecisionEngineException("laya returned invalid JSON: ${text.take(200)}", e)
        }
        val answers = parseAnswers(parsed, request)
        return DecisionResponse(answers, name, (System.nanoTime() - started) / 1_000_000)
    }

    // ---- request ----

    internal fun wireRequest(request: DecisionRequest): JsonObject = buildJsonObject {
        put("state", buildJsonObject { put("body", request.state.take(maxStateChars)) })
        put(
            "questions",
            buildJsonObject {
                for ((id, q) in request.questions) {
                    put(id, wireQuestion(q))
                }
            },
        )
        model?.let { put("model", it) }
        minConfidence?.let { put("min_confidence", it) }
    }

    private fun wireQuestion(q: Question): JsonObject = buildJsonObject {
        when (q) {
            is Question.Choice -> {
                put("type", "choice")
                put("instructions", q.instructions)
                put("criteria", buildJsonObject { q.options.forEach { put(it.id, it.description) } })
            }
            is Question.Score -> {
                put("type", "score")
                put("instructions", q.instructions)
                put("criteria", buildJsonObject { q.levels.forEach { put(it.id, it.description) } })
            }
            is Question.Noul -> {
                put("type", "noul")
                put("instructions", q.instructions)
                // The documented noul example names both sides; say so explicitly rather than rely on a default.
                put("criteria", buildJsonObject { put("false", "the statement is false"); put("true", "the statement is true") })
            }
        }
    }

    // ---- response ----

    private fun parseAnswers(root: JsonElement, request: DecisionRequest): Map<String, Answer> {
        val obj = root as? JsonObject ?: throw DecisionEngineException("laya response is not a JSON object")
        val answers = (obj["answers"] as? JsonObject) ?: obj
        val out = LinkedHashMap<String, Answer>()
        for ((id, question) in request.questions) {
            val element = answers[id] ?: throw DecisionEngineException("laya response has no answer for '$id'")
            out[id] = parseAnswer(id, question, element)
        }
        return out
    }

    private fun parseAnswer(id: String, q: Question, e: JsonElement): Answer = when (q) {
        is Question.Choice -> parseChoice(id, q.options, e)
        is Question.Score -> parseScore(id, q.levels, e)
        is Question.Noul -> parseNoul(id, e)
    }

    private fun parseChoice(id: String, options: List<Option>, e: JsonElement): Answer.ChoiceAnswer {
        val obj = e as? JsonObject
        val probs = obj?.firstObject("probabilities", "probs", "probability", "scores", "distribution")
            ?.mapValues { it.value.asDouble() }
            ?.filterValues { it != null }
            ?.mapValues { it.value!! }
            ?: obj?.firstArray("probabilities", "probs", "distribution")?.toDoubles()?.let { list ->
                if (list.size == options.size) options.indices.associate { options[it].id to list[it] } else null
            }
        val label = (obj?.firstString("choice", "answer", "label", "value", "selected") ?: (e as? JsonPrimitive)?.contentOrNull)
            ?: probs?.maxByOrNull { it.value }?.key
            ?: throw DecisionEngineException("laya answer for '$id' has no choice")
        val matched = options.firstOrNull { it.id == label } ?: options.firstOrNull { it.id.equals(label, ignoreCase = true) }
            ?: throw DecisionEngineException("laya answered '$label' for '$id', which is not one of ${options.map { it.id }}")
        val complete = options.associate { it.id to (probs?.entries?.firstOrNull { p -> p.key.equals(it.id, true) }?.value ?: 0.0) }
        val confidence = obj?.firstNumber("confidence", "answer_confidence", "conf") ?: complete.values.maxOrNull()?.takeIf { probs != null } ?: 0.5
        return Answer.ChoiceAnswer(matched.id, complete, confidence.coerceIn(0.0, 1.0))
    }

    private fun parseScore(id: String, levels: List<Option>, e: JsonElement): Answer.ScoreAnswer {
        val obj = e as? JsonObject
        val dist = obj?.firstArray("distribution", "probabilities", "probs")?.toDoubles()
            ?: obj?.firstObject("distribution", "probabilities", "probs")?.let { m -> levels.map { m[it.id].asDouble() ?: 0.0 } }
            ?: emptyList()
        val raw = obj?.firstNumber("score", "value", "answer") ?: (e as? JsonPrimitive)?.doubleOrNull
            ?: if (dist.isNotEmpty()) dist.indices.sumOf { it * dist[it] } else throw DecisionEngineException("laya answer for '$id' has no score")
        val confidence = obj?.firstNumber("confidence", "answer_confidence", "conf") ?: dist.maxOrNull() ?: 0.5
        return Answer.ScoreAnswer(raw, dist, confidence.coerceIn(0.0, 1.0))
    }

    private fun parseNoul(id: String, e: JsonElement): Answer.NoulAnswer {
        val obj = e as? JsonObject
        val primitive = e as? JsonPrimitive
        val p = obj?.firstNumber("noul", "probability", "p", "value", "answer", "yes")
            ?: obj?.firstBoolean("noul", "answer", "value")?.let { if (it) 1.0 else 0.0 }
            ?: primitive?.doubleOrNull
            ?: primitive?.booleanOrNull?.let { if (it) 1.0 else 0.0 }
            ?: throw DecisionEngineException("laya answer for '$id' has no probability")
        val confidence = obj?.firstNumber("confidence", "answer_confidence", "conf") ?: (kotlin.math.abs(p - 0.5) * 2)
        return Answer.NoulAnswer(p.coerceIn(0.0, 1.0), confidence.coerceIn(0.0, 1.0))
    }

    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content }

    private fun JsonObject.firstNumber(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull }

    private fun JsonObject.firstBoolean(vararg keys: String): Boolean? =
        keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull }

    private fun JsonObject.firstObject(vararg keys: String): JsonObject? = keys.firstNotNullOfOrNull { this[it] as? JsonObject }

    private fun JsonObject.firstArray(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { this[it] as? JsonArray }

    private fun JsonArray.toDoubles(): List<Double> = map { it.asDouble() ?: 0.0 }

    private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, _, _ -> response.close() }
                }
            },
        )
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
