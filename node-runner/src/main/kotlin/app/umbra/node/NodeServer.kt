package app.umbra.node

import app.umbra.core.nodes.DataManifest
import app.umbra.core.nodes.NodeInfo
import app.umbra.core.nodes.Pairing
import app.umbra.core.nodes.PinnedTrust
import app.umbra.core.nodes.Rsp
import app.umbra.core.nodes.Rsp.bool
import app.umbra.core.nodes.Rsp.long
import app.umbra.core.nodes.Rsp.str
import app.umbra.core.nodes.fingerprint
import app.umbra.core.nodes.tlsContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A pairing window: one code, a deadline, a few attempts; used once. */
class PairingWindow(val code: String, val expiresAtMs: Long, @Volatile var attemptsLeft: Int = 5) {
    @Volatile var used = false
    fun open(now: Long) = !used && attemptsLeft > 0 && now < expiresAtMs
}

/**
 * The node's server: mutual TLS 1.3 with the node's certificate; only paired phones' certificates get past the
 * handshake, except while a pairing window is open, when an unknown phone may connect but do nothing except prove it
 * knows the code. Every request is handled for the phone whose certificate the connection carries.
 */
class NodeServer(
    private val home: NodeHome,
    private val port: Int,
    @Volatile var pairing: PairingWindow? = null,
    private val maxManifestBytes: Long = 256L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { println(it) },
) {
    val epoch = "${clock()}:${bootId()}"
    val jobs = Jobs(home.jobsDir, epoch, maxManifestBytes, clock)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "leases").apply { isDaemon = true } }
    private lateinit var server: SSLServerSocket
    private val nodeFingerprint by lazy { home.certificate().fingerprint() }

    private fun pairingOpen() = pairing?.open(clock()) == true

    /** Binds and starts serving; returns the port it listens on. */
    fun start(): Int {
        home.ensureIdentity()
        val trust = PinnedTrust(pins = { home.paired().map { it.fingerprint }.toSet() }, learn = { pairingOpen() })
        val context = tlsContext(home.keyManagers(), trust)
        server = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
            enabledProtocols = arrayOf("TLSv1.3")
        }
        timer.scheduleAtFixedRate({ runCatching { jobs.enforceLeases() } }, 1, 1, TimeUnit.SECONDS)
        pool.execute {
            while (!server.isClosed) {
                val s = runCatching { server.accept() as SSLSocket }.getOrNull() ?: continue
                // While pairing is open an unknown phone may connect (it must still present a certificate to bind).
                if (pairingOpen()) s.wantClientAuth = true else s.needClientAuth = true
                pool.execute { serve(s) }
            }
        }
        return server.localPort
    }

    fun stop() {
        runCatching { server.close() }
        timer.shutdownNow()
        pool.shutdownNow()
    }

    private fun serve(socket: SSLSocket) {
        val out = socket.outputStream
        fun send(frame: JsonObject) = synchronized(out) { out.write((frame.toString() + "\n").toByteArray()); out.flush() }
        var sink: ((JsonObject) -> Unit)? = null
        try {
            socket.startHandshake()
            val phone = (socket.session.peerCertificates.firstOrNull() as? X509Certificate)?.fingerprint()
            val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                val req = runCatching { Rsp.json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val id = req.long("id") ?: continue
                val op = req.str("op").orEmpty()
                val paired = phone != null && home.paired().any { it.fingerprint == phone }
                val reply: JsonObject = try {
                    when {
                        op == "pair" -> pair(req, phone)
                        !paired -> throw JobException("this phone is not paired with this node")
                        op == "revoke" -> revoke(phone!!)
                        op == "anchor" -> anchor(req, phone!!)
                        op == "anchors" -> anchors(phone!!)
                        else -> {
                            if (sink == null) {
                                val s: (JsonObject) -> Unit = { e -> runCatching { send(e) } }
                                jobs.sinks.add(s)
                                sink = s
                            }
                            handle(op, req)
                        }
                    }
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("error", e.message ?: e::class.simpleName) }
                }
                send(JsonObject(reply + ("re" to JsonPrimitive(id))))
                if (op == "revoke" && reply.bool("ok")) break
            }
        } catch (_: Exception) {
        } finally {
            sink?.let { jobs.sinks.remove(it) }
            runCatching { socket.close() }
        }
    }

    private fun pair(req: JsonObject, phone: String?): JsonObject {
        val window = pairing
        if (window == null || !window.open(clock())) throw JobException("pairing is not open on this node (start it with --pair)")
        if (phone == null) throw JobException("present a certificate to pair")
        val proof = req.str("proof").orEmpty()
        if (!Pairing.same(proof, Pairing.phoneProof(window.code, nodeFingerprint, phone))) {
            window.attemptsLeft--
            log("pairing: a wrong code was tried (${window.attemptsLeft} attempt(s) left)")
            throw JobException("wrong pairing code")
        }
        window.used = true
        val name = req.str("name")?.take(60) ?: "phone"
        home.addPaired(PairedPhone(phone, name, clock()))
        log("paired with “$name” (${phone.take(16)}); it may now run commands as ${System.getProperty("user.name")} in ${home.jobsDir}")
        return buildJsonObject {
            put("ok", true); put("node_id", home.config.nodeId); put("node_name", home.config.name)
            put("proof", Pairing.nodeProof(window.code, nodeFingerprint, phone))
        }
    }

    /**
     * The phone revoked this pairing (architecture spec §13.1): the node forgets it, so its certificate gets nothing
     * here again, and answers with its own account of the jobs. They keep their leases and end when those run out,
     * since the phone renews nothing from now on.
     */
    private fun revoke(phone: String): JsonObject {
        val list = jobs.list()
        home.removePaired(phone)
        log("a phone (${phone.take(16)}) revoked its pairing with this node: it is forgotten here; ${list.count { it.state == app.umbra.core.nodes.JobState.RUNNING }} job(s) run until their leases end")
        return buildJsonObject { put("ok", true); put("jobs", Rsp.json.encodeToJsonElement(ListSerializer(app.umbra.core.nodes.JobStatus.serializer()), list)) }
    }

    /**
     * Keeps a phone's evidence digest here, outside the phone's own trust boundary (§13.2): one line per anchor in
     * `anchors.jsonl`, with the phone's certificate and the time this node received it.
     */
    private fun anchor(req: JsonObject, phone: String): JsonObject {
        val payload = req["anchor"] as? JsonObject ?: throw JobException("no anchor")
        val line = buildJsonObject { put("phone", phone); put("received_ms", clock()); put("anchor", payload) }.toString()
        synchronized(this) { java.io.File(home.dir, "anchors.jsonl").appendText(line + "\n") }
        log("anchored an evidence digest from ${phone.take(16)}")
        return buildJsonObject { put("ok", true); put("received_ms", clock()) }
    }

    /** The anchors this phone left here: a phone sees only its own. */
    private fun anchors(phone: String): JsonObject {
        val f = java.io.File(home.dir, "anchors.jsonl")
        val mine = if (!f.exists()) emptyList() else f.readLines().mapNotNull { runCatching { Rsp.json.parseToJsonElement(it).jsonObject }.getOrNull() }.filter { it.str("phone") == phone }
        return buildJsonObject { put("ok", true); put("anchors", JsonArray(mine)) }
    }

    private fun handle(op: String, req: JsonObject): JsonObject = when (op) {
        "hello" -> buildJsonObject { put("ok", true); put("info", Rsp.json.encodeToJsonElement(NodeInfo.serializer(), info())) }
        "manifest" -> {
            jobs.manifest(job(req), Rsp.json.decodeFromJsonElement(DataManifest.serializer(), req["manifest"] ?: throw JobException("no manifest")))
            ok()
        }
        "put" -> {
            jobs.put(job(req), req.str("path").orEmpty(), req.long("offset") ?: 0, req.str("data").orEmpty(), req.bool("last"))
            ok()
        }
        "run" -> {
            val argv = (req["argv"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: throw JobException("no argv")
            val env = (req["env"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content }.orEmpty()
            val s = jobs.run(job(req), argv, req.str("cwd").orEmpty(), env, req.long("lease_ms") ?: 60_000)
            buildJsonObject { put("ok", true); put("status", Rsp.json.encodeToJsonElement(app.umbra.core.nodes.JobStatus.serializer(), s)) }
        }
        "renew" -> buildJsonObject { put("ok", true); put("status", Rsp.json.encodeToJsonElement(app.umbra.core.nodes.JobStatus.serializer(), jobs.renew(job(req), req.long("lease_ms") ?: 60_000))) }
        "cancel" -> buildJsonObject { put("ok", true); put("status", Rsp.json.encodeToJsonElement(app.umbra.core.nodes.JobStatus.serializer(), jobs.cancel(job(req)))) }
        "jobs" -> buildJsonObject { put("ok", true); put("jobs", Rsp.json.encodeToJsonElement(ListSerializer(app.umbra.core.nodes.JobStatus.serializer()), jobs.list())) }
        "fetch" -> {
            val bytes = jobs.fetch(job(req), req.str("path").orEmpty())
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            buildJsonObject { put("ok", true); put("data", Base64.getEncoder().encodeToString(bytes)); put("sha256", sha) }
        }
        "forget" -> { jobs.forget(job(req)); ok() }
        else -> throw JobException("unknown op $op")
    }

    private fun job(req: JsonObject) = req.str("job") ?: throw JobException("no job id")

    private fun ok() = buildJsonObject { put("ok", true) }

    private fun info(): NodeInfo {
        val rt = Runtime.getRuntime()
        val mem = runCatching { (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / (1024 * 1024) }.getOrDefault(rt.maxMemory() / (1024 * 1024))
        return NodeInfo(
            nodeId = home.config.nodeId, name = home.config.name,
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}", arch = System.getProperty("os.arch"),
            cpus = rt.availableProcessors(), memoryMb = mem, tools = tools(), epoch = epoch, clockMs = clock(),
            workspace = home.jobsDir.path, maxManifestBytes = maxManifestBytes,
        )
    }

    private val toolVersions by lazy {
        listOf("git" to listOf("git", "--version"), "java" to listOf("java", "-version"), "python3" to listOf("python3", "--version"), "node" to listOf("node", "--version"), "bun" to listOf("bun", "--version"))
            .mapNotNull { (name, argv) ->
                runCatching {
                    val p = ProcessBuilder(argv).redirectErrorStream(true).start()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else if (p.exitValue() == 0) name to p.inputStream.bufferedReader().readText().lines().first().trim() else null
                }.getOrNull()
            }.toMap()
    }

    private fun tools() = toolVersions

    /** The host's boot: Linux's boot id, macOS's boot time. A new boot is a new epoch. */
    private fun bootId(): String = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }.getOrNull()
        ?: runCatching {
            val p = ProcessBuilder("sysctl", "-n", "kern.boottime").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().substringBefore('}').substringAfter("sec = ").substringBefore(',').takeIf { p.waitFor() == 0 && it.isNotBlank() }
        }.getOrNull() ?: "unknown-boot"
}
