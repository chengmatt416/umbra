package app.umbra.agent.publish

import app.umbra.core.model.UmbraJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class Publication(
    val artifactKey: String,
    val projectName: String,
    val accountId: String,
    val lastDeploymentId: String? = null,
    val lastDeploymentUrl: String? = null,
    val publicUrl: String,
    /** Epoch milliseconds; null while a project name is reserved for a publish that has not completed. */
    val publishedAt: Long? = null,
    val fileCount: Int = 0,
    val bytes: Long = 0,
)

/** Pass Context.filesDir. Reservations survive failed uploads so retries do not create orphan projects. */
class Publications(filesDir: File) {
    private val file = File(filesDir, "publish/publications.json")
    private val lock = locks.getOrPut(file.canonicalPath) { Any() }

    /** A null publishedAt means an incomplete upload; its reserved project can still be taken down. */
    fun publicationFor(artifact: File): Publication? = synchronized(lock) { read()[artifact.canonicalPath] }

    /** Every publication on this phone, including reserved ones whose upload did not finish. */
    fun all(): List<Publication> = synchronized(lock) { read().values.toList() }

    internal fun reserve(artifact: File, accountId: String, projectName: () -> String): Publication = synchronized(lock) {
        val key = artifact.canonicalPath
        val all = read()
        all[key]?.let {
            require(it.accountId == accountId) { "Reconnect the Cloudflare account that owns this publication" }
            return@synchronized it
        }
        require(all.size < MAX_ENTRIES) { "At most 200 publications; take down an existing site first" }
        val name = projectName()
        val publication = Publication(key, name, accountId, publicUrl = "https://$name.pages.dev")
        write(all + (key to publication))
        publication
    }

    internal fun save(publication: Publication) = synchronized(lock) {
        val all = read()
        val reserved = requireNotNull(all[publication.artifactKey]) { "Publication is no longer reserved" }
        require(reserved.projectName == publication.projectName && reserved.accountId == publication.accountId)
        write(all + (publication.artifactKey to publication))
    }

    internal fun remove(publication: Publication) = synchronized(lock) {
        write(read().filterValues { it.projectName != publication.projectName || it.accountId != publication.accountId })
    }

    private fun read(): Map<String, Publication> {
        require(!Files.isSymbolicLink(file.parentFile!!.toPath()) && !Files.isSymbolicLink(file.toPath())) { "Linked publication state is not allowed" }
        if (!file.exists()) return emptyMap()
        require(file.length() <= MAX_STATE_BYTES) { "Publication state is too large" }
        val bytes = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { it.readNBytes(MAX_STATE_BYTES + 1) }
        require(bytes.size <= MAX_STATE_BYTES) { "Publication state is too large" }
        return UmbraJson.decodeFromString(serializer, bytes.toString(Charsets.UTF_8)).also { values ->
            require(values.size <= MAX_ENTRIES && values.all { (key, value) -> key == value.artifactKey }) { "Invalid publication state" }
        }
    }

    private fun write(values: Map<String, Publication>) {
        require(values.size <= MAX_ENTRIES)
        val bytes = UmbraJson.encodeToString(serializer, values).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STATE_BYTES) { "Publication state is too large" }
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create publication directory" }
        val temporary = Files.createTempFile(parent.toPath(), ".publications-", ".tmp")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_STATE_BYTES = 2_000_000
        val locks = ConcurrentHashMap<String, Any>()
        val serializer = MapSerializer(String.serializer(), Publication.serializer())
    }
}
