package app.umbra.platform.privileged

import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellIdentity

/**
 * Root through a helper that itself runs as root (uid 0): started with `su`, or by Shizuku on a rooted
 * phone. It gives the app root without the app ever calling `su`, which matters where `su` answers only
 * to the shell user (an emulator image) or where the root manager prompts on every call.
 */
class RootViaHelperShell(private val client: PrivilegedClient) : ShellBackend by ServerShell(client, ShellIdentity.ROOT) {
    override val name = "root_helper"

    override val identity = ShellIdentity.ROOT

    override suspend fun isAvailable(): Boolean = client.isUp() && client.serverInfo?.int("uid") == 0
}
