package app.umbra.agent.publish

import app.umbra.agent.data.SecretStore
import app.umbra.core.model.UmbraJson
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.jsoup.Jsoup

data class Progress(val stage: Stage, val completed: Int = 0, val total: Int = 0) {
    enum class Stage { SCANNING, HASHING, PROJECT, UPLOADING, DEPLOYING, COMPLETE }
}

class PagesException(val status: Int?, val code: String?, message: String) : IOException(message)

/** Only status and body cross the transport boundary; credentials never appear in response diagnostics. */
data class PagesResponse(val status: Int, val body: String)

fun interface PagesTransport {
    suspend fun execute(request: Request): PagesResponse
}

class OkHttpPagesTransport(client: OkHttpClient = OkHttpClient()) : PagesTransport {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).apply {
        // A shared app client's logging interceptors must not see bearer credentials or upload JWTs.
        interceptors().clear()
        networkInterceptors().clear()
    }.build()

    override suspend fun execute(request: Request): PagesResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(PagesException(null, null, "Cloudflare request failed"))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val source = it.body.source()
                        if (source.request(MAX_RESPONSE_BYTES + 1)) throw PagesException(it.code, null, "Cloudflare response is too large")
                        PagesResponse(it.code, source.readUtf8())
                    }
                    continuation.resume(result)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(PagesException(response.code, null, "Cloudflare response could not be read"))
                }
            }
        })
    }

    private companion object { const val MAX_RESPONSE_BYTES = 2_000_000L }
}

