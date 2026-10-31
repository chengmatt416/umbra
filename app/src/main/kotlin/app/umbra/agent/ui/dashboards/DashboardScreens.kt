package app.umbra.agent.ui.dashboards

import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.proactive.CronText
import app.umbra.agent.cards.BlockView
import app.umbra.agent.cards.CardBlock
import app.umbra.agent.cards.CardState
import app.umbra.agent.cards.iconNamed
import app.umbra.agent.container
import app.umbra.agent.dashboards.Dashboard
import app.umbra.agent.dashboards.DashboardPath
import app.umbra.agent.dashboards.Source
import app.umbra.agent.dashboards.Tile
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.PorcelainPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Live dashboards (2026-10-28): a page an agent keeps for something that changes, drawn with the card design system
 * so a tile looks like a piece of a card. Tiles sit two to a row (a wide one alone), paired tiles share a height.
 * Nothing loops: when a value changes, its tile warms for a moment and the live dot rings once. Below the tiles,
 * where the numbers come from, when the agent looks again, and what changed lately, in words.
 */

private val TILE = RoundedCornerShape(20.dp)

/** Minutes tick on while a dashboard is open, so "2 min ago" stays true without anything moving. */
@Composable
private fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    return now
}

/** Tells the sources' fallback poll that someone is looking, for as long as this screen is shown. */
@Composable
private fun Watching() {
    val container = LocalContext.current.container
    DisposableEffect(Unit) {
        container.dashboardViewers.value++
        onDispose { container.dashboardViewers.value-- }
    }
}

