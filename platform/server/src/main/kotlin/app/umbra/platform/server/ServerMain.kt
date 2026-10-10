package app.umbra.platform.server

import android.os.Looper

/**
 * Entry point of the privileged helper, run with `app_process` (see [ServerLauncher] in the app):
 *
 *     CLASSPATH=/data/app/.../base.apk app_process /system/bin app.umbra.platform.server.ServerMain --port 47100 --token <secret>
 *
 * It runs on the main looper, where display calls must be made, and serves until told to shut down.
 */
object ServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().windowed(2, 1).filter { it[0].startsWith("--") }.associate { it[0].removePrefix("--") to it[1] }
        val token = options["token"] ?: run {
            System.err.println("usage: ServerMain --port <port> --token <secret>")
            return
        }
        val port = options["port"]?.toIntOrNull() ?: 0
        options["uid"]?.toIntOrNull()?.let { if (!dropPrivileges(it)) return }
        Looper.prepareMainLooper()
        val server = PrivilegedServer(port, token)
        server.start()
        println("umbra-helper listening on ${server.boundPort}")
        System.out.flush()
        Looper.loop()
    }

    /**
     * A helper started as root can become the shell user (uid 2000): virtual displays are created in the
     * name of an installed package, and only uid 2000 has one that is not an app (`com.android.shell`).
     *
     * Android's libcore cannot set supplementary groups, so the process keeps the ones it was started
     * with: those of the adb shell on a development image, whatever the root manager hands out on a phone.
     * They are reported in the hello reply, so a missing `log` or `input` group is visible, not a surprise.
     */
    private fun dropPrivileges(uid: Int): Boolean {
        if (android.os.Process.myUid() == uid) return true
        if (android.os.Process.myUid() != 0) {
            System.err.println("cannot switch to uid $uid: not running as root")
            return false
        }
        return try {
            val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
            val osClass = Class.forName("libcore.io.Os")
            // The group first: after setuid the process may no longer change it.
            osClass.getMethod("setgid", Int::class.javaPrimitiveType).invoke(os, uid)
            osClass.getMethod("setuid", Int::class.javaPrimitiveType).invoke(os, uid)
            android.os.Process.myUid() == uid
        } catch (e: Throwable) {
            System.err.println("cannot switch to uid $uid: ${e.cause ?: e}")
            false
        }
    }
}
