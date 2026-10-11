package app.umbra.core.control

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A private git server for tests: `git http-backend` behind HTTPS (a self-signed certificate made with keytool) and
 * Basic authentication. Every request, read or write, needs [user] and [token]; anything else gets 401, as a private
 * repository on a forge would.
 */
class GitHttpsServer(private val dir: File, private val user: String, @Volatile var token: String) : AutoCloseable {
    val root = File(dir, "served").apply { mkdirs() }
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val store = File(dir, "server.p12")
    private val pass = "server-pass"
    private val server: HttpsServer
    val port: Int
    val caPem: String

    init {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        fun tool(vararg a: String): String {
            val p = ProcessBuilder(keytool, *a).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { out }
            return out
        }
        tool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", pass, "-keypass", pass)
        caPem = tool("-exportcert", "-rfc", "-alias", "server", "-keystore", store.path, "-storepass", pass).trim()
        val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, pass.toCharArray()) } }
        val ssl = SSLContext.getInstance("TLS").apply { init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass.toCharArray()) }.keyManagers, null, null) }
        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ssl)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { ex ->
            try {
                val auth = ex.requestHeaders.getFirst("Authorization")
                val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$token".toByteArray())
                requests += "${ex.requestMethod} ${ex.requestURI} ${if (auth == null) "anonymous" else if (auth == expected) "authorized" else "wrong"}"
                if (auth != expected) {
                    ex.responseHeaders.add("WWW-Authenticate", "Basic realm=\"test\"")
                    ex.sendResponseHeaders(401, -1)
                    return@createContext
                }
                val body = ex.requestBody.readBytes()
                val pb = ProcessBuilder("git", "http-backend").redirectError(ProcessBuilder.Redirect.DISCARD)
                pb.environment().apply {
                    clear()
                    put("PATH", System.getenv("PATH"))
                    put("GIT_PROJECT_ROOT", root.path)
                    put("GIT_HTTP_EXPORT_ALL", "1")
                    put("PATH_INFO", ex.requestURI.path)
                    put("QUERY_STRING", ex.requestURI.rawQuery.orEmpty())
                    put("REQUEST_METHOD", ex.requestMethod)
                    put("CONTENT_TYPE", ex.requestHeaders.getFirst("Content-Type").orEmpty())
                    put("CONTENT_LENGTH", body.size.toString())
                    put("REMOTE_USER", user)
                    put("REMOTE_ADDR", "127.0.0.1")
                    ex.requestHeaders.getFirst("Content-Encoding")?.let { put("HTTP_CONTENT_ENCODING", it) }
                    ex.requestHeaders.getFirst("Git-Protocol")?.let { put("GIT_PROTOCOL", it) }
                    put("GIT_CONFIG_NOSYSTEM", "1")
                    put("HOME", dir.path)
                }
                val p = pb.start()
                p.outputStream.use { it.write(body) }
                val out = p.inputStream.readBytes()
                p.waitFor()
                // CGI: headers, a blank line, the body.
                var split = indexOf(out, "\r\n\r\n".toByteArray())
                var skip = 4
                if (split < 0) { split = indexOf(out, "\n\n".toByteArray()); skip = 2 }
                val head = String(out, 0, maxOf(split, 0))
                var status = 200
                head.lines().map { it.trim() }.filter { it.contains(':') }.forEach { line ->
                    val (k, v) = line.split(':', limit = 2).map { it.trim() }
                    if (k.equals("Status", true)) status = v.substringBefore(' ').toInt() else ex.responseHeaders.add(k, v)
                }
                val rest = if (split >= 0) out.copyOfRange(split + skip, out.size) else ByteArray(0)
                ex.sendResponseHeaders(status, if (rest.isEmpty()) -1 else rest.size.toLong())
                if (rest.isNotEmpty()) ex.responseBody.use { it.write(rest) }
            } catch (e: Exception) {
                runCatching { ex.sendResponseHeaders(500, -1) }
            } finally {
                ex.close()
            }
        }
        server.start()
        port = server.address.port
    }

    fun url(name: String) = "https://127.0.0.1:$port/$name"

    /** A bare repository served as [name], seeded with one commit on main. */
    fun repository(name: String, git: (File, List<String>) -> String): File {
        val bare = File(root, name)
        git(dir, listOf("init", "-q", "--bare", "-b", "main", bare.path))
        val seed = File(dir, "seed-$name")
        git(dir, listOf("clone", "-q", bare.path, seed.path))
        File(seed, "README").writeText("seed\n")
        git(seed, listOf("add", "README"))
        git(seed, listOf("commit", "-q", "-m", "seed"))
        git(seed, listOf("push", "-q", "origin", "HEAD:main"))
        return bare
    }

    override fun close() = server.stop(0)

    private fun indexOf(a: ByteArray, b: ByteArray): Int {
        outer@ for (i in 0..a.size - b.size) {
            for (j in b.indices) if (a[i + j] != b[j]) continue@outer
            return i
        }
        return -1
    }
}