private fun ago(at: Long?, now: Long): String? = at?.takeIf { it > 0 }?.let {
    if (now - it < DateUtils.MINUTE_IN_MILLIS) null else DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

@Composable
private fun statusLine(d: Dashboard, now: Long): String {
    val updated = ago(d.lastRefreshAtMs ?: d.updatedAtMs, now)
    return when (d.status) {
        Dashboard.Status.LIVE -> if (updated == null) stringResource(R.string.dash_live_now) else stringResource(R.string.dash_live_ago, updated)
        Dashboard.Status.PAUSED -> stringResource(R.string.dash_paused)
        Dashboard.Status.ERROR -> stringResource(R.string.dash_error)
    }
}

/** The status dot: green while live, grey paused, red when something is wrong; it rings once when a value arrives. */
@Composable
private fun LiveDot(status: Dashboard.Status, changedAt: Long) {
    val context = LocalContext.current
    val color = when (status) { Dashboard.Status.LIVE -> P.good; Dashboard.Status.PAUSED -> P.muted; Dashboard.Status.ERROR -> P.danger }
    val ring = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(changedAt) {
        if (first) { first = false; return@LaunchedEffect }
        if (!motionAllowed(context)) return@LaunchedEffect
        ring.snapTo(1f); ring.animateTo(0f, tween(1400))
    }
    Box(Modifier.size(18.dp).drawBehind {
        val r = 4.dp.toPx()
        if (ring.value > 0f) drawCircle(color.copy(alpha = ring.value * .5f), radius = r + (1f - ring.value) * 5.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        drawCircle(color, radius = r, center = Offset(size.width / 2, size.height / 2))
    })
}

@Composable
fun DashboardScreen(id: String, onBack: () -> Unit, onOpenAgent: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val store = container.dashboards
    val revision by store.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // null while loading; a result holding null once it is known the dashboard is gone.
    val loaded by produceState<Result<DashboardPath?>?>(null, id, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching { store.get(id)?.let { d -> store.paths(d.agentId).firstOrNull { it.dashboard.id == id } } }
        }
    }
    val page = loaded?.getOrNull()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val now = rememberNow()
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var asked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Watching()
    val p = page
    val d = p?.dashboard
    val agent = d?.let { dash -> profiles.firstOrNull { it.id == dash.agentId } }
    fun run(action: suspend () -> Unit) { scope.launch {
        try { withContext(Dispatchers.IO) { action() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    } }
    val refreshText = d?.let { stringResource(R.string.dash_refresh_message, it.title, it.id) }.orEmpty()

    PorcelainPage(d?.title ?: stringResource(R.string.dash_title), onBack, actions = {
        if (d != null) Box {
            PIconButton(PIcons.more, stringResource(R.string.dash_actions), { menu = true }, size = 36.dp)
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.dash_refresh_now)) }, onClick = {
                    menu = false
                    run { container.agents.talk(d.agentId, refreshText) }
                    asked = true
                })
                DropdownMenuItem(text = { Text(stringResource(if (d.status == Dashboard.Status.PAUSED) R.string.dash_resume else R.string.dash_pause)) }, onClick = {
                    menu = false
                    run { store.update(d.id) { it.copy(status = if (it.status == Dashboard.Status.PAUSED) Dashboard.Status.LIVE else Dashboard.Status.PAUSED, updatedAtMs = System.currentTimeMillis()) } }
                })
                if (d.refreshCron != null) DropdownMenuItem(text = { Text(stringResource(R.string.dash_stop_schedule)) }, onClick = {
                    menu = false
                    run { container.dashboardTools.unschedule(d.id) }
                })
                agent?.let { a -> DropdownMenuItem(text = { Text(stringResource(R.string.dash_open_chat, a.name)) }, onClick = { menu = false; onOpenAgent(a.id) }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.dash_remove), color = P.danger) }, onClick = { menu = false; confirmRemove = true })
            }
        }
    }) {
        when {
            loaded == null -> Box(Modifier.fillMaxSize())
            p == null || d == null -> PEmpty(stringResource(R.string.dash_gone_title), loaded?.exceptionOrNull()?.message ?: stringResource(R.string.dash_gone_body), icon = PIcons.dashboard)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "head") { Head(d, now, agent?.name) }
                if (asked) item(key = "asked") { Callout(stringResource(R.string.dash_asked, agent?.name.orEmpty()), Tone.Good) }
                error?.let { e -> item(key = "error") { Callout(e, Tone.Danger) } }
                if (p.tiles.isEmpty()) item(key = "empty") {
                    Text(stringResource(R.string.dash_no_tiles, agent?.name.orEmpty()), Modifier.padding(vertical = 24.dp), fontSize = 14.sp, color = P.sub)
                }
                items(rows(p.tiles), key = { row -> row.joinToString("+") { it.id } }) { row -> TileRow(row, d.sources, now) }
                if (d.sources.isNotEmpty()) item(key = "sources") { SourcesSection(d.sources, now) }
                item(key = "schedule") { ScheduleSection(d, p.nextRefreshAtMs) }
                if (d.history.isNotEmpty()) item(key = "history") { HistorySection(d, now, agent?.name) }
            }
        }
    }
    if (confirmRemove && d != null) PConfirm(
        stringResource(R.string.dash_remove_title, d.title), stringResource(R.string.dash_remove_body), stringResource(R.string.dash_remove),
        onConfirm = { confirmRemove = false; run { container.dashboardTools.remove(d.id) }; onBack() },
        onDismiss = { confirmRemove = false },
    )
}

@Composable
private fun Head(d: Dashboard, now: Long, agentName: String?) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LiveDot(d.status, d.updatedAtMs)
            Text(statusLine(d, now), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (d.status == Dashboard.Status.ERROR) P.danger else P.sub)
            agentName?.let { Text("· " + stringResource(R.string.dash_kept_by, it), fontSize = 12.5.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        d.subtitle?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = P.sub) }
        d.statusText?.takeIf { it.isNotBlank() }?.let { text ->
            if (d.status == Dashboard.Status.ERROR) Callout(text, Tone.Danger)
            else Text(text, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.base2).padding(horizontal = 14.dp, vertical = 10.dp),
                fontSize = 13.5.sp, lineHeight = 19.sp, color = P.ink)
        }
    }
}

/** Two half tiles to a row, a wide one alone; a half tile with no partner takes the row. */
private fun rows(tiles: List<Tile>): List<List<Tile>> {
    val out = ArrayList<List<Tile>>()
    var half: Tile? = null
    for (t in tiles) {
        if (t.size == Tile.Size.WIDE) {
            half?.let { out += listOf(it); half = null }
            out += listOf(t)
        } else if (half == null) half = t
        else { out += listOf(half!!, t); half = null }
    }
    half?.let { out += listOf(it) }
    return out
}

