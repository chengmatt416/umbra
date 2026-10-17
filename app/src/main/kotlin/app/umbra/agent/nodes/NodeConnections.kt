package app.umbra.agent.nodes

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.umbra.agent.AppContainer
import app.umbra.core.control.NodeJob
import app.umbra.core.control.NodeRegistry
import app.umbra.core.control.PairedNode
import app.umbra.core.nodes.DataManifest
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.JobStatus
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
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The phone's side of distributed execution (architecture spec §10): its node identity (an EC key in the Android
 * Keystore that never leaves it, with a self-signed certificate), pairing, preflight, jobs placed by the policy with a
 * manifest of what leaves the phone, lease renewal while connected, and rejoin reconciliation.
 */
class NodeConnections(private val c: AppContainer, private val scope: CoroutineScope) {
    private val registry: NodeRegistry get() = c.nodes
    private val channels = ConcurrentHashMap<String, NodeChannel>()
    private val renewals = ConcurrentHashMap<String, Job>()

    // ---- identity ----

    private val keystore: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    private fun ensureIdentity() {
        if (keystore.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=Umbra phone"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L))
                .build(),
        )
        gen.generateKeyPair()
    }

    /** Hands the keystore key and its certificate to TLS for client authentication. */
    private fun keyManagers(): Array<KeyManager> {
        ensureIdentity()
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(keystore.getCertificate(ALIAS) as X509Certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = keystore.getKey(ALIAS, null) as PrivateKey
        }
        return arrayOf(km)
    }

    // ---- pairing ----

    /** Pairs with the node at [host]:[port] showing [code]; its certificate is pinned from then on. */
    suspend fun pair(host: String, port: Int, code: String, phoneName: String): PairedNode = withContext(Dispatchers.IO) {
        var seen = ""
        val ch = NodeChannel.connect(host, port, tlsContext(keyManagers(), PinnedTrust({ emptySet() }, { true }) { seen = it }), scope)
        ch.use {
            val mine = it.localFingerprint ?: throw NodeException("this phone presented no certificate")
            val reply = it.request("pair", buildJsonObject { put("name", phoneName); put("proof", Pairing.phoneProof(code, seen, mine)) })
            if (!Pairing.same(reply.str("proof").orEmpty(), Pairing.nodeProof(code, seen, mine))) throw NodeException("the node could not prove it knows the code: not paired")
            val node = PairedNode(reply.str("node_id") ?: seen.take(16), reply.str("node_name") ?: host, host, port, seen, pairedAtMs = System.currentTimeMillis())
            registry.paired(node)
            node
        }
    }

    private suspend fun channel(node: PairedNode): NodeChannel {
        if (node.revocation != null) throw NodeException("${node.name} is revoked: this phone does not talk to it")
        channels[node.id]?.takeIf { !it.closed }?.let { return it }
        val ch = NodeChannel.connect(node.host, node.port, tlsContext(keyManagers(), PinnedTrust({ setOf(node.fingerprint) })), scope)
        channels[node.id] = ch
        scope.launch {
            ch.events.collect { e ->
                val job = e.str("job") ?: return@collect
                when (e.str("event")) {
                    "output" -> registry.output(job, e.str("data").orEmpty())
                    "exit" -> runCatching { reconcile(node.id) }
                }
            }
        }
        return ch
    }

    /** Preflight: what the node is now (its capabilities and epoch), and that its pinned certificate still answers. */
    suspend fun preflight(nodeId: String): NodeInfo {
        val node = registry[nodeId] ?: throw NodeException("no such node")
        val info = Rsp.json.decodeFromJsonElement(NodeInfo.serializer(), channel(node).request("hello")["info"]!!)
        registry.seen(nodeId, info)
        return info
    }

