package app.umbra.core.nodes

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts exactly the certificates whose fingerprints [pins] returns. With [learn] set (pairing only), any certificate is
 * let through and its fingerprint handed to [seen]; the pairing proofs then decide whether it is the right one.
 */
class PinnedTrust(private val pins: () -> Set<String>, private val learn: () -> Boolean = { false }, private val seen: (String) -> Unit = {}) : X509ExtendedTrustManager() {
    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val fp = leaf.fingerprint()
        seen(fp)
        if (learn()) return
        if (fp !in pins()) throw CertificateException("certificate ${shortFingerprint(fp)} is not paired")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** A TLS 1.3 context with this side's identity ([keys]) and [trust]. */
fun tlsContext(keys: Array<KeyManager>, trust: PinnedTrust): SSLContext =
    SSLContext.getInstance("TLSv1.3").apply { init(keys, arrayOf(trust), null) }
