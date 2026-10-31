package app.umbra.agent.dashboards

import android.os.FileObserver
import app.umbra.agent.cards.CardBlock
import app.umbra.agent.cards.CardTone
import app.umbra.core.model.UmbraJson
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Watches live pages with app-supplied filesystem and session access. Only the visibility fallback polls;
 * observers continue to work while a page is closed, and each dashboard's model wake has its own budget.
 *
 * @param toHost Maps guest paths, including /workspace itself; canonical containment is checked before reading.
 * @param visible True while any dashboard screen is open, so fallback polling does not run in the background.
 * @param sessions Supplies the complete session list rather than a paginated screen's subset.
 * @param sessionChanges Signals mutations without making this backend depend on a particular session store.
 * @param onAgentRefresh Lets the app retain its normal wake budget and background scheduling policy.
 * @param now Supplies wall time for history and the per-dashboard model wake limit.
 * @param onError Reports service failures; individual source failures are persisted on the source instead.
 */
@OptIn(FlowPreview::class)
class DashboardSources(
    private val store: DashboardStore,
    private val toHost: (String) -> File?,
    private val visible: StateFlow<Boolean>,
    private val sessions: suspend () -> List<Session>,
    private val sessionChanges: Flow<Unit>,
    private val onAgentRefresh: suspend (Dashboard, Source, String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val onError: (Throwable) -> Unit = {},
) {
    private var job: Job? = null
    private val refreshLock = Mutex()
    private val lastWake = mutableMapOf<String, Long>()
    private val appliedTiles = mutableMapOf<Target, List<Tile>>()

    /** Starts once in a child scope; cancelling the parent also releases every native observer. */
    @Synchronized fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val supervisor = SupervisorJob(scope.coroutineContext[Job])
        val worker = CoroutineScope(scope.coroutineContext + supervisor + Dispatchers.IO)
        job = supervisor
        worker.launch {
            val observers = mutableMapOf<Target, List<FileObserver>>()
            val observerLock = Any()
            val pending = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, Job>()
            val events = Channel<Target>(Channel.UNLIMITED)
            fun arm(target: Target) = synchronized(observerLock) {
                observers.remove(target)?.forEach { it.stopWatching() }
                if (target !in targets()) return@synchronized
                val file = runCatching { resolve(target.path) }.getOrNull() ?: return@synchronized
                val watches = mutableListOf<FileObserver>()
                fun watch(directory: File?, name: String?) {
                    if (directory?.isDirectory != true) return
                    runCatching {
                        dashboardObserver(directory, name) { events.trySend(target) }.also { it.startWatching() }
                    }.onSuccess { watches += it }.onFailure(onError)
                }
                if (target.kind == Source.Kind.FOLDER) watch(file, null)
                var child = file
                while (child.parentFile != null && child.parentFile?.isDirectory != true) child = child.parentFile!!
                watch(child.parentFile, child.name)
                observers[target] = watches
            }
            try {
                kotlinx.coroutines.coroutineScope {
                    launch {
                        store.revision.collect {
                            val targets = targets()
                            synchronized(observerLock) {
                                (observers.keys - targets.toSet()).forEach { target ->
                                    observers.remove(target)?.forEach { it.stopWatching() }
                                }
                                targets.filter { it !in observers }.forEach(::arm)
                            }
                            targets.forEach { events.trySend(it) }
                            // TASKS bindings also need their initial values, even before the first session mutation.
                            safely { refreshTasks() }
                        }
                    }
                    launch {
                        for (target in events) {
                            val key = target.dashboardId to target.sourceId
                            pending.remove(key)?.cancel()
                            pending[key] = launch {
                                delay(1500)
                                // Debouncing must never cancel a refresh that is already committing or waking an agent.
                                pending.remove(key, requireNotNull(coroutineContext[Job]))
                                safely { refresh(target) }
                                if (target in targets()) arm(target)
                            }
                        }
                    }
                    launch {
                        visible.collectLatest { open ->
                            if (open) while (true) {
                                targets().forEach { events.trySend(it) }
                                safely { refreshTasks() }
                                delay(60_000)
                            }
                        }
                    }
                    launch { sessionChanges.debounce(2000).collect { safely { refreshTasks() } } }
                }
            } catch (e: Exception) {
                if (e !is CancellationException) onError(e)
                supervisor.cancel()
            } finally {
                events.close()
                synchronized(observerLock) { observers.values.flatten().forEach { it.stopWatching() } }
            }
        }
    }

    /** Stops polling, pending debounces and native watches; the instance can be started again. */
    @Synchronized fun stop() {
        job?.cancel()
        job = null
    }

    private data class Target(val dashboardId: String, val sourceId: String, val kind: Source.Kind, val path: String)

    private fun targets(): List<Target> = store.all().filter { it.status == Dashboard.Status.LIVE }.flatMap { d ->
        d.sources.filter { it.kind == Source.Kind.FILE || it.kind == Source.Kind.FOLDER }
            .map { Target(d.id, it.id, it.kind, requireNotNull(it.path)) }
    }

    private fun resolve(path: String): File {
        requireWorkspacePath(path)
        val base = requireNotNull(toHost("/workspace")) { "Workspace is unavailable" }.canonicalFile
        val file = requireNotNull(toHost(path)) { "Source is unavailable" }.canonicalFile
        require(file.toPath().startsWith(base.toPath())) { "Source leaves /workspace" }
        return file
    }

    private suspend fun refresh(target: Target) = refreshLock.withLock {
        val before = store.get(target.dashboardId) ?: return@withLock
        if (!before.matches(target)) return@withLock
        val source = before.sources.first { it.id == target.sourceId }
        val time = now()
        val snapshot = try { DashboardValues.read(resolve(target.path), target.kind) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            markError(target, reason(e), time)
            return@withLock
        }
        if (source.fingerprint == snapshot.fingerprint && source.error == null &&
            before.tiles.filter { it.binding?.sourceId() == source.id } == appliedTiles[target]) return@withLock
        val changed = source.fingerprint != snapshot.fingerprint || source.error != null
        val summary = (if (target.kind == Source.Kind.FOLDER) {
            val files = requireNotNull(snapshot.files)
            "Folder changed: ${target.path} (${files.size} files, ${files.sumOf { it.size }} bytes)"
        } else "File changed: ${target.path} (${snapshot.fingerprint.substringBefore(':')} bytes; size/mtime ${snapshot.fingerprint})").take(2000)
        val next = try {
            store.update(before.id) { current ->
                if (!current.matches(target)) current else {
                    // Compute all blocks before saving, so a bad parse leaves every bound tile untouched.
                    val tiles = current.tiles.map { tile ->
                        val binding = tile.binding
                        if (binding == null || binding.sourceId() != source.id) tile else DashboardValues.apply(tile, snapshot, emptyList(), time)
                    }
                    current.copy(tiles = tiles, sources = current.sources.map {
                        if (it.id == source.id) it.copy(lastSeenMs = time, fingerprint = snapshot.fingerprint, error = null) else it
                    }, updatedAtMs = time, lastRefreshAtMs = time,
                        // The history keeps the path only; the page says it in the user's words, the agent's wake gets the details.
                        history = if (changed) listOf(Update(time, if (target.kind == Source.Kind.FOLDER) "folder" else "file", target.path)) + current.history else current.history)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            markError(target, reason(e), time)
            return@withLock
        } ?: return@withLock
        appliedTiles[target] = next.tiles.filter { it.binding?.sourceId() == source.id }
        if (changed && next.matches(target)) maybeWake(next, next.sources.first { it.id == source.id }, summary, time)
    }

    private fun Dashboard.matches(target: Target): Boolean = status == Dashboard.Status.LIVE &&
        sources.any { it.id == target.sourceId && it.kind == target.kind && it.path == target.path }

    private fun markError(target: Target, message: String, time: Long) {
        store.update(target.dashboardId) { current ->
            if (!current.matches(target) || current.sources.first { it.id == target.sourceId }.error == message) current
            else current.copy(updatedAtMs = time, sources = current.sources.map { if (it.id == target.sourceId) it.copy(error = message) else it })
        }
    }

    private suspend fun refreshTasks() = refreshLock.withLock {
        val pages = store.all().filter { it.status == Dashboard.Status.LIVE && it.sources.any { source -> source.kind == Source.Kind.TASKS } }
        if (pages.isEmpty()) return@withLock
        val time = now()
        val tasks = try { sessions() } catch (e: Exception) {
            if (e is CancellationException) throw e
            pages.forEach { page -> store.update(page.id) { current ->
                if (current.status != Dashboard.Status.LIVE) current else current.copy(sources = current.sources.map {
                    if (it.kind == Source.Kind.TASKS) it.copy(error = reason(e)) else it
                })
            } }
            return@withLock
        }
        pages.forEach { page ->
            val fingerprint = DashboardValues.taskFingerprint(tasks.filter { it.agentId == page.agentId }, time)
            var changed = false
            val saved = store.update(page.id) { current ->
                if (current.status != Dashboard.Status.LIVE || current.sources.none { it.kind == Source.Kind.TASKS }) current
                else {
                    changed = current.sources.any { it.kind == Source.Kind.TASKS && (it.fingerprint != fingerprint || it.error != null) }
                    val tiles = current.tiles.map { tile ->
                        val binding = tile.binding as? Binding.Tasks
                        if (binding == null) tile else {
                            val applied = DashboardValues.apply(tile, null, tasks, time)
                            if (applied.block == tile.block) tile else applied
                        }
                    }
                    if (!changed && tiles == current.tiles) current else current.copy(tiles = tiles,
                        sources = current.sources.map { if (it.kind == Source.Kind.TASKS) it.copy(lastSeenMs = time, fingerprint = fingerprint, error = null) else it },
                        updatedAtMs = time, lastRefreshAtMs = time,
                        history = listOf(Update(time, "tasks", "Task states updated")) + current.history)
                }
            }
            if (changed && saved != null) maybeWake(saved, saved.sources.first { it.kind == Source.Kind.TASKS }, "Task states updated", time)
        }
    }

    private suspend fun maybeWake(dashboard: Dashboard, source: Source, summary: String, time: Long) {
        if (dashboard.status != Dashboard.Status.LIVE || !dashboard.agentOnChange && dashboard.tiles.none { it.binding == null } ||
            lastWake[dashboard.id]?.let { time - it < 5 * 60_000L } == true) return
        lastWake[dashboard.id] = time
        onAgentRefresh(dashboard, source, summary)
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) { if (e is CancellationException) throw e; onError(e) }
    }

    private fun reason(e: Exception): String = (e.message ?: "Source could not be read").replace(Regex("[\\r\\n]+"), " ").take(300)
}

// Keep Android construction out of the parsing helpers so local JVM tests never need a native observer.
@Suppress("DEPRECATION")
private fun dashboardObserver(directory: File, name: String?, changed: () -> Unit): FileObserver =
    object : FileObserver(directory.path, FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
        FileObserver.CREATE or FileObserver.DELETE or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.ATTRIB) {
        override fun onEvent(event: Int, path: String?) {
            if (name == null || path == name || event and (FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) != 0) changed()
        }
    }

/** Pure parsing and block rewriting shared by file refreshes and JVM tests. */
internal object DashboardValues {
    internal data class Entry(val name: String, val size: Long, val modifiedMs: Long)
    internal data class Snapshot(val fingerprint: String, val text: String? = null, val files: List<Entry>? = null) {
        val json: JsonElement by lazy { UmbraJson.parseToJsonElement(requireNotNull(text)) }
        val csv: List<List<String>> by lazy { DashboardValues.csv(requireNotNull(text)) }
    }

    /** Takes a bounded snapshot so every binding in one refresh reads the same input. */
    fun read(file: File, kind: Source.Kind): Snapshot {
        if (kind == Source.Kind.FOLDER) {
            require(file.isDirectory) { "Folder is missing" }
            val children = requireNotNull(file.listFiles()) { "Folder is unreadable" }
            require(children.size <= 10_000) { "Folder has more than 10000 entries" }
            val entries = children.filter { !Files.isSymbolicLink(it.toPath()) && it.isFile }
                .map { Entry(it.name, it.length(), it.lastModified()) }.sortedBy { it.name }
            val fingerprint = digest(buildString {
                append(file.lastModified()).append('\n')
                children.sortedBy { it.name }.forEach { append(it.name.length).append(':').append(it.name).append(':').append(it.length()).append(':').append(it.lastModified()).append('\n') }
            })
            return Snapshot(fingerprint, files = entries)
        }
        require(kind == Source.Kind.FILE && file.isFile) { "File is missing" }
        require(file.length() <= 2 * 1024 * 1024) { "File exceeds 2 MB" }
        val modified = file.lastModified()
        val bytes = file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 2 * 1024 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= 2 * 1024 * 1024) { "File exceeds 2 MB" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("File is not UTF-8 text") }
        require(text.none { it.isISOControl() && it !in "\t\n\r" }) { "File contains binary content" }
        require(file.length() == bytes.size.toLong() && file.lastModified() == modified) { "File changed while being read" }
        return Snapshot("${bytes.size}:$modified", text)
    }

    /** Rejects combinations whose meaning would require a model to guess which block part to replace. */
    fun accepts(block: CardBlock, binding: Binding): Boolean = when (binding) {
        is Binding.JsonValue -> block is CardBlock.Stats && block.items.isNotEmpty() || block is CardBlock.Progress || block is CardBlock.Gauge
        is Binding.JsonTable -> block is CardBlock.Table || block is CardBlock.Items || block is CardBlock.Bars || block is CardBlock.Columns
        is Binding.CsvTable -> block is CardBlock.Table || block is CardBlock.Columns || block is CardBlock.Line
        is Binding.TextTail -> block is CardBlock.Code
        is Binding.TextCount -> block is CardBlock.Stats && block.items.isNotEmpty()
        is Binding.FolderList -> block is CardBlock.Items
        is Binding.FolderStats, is Binding.Tasks -> block is CardBlock.Stats || binding is Binding.Tasks && block is CardBlock.Items
    }

    /** Preserves tile identity, layout and annotations while replacing its computable content. */
    fun apply(tile: Tile, snapshot: Snapshot?, sessions: List<Session>, now: Long): Tile {
        val binding = tile.binding ?: return tile
        return tile.copy(block = apply(tile.block, binding, snapshot, sessions, now), updatedAtMs = now)
    }

    /** Copies only bound values and rows, keeping the card's labels, tones and other presentation choices. */
    fun apply(block: CardBlock, binding: Binding, snapshot: Snapshot?, sessions: List<Session>, now: Long): CardBlock {
        require(accepts(block, binding)) { "Binding has an incompatible block" }
        return when (binding) {
            is Binding.JsonValue -> value(block, scalar(jsonPath(requireNotNull(snapshot).json, binding.path)))
            is Binding.JsonTable -> {
                val array = jsonPath(requireNotNull(snapshot).json, binding.path) as? JsonArray ?: error("JSON path is not an array")
                require(array.size <= 200) { "JSON table exceeds 200 rows" }
                val rows = array.map { element ->
                    val obj = element as? JsonObject ?: error("JSON table rows must be objects")
                    binding.columns.map { key -> scalar(obj[key] ?: error("Missing column: $key")) }
                }
                table(block, binding.columns, rows)
            }
            is Binding.CsvTable -> {
                val parsed = requireNotNull(snapshot).csv
                val header = parsed.first()
                val columns = binding.columns ?: header
                val indices = columns.map { column -> header.indexOf(column).also { require(it >= 0) { "Missing CSV column: $column" } } }
                table(block, columns, parsed.drop(1).take(binding.limit).map { row -> indices.map { row[it] } })
            }
            is Binding.TextTail -> (block as CardBlock.Code).copy(code = tail(requireNotNull(snapshot?.text), binding.lines))
            is Binding.TextCount -> value(block, count(requireNotNull(snapshot?.text), binding.regex).toString())
            is Binding.FolderList -> {
                val files = requireNotNull(snapshot?.files)
                val sorted = when (binding.sort) {
                    FolderSort.NEWEST -> files.sortedWith(compareByDescending<Entry> { it.modifiedMs }.thenBy { it.name })
                    FolderSort.NAME -> files.sortedBy { it.name }
                    FolderSort.SIZE -> files.sortedWith(compareByDescending<Entry> { it.size }.thenBy { it.name })
                }.take(binding.limit)
                val items = block as CardBlock.Items
                items.copy(items = sorted.mapIndexed { index, entry ->
                    item(items, index).copy(title = entry.name, detail = TileWords.size(entry.size), meta = TileWords.time(entry.modifiedMs, now))
                })
            }
            is Binding.FolderStats -> {
                val files = requireNotNull(snapshot?.files)
                stats(block as CardBlock.Stats, listOf(TileWords.files() to files.size.toString(), TileWords.size() to TileWords.size(files.sumOf { it.size }),
                    TileWords.lastChange() to (files.maxOfOrNull { it.modifiedMs }?.let { TileWords.time(it, now) } ?: "—")))
            }
            is Binding.Tasks -> tasks(block, sessions.filter { it.agentId == binding.agentId }, now)
        }
    }

    /** Evaluates keys and array indices without allowing executable expressions or recursive queries. */
    fun jsonPath(root: JsonElement, path: String): JsonElement {
        if (path.isEmpty() || path == "$") return root
        val input = if (path.startsWith("$.")) path.substring(2) else if (path.startsWith("$[")) path.substring(1) else path
        var current = root
        var i = 0
        var needKey = true
        while (i < input.length) {
            if (input[i] == '[') {
                require(!needKey || i == 0) { "Invalid JSON path" }
                val end = input.indexOf(']', i + 1)
                require(end > i + 1 && input.substring(i + 1, end).all { it in '0'..'9' }) { "Invalid JSON array index" }
                val index = input.substring(i + 1, end).toIntOrNull() ?: error("Invalid JSON array index")
                current = requireNotNull((current as? JsonArray)?.getOrNull(index)) { "JSON array index is missing" }
                i = end + 1
                needKey = false
            } else {
                require(needKey) { "Invalid JSON path" }
                val start = i
                while (i < input.length && input[i] != '.' && input[i] != '[' && input[i] != ']') i++
                require(i > start) { "Invalid JSON path" }
                current = requireNotNull((current as? JsonObject)?.get(input.substring(start, i))) { "JSON key is missing" }
                needKey = false
            }
            if (i < input.length && input[i] == '.') { i++; needKey = true }
        }
        require(!needKey) { "Invalid JSON path" }
        return current
    }

    /** Reads quoted records so embedded delimiters, escaped quotes and log-like newlines stay in their cells. */
    fun csv(text: String): List<List<String>> {
        val input = text.removePrefix("\uFEFF")
        var quoted = false
        var delimiter = ','
        for (c in input) {
            if (c == '"') quoted = !quoted
            if (!quoted && c == '\t') { delimiter = '\t'; break }
            if (!quoted && c in "\r\n") break
        }
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        quoted = false
        var closed = false
        var started = false
        var i = 0
        fun endField() { row += field.toString(); field.setLength(0); closed = false; started = false }
        fun endRow() { endField(); rows += row; row = mutableListOf(); require(rows.size <= 10_001) { "CSV exceeds 10000 rows" } }
        while (i < input.length) {
            val c = input[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length && input[i + 1] == '"') { field.append('"'); i++ }
                    else { quoted = false; closed = true }
                } else field.append(c)
            } else when {
                c == delimiter -> endField()
                c == '\n' || c == '\r' -> { endRow(); if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++ }
                c == '"' && !started && !closed -> { quoted = true; started = true }
                else -> { require(!closed && c != '"') { "Malformed CSV quote" }; field.append(c); started = true }
            }
            i++
        }
        require(!quoted) { "Unclosed CSV quote" }
        if (started || closed || field.isNotEmpty() || row.isNotEmpty()) endRow()
        require(rows.isNotEmpty() && rows.first().all { it.isNotBlank() } && rows.first().distinct().size == rows.first().size) { "CSV needs unique nonempty headers" }
        require(rows.all { it.size == rows.first().size }) { "CSV rows have inconsistent column counts" }
        return rows
    }

    /** Treats the final newline as a record terminator rather than an extra empty log line. */
    fun tail(text: String, lines: Int): String {
        require(lines in 1..1000)
        return text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").split('\n').takeLast(lines).joinToString("\n")
    }

    /** Counts matching lines once each, even if a line contains several matches. */
    fun count(text: String, regex: String): Int {
        val pattern = pattern(regex)
        return text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").let {
            if (it.isEmpty() && text.isEmpty()) 0 else it.lineSequence().count { line -> pattern.matcher(line).find() }
        }
    }

    /** Uses the app's existing non-backtracking engine so a log line cannot stall the watcher. */
    fun pattern(regex: String): Pattern {
        require(regex.length <= 1000) { "Line patterns are limited to 1000 characters" }
        val pattern = try { Pattern.compile(regex) }
        catch (e: PatternSyntaxException) { throw IllegalArgumentException("Use a valid RE2 line pattern; lookarounds and backreferences are not supported", e) }
        require(pattern.programSize() <= 4096) { "This line pattern is too complex" }
        return pattern
    }

    /** Includes the local date so yesterday's terminal sessions leave today's counts after midnight. */
    fun taskFingerprint(sessions: List<Session>, now: Long): String = digest(
        LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).toString() + "\n" +
            sessions.sortedBy { it.id }.joinToString("\n") { "${it.id}:${it.state}:${it.updatedAtMs}:${it.title}" })

    private fun scalar(element: JsonElement): String {
        require(element is JsonPrimitive && element != JsonNull) { "JSON value must be a non-null scalar" }
        return element.content
    }

    private fun number(value: String): Double = requireNotNull(value.toDoubleOrNull()?.takeIf { it.isFinite() }) { "Bound value is not a finite number" }

    private fun value(block: CardBlock, value: String): CardBlock = when (block) {
        is CardBlock.Stats -> block.copy(items = block.items.mapIndexed { index, stat -> if (index == 0) stat.copy(value = value) else stat })
        is CardBlock.Progress -> block.copy(value = number(value))
        is CardBlock.Gauge -> block.copy(value = number(value), display = value)
        else -> error("Expected a value block")
    }

    private fun table(block: CardBlock, columns: List<String>, rows: List<List<String>>): CardBlock = when (block) {
        is CardBlock.Table -> block.copy(columns = if (block.columns.size == columns.size) block.columns else columns, rows = rows,
            numericColumns = block.numericColumns.filter { it in columns.indices }, highlightRow = block.highlightRow?.takeIf { it in rows.indices })
        is CardBlock.Items -> block.copy(items = rows.mapIndexed { index, row -> item(block, index).copy(
            title = row.first(), detail = row.drop(1).joinToString(" · ").ifEmpty { null }) })
        is CardBlock.Bars -> { require(columns.size >= 2) { "Bars need label and numeric columns" }; block.copy(items = rows.mapIndexed { index, row ->
            (block.items.getOrNull(index) ?: block.items.firstOrNull() ?: CardBlock.Bars.Bar("", 0.0)).copy(label = row[0], value = number(row[1]), display = row[1])
        }) }
        is CardBlock.Columns -> block.copy(points = rows.mapIndexed { index, row ->
            CardBlock.Columns.Point(if (columns.size == 1) (index + 1).toString() else row[0], number(row[if (columns.size == 1) 0 else 1]))
        })
        is CardBlock.Line -> {
            val values = if (columns.size == 1) columns.indices.toList() else columns.indices.drop(1)
            block.copy(labels = rows.mapIndexed { index, row -> if (columns.size == 1) (index + 1).toString() else row[0] },
                series = values.mapIndexed { index, column ->
                    (block.series.getOrNull(index) ?: CardBlock.Line.Series(columns[column], emptyList())).copy(points = rows.map { number(it[column]) })
                })
        }
        else -> error("Expected a table or chart block")
    }

    private fun item(block: CardBlock.Items, index: Int): CardBlock.Items.Item =
        block.items.getOrNull(index) ?: block.items.firstOrNull() ?: CardBlock.Items.Item("")

    /**
     * Fills the figures the agent chose to show, in order, under its own labels; a tile it left empty gets them all
     * under Umbra's labels. Two figures asked for means two figures shown, not every one Umbra can compute.
     */
    private fun stats(block: CardBlock.Stats, values: List<Pair<String, String>>): CardBlock.Stats =
        if (block.items.isEmpty()) block.copy(items = values.map { (label, value) -> CardBlock.Stat(label, value) })
        else block.copy(items = block.items.mapIndexed { index, stat -> values.getOrNull(index)?.let { stat.copy(value = it.second) } ?: stat })

    private fun tasks(block: CardBlock, sessions: List<Session>, now: Long): CardBlock {
        val start = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = LocalDate.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun today(session: Session) = session.updatedAtMs in start until end
        if (block is CardBlock.Stats) return stats(block, listOf(
            TileWords.running() to sessions.count { it.state == SessionState.RUNNING }.toString(),
            TileWords.waiting() to sessions.count { it.state == SessionState.WAITING_APPROVAL || it.state == SessionState.CREATED || it.state == SessionState.PAUSED }.toString(),
            TileWords.doneToday() to sessions.count { it.state == SessionState.COMPLETED && today(it) }.toString(),
            TileWords.failedToday() to sessions.count { it.state == SessionState.FAILED && today(it) }.toString()))
        val list = block as CardBlock.Items
        // Each session as the user knows it: its title, what it is doing in words, and when it last moved (never its id).
        return list.copy(items = sessions.filter { !it.state.isTerminal || today(it) && it.state != SessionState.STOPPED }
            .sortedWith(compareByDescending<Session> { it.state == SessionState.RUNNING }.thenByDescending { it.updatedAtMs }).take(100)
            .mapIndexed { index, session -> item(list, index).copy(title = session.title, detail = TileWords.state(session.state), meta = TileWords.time(session.updatedAtMs, now),
                tone = when (session.state) { SessionState.FAILED -> CardTone.DANGER; SessionState.WAITING_APPROVAL -> CardTone.WARN; SessionState.COMPLETED -> CardTone.GOOD; else -> item(list, index).tone }) })
    }

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

