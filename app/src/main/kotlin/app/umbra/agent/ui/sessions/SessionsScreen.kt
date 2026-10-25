package app.umbra.agent.ui.sessions

import app.umbra.agent.ui.porcelain.inkItem
import app.umbra.agent.ui.sessionsFlow
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.annotation.StringRes
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.Project
import app.umbra.core.session.Session
import app.umbra.core.session.SessionState
import kotlinx.coroutines.launch

@androidx.annotation.StringRes
fun stateRes(state: SessionState) = when (state) {
    SessionState.CREATED -> app.umbra.agent.R.string.state_new
    SessionState.RUNNING -> app.umbra.agent.R.string.state_working
    SessionState.PAUSED -> app.umbra.agent.R.string.state_paused
    SessionState.WAITING_APPROVAL -> app.umbra.agent.R.string.state_needs_you
    SessionState.STOPPED -> app.umbra.agent.R.string.state_stopped
    SessionState.COMPLETED -> app.umbra.agent.R.string.state_done
    SessionState.FAILED -> app.umbra.agent.R.string.state_failed
}

/** A queued session (waiting for admission, AC-022) says so; otherwise its state. */
@androidx.annotation.StringRes
fun stateRes(session: Session) = if (session.queuedAtMs != null && session.state == SessionState.CREATED) app.umbra.agent.R.string.state_queued else stateRes(session.state)

@Composable
fun stateText(state: SessionState): String = androidx.compose.ui.res.stringResource(stateRes(state))

@Composable
fun stateText(session: Session): String = androidx.compose.ui.res.stringResource(stateRes(session))

fun sessionTone(session: Session): Tone = if (session.queuedAtMs != null && session.state == SessionState.CREATED) Tone.Warn else sessionTone(session.state)

fun sessionTone(state: SessionState) = when (state) {
    SessionState.RUNNING, SessionState.COMPLETED -> Tone.Good
    SessionState.WAITING_APPROVAL, SessionState.PAUSED -> Tone.Warn
    SessionState.FAILED -> Tone.Danger
    SessionState.CREATED, SessionState.STOPPED -> Tone.Neutral
}

private enum class Filter(@param:StringRes val label: Int) { All(R.string.filter_all), Active(R.string.filter_active), Done(R.string.filter_done) }

