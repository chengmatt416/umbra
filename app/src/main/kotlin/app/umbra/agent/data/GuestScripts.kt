package app.umbra.agent.data

import android.content.Context
import app.umbra.runtime.linux.GuestFile
import app.umbra.runtime.linux.GuestFileSet

/**
 * The helper scripts and package lists the app keeps in the Linux environment (`/opt/umbra/bin`, `/opt/umbra/packs`),
 * read from the app's own assets, where the build copies the repository's `guest/` folder. Scripts (the ones in `bin` and the
 * `.post` steps of packs) are executable and every file belongs to root in the guest, out of the agent's reach.
 */
object GuestScripts {
    fun fileSet(context: Context): GuestFileSet {
        val assets = context.assets
        val files = ArrayList<GuestFile>()
        for (dir in listOf("bin", "packs")) {
            for (name in assets.list("guest/$dir").orEmpty()) {
                if (assets.list("guest/$dir/$name").orEmpty().isNotEmpty()) continue // a folder, not a file
                val content = assets.open("guest/$dir/$name").use { it.readBytes().toString(Charsets.UTF_8) }
                files += GuestFile("/opt/umbra/$dir/$name", content, if (dir == "bin" || name.endsWith(".post")) "755" else "644")
            }
        }
        return GuestFileSet(files)
    }
}