@Composable
private fun TileRow(row: List<Tile>, sources: List<Source>, now: Long) {
    if (row.size == 1) return TileView(row[0], sources, now, Modifier.fillMaxWidth())
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        row.forEach { TileView(it, sources, now, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun TileView(tile: Tile, sources: List<Source>, now: Long, modifier: Modifier) {
    val warm = remember { Animatable(0f) }
    var first by remember { mutableStateOf(true) }
    val context = LocalContext.current
    LaunchedEffect(tile.updatedAtMs) {
        if (first) { first = false; return@LaunchedEffect }
        if (!motionAllowed(context)) return@LaunchedEffect
        warm.snapTo(1f); warm.animateTo(0f, tween(1100))
    }
    val source = tile.binding?.let { b -> sources.firstOrNull { s -> s.id == sourceIdOf(b) } }
    val highlight = P.highlight
    Column(
        modifier.heightIn(min = if (tile.size == Tile.Size.TALL) 220.dp else 0.dp).inkSurface(TILE, CardStyle.Ceramic)
            .drawBehind { if (warm.value > 0f) drawRect(highlight.copy(alpha = warm.value * .8f)) }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tile.title, Modifier.weight(1f).semantics { heading() }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tile.binding != null) PIcon(PIcons.link, size = 13.dp, tint = P.muted)
        }
        BlockView(tile.block, CardState(), onEvent = {}, interactive = false, cardTitle = null)
        tile.note?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = P.sub) }
        // Paired tiles share a height: the footer settles at the bottom. A tile alone has no height to share, and the
        // spacer is nothing (a weight in an unbounded column gets no room, which is why the block itself has none).
        Spacer(Modifier.weight(1f))
        val footer = listOfNotNull(source?.let { sourceName(it) }, ago(tile.updatedAtMs, now)).joinToString(" · ")
        if (footer.isNotEmpty()) Text(footer, fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun sourceIdOf(b: app.umbra.agent.dashboards.Binding): String? = when (b) {
    is app.umbra.agent.dashboards.Binding.JsonValue -> b.sourceId
    is app.umbra.agent.dashboards.Binding.JsonTable -> b.sourceId
    is app.umbra.agent.dashboards.Binding.CsvTable -> b.sourceId
    is app.umbra.agent.dashboards.Binding.TextTail -> b.sourceId
    is app.umbra.agent.dashboards.Binding.TextCount -> b.sourceId
    is app.umbra.agent.dashboards.Binding.FolderList -> b.sourceId
    is app.umbra.agent.dashboards.Binding.FolderStats -> b.sourceId
    is app.umbra.agent.dashboards.Binding.Tasks -> null
}

@Composable
private fun sourceName(s: Source): String = when (s.kind) {
    Source.Kind.FILE, Source.Kind.FOLDER -> s.path?.trimEnd('/')?.substringAfterLast('/')?.ifEmpty { null } ?: stringResource(R.string.dash_source_file)
    Source.Kind.MODEL -> stringResource(R.string.dash_source_agent)
    Source.Kind.TASKS -> stringResource(R.string.dash_source_tasks)
}

@Composable
private fun SectionLabel(title: String, body: String? = null) {
    Column(Modifier.padding(start = 6.dp, top = 12.dp, bottom = 2.dp)) {
        Text(title, Modifier.semantics { heading() }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
        body?.let { Text(it, fontSize = 12.sp, lineHeight = 17.sp, color = P.muted) }
    }
}

@Composable
private fun SourcesSection(sources: List<Source>, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.dash_sources), stringResource(R.string.dash_sources_body))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pc(0xFFF6F8F2))) {
            sources.forEachIndexed { i, s ->
                if (i > 0) Box(Modifier.padding(start = 52.dp).fillMaxWidth().height(1.dp).background(P.line2))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    PIcon(when (s.kind) { Source.Kind.FILE -> PIcons.file; Source.Kind.FOLDER -> PIcons.folder; Source.Kind.MODEL -> PIcons.spark; Source.Kind.TASKS -> PIcons.activity },
                        Modifier.padding(top = 1.dp), size = 20.dp, tint = if (s.error != null) P.danger else P.sub)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(sourceName(s), fontSize = 14.5.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        s.path?.let { Text(it, fontFamily = P.mono, fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.StartEllipsis) }
                        val state = when {
                            s.error != null -> stringResource(R.string.dash_source_error, s.error!!)
                            s.kind == Source.Kind.MODEL -> stringResource(R.string.dash_source_model)
                            s.kind == Source.Kind.TASKS -> stringResource(R.string.dash_source_tasks_live)
                            else -> ago(s.lastSeenMs, now)?.let { stringResource(R.string.dash_source_watching_ago, it) } ?: stringResource(R.string.dash_source_watching)
                        }
                        Text(state, fontSize = 12.sp, lineHeight = 16.sp, color = if (s.error != null) P.danger else P.sub)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleSection(d: Dashboard, nextAt: Long?) {
    val context = LocalContext.current
    val zh = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.dash_schedule))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pc(0xFFF6F8F2)).padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            PIcon(PIcons.clock, Modifier.padding(top = 1.dp), size = 20.dp, tint = P.sub)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val cron = d.refreshCron
                if (cron == null) {
                    Text(stringResource(R.string.dash_schedule_none), fontSize = 14.5.sp, color = P.ink)
                    Text(stringResource(R.string.dash_schedule_none_body), fontSize = 12.sp, lineHeight = 17.sp, color = P.sub)
                } else {
                    Text(CronText.describe(cron, zh) ?: stringResource(R.string.dash_schedule_cron, cron), fontSize = 14.5.sp, color = P.ink)
                    nextAt?.let { Text(stringResource(R.string.dash_schedule_next, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)), fontSize = 12.sp, color = P.sub) }
                }
            }
        }
    }
}