/**
 * Your work: projects with the sessions they own, then independent sessions (architecture spec §2.1). Long-press a
 * session to move it to another project (changes its owner) or link a project's context (does not).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val projectsById by container.projects.all.collectAsStateWithLifecycle()
    val projects = projectsById.values.sortedBy { it.createdAtMs }
    var filter by remember { mutableStateOf(Filter.All) }
    var newProject by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deletingProject by remember { mutableStateOf<Project?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val shown = sessions.filter {
        when (filter) {
            Filter.All -> true
            Filter.Active -> !it.state.isTerminal
            Filter.Done -> it.state.isTerminal
        }
    }
    val byProject = shown.groupBy { s -> s.projectId?.takeIf { projectsById.containsKey(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp + app.umbra.agent.ui.shell.LocalChromeBottom.current), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Eyebrow(stringResource(R.string.sessions_eyebrow))
            SerifTitle(stringResource(R.string.dest_sessions), Modifier.padding(top = 8.dp, bottom = 12.dp), size = 28)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(Filter.entries.map { it to stringResource(it.label) }, filter, { filter = it }, Modifier.weight(1f), fill = true)
                PButton(stringResource(R.string.sessions_new_project), { newProject = true }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.plus)
            }
            error?.let { Gap(8.dp); Callout(it, Tone.Danger) }
        }
        projects.forEach { project ->
            val mine = byProject[project.id].orEmpty()
            item(key = "p-${project.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Kicker(stringResource(R.string.sessions_project))
                        Text(project.name, fontSize = 17.sp, color = P.ink)
                    }
                    val count = sessions.count { it.projectId == project.id }
                    Tag(pluralStringResource(R.plurals.sessions_count, count, count), Tone.Neutral)
                    ProjectMenu(onRename = { renaming = project }, onDelete = { deletingProject = project })
                }
            }
            if (mine.isEmpty()) item(key = "pe-${project.id}") { Subtle(stringResource(R.string.sessions_project_empty)) }
            items(mine, key = { it.id }) { s -> androidx.compose.foundation.layout.Box(inkItem()) { SessionRow(s, projects, onOpenSession, onError = { error = it }) } }
        }
        item(key = "independent") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker(stringResource(R.string.sessions_no_project))
                    Text(stringResource(R.string.sessions_independent), fontSize = 17.sp, color = P.ink)
                }
                Tag("${sessions.count { s -> s.projectId == null || !projectsById.containsKey(s.projectId) }}", Tone.Neutral)
            }
        }
        val independent = byProject[null].orEmpty()
        if (independent.isEmpty()) item { Subtle(stringResource(if (sessions.isEmpty()) R.string.sessions_nothing else R.string.sessions_none_here)) }
        items(independent, key = { it.id }) { s -> androidx.compose.foundation.layout.Box(inkItem()) { SessionRow(s, projects, onOpenSession, onError = { error = it }) } }
    }

    if (newProject) NameDialog(stringResource(R.string.project_new_title), "", onDismiss = { newProject = false }) { name ->
        newProject = false
        container.projects.create(name)
    }
    renaming?.let { p ->
        NameDialog(stringResource(R.string.project_rename_title), p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            container.projects.rename(p.id, name)
        }
    }
    deletingProject?.let { project ->
        PConfirm(
            stringResource(R.string.project_delete_title, project.name), stringResource(R.string.project_delete_body), stringResource(R.string.action_delete),
            onDismiss = { deletingProject = null },
            onConfirm = {
                deletingProject = null
                scope.launch {
                    try {
                        // Its sessions stay; they become independent.
                        sessions.filter { it.projectId == project.id }.forEach { container.supervisor.moveSession(it.id, null) }
                        container.projects.delete(project.id)
                    } catch (e: Exception) { error = e.message }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: Session, projects: List<Project>, onOpen: (String) -> Unit, onError: (String?) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val links = s.linkedContextIds.mapNotNull { id -> projects.firstOrNull { it.id == id }?.name }
    val linked = if (links.isNotEmpty()) " · " + stringResource(R.string.sessions_linked, links.joinToString()) else ""
    Box {
        PObject(
            s.title,
            DateUtils.getRelativeTimeSpanString(s.updatedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() + linked,
            if (s.state == SessionState.RUNNING) PIcons.activity else PIcons.bot,
            Modifier.combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menu = true }),
            trailing = { Tag(stateText(s), sessionTone(s)) },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.session_open)) }, onClick = { menu = false; onOpen(s.id) })
            if (s.projectId != null) DropdownMenuItem(text = { Text(stringResource(R.string.session_make_independent)) }, onClick = {
                menu = false
                scope.launch { runCatching { container.supervisor.moveSession(s.id, null) }.onFailure { onError(it.message) } }
            })
            projects.filter { it.id != s.projectId }.forEach { p ->
                DropdownMenuItem(text = { Text(stringResource(R.string.session_move_to, p.name)) }, onClick = {
                    menu = false
                    scope.launch { runCatching { container.supervisor.moveSession(s.id, p.id) }.onFailure { onError(it.message) } }
                })
            }
            projects.filter { it.id != s.projectId }.forEach { p ->
                val isLinked = p.id in s.linkedContextIds
                DropdownMenuItem(text = { Text(stringResource(if (isLinked) R.string.session_unlink else R.string.session_link, p.name)) }, onClick = {
                    menu = false
                    scope.launch { runCatching { container.supervisor.linkContext(s.id, p.id, !isLinked) }.onFailure { onError(it.message) } }
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.session_run_again)) }, onClick = {
                menu = false
                scope.launch { runCatching { container.supervisor.duplicate(s.id) }.onSuccess { onOpen(it.id) }.onFailure { onError(it.message) } }
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = P.danger) }, onClick = { menu = false; deleting = true })
        }
    }
    if (deleting) ConfirmDeleteSession(s, onDismiss = { deleting = false }) {
        deleting = false
        scope.launch { runCatching { container.supervisor.delete(s.id) }.onFailure { onError(it.message) } }
    }
}

/** Deleting a task says what goes (conversation, timeline, private files) and what stays (its workspace). */
@Composable
fun ConfirmDeleteSession(session: Session, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val running = !session.state.isTerminal && session.state != SessionState.CREATED
    PConfirm(
        stringResource(R.string.session_delete_title, session.title),
        stringResource(R.string.session_delete_body) + if (running) "\n\n" + stringResource(R.string.session_delete_running) else "",
        stringResource(R.string.action_delete),
        onConfirm = onConfirm, onDismiss = onDismiss,
    )
}

@Composable
private fun ProjectMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PIconButton(PIcons.more, stringResource(R.string.project_actions), { open = true }, size = 34.dp)
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.project_rename)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.project_delete), color = P.danger) }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    PConfirm(
        title = title, body = "", confirmLabel = stringResource(R.string.action_save), danger = false, confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) onDone(name.trim()) }, onDismiss = onDismiss,
        extra = { PField(name, { name = it }, singleLine = true) },
    )
}
