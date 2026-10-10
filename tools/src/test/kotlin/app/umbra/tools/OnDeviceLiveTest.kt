package app.umbra.tools

import app.umbra.platform.adb.Adb
import java.io.File
import kotlinx.coroutines.runBlocking

/** The tool stack inside the installed app (accessibility service, the app's own shells), reached through its tool bridge. */
class OnDeviceLiveTest : PipelineScenarios() {
    override fun createRig(): ToolRig? {
        val adb = runBlocking { Adb.connect() } ?: return null
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        val apk = dir?.let { File(it, "app/build/outputs/apk/debug/app-debug.apk") }?.takeIf { it.exists() } ?: return null
        return DeviceRig(adb, apk)
    }
}