@Composable
private fun HistorySection(d: Dashboard, now: Long, agentName: String?) {
    val context = LocalContext.current
    Disclosure(stringResource(R.string.dash_history, d.history.size), Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            d.history.take(30).forEach { u ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    PIcon(when (u.by) { "file" -> PIcons.file; "folder" -> PIcons.folder; "tasks" -> PIcons.activity; else -> PIcons.bot }, Modifier.padding(top = 2.dp), size = 16.dp, tint = P.muted)
                    Column(Modifier.weight(1f)) {
                        Text(historyText(u), fontSize = 13.sp, lineHeight = 18.sp, color = P.ink)
                        // The line above already says what changed; below it, who (only the agent needs naming) and when.
                        val who = if (u.by == "agent") agentName else null
                        Text(listOfNotNull(who, DateUtils.formatDateTime(context, u.atMs, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)).joinToString(" · "),
                            fontSize = 11.5.sp, color = P.muted)
                    }
                }
            }
        }
    }
}

/**
 * One change in words. What Umbra itself recorded (a watched file or folder changed, sessions moved, the dashboard was
 * made or rescheduled) is said in the user's language with the file's name, never its raw path or byte counts; what
 * the agent wrote stays as it wrote it.
 */
@Composable
private fun historyText(u: app.umbra.agent.dashboards.Update): String {
    // Older entries carried "File changed: <path> (<bytes>…)"; newer ones only the path.
    fun name() = u.text.removePrefix("File changed: ").removePrefix("Folder changed: ").substringBefore(" (").trimEnd('/').substringAfterLast('/')
    return when (u.by) {
        "file" -> stringResource(R.string.dash_hist_file, name())
        "folder" -> stringResource(R.string.dash_hist_folder, name())
        "tasks" -> stringResource(R.string.dash_hist_tasks)
        else -> when (u.text) {
            "Dashboard created" -> stringResource(R.string.dash_hist_created)
            "Dashboard updated" -> stringResource(R.string.dash_hist_updated)
            "Refresh schedule updated" -> stringResource(R.string.dash_hist_schedule)
            "Refresh timer cancelled" -> stringResource(R.string.dash_hist_unscheduled)
            else -> u.text
        }
    }
}

