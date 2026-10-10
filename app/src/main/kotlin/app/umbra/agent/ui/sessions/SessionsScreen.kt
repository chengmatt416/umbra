package app.umbra.agent.ui.sessions

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
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

fun stateText(state: SessionState) = when (state) {
    SessionState.CREATED -> "New"
    SessionState.RUNNING -> "Working"
    SessionState.PAUSED -> "Paused"
    SessionState.WAITING_APPROVAL -> "Needs you"
    SessionState.STOPPED -> "Stopped"
    SessionState.COMPLETED -> "Done"
    SessionState.FAILED -> "Failed"
}

fun sessionTone(state: SessionState) = when (state) {
    SessionState.RUNNING, SessionState.COMPLETED -> Tone.Good
    SessionState.WAITING_APPROVAL, SessionState.PAUSED -> Tone.Warn
    SessionState.FAILED -> Tone.Danger
    SessionState.CREATED, SessionState.STOPPED -> Tone.Neutral
}

private enum class Filter(val label: String) { All("All"), Active("Active"), Done("Done") }

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
    var error by remember { mutableStateOf<String?>(null) }

    val shown = sessions.filter {
        when (filter) {
            Filter.All -> true
            Filter.Active -> !it.state.isTerminal
            Filter.Done -> it.state.isTerminal
        }
    }
    val byProject = shown.groupBy { s -> s.projectId?.takeIf { projectsById.containsKey(it) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Eyebrow("Your work")
            SerifTitle("Sessions", Modifier.padding(top = 8.dp, bottom = 10.dp), size = 28)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Segmented(Filter.entries.map { it to it.label }, filter, { filter = it })
                Box(Modifier.weight(1f))
                PButton("Project", { newProject = true }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.plus)
            }
            error?.let { Gap(8.dp); Callout(it, Tone.Danger) }
        }
        projects.forEach { project ->
            val mine = byProject[project.id].orEmpty()
            item(key = "p-${project.id}") {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Kicker("Project")
                        Text(project.name, fontSize = 17.sp, color = P.ink)
                    }
                    Tag("${sessions.count { it.projectId == project.id }} sessions", Tone.Neutral)
                    ProjectMenu(project, onRename = { renaming = project }, onDelete = {
                        scope.launch {
                            try {
                                // Its sessions stay; they become independent.
                                sessions.filter { it.projectId == project.id }.forEach { container.supervisor.moveSession(it.id, null) }
                                container.projects.delete(project.id)
                            } catch (e: Exception) { error = e.message }
                        }
                    })
                }
            }
            if (mine.isEmpty()) item(key = "pe-${project.id}") { Subtle("No ${filter.label.lowercase().takeIf { filter != Filter.All }?.plus(" ") ?: ""}sessions yet. Choose this project on Home to start one here.") }
            items(mine, key = { it.id }) { s -> SessionRow(s, projects, onOpenSession, onError = { error = it }) }
        }
        item(key = "independent") {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("No owning project")
                    Text("Independent sessions", fontSize = 17.sp, color = P.ink)
                }
                Tag("${sessions.count { s -> s.projectId == null || !projectsById.containsKey(s.projectId) }}", Tone.Neutral)
            }
        }
        val independent = byProject[null].orEmpty()
        if (independent.isEmpty()) item { Subtle(if (sessions.isEmpty()) "Nothing yet. Start from Home." else "None here.") }
        items(independent, key = { it.id }) { s -> SessionRow(s, projects, onOpenSession, onError = { error = it }) }
    }

    if (newProject) NameDialog("New project", "", onDismiss = { newProject = false }) { name ->
        newProject = false
        container.projects.create(name)
    }
    renaming?.let { p ->
        NameDialog("Rename project", p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            container.projects.rename(p.id, name)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: Session, projects: List<Project>, onOpen: (String) -> Unit, onError: (String?) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    val links = s.linkedContextIds.mapNotNull { id -> projects.firstOrNull { it.id == id }?.name }
    Box {
        PObject(
            s.title,
            buildString {
                append(stateText(s.state))
                append(" · ")
                append(DateUtils.getRelativeTimeSpanString(s.updatedAtMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                if (links.isNotEmpty()) append(" · linked: ").append(links.joinToString())
            },
            if (s.state == SessionState.RUNNING) PIcons.activity else PIcons.bot,
            Modifier.combinedClickable(onClick = { onOpen(s.id) }, onLongClick = { menu = true }),
            trailing = { Tag(stateText(s.state), sessionTone(s.state)) },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen(s.id) })
            if (s.projectId != null) DropdownMenuItem(text = { Text("Make independent") }, onClick = {
                menu = false
                scope.launch { runCatching { container.supervisor.moveSession(s.id, null) }.onFailure { onError(it.message) } }
            })
            projects.filter { it.id != s.projectId }.forEach { p ->
                DropdownMenuItem(text = { Text("Move to ${p.name}") }, onClick = {
                    menu = false
                    scope.launch { runCatching { container.supervisor.moveSession(s.id, p.id) }.onFailure { onError(it.message) } }
                })
            }
            projects.filter { it.id != s.projectId }.forEach { p ->
                val linked = p.id in s.linkedContextIds
                DropdownMenuItem(text = { Text(if (linked) "Unlink ${p.name} context" else "Link ${p.name} context") }, onClick = {
                    menu = false
                    scope.launch { runCatching { container.supervisor.linkContext(s.id, p.id, !linked) }.onFailure { onError(it.message) } }
                })
            }
            DropdownMenuItem(text = { Text("Run again") }, onClick = {
                menu = false
                scope.launch { runCatching { container.supervisor.duplicate(s.id) }.onSuccess { onOpen(it.id) }.onFailure { onError(it.message) } }
            })
            DropdownMenuItem(text = { Text("Delete") }, onClick = {
                menu = false
                scope.launch { runCatching { container.supervisor.delete(s.id) }.onFailure { onError(it.message) } }
            })
        }
    }
}

@Composable
private fun ProjectMenu(project: Project, onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋯", color = P.sub) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text("Delete project (sessions stay)") }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
