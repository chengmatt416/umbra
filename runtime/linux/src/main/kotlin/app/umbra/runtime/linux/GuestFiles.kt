package app.umbra.runtime.linux

import java.security.MessageDigest

/** One file the app keeps in the Linux environment. */
data class GuestFile(val path: String, val content: String, val mode: String = "644")

/**
 * The files the app carries inside itself and keeps in step in the Linux environment: its helper scripts and
 * package lists. They are not part of the root file system archive on purpose, so that fixing a script never
 * needs a 170 MB download, and an archive built from an older checkout still works with a newer app.
 */
class GuestFileSet(files: List<GuestFile>) {
    val files: List<GuestFile> = files.sortedBy { it.path }

    /** Changes whenever the path, the mode or the content of any file does. */
    val version: String = MessageDigest.getInstance("SHA-256").let { digest ->
        for (f in this.files) digest.update("${f.path}\n${f.mode}\n${f.content.length}\n${f.content}\n".toByteArray(Charsets.UTF_8))
        digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
