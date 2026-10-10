package app.umbra.platform.adb

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Integration tests run against a real device or emulator reached through adb. When none is
 * attached they are skipped (not faked), so `./gradlew check` stays green on any machine; start the
 * `umbra36` emulator (see docs/DEVELOPMENT.md) to run them.
 */
object DeviceTestSupport {
    private val device: Adb? by lazy { runBlocking { Adb.connect() } }

    /** The device, or skips the calling test. */
    fun requireDevice(): Adb {
        assumeTrue(device != null, "no adb device attached; skipping the live-device test")
        return device!!
    }

    /** The debug APK built by `./gradlew :app:assembleDebug`, or skips the calling test. */
    fun requireDebugApk(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val apk = dir?.let { File(it, "app/build/outputs/apk/debug/app-debug.apk") }
        assumeTrue(apk != null && apk.exists(), "the debug APK has not been built (run :app:assembleDebug)")
        return apk!!
    }
}
