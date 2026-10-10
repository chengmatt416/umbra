package app.umbra.tools

import app.umbra.platform.adb.Adb
import kotlinx.coroutines.runBlocking

/** The tool stack on the development machine, reaching the device through adb shell commands. */
class HostSideLiveTest : PipelineScenarios() {
    override fun createRig(): ToolRig? = runBlocking { Adb.connect() }?.let { LiveRig(it) }
}
