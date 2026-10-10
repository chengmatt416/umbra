package app.umbra.node

import app.umbra.core.nodes.DataManifest
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.JobStatus
import app.umbra.core.nodes.ManifestException
import app.umbra.core.nodes.Manifests
import app.umbra.core.nodes.Rsp
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JobException(message: String) : Exception(message)

/**
 * The node's jobs (architecture spec §10.2). Each job has its own folder; its files arrive only as its manifest lists
 * them; its process runs in that folder with a small environment (no secrets are inherited), and only until its lease
 * runs out: the phone renews the lease while it is connected; when it does not, the node ends the job at the lease's
 * end, whether or not the phone ever hears of it. Statuses are kept on disk so the phone can reconcile at rejoin.
 */
class Jobs(private val root: File, private val epoch: String, private val maxManifestBytes: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private class Rec(@Volatile var status: JobStatus, val process: Process?, val output: StringBuilder = StringBuilder())

    private val jobs = ConcurrentHashMap<String, Rec>()
    val sinks = CopyOnWriteArraySet<(JsonObject) -> Unit>()

    init {
        root.mkdirs()
        // Jobs of an earlier run of the runner: their processes are no longer watched, and are reported as such.
        root.listFiles()?.forEach { dir ->
            val f = File(dir, "status.json")
            if (f.exists()) runCatching {
                val s = Rsp.json.decodeFromString(JobStatus.serializer(), f.readText())
                val now = if (s.state == JobState.RUNNING && s.epoch != epoch) s.copy(state = JobState.LOST, endedAtMs = clock(), note = "the runner restarted (epoch changed); this job's process is no longer watched") else s
                jobs[s.job] = Rec(now, null)
                if (now != s) save(now)
            }
        }
    }

    private fun dir(job: String): File {
        if (!job.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) throw JobException("bad job id")
        return File(root, job)
    }

    private fun files(job: String) = File(dir(job), "files")

    /** The manifest comes first: nothing arrives for a job that is not listed in it. */
    fun manifest(job: String, m: DataManifest) {
        if (jobs[job]?.status?.state == JobState.RUNNING) throw JobException("job $job is running")
        if (m.maxBytes > maxManifestBytes) throw JobException("the manifest allows ${m.maxBytes} bytes; this node takes at most $maxManifestBytes")
        if (m.totalBytes > m.maxBytes) throw JobException("the files come to ${m.totalBytes} bytes, over the manifest's ${m.maxBytes}")
        if (m.secrets.isNotEmpty()) throw JobException("this node takes no secrets")
        try { m.files.forEach { Manifests.checkRelative(it.path) } } catch (e: ManifestException) { throw JobException(e.message ?: "bad path") }
        files(job).mkdirs()
        File(dir(job), "manifest.json").writeText(Rsp.json.encodeToString(DataManifest.serializer(), m))
    }

    private fun manifestOf(job: String): DataManifest {
        val f = File(dir(job), "manifest.json")
        if (!f.exists()) throw JobException("job $job has no manifest")
        return Rsp.json.decodeFromString(DataManifest.serializer(), f.readText())
    }

    /** One chunk of a listed file, at its offset; the last one is checked against the manifest's hash. */
    fun put(job: String, path: String, offset: Long, data: String, last: Boolean) {
        val entry = manifestOf(job).files.firstOrNull { it.path == path } ?: throw JobException("“$path” is not in the job's manifest")
        val bytes = Base64.getDecoder().decode(data)
        val target = File(files(job), entry.path)
        if (!target.canonicalPath.startsWith(files(job).canonicalPath + File.separator)) throw JobException("“$path” leaves the job's folder")
        target.parentFile.mkdirs()
        if (offset == 0L) target.delete()
        if (target.length() != offset) throw JobException("chunk at $offset, but ${target.length()} bytes are there")
        if (offset + bytes.size > entry.bytes) throw JobException("“$path” would exceed its listed ${entry.bytes} bytes")
        RandomAccessFile(target, "rw").use { it.seek(offset); it.write(bytes) }
        if (last) {
            val sha = Manifests.sha256(target.toPath())
            if (target.length() != entry.bytes || sha != entry.sha256) {
                target.delete()
                throw JobException("“$path” arrived different from its manifest (size or hash)")
            }
        }
    }

    fun run(job: String, argv: List<String>, cwd: String, env: Map<String, String>, leaseMs: Long): JobStatus {
        if (argv.isEmpty()) throw JobException("nothing to run")
        if (leaseMs <= 0 || leaseMs > 24 * 3_600_000L) throw JobException("a lease is between 1 ms and 24 hours")
        if (jobs[job]?.status?.state == JobState.RUNNING) throw JobException("job $job is already running")
        val base = files(job).apply { mkdirs() }
        val work = if (cwd.isBlank() || cwd == ".") base else File(base, try { Manifests.checkRelative(cwd) } catch (e: ManifestException) { throw JobException(e.message ?: "bad cwd") })
        if (!work.canonicalPath.startsWith(base.canonicalPath)) throw JobException("the working folder leaves the job's folder")
        work.mkdirs()
        val tmp = File(dir(job), "tmp").apply { mkdirs() }
        val now = clock()
        val pb = ProcessBuilder(argv).directory(work).redirectErrorStream(false)
        val e = pb.environment()
        val keep = setOf("PATH", "HOME", "LANG", "USER", "SHELL")
        e.keys.retainAll(keep)
        e["TMPDIR"] = tmp.path
        env.forEach { (k, v) -> if (k.matches(Regex("[A-Z_][A-Z0-9_]{0,63}"))) e[k] = v }
        val process = try {
            pb.start()
        } catch (ex: Exception) {
            val failed = JobStatus(job, JobState.FAILED_TO_START, null, now, now, now + leaseMs, epoch, note = ex.message)
            jobs[job] = Rec(failed, null)
            save(failed)
            throw JobException("could not start: ${ex.message}")
        }
        val rec = Rec(JobStatus(job, JobState.RUNNING, null, now, null, now + leaseMs, epoch, note = "pid ${process.pid()}"), process)
        jobs[job] = rec
        save(rec.status)
        pump(job, rec, process.inputStream, "stdout")
        pump(job, rec, process.errorStream, "stderr")
        Thread({
            val code = process.waitFor()
            synchronized(rec) {
                if (rec.status.state == JobState.RUNNING) rec.status = rec.status.copy(state = JobState.EXITED, exit = code, endedAtMs = clock())
                else rec.status = rec.status.copy(exit = code)
            }
            Thread.sleep(150) // let the output pumps finish
            rec.status = rec.status.copy(outputTail = tail(rec))
            save(rec.status)
            emit(buildJsonObject { put("event", "exit"); put("job", job); put("state", rec.status.state.name); put("code", code) })
        }, "job-$job-wait").apply { isDaemon = true }.start()
        return rec.status
    }

    private fun pump(job: String, rec: Rec, input: java.io.InputStream, stream: String) {
        Thread({
            val buf = ByteArray(8192)
            input.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    val text = String(buf, 0, n, Charsets.UTF_8)
                    synchronized(rec.output) {
                        rec.output.append(text)
                        if (rec.output.length > 64_000) rec.output.delete(0, rec.output.length - 64_000)
                    }
                    emit(buildJsonObject { put("event", "output"); put("job", job); put("stream", stream); put("data", text) })
                }
            }
        }, "job-$job-$stream").apply { isDaemon = true }.start()
    }

    private fun tail(rec: Rec) = synchronized(rec.output) { rec.output.takeLast(4_000).toString() }

    fun renew(job: String, leaseMs: Long): JobStatus {
        val rec = jobs[job] ?: throw JobException("no job $job")
        if (rec.status.state != JobState.RUNNING) throw JobException("job $job is not running")
        rec.status = rec.status.copy(leaseUntilMs = maxOf(rec.status.leaseUntilMs, clock() + leaseMs))
        save(rec.status)
        return rec.status
    }

    fun cancel(job: String): JobStatus {
        val rec = jobs[job] ?: throw JobException("no job $job")
        end(rec, JobState.CANCELLED, "cancelled from the phone")
        return rec.status
    }

    /** Ends jobs whose lease ran out: the node does this itself, connected or not. */
    fun enforceLeases() {
        val now = clock()
        for (rec in jobs.values) if (rec.status.state == JobState.RUNNING && now > rec.status.leaseUntilMs) end(rec, JobState.LEASE_EXPIRED, "its lease ran out at ${rec.status.leaseUntilMs}")
    }

    private fun end(rec: Rec, state: JobState, why: String) {
        val p = rec.process ?: return
        synchronized(rec) {
            if (rec.status.state != JobState.RUNNING) return
            rec.status = rec.status.copy(state = state, endedAtMs = clock(), note = why)
        }
        p.descendants().forEach { it.destroy() }
        p.destroy()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.descendants().forEach { it.destroyForcibly() }
            p.destroyForcibly()
        }
        save(rec.status)
    }

    fun list(): List<JobStatus> = jobs.values.map { r -> r.status.copy(outputTail = if (r.process != null) tail(r) else r.status.outputTail) }.sortedBy { it.startedAtMs }

    fun fetch(job: String, path: String): ByteArray {
        val base = files(job)
        val f = File(base, try { Manifests.checkRelative(path) } catch (e: ManifestException) { throw JobException(e.message ?: "bad path") })
        if (!f.canonicalPath.startsWith(base.canonicalPath + File.separator) || !f.isFile) throw JobException("no file “$path” in job $job")
        if (f.length() > 16L * 1024 * 1024) throw JobException("“$path” is over 16 MB")
        return f.readBytes()
    }

    /** Retention `delete-after-job`: the phone has the result, the job's folder goes. */
    fun forget(job: String) {
        val rec = jobs[job]
        if (rec?.status?.state == JobState.RUNNING) throw JobException("job $job is running")
        dir(job).deleteRecursively()
        jobs.remove(job)
    }

    private fun save(s: JobStatus) {
        val d = dir(s.job).apply { mkdirs() }
        File(d, "status.json").writeText(Rsp.json.encodeToString(JobStatus.serializer(), s))
    }

    private fun emit(event: JsonObject) {
        for (sink in sinks) runCatching { sink(event) }
    }
}