/** An agent's dashboards: each with its state, a peek at its first numbers, and when it was last updated. */
@Composable
fun DashboardsScreen(agentId: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    val store = container.dashboards
    val revision by store.revision.collectAsStateWithLifecycle()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val name = profiles.firstOrNull { it.id == agentId }?.name.orEmpty()
    val pages by produceState<List<DashboardPath>?>(null, agentId, revision) {
        value = withContext(Dispatchers.IO) { runCatching { store.paths(agentId) }.getOrDefault(emptyList()) }
    }
    val now = rememberNow()
    PorcelainPage(stringResource(R.string.dash_list_title), onBack) {
        val list = pages ?: return@PorcelainPage
        if (list.isEmpty()) PEmpty(stringResource(R.string.dash_list_empty_title), stringResource(R.string.dash_list_empty_body, name), icon = PIcons.dashboard)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list.sortedByDescending { it.dashboard.updatedAtMs }, key = { it.dashboard.id }) { p -> DashboardCard(p, now) { onOpen(p.dashboard.id) } }
        }
    }
}

@Composable
private fun DashboardCard(p: DashboardPath, now: Long, onClick: () -> Unit) {
    val d = p.dashboard
    val context = LocalContext.current
    val zh = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "zh"
    Column(Modifier.fillMaxWidth().clip(TILE).background(pc(0xFFF6F8F2)).clickable(role = Role.Button, onClick = onClick).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(P.highlight).border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
                PIcon(iconNamed(d.icon) ?: PIcons.dashboard, size = 18.dp, tint = P.sub)
            }
            Column(Modifier.weight(1f)) {
                Text(d.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LiveDot(d.status, d.updatedAtMs)
                    Text(statusLine(d, now), fontSize = 12.sp, color = if (d.status == Dashboard.Status.ERROR) P.danger else P.sub, maxLines = 1)
                }
            }
            PIcon(PIcons.chevronRight, size = 18.dp, tint = P.muted)
        }
        // A peek: the first few figures the dashboard holds, the way they read on it.
        val figures = p.tiles.flatMap { t -> (t.block as? CardBlock.Stats)?.items?.map { it.label to (it.value + (it.unit?.let { u -> " $u" } ?: "")) }.orEmpty() }.take(3)
        if (figures.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            figures.forEach { (label, value) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(P.base).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text(value, fontSize = 16.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(fontFeatureSettings = P.tabular))
                    Text(label, fontSize = 11.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val schedule = d.refreshCron?.let { CronText.describe(it, zh) ?: it }
        val sources = d.sources.filter { it.kind == Source.Kind.FILE || it.kind == Source.Kind.FOLDER }.map { it.path?.substringAfterLast('/').orEmpty() }.filter { it.isNotEmpty() }
        val line = listOfNotNull(
            context.resources.getQuantityString(R.plurals.dash_tiles, d.tiles.size, d.tiles.size),
            sources.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.dash_watches, it.take(2).joinToString(", ")) },
            schedule,
        ).joinToString(" · ")
        Text(line, fontSize = 11.5.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A dashboard the agent started, as a line in its chat: what it is, and a tap to open it. */
@Composable
fun DashboardChatLink(dashboardId: String, title: String, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val container = LocalContext.current.container
    val revision by container.dashboards.revision.collectAsStateWithLifecycle()
    val loaded by produceState<Result<Dashboard?>?>(null, dashboardId, revision) { value = withContext(Dispatchers.IO) { runCatching { container.dashboards.get(dashboardId) } } }
    val d = loaded?.getOrNull()
    val now = rememberNow()
    val gone = loaded != null && d == null
    Row(
        modifier.fillMaxWidth().inkSurface(TILE, CardStyle.Ceramic).clickable(role = Role.Button, enabled = d != null) { onOpen(dashboardId) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(P.highlight).border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
            PIcon(PIcons.dashboard, size = 18.dp, tint = P.sub)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.dash_chat_eyebrow).uppercase(), color = P.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
            Text(d?.title ?: title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d?.let { statusLine(it, now) } ?: if (gone) stringResource(R.string.dash_chat_removed) else "", fontSize = 12.sp, color = P.sub, maxLines = 1)
        }
        if (d != null) PIcon(PIcons.chevronRight, size = 18.dp, tint = P.muted)
    }
}

/** How many dashboards an agent keeps, for the button above its chat. */
@Composable
fun dashboardCount(agentId: String): Int {
    val container = LocalContext.current.container
    val revision by container.dashboards.revision.collectAsStateWithLifecycle()
    val count by produceState(0, agentId, revision) { value = withContext(Dispatchers.IO) { runCatching { container.dashboards.forAgent(agentId).size }.getOrDefault(0) } }
    return count
}