/**
 * The few words and figures Umbra itself writes into a bound tile, the way the rest of the app shows them: sizes in
 * B/KB/MB, times as the phone's short time (a date too when not today), session states in words, labels in the phone's
 * language (Chinese or English). Never raw byte counts, ISO timestamps or ids.
 */
internal object TileWords {
    private val zh get() = java.util.Locale.getDefault().language == "zh"
    fun files() = if (zh) "檔案" else "Files"
    fun size() = if (zh) "大小" else "Size"
    fun lastChange() = if (zh) "最近變更" else "Last change"
    fun running() = if (zh) "進行中" else "Running"
    fun waiting() = if (zh) "等待中" else "Waiting"
    fun doneToday() = if (zh) "今天完成" else "Done today"
    fun failedToday() = if (zh) "今天失敗" else "Failed today"

    fun state(state: SessionState): String = when (state) {
        SessionState.CREATED -> if (zh) "排隊中" else "Queued"
        SessionState.RUNNING -> if (zh) "進行中" else "Running"
        SessionState.PAUSED -> if (zh) "已暫停" else "Paused"
        SessionState.WAITING_APPROVAL -> if (zh) "等你核准" else "Waiting for you"
        SessionState.STOPPED -> if (zh) "已停止" else "Stopped"
        SessionState.COMPLETED -> if (zh) "完成" else "Done"
        SessionState.FAILED -> if (zh) "失敗" else "Failed"
    }

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    }.replace(".0 ", " ")

    fun time(atMs: Long, nowMs: Long): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(atMs).atZone(zone)
        val sameDay = at.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val format = if (sameDay) java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
            else java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
        return format.withLocale(java.util.Locale.getDefault()).format(at)
    }
}
