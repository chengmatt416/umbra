package app.umbra.agent.data

import app.umbra.agent.AppContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

data class CatalogModel(val provider: String, val id: String, val name: String, val contextWindow: Long?, val reasoning: Boolean, val images: Boolean) {
    val selector: String get() = "$provider/$id"
}

/**
 * The models omp knows for the providers that have a key, read from omp itself (`omp models --json`)
 * so the list is exactly what a session can use. It needs no network: the catalog ships with omp.
 */
class ModelCatalog(private val container: AppContainer) {
    suspend fun fetch(): List<CatalogModel> {
        if (!container.guest.ensureReady()) return emptyList()
        val secrets = LinkedHashMap<String, String>()
        for (p in container.settings.value.providers) p.apiKeyEnv?.let { env -> container.secrets.get(env)?.let { secrets[env] = it } }
        val out = container.guest.runtime.execWithSecrets(listOf("omp", "models", "--json"), secrets, timeoutMs = 120_000, maxOutputBytes = 8 * 1024 * 1024)
        if (!out.ok) error("Could not list models: ${out.combined.take(300)}")
        return parse(out.stdout)
    }

    internal fun parse(json: String): List<CatalogModel> {
        // omp prints warnings before the document on some versions; the object starts at the first brace.
        val start = json.indexOf('{').takeIf { it >= 0 } ?: return emptyList()
        val root = Json.parseToJsonElement(json.substring(start)).jsonObject
        return (root["models"] as? JsonArray).orEmpty().mapNotNull { element ->
            val m = element as? JsonObject ?: return@mapNotNull null
            if (str(m, "kind") != "chat") return@mapNotNull null
            CatalogModel(
                provider = str(m, "provider") ?: return@mapNotNull null,
                id = str(m, "id") ?: return@mapNotNull null,
                name = str(m, "name") ?: str(m, "id").orEmpty(),
                contextWindow = (m["contextWindow"] as? JsonPrimitive)?.content?.toLongOrNull(),
                reasoning = (m["reasoning"] as? JsonPrimitive)?.content == "true",
                images = (m["input"] as? JsonArray).orEmpty().any { (it as? JsonPrimitive)?.content == "image" },
            )
        }
    }

    private fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this?.toList() ?: emptyList()
}
