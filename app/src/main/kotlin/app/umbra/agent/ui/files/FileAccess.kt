package app.umbra.agent.ui.files

import app.umbra.agent.AppContainer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Canonical roots prevent an edited file or a workspace symlink from reaching app credentials. */
internal class FileAccess(val file: File, val writable: Boolean, private val roots: List<Path>, private val writeRoots: List<Path>) {
    fun check() {
        val path = file.toPath().toRealPath()
        if (roots.none { path.startsWith(it) } || !Files.isRegularFile(path)) throw FileNotAllowed()
    }

    fun read(): ByteArray {
        check()
        if (file.length() > MAX_FILE_BYTES) throw FileTooLarge()
        return file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }.also { if (it.size > MAX_FILE_BYTES) throw FileTooLarge() }
    }

    fun save(expected: ByteArray, bytes: ByteArray) {
        if (!writable) throw FileNotAllowed()
        atomicFileEdit(file, writeRoots, expected, bytes)
    }

    companion object {
        suspend fun open(container: AppContainer, path: String, sessionId: String?): FileAccess {
            val context = container.context
            val file = File(path).canonicalFile
            val copies = File(context.cacheDir, "share/deliverables").canonicalFile.toPath()
            val workspace = container.workspaceLayout
            val session = sessionId?.let { container.sessionStore.get(it) }
            val writableRoots = buildList {
                add(copies)
                if (session != null) {
                    if (!session.id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) throw FileNotAllowed()
                    add(workspace.sessionDir(session.id).toFile().canonicalFile.toPath())
                    session.workspaceId?.let { add(workspace.projectDir(it).toFile().canonicalFile.toPath()) }
                } else if (sessionId == null) {
                    container.workspaces.list().forEach { add(workspace.projectDir(it.id).toFile().canonicalFile.toPath()) }
                }
            }
            // Readable: the workspaces (what agents and the user work on) and verified deliverable copies — the same
            // scope the share sheet exposes (res/xml/file_paths.xml). Never the rest of filesDir, which holds secrets.
            val privateRoots = listOf(File(container.guest.layout.workspaces).canonicalFile.toPath(), copies)
            if (privateRoots.none { file.toPath().startsWith(it) }) throw FileNotAllowed()
            return FileAccess(file, writableRoots.any { file.toPath().startsWith(it) }, privateRoots, writableRoots).also { it.check() }
        }
    }
}

internal enum class FileKind { Text, Markdown, Json, Csv, Tsv, Xlsx, Xls, Docx, Doc, Pptx, Html, Image, Svg, Video, Unsupported }

internal fun fileKind(extension: String, mime: String? = null): FileKind = when (extension.lowercase()) {
    "md", "markdown" -> FileKind.Markdown
    "json" -> FileKind.Json
    "csv" -> FileKind.Csv
    "tsv" -> FileKind.Tsv
    "xlsx" -> FileKind.Xlsx
    "xls" -> FileKind.Xls
    "docx" -> FileKind.Docx
    "doc" -> FileKind.Doc
    "pptx" -> FileKind.Pptx
    "html", "htm" -> FileKind.Html
    "svg" -> FileKind.Svg
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> FileKind.Image
    "mp4", "webm", "mkv", "mov", "3gp", "m4v", "ts", "avi" -> FileKind.Video
    else -> when {
        mime?.startsWith("video/") == true -> FileKind.Video
        mime?.startsWith("image/") == true -> FileKind.Image
        mime == "text/markdown" -> FileKind.Markdown
        mime == "application/json" -> FileKind.Json
        mime == "text/html" -> FileKind.Html
        else -> FileKind.Text
    }
}
