package app.umbra.agent.notifications

import app.umbra.agent.data.LocalDocumentPaths
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalDocumentPathsTest {
    private val volumes = mapOf("primary" to Path.of("/storage/emulated/0"), "ABCD-1234" to Path.of("/storage/ABCD-1234"))

    @Test fun `device and removable storage resolve filenames without losing spaces or Unicode`() {
        assertEquals(Path.of("/storage/emulated/0/Documents/工作 folder/a.txt"), LocalDocumentPaths.resolve("com.android.externalstorage.documents", "primary:Documents/工作 folder/a.txt", volumes))
        assertEquals(Path.of("/storage/ABCD-1234/notes.txt"), LocalDocumentPaths.resolve("com.android.externalstorage.documents", "ABCD-1234:notes.txt", volumes))
        assertEquals(Path.of("/storage/emulated/0/Download/a.txt"), LocalDocumentPaths.resolve("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/a.txt", volumes))
    }

    @Test fun `cloud and unknown storage identifiers never pretend to be native mounts`() {
        assertNull(LocalDocumentPaths.resolve("example.cloud", "primary:Documents", volumes))
        assertNull(LocalDocumentPaths.resolve("com.android.externalstorage.documents", "UNKNOWN:Documents", volumes))
    }

    @Test fun `provider paths cannot escape a selected storage volume`() {
        assertFailsWith<IllegalArgumentException> { LocalDocumentPaths.resolve("com.android.externalstorage.documents", "primary:../../data/private", volumes) }
        assertFailsWith<IllegalArgumentException> { LocalDocumentPaths.resolve("com.android.providers.downloads.documents", "raw:/data/user/0/private", volumes) }
    }
}
