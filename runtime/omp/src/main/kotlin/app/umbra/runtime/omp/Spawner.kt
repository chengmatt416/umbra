package app.umbra.runtime.omp

import java.io.File

/** Everything needed to start omp, from the host's point of view. Paths are the ones omp itself will see. */
data class SpawnSpec(
    val command: List<String>,
    /** Environment variables. Their values may show up in process listings. */
    val environment: Map<String, String> = emptyMap(),
    /** Variables whose values must stay out of every command line (API keys). */
    val secrets: Map<String, String> = emptyMap(),
    val workingDirectory: String,
    val stderrLines: Int = 200,
)

/** Starts omp somewhere and hands back the pipes to talk to it. */
fun interface OmpSpawner {
    suspend fun spawn(spec: SpawnSpec): OmpTransport
}

/** Runs omp as a child of this process: how it runs on a development machine, and in tests. */
object LocalSpawner : OmpSpawner {
    override suspend fun spawn(spec: SpawnSpec): OmpTransport = ProcessTransport.start(
        command = spec.command,
        environment = spec.environment + spec.secrets,
        workingDirectory = File(spec.workingDirectory).takeIf { it.isDirectory },
        stderrLines = spec.stderrLines,
    )
}

/**
 * The directory omp keeps its config and state in (`PI_CODING_AGENT_DIR`). The host writes
 * `models.yml` and the per-session overlay there, so each session sees exactly the providers and
 * policies the host chose.
 */
interface AgentFiles {
    /** The directory as omp sees it. */
    val ompPath: String

    /** Writes [name] inside the directory, replacing it; the directory is created if needed. */
    suspend fun write(name: String, content: String)
}

/** An agent directory this process can write to directly. [ompPath] differs from [dir] when omp reaches it through a mount. */
class LocalAgentFiles(val dir: java.nio.file.Path, override val ompPath: String = dir.toString()) : AgentFiles {
    override suspend fun write(name: String, content: String) {
        java.nio.file.Files.createDirectories(dir)
        java.nio.file.Files.writeString(dir.resolve(name), content)
    }
}