/** One instance per app container. Progress callbacks run on IO; the UI should marshal them as needed. */
class PagesPublisher(
    private val publications: Publications,
    private val secrets: Secrets,
    private val transport: PagesTransport = OkHttpPagesTransport(),
    private val backoff: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Secrets {
        fun get(name: String): String?
        fun put(name: String, value: String)
        fun remove(name: String)
    }

    constructor(filesDir: File, secrets: SecretStore, client: OkHttpClient = OkHttpClient()) : this(
        Publications(filesDir),
        object : Secrets {
            override fun get(name: String) = secrets.get(name)
            override fun put(name: String, value: String) = secrets.putDurable(name, value)
            override fun remove(name: String) = secrets.removeDurable(name)
        },
        OkHttpPagesTransport(client),
    )

    private class Credentials(val accountId: String, val token: String, val generation: Long)
    private val operation = Mutex()
    private val credentialsLock = Any()
    private var generation = 0L

    fun connected(): Boolean = synchronized(credentialsLock) {
        ACCOUNT_ID.matches(secrets.get(ACCOUNT_SECRET).orEmpty()) && validBearer(secrets.get(TOKEN_SECRET).orEmpty())
    }

    suspend fun connect(accountId: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        operation.withLock {
            try {
                val id = accountId.trim().lowercase(Locale.ROOT)
                val key = token.trim()
                require(ACCOUNT_ID.matches(id)) { "Enter a Cloudflare account id (32 hexadecimal characters)" }
                require(validBearer(key)) { "Enter a valid Cloudflare API token" }
                val epoch = synchronized(credentialsLock) { generation }
                val verified = api("GET", "/user/tokens/verify", key).jsonObject
                require(verified["status"]?.jsonPrimitive?.contentOrNull == "active") { "The Cloudflare API token is not active" }
                val account = api("GET", "/accounts/$id", key).jsonObject
                val name = requireNotNull(account["name"]?.jsonPrimitive?.contentOrNull) { "Cloudflare did not return an account name" }
                synchronized(credentialsLock) {
                    check(epoch == generation) { "Cloudflare connection changed" }
                    val previousAccount = secrets.get(ACCOUNT_SECRET)
                    val previousToken = secrets.get(TOKEN_SECRET)
                    try {
                        secrets.put(ACCOUNT_SECRET, id)
                        secrets.put(TOKEN_SECRET, key)
                    } catch (error: Exception) {
                        if (previousAccount == null) secrets.remove(ACCOUNT_SECRET) else secrets.put(ACCOUNT_SECRET, previousAccount)
                        if (previousToken == null) secrets.remove(TOKEN_SECRET) else secrets.put(TOKEN_SECRET, previousToken)
                        throw error
                    }
                    generation++
                }
                revision.value++
                Result.success(name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
    }

    fun disconnect() = synchronized(credentialsLock) {
        generation++
        try { secrets.remove(TOKEN_SECRET) } finally { secrets.remove(ACCOUNT_SECRET); revision.value++ }
    }

    fun publicationFor(artifact: File): Publication? = publications.publicationFor(artifact)

    /** Bumps whenever what is connected or published changes, for screens that show it. */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Every site published from this phone, newest first. */
    fun publications(): List<Publication> = publications.all().sortedByDescending { it.publishedAt ?: 0L }

    /**
     * Whether [artifact] now differs from what was last published (other files, other sizes, or a change after it),
     * so a preview can offer "Publish update" in one tap instead of the link.
     */
    fun changedSince(artifact: File, publication: Publication): Boolean = runCatching {
        val published = publication.publishedAt ?: return true
        val files = selectFiles(artifact)
        files.size != publication.fileCount || files.sumOf { it.size } != publication.bytes ||
            files.any { Files.getLastModifiedTime(it.file, NOFOLLOW_LINKS).toMillis() > published }
    }.getOrDefault(true)

    suspend fun publish(artifact: File, onProgress: (Progress) -> Unit): Publication = operation.withLock {
        withContext(Dispatchers.IO) {
            val credentials = credentials()
            onProgress(Progress(Progress.Stage.SCANNING))
            val files = selectFiles(artifact)
            val assets = files.mapIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val key = assetKey(file.read(), file.extension)
                onProgress(Progress(Progress.Stage.HASHING, index + 1, files.size))
                Asset(file, key)
            }
            val publication = publications.reserve(artifact, credentials.accountId) { projectName(artifact) }
            val projectPath = projectPath(credentials.accountId, publication.projectName)
            onProgress(Progress(Progress.Stage.PROJECT))
            authenticated(credentials)
            val project = try {
                api("GET", projectPath, credentials.token)
            } catch (error: PagesException) {
                if (error.status != 404) throw error
                authenticated(credentials)
                api("POST", "/accounts/${credentials.accountId}/pages/projects", credentials.token, buildJsonObject {
                    put("name", publication.projectName)
                    put("production_branch", "main")
                }.body())
            }
            suspend fun uploadToken(): String {
                authenticated(credentials)
                return api("GET", "$projectPath/upload-token", credentials.token).jsonObject["jwt"]
                    ?.jsonPrimitive?.contentOrNull?.takeIf(::validBearer)
                    ?: throw PagesException(null, null, "Cloudflare did not return an upload token")
            }
            var jwt = uploadToken()
            suspend fun assetsApi(path: String, body: RequestBody): JsonElement {
                authenticated(credentials)
                return try {
                    api("POST", path, jwt, body, credentials.token)
                } catch (error: PagesException) {
                    if (error.status != 401) throw error
                    jwt = uploadToken()
                    authenticated(credentials)
                    api("POST", path, jwt, body, credentials.token)
                }
            }
            val unique = assets.distinctBy { it.key }
            val hashes = buildJsonObject { put("hashes", JsonArray(unique.map { JsonPrimitive(it.key) })) }
            val missing = assetsApi("/pages/assets/check-missing", hashes.body()).jsonArray.map { it.jsonPrimitive.content }.toSet()
            check(missing.all { key -> unique.any { it.key == key } }) { "Cloudflare requested an unknown asset" }
            val uploads = unique.filter { it.key in missing }
            var uploaded = 0
            onProgress(Progress(Progress.Stage.UPLOADING, uploaded, uploads.size))
            for (bucket in buckets(uploads)) {
                var failure: PagesException? = null
                for (attempt in 0 until 3) {
                    currentCoroutineContext().ensureActive()
                    try {
                        assetsApi("/pages/assets/upload", uploadBody(bucket))
                        failure = null
                        break
                    } catch (error: PagesException) {
                        failure = error
                        if (attempt < 2) backoff(500L shl attempt)
                    }
                }
                failure?.let { throw it }
                uploaded += bucket.size
                onProgress(Progress(Progress.Stage.UPLOADING, uploaded, uploads.size))
            }
            assetsApi("/pages/assets/upsert-hashes", hashes.body())
            onProgress(Progress(Progress.Stage.DEPLOYING))
            authenticated(credentials)
            val deployment = api("POST", "$projectPath/deployments", credentials.token,
                MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("manifest", manifest(assets).toString())
                    .addFormDataPart("branch", "main").build(),
            ).jsonObject
            val id = deployment["id"]?.jsonPrimitive?.contentOrNull
                ?: throw PagesException(null, null, "Cloudflare did not return a deployment id")
            val url = deployment["url"]?.jsonPrimitive?.contentOrNull
                ?: throw PagesException(null, null, "Cloudflare did not return a deployment URL")
            val subdomain = ((project as? JsonObject)?.get("subdomain") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { Regex("[a-z0-9-]+\\.pages\\.dev").matches(it) } ?: "${publication.projectName}.pages.dev"
            val publicUrl = "https://$subdomain"
            val alias = (deployment["aliases"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.firstOrNull { it.trimEnd('/') == publicUrl }
            val saved = publication.copy(lastDeploymentId = id, lastDeploymentUrl = url, publicUrl = alias?.trimEnd('/') ?: publicUrl,
                publishedAt = now(), fileCount = files.size, bytes = files.sumOf { it.size })
            publications.save(saved)
            revision.value++
            onProgress(Progress(Progress.Stage.COMPLETE, files.size, files.size))
            saved
        }
    }

    /** UI confirmation belongs to the caller. A previously deleted project is also a successful takedown. */
    suspend fun takeDown(publication: Publication) = operation.withLock {
        withContext(Dispatchers.IO) {
            val credentials = credentials()
            require(credentials.accountId == publication.accountId) { "Reconnect the Cloudflare account that owns this publication" }
            authenticated(credentials)
            try {
                api("DELETE", projectPath(publication.accountId, publication.projectName), credentials.token)
            } catch (error: PagesException) {
                if (error.status != 404) throw error
            }
            publications.remove(publication)
            revision.value++
        }
    }

    private fun credentials(): Credentials = synchronized(credentialsLock) {
        Credentials(requireNotNull(secrets.get(ACCOUNT_SECRET)) { "Connect Cloudflare first" },
            requireNotNull(secrets.get(TOKEN_SECRET)) { "Connect Cloudflare first" }, generation)
    }

    private fun authenticated(credentials: Credentials) = synchronized(credentialsLock) {
        check(generation == credentials.generation && secrets.get(ACCOUNT_SECRET) == credentials.accountId && secrets.get(TOKEN_SECRET) == credentials.token) {
            "Cloudflare connection changed; try publishing again"
        }
    }

    private suspend fun api(method: String, path: String, token: String, body: RequestBody? = null, apiToken: String = token): JsonElement {
        currentCoroutineContext().ensureActive()
        require(validBearer(token)) { "Invalid Cloudflare credential; reconnect Cloudflare" }
        val request = Request.Builder().url(BASE + path).header("Authorization", "Bearer $token").method(method, body).build()
        val response = try { transport.execute(request) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            throw PagesException(null, null, "Cloudflare request failed")
        }
        val envelope = runCatching { UmbraJson.parseToJsonElement(response.body).jsonObject }.getOrNull()
        if (response.status !in 200..299 || (envelope?.get("success") as? JsonPrimitive)?.booleanOrNull != true) {
            val first = (envelope?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject
            val message = (first?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Cloudflare request failed (HTTP ${response.status})"
            fun redact(value: String): String = listOf(token, apiToken).distinct().sortedByDescending { it.length }
                .fold(value) { safe, secret -> safe.replace(secret, "[redacted]") }
            throw PagesException(response.status, (first?.get("code") as? JsonPrimitive)?.contentOrNull?.let(::redact), redact(message))
        }
        return requireNotNull(envelope)["result"] ?: JsonNull
    }

    companion object {
        const val ACCOUNT_SECRET = "CLOUDFLARE_PAGES_ACCOUNT_ID"
        const val TOKEN_SECRET = "CLOUDFLARE_PAGES_API_TOKEN"
        const val ARTIFACT_GUIDE = "When making an HTML artifact (a page, app, report, or slide deck), make it a static site that Cloudflare Pages can serve. " +
            "Use a folder with index.html at its root and keep every asset inside that folder, referenced by relative path. " +
            "Include no server code and require no build step, or ship the built output. " +
            "Keep every file at or below 25 MiB and tell the user they can publish the artifact from its preview."

        internal const val MAX_FILES = 1000
        internal const val MAX_FILE_BYTES = 25L * 1024 * 1024
        internal const val MAX_TOTAL_BYTES = 100L * 1024 * 1024
        internal const val MAX_BUCKET_BYTES = 40L * 1024 * 1024
        private const val BASE = "https://api.cloudflare.com/client/v4"
        private val ACCOUNT_ID = Regex("[a-f0-9]{32}")
        private val PROJECT_NAME = Regex("[a-z0-9](?:[a-z0-9-]{0,56}[a-z0-9])?")
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val random = SecureRandom()
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        private fun validBearer(value: String): Boolean = value.isNotEmpty() && value.all { it.code in 33..126 }

        internal fun projectName(artifact: File): String {
            val source = if (artifact.isDirectory) artifact.name else artifact.nameWithoutExtension
            val slug = source.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(45).trimEnd('-').ifEmpty { "site" }
            return "umbra-$slug-" + (1..6).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        }

        private fun projectPath(accountId: String, name: String): String {
            require(ACCOUNT_ID.matches(accountId) && PROJECT_NAME.matches(name)) { "Invalid Cloudflare project identity" }
            return "/accounts/$accountId/pages/projects/$name"
        }

        private fun JsonElement.body(): RequestBody = toString().toRequestBody(JSON)

        internal fun assetKey(content: ByteArray, extension: String): String = keyForBase64(Base64.getEncoder().encode(content), extension)

        private fun keyForBase64(value: ByteArray, extension: String): String = Blake3.Hasher().update(value)
            .update(extension.toByteArray(Charsets.UTF_8)).digest().take(16).joinToString("") { "%02x".format(it.toInt() and 255) }

        internal data class SiteFile(val root: Path, val file: Path, val path: String, val size: Long) {
            val extension: String get() = path.substringAfterLast('/').substringAfterLast('.', "")
            val contentType: String get() = contentType(extension)
            val base64Size: Long get() = ((size + 2) / 3) * 4

            fun read(): ByteArray {
                safePath(root, file)
                val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attrs.isRegularFile && attrs.size() == size && size <= MAX_FILE_BYTES) { "An artifact file changed; try again" }
                // NOFOLLOW_LINKS closes the final-component link race; ancestors are checked before and after reading.
                val bytes = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { it.readNBytes((size + 1).toInt()) }
                safePath(root, file)
                require(bytes.size.toLong() == size) { "An artifact file changed; try again" }
                return bytes
            }
        }

        internal data class Asset(val file: SiteFile, val key: String)

        internal fun manifest(assets: List<Asset>): JsonObject = buildJsonObject { assets.forEach { put(it.file.path, it.key) } }

        internal fun buckets(assets: List<Asset>): List<List<Asset>> {
            val result = mutableListOf<List<Asset>>()
            var bucket = mutableListOf<Asset>()
            var bytes = 0L
            for (asset in assets) {
                require(asset.file.base64Size <= MAX_BUCKET_BYTES) { "An asset exceeds the upload bucket limit" }
                if (bucket.isNotEmpty() && (bytes + asset.file.base64Size > MAX_BUCKET_BYTES || bucket.size >= 2000)) {
                    result += bucket
                    bucket = mutableListOf()
                    bytes = 0
                }
                bucket += asset
                bytes += asset.file.base64Size
            }
            if (bucket.isNotEmpty()) result += bucket
            return result
        }

        private fun uploadBody(bucket: List<Asset>): RequestBody = object : RequestBody() {
            override fun contentType() = JSON
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("[")
                bucket.forEachIndexed { index, asset ->
                    val value = Base64.getEncoder().encode(asset.file.read())
                    require(keyForBase64(value, asset.file.extension) == asset.key) { "An artifact file changed; try again" }
                    if (index > 0) sink.writeUtf8(",")
                    val metadata = buildJsonObject {
                        put("key", asset.key)
                        putJsonObject("metadata") { put("contentType", asset.file.contentType) }
                        put("base64", true)
                    }.toString()
                    // Base64 contains no JSON metacharacters. Avoid allocating a second large JSON string.
                    sink.writeUtf8(metadata.dropLast(1))
                    sink.writeUtf8(",\"value\":\"")
                    sink.write(value)
                    sink.writeUtf8("\"}")
                }
                sink.writeUtf8("]")
            }
        }

        internal fun selectFiles(artifact: File): List<SiteFile> {
            val original = artifact.toPath().toAbsolutePath()
            rejectLinks(original)
            val path = original.normalize()
            require(Files.exists(path, NOFOLLOW_LINKS)) { "Artifact does not exist" }
            val directory = Files.isDirectory(path, NOFOLLOW_LINKS)
            require(directory || artifact.extension.equals("html", true)) { "Choose a folder or an HTML file" }
            val root = if (directory) path else requireNotNull(path.parent)
            val selected = linkedMapOf<Path, SiteFile>()
            var total = 0L
            fun add(file: Path, destination: String = "/" + root.relativize(file).toString().replace(File.separatorChar, '/')) {
                safePath(root, file)
                if (file in selected) return
                val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attrs.isRegularFile) { "Only regular files can be published" }
                require(selected.size < MAX_FILES) { "Artifacts may contain at most 1,000 files" }
                require(attrs.size() <= MAX_FILE_BYTES) { "Each file must be at most 25 MiB" }
                total += attrs.size()
                require(total <= MAX_TOTAL_BYTES) { "Artifacts must be at most 100 MiB in total" }
                require(selected.values.none { it.path == destination }) { "Two artifact files have the same published path" }
                selected[file] = SiteFile(root, file, destination, attrs.size())
            }
            if (directory) {
                require(Files.isRegularFile(root.resolve("index.html"), NOFOLLOW_LINKS)) { "The folder needs index.html at its root" }
                Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        safePath(root, dir)
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        add(file)
                        return FileVisitResult.CONTINUE
                    }
                })
            } else {
                add(path, "/index.html")
                // Not apply { add(path) }: inside it the local add() above would win over the deque's own add.
                val queue = java.util.ArrayDeque<Path>()
                queue.addLast(path)
                while (queue.isNotEmpty()) {
                    val source = queue.removeFirst()
                    val entry = selected.getValue(source)
                    val extension = source.toFile().extension.lowercase(Locale.ROOT)
                    if (extension !in setOf("html", "htm", "css")) continue
                    val text = entry.read().toString(Charsets.UTF_8)
                    val references = if (extension == "css") cssReferences(text) else htmlReferences(text)
                    for (reference in references) {
                        val target = relativeFile(root, source.parent, reference) ?: continue
                        safePath(root, target)
                        if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isDirectory(target, NOFOLLOW_LINKS) && target !in selected) {
                            add(target)
                            queue.add(target)
                        }
                    }
                }
            }
            return selected.values.sortedBy { it.path }
        }

        private fun htmlReferences(text: String): List<String> {
            val html = Jsoup.parse(text)
            // A base URL would change relative-path semantics after the entry file becomes index.html.
            require(html.select("base[href]").all { it.attr("href").isBlank() || it.attr("href") == "." || it.attr("href") == "./" }) {
                "Use relative asset paths without an HTML base URL"
            }
            return buildList {
                html.select("[src], [href]").forEach { element ->
                    if (element.hasAttr("src")) add(element.attr("src"))
                    if (element.hasAttr("href")) add(element.attr("href"))
                }
                html.select("style").forEach { addAll(cssReferences(it.data())) }
                html.select("[style]").forEach { addAll(cssReferences(it.attr("style"))) }
            }
        }

        private val CSS_TOKENS = Regex("""/\*[\s\S]*?\*/|"(?:\\[\s\S]|[^"\\])*"|'(?:\\[\s\S]|[^'\\])*'""")
        private val CSS_URL = Regex("""(?<![a-zA-Z0-9_-])url\(\s*(?:"((?:\\[\s\S]|[^"\\])*)"|'((?:\\[\s\S]|[^'\\])*)'|((?:\\[\s\S]|[^\s)\\])*))\s*\)""", RegexOption.IGNORE_CASE)
        private val CSS_IMPORT = Regex("""@import\s+(?:"((?:\\[\s\S]|[^"\\])*)"|'((?:\\[\s\S]|[^'\\])*)')""", RegexOption.IGNORE_CASE)
        private val CSS_REFERENCES = Regex("${CSS_URL.pattern}|${CSS_IMPORT.pattern}|${CSS_TOKENS.pattern}", RegexOption.IGNORE_CASE)
        private val CSS_ESCAPE = Regex("""\\([a-fA-F0-9]{1,6})(?:\r\n|[ \t\r\n\u000C])?|\\(\r\n|[\r\n\u000C])|\\([\s\S])""")

        private fun cssReferences(text: String): List<String> {
            return CSS_REFERENCES.findAll(text).mapNotNull { match ->
                val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
                CSS_ESCAPE.replace(value) { escape ->
                    val hex = escape.groups[1]?.value
                    when {
                        hex != null -> {
                            val code = hex.toInt(16).takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF } ?: 0xFFFD
                            String(Character.toChars(code))
                        }
                        escape.groups[2] != null -> ""
                        else -> escape.groupValues[3]
                    }
                }
            }.toList()
        }

        private fun relativeFile(root: Path, parent: Path, reference: String): Path? {
            val value = reference.trim()
            if (value.isEmpty() || value.startsWith('#') || value.startsWith("//") || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) return null
            val encoded = value.substringBefore('#').substringBefore('?')
            if (encoded.isEmpty()) return null
            val decoded = URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
            require(!decoded.startsWith('/') && '\\' !in decoded && '\u0000' !in decoded) { "Use relative paths inside the artifact folder" }
            val unresolved = parent.resolve(decoded)
            val target = unresolved.normalize()
            require(target.startsWith(root)) { "An asset path escapes the artifact folder" }
            rejectLinks(unresolved)
            return target
        }

        private fun safePath(root: Path, file: Path) {
            require(file.startsWith(root)) { "An asset path escapes the artifact folder" }
            rejectLinks(file)
        }

        private fun rejectLinks(path: Path) {
            var current: Path? = path
            while (current != null) {
                require(!Files.isSymbolicLink(current)) { "Symlinks cannot be published" }
                current = current.parent
            }
        }

        internal fun contentType(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js", "mjs" -> "application/javascript"
            "json" -> "application/json"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "avif" -> "image/avif"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "txt" -> "text/plain"
            "xml" -> "application/xml"
            "wasm" -> "application/wasm"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }
    }
}