    /**
     * Places a job: the policy decides; the files under [folder] (a host path of the workspace) named by [allowed] go
     * with it per a manifest; it runs for at most the policy's offline lease unless this phone renews it while connected.
     */
    suspend fun run(nodeId: String, argv: List<String>, folder: File? = null, allowed: List<String> = emptyList()): NodeJob = withContext(Dispatchers.IO) {
        val node = registry[nodeId] ?: throw NodeException("no such node")
        val policy = registry.policy()
        val manifest = if (folder != null && allowed.isNotEmpty()) Manifests.build(folder.toPath(), allowed, policy.maxUploadBytes) else null
        registry.placementProblem(nodeId, manifest?.totalBytes ?: 0)?.let { throw NodeException(it) }
        val ch = channel(node)
        val id = "job-" + java.lang.Long.toString(System.currentTimeMillis(), 36) + "-" + (1000..9999).random()
        if (manifest != null) {
            ch.request("manifest", buildJsonObject { put("job", id); put("manifest", Rsp.json.encodeToJsonElement(DataManifest.serializer(), manifest)) })
            for (f in manifest.files) {
                val bytes = File(folder, f.path).readBytes()
                var offset = 0
                do {
                    val end = minOf(bytes.size, offset + 256 * 1024)
                    ch.request("put", buildJsonObject {
                        put("job", id); put("path", f.path); put("offset", offset.toLong()); put("data", Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end))); put("last", end == bytes.size)
                    })
                    offset = end
                } while (offset < bytes.size)
            }
        }
        val leaseMs = policy.offlineLeaseMinutes * 60_000L
        val reply = ch.request("run", buildJsonObject { put("job", id); put("argv", JsonArray(argv.map { JsonPrimitive(it) })); put("lease_ms", leaseMs) })
        val status = Rsp.json.decodeFromJsonElement(JobStatus.serializer(), reply["status"]!!)
        val job = NodeJob(id, nodeId, argv, status.state, status.startedAtMs, status.leaseUntilMs, policy.revision, status.epoch,
            manifestFiles = manifest?.files?.size ?: 0, manifestBytes = manifest?.totalBytes ?: 0, note = status.note)
        registry.started(job)
        keepLease(node, id, leaseMs)
        job
    }

    /** While connected, the phone renews the lease at a third of its length; a lost connection lets it run out. */
    private fun keepLease(node: PairedNode, job: String, leaseMs: Long) {
        renewals[job] = scope.launch {
            while (true) {
                delay(leaseMs / 3)
                val ch = channels[node.id]?.takeIf { !it.closed } ?: break
                val r = runCatching { ch.request("renew", buildJsonObject { put("job", job); put("lease_ms", leaseMs) }) }.getOrNull() ?: break
                val s = Rsp.json.decodeFromJsonElement(JobStatus.serializer(), r["status"]!!)
                registry.leaseRenewed(job, s.leaseUntilMs)
            }
            renewals.remove(job)
        }
    }

    suspend fun cancel(nodeId: String, job: String) {
        val node = registry[nodeId] ?: return
        channel(node).request("cancel", buildJsonObject { put("job", job) })
        renewals.remove(job)?.cancel()
        reconcile(nodeId)
    }

    /** Rejoin: the node's own account of its jobs becomes the phone's record (nothing is assumed in between). */
    suspend fun reconcile(nodeId: String) {
        val node = registry[nodeId] ?: return
        val list = Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), channel(node).request("jobs")["jobs"]!!)
        registry.reconcile(nodeId, list)
        registry.seen(nodeId, null)
        for (s in list) if (s.state != JobState.RUNNING) renewals.remove(s.job)?.cancel()
    }

    /** Retention: the node deletes the job's folder; the phone forgets it too. */
    suspend fun forget(nodeId: String, job: String) {
        registry[nodeId]?.let { runCatching { channel(it).request("forget", buildJsonObject { put("job", job) }) } }
        registry.forgetJob(job)
    }

    /** Leaves [anchor] on the node: a copy of the evidence digest outside this phone's trust boundary (§13.2). */
    suspend fun anchor(nodeId: String, anchor: app.umbra.core.control.EvidenceAnchor) {
        val node = registry[nodeId] ?: throw NodeException("no such node")
        channel(node).request("anchor", buildJsonObject { put("anchor", Rsp.json.encodeToJsonElement(app.umbra.core.control.EvidenceAnchor.serializer(), anchor)) })
    }

    /** The anchors this phone left on the node, as the node kept them. */
    suspend fun anchors(nodeId: String): List<app.umbra.core.control.EvidenceAnchor> {
        val node = registry[nodeId] ?: throw NodeException("no such node")
        val list = channel(node).request("anchors")["anchors"] as? JsonArray ?: return emptyList()
        return list.mapNotNull { e -> (e as? kotlinx.serialization.json.JsonObject)?.get("anchor")?.let { runCatching { Rsp.json.decodeFromJsonElement(app.umbra.core.control.EvidenceAnchor.serializer(), it) }.getOrNull() } }
    }

    /**
     * Revokes [nodeId] (architecture spec §13.1, AC-017). This phone's side first, and at once: the connection closes,
     * no lease is renewed, the certificate is refused. Then the node is told, if it can be reached; until it answers
     * the revocation is pending, and its running jobs end only when their leases do, if the node keeps its contract.
     */
    suspend fun revoke(nodeId: String, reason: String): Boolean {
        disconnect(nodeId)
        registry.allJobs.value.values.filter { it.nodeId == nodeId }.forEach { renewals.remove(it.id)?.cancel() }
        registry.revoke(nodeId, reason, anchorsThere = c.security.allAnchors.value.values.count { it.nodeId == nodeId })
        return notifyRevoked(nodeId)
    }

    /** Delivers the revocation notice; the node's answer is recorded as its own account, not as proof. */
    suspend fun notifyRevoked(nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val node = registry[nodeId]?.takeIf { it.revocation != null } ?: return@withContext false
        try {
            NodeChannel.connect(node.host, node.port, tlsContext(keyManagers(), PinnedTrust({ setOf(node.fingerprint) })), scope).use { ch ->
                val reply = ch.request("revoke", timeoutMs = 10_000)
                registry.revocationAcknowledged(nodeId, Rsp.json.decodeFromJsonElement(ListSerializer(JobStatus.serializer()), reply["jobs"]!!))
            }
            true
        } catch (e: Exception) {
            registry.revocationUnreachable(nodeId, e.message ?: e::class.simpleName.orEmpty())
            false
        }
    }

    /**
     * Unpairs [nodeId]: this phone forgets it, and the node is asked to forget this phone too (its "revoke" request,
     * which removes the phone's certificate there and answers with its jobs). A node that cannot be reached keeps the
     * phone in its list until `umbra-node unpair` is run on it. Returns whether the node answered.
     */
    suspend fun unpair(nodeId: String): Boolean = withContext(Dispatchers.IO) {
        val node = registry[nodeId] ?: return@withContext false
        disconnect(nodeId)
        val told = try {
            NodeChannel.connect(node.host, node.port, tlsContext(keyManagers(), PinnedTrust({ setOf(node.fingerprint) })), scope).use { ch ->
                ch.request("revoke", timeoutMs = 10_000)
            }
            true
        } catch (_: Exception) {
            false
        }
        registry.unpair(nodeId)
        told
    }

    fun disconnect(nodeId: String) {
        channels.remove(nodeId)?.close()
    }

    fun connected(nodeId: String) = channels[nodeId]?.closed == false

    private companion object {
        const val ALIAS = "umbra.node.client"
    }
}
