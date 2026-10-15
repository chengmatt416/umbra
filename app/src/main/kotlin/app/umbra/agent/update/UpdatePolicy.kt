package app.umbra.agent.update

import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the site's update.json says about the current release (website/build.mjs writes it at every deploy). */
@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val file: String,
    val bytes: Long,
    val sha256: String,
    /** The APK's address, usually relative to update.json (`/download/<file>`, served from R2). */
    val url: String,
)

/** The updater's rules that need no phone, so they can be tested on their own. */
object UpdatePolicy {
    const val DEFAULT_URL = "https://umbra-agent.pages.dev/update.json"

    private val json = Json { ignoreUnknownKeys = true }
    private val hex64 = Regex("^[0-9a-f]{64}$")
    private val apkName = Regex("^umbra-[0-9][\\w.\\-]*\\.apk$")

    fun parse(text: String): UpdateManifest = json.decodeFromString(UpdateManifest.serializer(), text)

    fun encode(m: UpdateManifest): String = json.encodeToString(UpdateManifest.serializer(), m)

    /** Why a manifest cannot be used, or null when it can. */
    fun problem(m: UpdateManifest): String? = when {
        m.versionCode <= 0 -> "versionCode ${m.versionCode}"
        m.bytes <= 0 -> "size ${m.bytes}"
        !hex64.matches(m.sha256) -> "sha256 '${m.sha256}'"
        !apkName.matches(m.file) -> "file name '${m.file}'"
        else -> null
    }

    fun isNewer(m: UpdateManifest, installedVersionCode: Long): Boolean = m.versionCode > installedVersionCode

    /** The APK's absolute address: [url] may be relative to the manifest's. Only https is accepted. */
    fun apkUrl(manifestUrl: String, url: String): String {
        val resolved = URI(manifestUrl).resolve(url)
        require(resolved.scheme == "https") { "not https: $resolved" }
        return resolved.toString()
    }

    /** The versionCode in a downloaded file's name (`umbra-<versionCode>.apk`, or `.apk.part` while it downloads). */
    fun versionCodeOf(fileName: String): Long? = Regex("^umbra-(\\d+)\\.apk(\\.part)?$").find(fileName)?.groupValues?.get(1)?.toLongOrNull()
}
