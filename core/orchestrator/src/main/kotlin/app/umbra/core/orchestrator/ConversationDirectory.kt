package app.umbra.core.orchestrator

import app.umbra.core.model.UmbraJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** omp binds a transcript to its header's cwd. Change only that header, after stopping its writer. */
internal object ConversationDirectory {
    suspend fun relocate(file: Path, cwd: String) = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext
        val temporary = file.resolveSibling(file.fileName.toString() + ".cwd.tmp")
        try {
            Files.newInputStream(file).buffered().use { input ->
                fun line(): ByteArray {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        check(byte != -1) { "The saved conversation has no header" }
                        if (byte == 10) return bytes.toByteArray()
                        check(bytes.size() < 1024 * 1024) { "The saved conversation header is too large" }
                        bytes.write(byte)
                    }
                }
                var raw = line()
                var fields = UmbraJson.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject
                    ?: error("Invalid conversation header")
                val title = if ((fields["type"] as? JsonPrimitive)?.content == "title") {
                    val saved = raw
                    raw = line()
                    fields = UmbraJson.parseToJsonElement(raw.toString(Charsets.UTF_8)) as? JsonObject ?: error("Invalid conversation header")
                    saved
                } else null
                check((fields["type"] as? JsonPrimitive)?.content == "session") { "Invalid conversation header" }
                if ((fields["cwd"] as? JsonPrimitive)?.content == cwd) return@withContext
                java.io.FileOutputStream(temporary.toFile()).use { output ->
                    if (title != null) { output.write(title); output.write(10) }
                    output.write(JsonObject(fields + ("cwd" to JsonPrimitive(cwd))).toString().toByteArray(Charsets.UTF_8))
                    output.write(10)
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
