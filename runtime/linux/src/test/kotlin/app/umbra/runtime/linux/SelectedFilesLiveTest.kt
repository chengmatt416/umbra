package app.umbra.runtime.linux

import app.umbra.platform.adb.Adb
import app.umbra.platform.api.exec
import app.umbra.platform.shell.ProcessShell.Companion.quote
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class SelectedFilesLiveTest {
    @Test
    fun selectedFilesRebindAfterExternalReplacementAndDisconnectWithoutDeletingOriginals() = runBlocking {
        val adb = Adb.connect()
        assumeTrue(adb != null, "No Android test device")
        val root = adb!!.root()
        assumeTrue(root.isAvailable(), "No root for mount verification")
        val id = UUID.randomUUID().toString()
        val fixture = "/data/local/tmp/umbra-bind-$id"
        val source = "/storage/emulated/0/Documents/Umbra bind $id"
        val file = "$source/notes.txt"
        val layout = RuntimeLayout("$fixture/rootfs", "$fixture/workspaces", "$fixture/home", uid = 2000)
        var binds = listOf(RuntimeLayout.Bind(file, file))
        val runtime = RootChrootRuntime(root, layout) { binds }
        fun q(path: String) = quote(path)
        try {
            assertTrue(root.exec("mkdir -p ${q(layout.rootfs + "/proc")} ${q(source)} && printf original > ${q(file)} && mount -t proc -o hidepid=2 proc ${q(layout.rootfs + "/proc")}").ok)
            runtime.mount()
            assertEquals("original", root.exec("cat ${q(layout.rootfs + file)}").stdout.trim())
            assertTrue(root.exec("printf updated > ${q(file + ".tmp")} && mv ${q(file + ".tmp")} ${q(file)}").ok)
            runtime.mount()
            assertEquals("updated", root.exec("cat ${q(layout.rootfs + file)}").stdout.trim())
            binds = listOf(RuntimeLayout.Bind(source, source))
            runtime.mount()
            assertTrue(root.exec("printf folder-edit > ${q(layout.rootfs + file)}").ok)
            assertEquals("folder-edit", root.exec("cat ${q(file)}").stdout.trim())
            binds = emptyList()
            runtime.mount()
            assertEquals("folder-edit", root.exec("cat ${q(file)}").stdout.trim())
            assertFalse(root.exec("cat /proc/mounts").stdout.contains("$fixture/rootfs/storage/"))
        } finally {
            runtime.unmount()
            // Only these UUID-named test fixtures are removed; no user-selected directories are cleaned up.
            root.exec("rm -rf ${q(fixture)} ${q(source)}")
        }
    }
}
