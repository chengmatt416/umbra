package app.umbra.platform.server

import android.os.Binder
import android.os.Parcel
import java.security.SecureRandom

/**
 * The privileged helper hosted by Shizuku (ADR 0004). Shizuku starts a process with shell rights (or
 * root, if Shizuku itself runs as root), creates this class in it, and hands its binder back to the app.
 * The binder carries one call: it returns where the helper listens and the secret that opens it. The
 * app then talks to it exactly as it talks to a helper started by root or over adb, through the same
 * authenticated loopback socket.
 */
class HelperUserService : Binder() {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = PrivilegedServer(0, token).also { it.start() }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != TRANSACTION_ENDPOINT) return super.onTransact(code, data, reply, flags)
        reply?.writeNoException()
        reply?.writeInt(server.boundPort)
        reply?.writeString(token)
        reply?.writeInt(android.os.Process.myUid())
        return true
    }

    companion object {
        const val TRANSACTION_ENDPOINT = FIRST_CALL_TRANSACTION
    }
}
