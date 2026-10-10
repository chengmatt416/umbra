package app.umbra.tools

import app.umbra.platform.api.ShellBackend

/** What a tool call returned, in the terms the scenarios assert on. */
class ToolReply(val isError: Boolean, val text: String, val images: List<ByteArray>)

/**
 * A way to call the agent's tools against a real device: [LiveRig] runs the tool stack on the
 * development machine and reaches the device with adb shell commands; [DeviceRig] runs it inside the
 * installed app and reaches it through the loopback tool bridge. The scenarios are identical.
 */
interface ToolRig : AutoCloseable {
    /** A shell on the device for checking real effects directly. */
    val shell: ShellBackend

    suspend fun call(tool: String, args: String = "{}", session: String = "live"): ToolReply

    /** Puts the device in a known state: awake, unlocked, no animations, at the launcher. */
    suspend fun reset()

    /** How the "user" answers approval requests from now on. */
    fun answerApprovals(approve: Boolean)

    /** Approval requests raised since the last [reset]. */
    fun approvalCount(): Int
}
