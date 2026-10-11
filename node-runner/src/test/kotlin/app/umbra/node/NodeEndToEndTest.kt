package app.umbra.node

import app.umbra.core.nodes.DataManifest
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.JobStatus
import app.umbra.core.nodes.ManifestException
import app.umbra.core.nodes.Manifests
import app.umbra.core.nodes.NodeChannel
import app.umbra.core.nodes.NodeException
import app.umbra.core.nodes.NodeInfo
import app.umbra.core.nodes.Pairing
import app.umbra.core.nodes.PinnedTrust
import app.umbra.core.nodes.Rsp
import app.umbra.core.nodes.Rsp.str
import app.umbra.core.nodes.tlsContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir

/** A real node server and a real TLS client standing in for the phone, on this machine. */
class NodeEndToEndTest {
    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** A phone identity: an EC key and self-signed certificate made with the JDK's keytool. */
    private class Phone(dir: File, name: String) {
        val store = File(dir, "$name.p12")
        val pass = "phone-pass-$name"

        init {
            val keytool = File(System.getProperty("java.home"), "bin/keytool").path
            val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "phone", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=$name", "-validity", "30",
                "-storetype", "PKCS12", "-keystore", store.path, "-storepass", pass, "-keypass", pass).redirectErrorStream(true).start()
            check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
        }

