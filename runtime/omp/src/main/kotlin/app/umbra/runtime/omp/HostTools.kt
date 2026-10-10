package app.umbra.runtime.omp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A block of tool output: text or an inline base64 image. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val base64: String, val mimeType: String = "image/jpeg") : ContentBlock
}

internal fun ContentBlock.toJson(): JsonObject = when (this) {
    is ContentBlock.Text -> buildJsonObject { put("type", "text"); put("text", text) }
    is ContentBlock.Image -> buildJsonObject { put("type", "image"); put("data", base64); put("mimeType", mimeType) }
}

/** What a host tool returns to omp (`host_tool_result.result`). */
data class HostToolPayload(
    val content: List<ContentBlock>,
    /** Structured data shown in the UI and kept in the transcript; the model sees [content]. */
    val details: JsonElement? = null,
    /** Surfaces [content] to the model as a tool error. */
    val isError: Boolean = false,
) {
    internal fun toResultJson(): JsonObject = buildJsonObject {
        put("content", JsonArray(content.map { it.toJson() }))
        details?.let { put("details", it) }
    }

    companion object {
        fun text(text: String, details: JsonElement? = null) = HostToolPayload(listOf(ContentBlock.Text(text)), details)
        fun error(message: String) = HostToolPayload(listOf(ContentBlock.Text(message)), isError = true)
    }
}

enum class ToolLoadMode(val wire: String) {
    /** A first-class function in the model's tool list. */
    ESSENTIAL("essential"),

    /**
     * Not in the tool list: omp mounts it behind an `xd://<tool>` virtual device that the model has to
     * discover and write to. Fine for rarely used tools (keeps the prompt small), poor for the core
     * observe/tap/type loop. This is omp's default for unknown tool names, hence the explicit default below.
     */
    DISCOVERABLE("discoverable"),
}

/** One tool the host offers to omp (`set_host_tools`). */
data class HostToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema of the arguments (an object schema). */
    val parameters: JsonObject,
    val label: String? = null,
    val hidden: Boolean = false,
    /** Essential by default: omp would otherwise hide the tool behind `xd://` (verified against omp 18.6.1). */
    val loadMode: ToolLoadMode? = ToolLoadMode.ESSENTIAL,
) {
    init {
        require(NAME.matches(name)) { "invalid tool name '$name'" }
    }

    internal fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parameters)
        label?.let { put("label", it) }
        if (hidden) put("hidden", true)
        loadMode?.let { put("loadMode", it.wire) }
    }

    private companion object {
        val NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]{0,63}$")
    }
}

/** A tool invocation delivered by omp. */
data class HostToolCall(
    /** Id of this host call; cancellation refers to it. */
    val id: String,
    /** The model's tool call id. */
    val toolCallId: String,
    val toolName: String,
    val arguments: JsonObject,
)

fun interface HostToolProgress {
    /** Streams partial output of a running host tool. */
    suspend fun update(partial: HostToolPayload)
}

/**
 * Executes host tools. Cancelling the calling coroutine means omp aborted the call; handlers must
 * stop promptly and release anything they hold (leases, input injection).
 */
fun interface HostToolHandler {
    suspend fun execute(call: HostToolCall, progress: HostToolProgress): HostToolPayload
}

/** Tiny builder for the object JSON Schemas host tools use. */
class SchemaBuilder {
    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    private fun add(name: String, required: Boolean, schema: JsonObject) {
        properties[name] = schema
        if (required) this.required += name
    }

    private fun prop(type: String, description: String?, extra: JsonObjectBuilder.() -> Unit = {}): JsonObject =
        buildJsonObject {
            put("type", type)
            description?.let { put("description", it) }
            extra()
        }

    fun string(name: String, description: String? = null, required: Boolean = false) = add(name, required, prop("string", description))

    fun integer(name: String, description: String? = null, required: Boolean = false) = add(name, required, prop("integer", description))

    fun number(name: String, description: String? = null, required: Boolean = false) = add(name, required, prop("number", description))

    fun boolean(name: String, description: String? = null, required: Boolean = false) = add(name, required, prop("boolean", description))

    fun enum(name: String, values: List<String>, description: String? = null, required: Boolean = false) =
        add(name, required, prop("string", description) { put("enum", JsonArray(values.map(::JsonPrimitive))) })

    fun stringArray(name: String, description: String? = null, required: Boolean = false) =
        add(name, required, prop("array", description) { put("items", buildJsonObject { put("type", "string") }) })

    /** An object whose values are all strings (environment variables, intent extras). */
    fun stringMap(name: String, description: String? = null, required: Boolean = false) =
        add(name, required, prop("object", description) { put("additionalProperties", buildJsonObject { put("type", "string") }) })

    /** An array of objects each described by [block]. */
    fun objectArray(name: String, description: String? = null, required: Boolean = false, block: SchemaBuilder.() -> Unit) {
        val item = SchemaBuilder().apply(block).build()
        add(name, required, prop("array", description) { put("items", item) })
    }

    /** A property with a hand-written JSON Schema, for shapes the other helpers do not cover. */
    fun raw(name: String, schema: JsonObject, required: Boolean = false) = add(name, required, schema)

    /** A nested object whose own schema is built by [block]. */
    fun obj(name: String, description: String? = null, required: Boolean = false, block: SchemaBuilder.() -> Unit) {
        val nested = SchemaBuilder().apply(block).build()
        add(name, required, JsonObject(nested + (description?.let { mapOf("description" to JsonPrimitive(it)) } ?: emptyMap())))
    }

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", false)
    }
}

fun objectSchema(block: SchemaBuilder.() -> Unit): JsonObject = SchemaBuilder().apply(block).build()
