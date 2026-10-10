package app.umbra.agent

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import app.umbra.platform.server.HelperUserService
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Starts the privileged helper through Shizuku, for phones without root (spec §8). Shizuku runs a
 * process with shell rights; the app asks it to host [HelperUserService], learns the port and secret from
 * the service's binder, and connects like it would to any other helper.
 *
 * What the user gets is shell-user power ([uid] 2000), which is deliberately less than root: the app
 * reports the two separately and never presents Shizuku as root.
 */
class ShizukuLauncher(private val context: Context, private val helper: HelperController) {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY }

    private val manager = "moe.shizuku.privileged.api"

    fun state(): State = when {
        !installed() -> State.NOT_INSTALLED
        !running() -> State.NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEEDS_PERMISSION
        else -> State.READY
    }

    private fun installed() = try {
        context.packageManager.getPackageInfo(manager, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun running() = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 2000 when Shizuku was started over adb or wireless debugging, 0 when it runs as root; null while it is not running. */
    val uid: Int? get() = if (running()) try { Shizuku.getUid() } catch (_: Throwable) { null } else null

    /** Shows Shizuku's permission dialog. */
    fun requestPermission() {
        if (running() && !Shizuku.shouldShowRequestPermissionRationale()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Hosts the helper in Shizuku's process and points [HelperController] at it. True when it answers. */
    suspend fun startHelper(timeoutMs: Long = 20_000): Boolean {
        if (state() != State.READY) return false
        if (helper.client.isUp()) return true
        val endpoint = withTimeoutOrNull(timeoutMs) { bind() } ?: return false
        helper.configure(endpoint.first, endpoint.second)
        return helper.client.isUp()
    }

    private suspend fun bind(): Pair<Int, String>? = suspendCancellableCoroutine { cont ->
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, HelperUserService::class.java.name))
            .processNameSuffix("helper")
            .daemon(true)
            .version(VERSION)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    binder.transact(HelperUserService.TRANSACTION_ENDPOINT, data, reply, 0)
                    reply.readException()
                    val port = reply.readInt()
                    val token = reply.readString().orEmpty()
                    if (cont.isActive) cont.resume(if (port > 0 && token.isNotEmpty()) port to token else null)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        Shizuku.bindUserService(args, connection)
        cont.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, connection, false) } }
    }

    private companion object {
        const val REQUEST_CODE = 4713
        const val VERSION = 1
    }
}
