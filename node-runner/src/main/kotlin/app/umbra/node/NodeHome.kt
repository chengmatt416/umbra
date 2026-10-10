package app.umbra.node

import app.umbra.core.nodes.Rsp
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A phone this node trusts: the certificate it presented when it proved it knew the pairing code. */
@Serializable
data class PairedPhone(val fingerprint: String, val name: String, val pairedAtMs: Long)

@Serializable
data class NodeConfig(val nodeId: String, val name: String)

/**
 * The node's private folder (`~/.umbra-node` by default, readable by its user only): its identity key and certificate
 * (created once with the JDK's keytool), the phones it is paired with, and its jobs.
 */
class NodeHome(val dir: File) {
    private val keystore = File(dir, "node.p12")
    private val passFile = File(dir, "node.pass")
    private val configFile = File(dir, "node.json")
    private val pairedFile = File(dir, "paired.json")
    val jobsDir = File(dir, "jobs")

    init {
        dir.mkdirs()
        private(dir)
        jobsDir.mkdirs()
    }

    val config: NodeConfig by lazy {
        if (configFile.exists()) Rsp.json.decodeFromString(NodeConfig.serializer(), configFile.readText())
        else NodeConfig(UUID.randomUUID().toString(), "${System.getProperty("user.name")}@${hostName()}").also { write(configFile, Rsp.json.encodeToString(NodeConfig.serializer(), it)) }
    }

    /** Creates the identity on first use: an EC P-256 key with a self-signed certificate, in a PKCS12 file. */
    fun ensureIdentity() {
        if (keystore.exists() && passFile.exists()) return
        val pass = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        write(passFile, pass)
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "node", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
            "-dname", "CN=umbra-node ${config.nodeId}", "-validity", "3650", "-storetype", "PKCS12",
            "-keystore", keystore.path, "-storepass", pass, "-keypass", pass,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool could not create the node's identity: $out" }
        private(keystore)
    }

    private fun load(): KeyStore {
        ensureIdentity()
        return KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, passFile.readText().toCharArray()) } }
    }

    fun keyManagers(): Array<KeyManager> =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(load(), passFile.readText().toCharArray()) }.keyManagers

    fun certificate(): X509Certificate = load().getCertificate("node") as X509Certificate

    @Synchronized
    fun paired(): List<PairedPhone> =
        if (pairedFile.exists()) runCatching { Rsp.json.decodeFromString(ListSerializer(PairedPhone.serializer()), pairedFile.readText()) }.getOrDefault(emptyList()) else emptyList()

    @Synchronized
    fun addPaired(phone: PairedPhone) {
        val list = paired().filterNot { it.fingerprint == phone.fingerprint } + phone
        write(pairedFile, Rsp.json.encodeToString(ListSerializer(PairedPhone.serializer()), list))
    }

    @Synchronized
    fun removePaired(fingerprint: String) {
        write(pairedFile, Rsp.json.encodeToString(ListSerializer(PairedPhone.serializer()), paired().filterNot { it.fingerprint == fingerprint }))
    }

    private fun write(file: File, text: String) {
        val tmp = File(file.path + ".new")
        tmp.writeText(text)
        private(tmp)
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun private(f: File) {
        runCatching {
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (f.isDirectory) "rwx------" else "rw-------"))
        }
    }

    private fun hostName() = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("node")
}