        fun keys() = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, pass.toCharArray()) } }, pass.toCharArray())
        }.keyManagers
    }

    private fun server(window: PairingWindow?): Pair<NodeServer, Int> {
        val s = NodeServer(NodeHome(dir.resolve("node").toFile()), 0, window, maxManifestBytes = 1_000_000, log = {})
        return s to s.start()
    }

    private suspend fun pair(phone: Phone, port: Int, code: String): String {
        var seen = ""
        val ch = NodeChannel.connect("127.0.0.1", port, tlsContext(phone.keys(), PinnedTrust({ emptySet() }, { true }) { seen = it }), scope)
        ch.use {
            val reply = it.request("pair", buildJsonObject { put("name", "test phone"); put("proof", Pairing.phoneProof(code, seen, it.localFingerprint!!)) })
            assertTrue(Pairing.same(reply.str("proof")!!, Pairing.nodeProof(code, seen, it.localFingerprint!!)), "the node proves it knows the code too")
        }
        return seen
    }

    private suspend fun connect(phone: Phone, port: Int, pin: String) = NodeChannel.connect("127.0.0.1", port, tlsContext(phone.keys(), PinnedTrust({ setOf(pin) })), scope)

    @Test
    fun pairingNeedsTheCodeAndThenOnlyThePairedCertificatesTalk(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (srv, port) = server(window)
        try {
            val phone = Phone(dir.toFile(), "phone")
            // A wrong code is refused and costs an attempt.
            var seen = ""
            NodeChannel.connect("127.0.0.1", port, tlsContext(phone.keys(), PinnedTrust({ emptySet() }, { true }) { seen = it }), scope).use { ch ->
                val e = assertFailsWith<NodeException> { ch.request("pair", buildJsonObject { put("name", "x"); put("proof", Pairing.phoneProof("0000-0000-0000", seen, ch.localFingerprint!!)) }) }
                assertTrue("wrong pairing code" in e.message.orEmpty())
                assertFailsWith<NodeException> { ch.request("hello") }.also { assertTrue("not paired" in it.message.orEmpty()) }
            }
            assertEquals(4, window.attemptsLeft)

            val pin = pair(phone, port, window.code)
            connect(phone, port, pin).use { ch ->
                val info = Rsp.json.decodeFromJsonElement(NodeInfo.serializer(), ch.request("hello")["info"]!!)
                assertTrue(info.cpus > 0 && info.workspace.isNotEmpty())
            }
            // The window is used up; another phone cannot get past the handshake now.
            val stranger = Phone(dir.toFile(), "stranger")
            assertFailsWith<NodeException> { connect(stranger, port, pin).use { it.request("hello") } }
            // And the phone refuses a node whose certificate is not the pinned one.
            assertFailsWith<NodeException> { connect(phone, port, "00".repeat(32)) }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun aJobGetsOnlyItsManifestFilesRunsInItsFolderAndReportsBack(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (srv, port) = server(window)
        try {
            val phone = Phone(dir.toFile(), "phone")
            val pin = pair(phone, port, window.code)
            val src = Files.createDirectories(dir.resolve("src"))
            Files.writeString(src.resolve("a.txt"), "hello from the phone\n")
            val manifest = Manifests.build(src, listOf("a.txt"), maxBytes = 1_000)
            connect(phone, port, pin).use { ch ->
                ch.request("manifest", buildJsonObject { put("job", "j1"); put("manifest", Rsp.json.encodeToJsonElement(DataManifest.serializer(), manifest)) })
                val bytes = Files.readAllBytes(src.resolve("a.txt"))
                ch.request("put", buildJsonObject { put("job", "j1"); put("path", "a.txt"); put("offset", 0); put("data", Base64.getEncoder().encodeToString(bytes)); put("last", true) })
                assertFailsWith<NodeException> { ch.request("put", buildJsonObject { put("job", "j1"); put("path", "b.txt"); put("offset", 0); put("data", ""); put("last", true) }) }
                    .also { assertTrue("not in the job's manifest" in it.message.orEmpty()) }

                val exit = scope.async { withTimeout(20_000) { ch.events.filter { it.str("event") == "exit" && it.str("job") == "j1" }.first() } }
                ch.request("run", buildJsonObject {
                    put("job", "j1"); put("argv", JsonArray(listOf("sh", "-c", "cat a.txt; pwd; echo \"secret=${'$'}{ANTHROPIC_API_KEY:-none}\"").map { JsonPrimitive(it) })); put("lease_ms", 30_000)
                })
                assertEquals("0", exit.await().str("code"))
                val jobs = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), ch.request("jobs")["jobs"]!!)
                val j = jobs.single { it.job == "j1" }
                assertEquals(JobState.EXITED, j.state)
                assertTrue("hello from the phone" in j.outputTail, j.outputTail)
                assertTrue("jobs/j1/files" in j.outputTail, "it ran in its own folder: ${j.outputTail}")
                assertTrue("secret=none" in j.outputTail, "no secret is inherited from the node's environment")
                ch.request("forget", buildJsonObject { put("job", "j1") })
                assertTrue(!File(srv.jobs.let { dir.resolve("node/jobs/j1").toFile() }.path).exists(), "deleted after the job")
            }
            assertFailsWith<ManifestException> { Manifests.build(src, listOf("../outside"), 1_000) }
            Files.createSymbolicLink(src.resolve("escape"), dir.resolve("node"))
            assertFailsWith<ManifestException> { Manifests.build(src, listOf("escape"), 1_000) }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun theNodeEndsAJobWhenItsLeaseRunsOutAndReportsItAtRejoin(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (srv, port) = server(window)
        try {
            val phone = Phone(dir.toFile(), "phone")
            val pin = pair(phone, port, window.code)
            connect(phone, port, pin).use { ch ->
                ch.request("run", buildJsonObject { put("job", "short"); put("argv", JsonArray(listOf("sh", "-c", "sleep 1; echo finished").map { JsonPrimitive(it) })); put("lease_ms", 20_000) })
                ch.request("run", buildJsonObject { put("job", "long"); put("argv", JsonArray(listOf("sleep", "60").map { JsonPrimitive(it) })); put("lease_ms", 1_500) })
            }
            // The phone is gone: nobody renews the long job's lease.
            delay(4_000)
            connect(phone, port, pin).use { ch ->
                val jobs = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), ch.request("jobs")["jobs"]!!).associateBy { it.job }
                assertEquals(JobState.EXITED, jobs.getValue("short").state)
                assertTrue("finished" in jobs.getValue("short").outputTail)
                assertEquals(JobState.LEASE_EXPIRED, jobs.getValue("long").state, "${jobs["long"]}")
            }
        } finally {
            srv.stop()
        }
    }

    @Test
    fun aRestartedRunnerReportsJobsItNoLongerWatches(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (first, port) = server(window)
        val phone = Phone(dir.toFile(), "phone")
        val pin = pair(phone, port, window.code)
        connect(phone, port, pin).use { ch -> ch.request("run", buildJsonObject { put("job", "r1"); put("argv", JsonArray(listOf("sleep", "2").map { JsonPrimitive(it) })); put("lease_ms", 30_000) }) }
        first.stop()
        delay(100)
        val second = NodeServer(NodeHome(dir.resolve("node").toFile()), 0, null, log = {}, clock = { System.currentTimeMillis() + 1 })
        val port2 = second.start()
        try {
            connect(phone, port2, pin).use { ch ->
                val j = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), ch.request("jobs")["jobs"]!!).single { it.job == "r1" }
                assertEquals(JobState.LOST, j.state)
                assertTrue("epoch changed" in j.note.orEmpty())
            }
        } finally {
            second.stop()
        }
    }

    @Test
    fun aNodeKeepsEachPhonesEvidenceAnchorsApart(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (srv, port) = server(window)
        try {
            val phone = Phone(dir.toFile(), "phone")
            val pin = pair(phone, port, window.code)
            connect(phone, port, pin).use { ch ->
                ch.request("anchor", buildJsonObject { put("anchor", buildJsonObject { put("id", "a1"); put("digest", "d".repeat(64)) }) })
                val list = ch.request("anchors")["anchors"] as JsonArray
                assertEquals(1, list.size)
                assertTrue("d".repeat(64) in list.toString())
            }
            assertTrue(File(dir.resolve("node").toFile(), "anchors.jsonl").readText().contains("\"a1\""))
        } finally {
            srv.stop()
        }
    }

    @Test
    fun aRevokedPhoneIsForgottenByTheNodeWhoseJobsKeepOnlyTheirLeases(): Unit = runBlocking {
        val window = PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 60_000)
        val (srv, port) = server(window)
        try {
            val phone = Phone(dir.toFile(), "phone")
            val pin = pair(phone, port, window.code)
            connect(phone, port, pin).use { ch ->
                ch.request("run", buildJsonObject { put("job", "bg"); put("argv", JsonArray(listOf("sleep", "60").map { JsonPrimitive(it) })); put("lease_ms", 1_500) })
                val report = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), ch.request("revoke")["jobs"]!!)
                assertEquals(JobState.RUNNING, report.single { it.job == "bg" }.state, "the node's account: still running, under its lease")
            }
            assertTrue(NodeHome(dir.resolve("node").toFile()).paired().isEmpty(), "the node forgot the phone")
            // The revoked certificate gets nothing any more.
            assertFailsWith<NodeException> { connect(phone, port, pin).use { it.request("hello") } }
            // Nothing renews the lease now: the node ends the job by itself when it runs out.
            delay(3_000)
            assertEquals(JobState.LEASE_EXPIRED, srv.jobs.list().single { it.job == "bg" }.state)
        } finally {
            srv.stop()
        }
    }
}
