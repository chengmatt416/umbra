package app.umbra.core.workspace

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class WorkspaceKind {
    /** A project the user asked the agent to work on (cloned or created). */
    @SerialName("project") PROJECT,

    /** A plain repository checkout. */
    @SerialName("repository") REPOSITORY,

    /** Throwaway space; eligible for automatic cleanup. */
    @SerialName("scratch") SCRATCH,
}

/** Who may touch the workspace (spec §22 "workspace permissions"). */
@Serializable
data class WorkspacePermissions(
    /** Linux guest processes (OMP, git, compilers). */
    val linuxRead: Boolean = true,
    val linuxWrite: Boolean = true,
    /** The Android-side agent tools (file operations, APK installs from build outputs). */
    val androidRead: Boolean = true,
    val androidWrite: Boolean = false,
) {
    companion object {
        val DEFAULT = WorkspacePermissions()
        val READ_ONLY = WorkspacePermissions(linuxWrite = false, androidWrite = false)
    }
}

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val kind: WorkspaceKind = WorkspaceKind.PROJECT,
    val createdAtMs: Long,
    val lastUsedAtMs: Long = createdAtMs,
    val permissions: WorkspacePermissions = WorkspacePermissions.DEFAULT,
    /** Sessions currently attached; a workspace with attached sessions is not auto-cleaned. */
    val sessionIds: Set<String> = emptySet(),
    /** Where it came from, for example a git URL. */
    val origin: String? = null,
    val pinned: Boolean = false,
    /** A user-selected directory, linked into the workspace tree. Its contents are never owned or deleted by Umbra. */
    val externalPath: String? = null,
)

/** Directories reserved for one session (spec §22 "session-specific files", "generated artifacts"). */
data class SessionDirs(
    val root: java.nio.file.Path,
    val files: java.nio.file.Path,
    val artifacts: java.nio.file.Path,
    val logs: java.nio.file.Path,
)

class WorkspaceException(message: String) : RuntimeException(message)
class WorkspaceNotFoundException(id: String) : NoSuchElementException("no such workspace: $id")
class WorkspaceInUseException(id: String, sessions: Set<String>) :
    IllegalStateException("workspace $id is in use by sessions ${sessions.sorted()}")
class PathEscapeException(path: String) : SecurityException("path escapes the workspace: $path")
