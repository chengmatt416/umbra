@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.umbra.agent.ui.skills

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.skills.InstalledSkill
import app.umbra.agent.skills.SkillEntry
import app.umbra.agent.skills.SkillRateLimitException
import app.umbra.agent.skills.SkillRepo
import app.umbra.agent.skills.SkillScope
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.workspace.Workspace
import app.umbra.core.workspace.WorkspaceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Skills (the menu's Skills, 2026-10-28). A skill is a folder with SKILL.md that omp offers to coding sessions; every
 * session sees the skills installed for every project and its own project's, unless it was given a list. Finding one
 * is a search over GitHub; installing one is a job for an agent, which reads the skill and its scripts first and says
 * so in its chat if something looks wrong. The pages keep the settings rows' surfaces so they read as part of the app.
 */

private val SURFACE = RoundedCornerShape(20.dp)

/** What the Discover tab shows while it works. */
private sealed interface Found {
    data object Loading : Found
    data class Repos(val query: String, val repos: List<SkillRepo>) : Found
    data class Failed(val error: Throwable) : Found
}

@Composable
fun SkillsScreen(onBack: () -> Unit, onOpenRepo: (String) -> Unit) {
    val container = LocalContext.current.container
    val focus = LocalFocusManager.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var found by remember { mutableStateOf<Found>(Found.Loading) }
    var installedRevision by remember { mutableIntStateOf(0) }
    val installed by produceState<List<Pair<String?, List<InstalledSkill>>>?>(null, installedRevision) {
        value = withContext(Dispatchers.IO) { runCatching { installedByPlace(container) }.getOrDefault(emptyList()) }
    }
    // Typing settles before GitHub is asked: one search per pause, not one per letter.
    LaunchedEffect(query, attempt) {
        if (query.isNotBlank()) delay(650)
        found = Found.Loading
        found = try { Found.Repos(query.trim(), container.skillHub.search(query.trim())) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Found.Failed(e) }
    }
    var open by remember { mutableStateOf<InstalledSkill?>(null) }

    PorcelainPage(stringResource(R.string.skills_title), onBack) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SearchField(query, { query = it; tab = 0 }, onSearch = { focus.clearFocus() })
                val count = installed?.sumOf { it.second.size } ?: 0
                Segmented(
                    listOf(0 to stringResource(R.string.skills_tab_discover), 1 to stringResource(R.string.skills_tab_installed, count)),
                    tab, { tab = it }, Modifier.fillMaxWidth(), fill = true,
                )
            }
            if (tab == 0) Discover(found, onOpenRepo, onRetry = { attempt++ })
            else Installed(installed, onOpen = { open = it })
        }
    }
    open?.let { skill ->
        InstalledSkillSheet(skill, onDismiss = { open = null }, onChanged = { open = null; installedRevision++ })
    }
}

/**
 * The project folders a skill can go to, most recently used first: one made on purpose (an agent's named project, a
 * cloned repository, a folder the user linked, a pinned one) or one a coding session works in. Every session has a
 * folder of its own, but an agent's conversation or background research works in folders nobody codes in, and a
 * deleted session leaves its folder to no one.
 */
private suspend fun skillProjects(container: app.umbra.agent.AppContainer, limit: Int = Int.MAX_VALUE): List<Workspace> {
    val coding = container.sessionStore.list(app.umbra.core.session.SessionFilter(limit = Int.MAX_VALUE))
        .filter { it.kind == app.umbra.core.session.SessionKind.CODING }.map { it.id }.toSet()
    return container.workspaces.list().filter { w ->
        w.kind == WorkspaceKind.PROJECT && (w.origin != null || w.externalPath != null || w.pinned || w.sessionIds.any { it in coding })
    }.take(limit)
}

/** Enough recent projects to choose from in the install sheet; the Installed list still shows every project's skills. */
private const val MAX_PLACES = 15

/** Every installed skill, the ones for every project first, then each project's own under its name. */
private suspend fun installedByPlace(container: app.umbra.agent.AppContainer): List<Pair<String?, List<InstalledSkill>>> {
    val global = container.installedSkills.scan(null)
    val projects = skillProjects(container)
    return buildList {
        if (global.isNotEmpty()) add(null to global)
        projects.forEach { w ->
            val own = container.installedSkills.scan(w.id).filter { it.scope == SkillScope.PROJECT }
            if (own.isNotEmpty()) add(w.name to own)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, onSearch: () -> Unit) {
    val hint = stringResource(R.string.skills_search_hint)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(P.base2).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PIcon(PIcons.search, size = 18.dp, tint = P.sub)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, fontSize = 15.sp, color = P.muted, maxLines = 1)
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = hint },
                textStyle = TextStyle(fontSize = 15.sp, color = P.ink), cursorBrush = SolidColor(P.accent2), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
        }
        if (value.isNotEmpty()) PIconButton(PIcons.close, stringResource(R.string.skills_clear), { onChange("") }, size = 34.dp, tint = P.sub)
    }
}

@Composable
private fun Discover(found: Found, onOpenRepo: (String) -> Unit, onRetry: () -> Unit) {
    when (found) {
        Found.Loading -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Still shapes of the cards to come; nothing moves while GitHub answers.
            repeat(4) { Box(Modifier.fillMaxWidth().height(104.dp).clip(SURFACE).background(pc(0xFFF6F8F2))) }
        }
        is Found.Failed -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val e = found.error
            if (e is SkillRateLimitException) {
                val reset = e.resetAtMs ?: e.retryAfterMs?.let { System.currentTimeMillis() + it }
                Callout(
                    if (reset != null) stringResource(R.string.skills_rate_limited, DateUtils.formatDateTime(LocalContext.current, reset, DateUtils.FORMAT_SHOW_TIME))
                    else stringResource(R.string.skills_rate_limited_soon),
                    Tone.Warn,
                )
            } else Callout(stringResource(R.string.skills_failed, e.message ?: e.javaClass.simpleName), Tone.Danger)
            PButton(stringResource(R.string.skills_retry), onRetry, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.refresh)
        }
        is Found.Repos -> if (found.repos.isEmpty()) PEmpty(stringResource(R.string.skills_none_found_title), stringResource(R.string.skills_none_found_body), icon = PIcons.search)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (found.query.isEmpty()) stringResource(R.string.skills_popular) else stringResource(R.string.skills_results, found.query),
                        Modifier.semantics { heading() }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub,
                    )
                    if (found.query.isEmpty()) Text(stringResource(R.string.skills_intro), fontSize = 13.sp, lineHeight = 19.sp, color = P.muted)
                }
            }
            items(found.repos, key = { it.fullName }) { repo -> RepoCard(repo) { onOpenRepo(repo.fullName) } }
        }
    }
}

@Composable
private fun RepoCard(repo: SkillRepo, onClick: () -> Unit) {
    val owner = repo.fullName.substringBefore('/')
    val name = repo.fullName.substringAfter('/')
    Column(
        Modifier.fillMaxWidth().clip(SURFACE).background(pc(0xFFF6F8F2)).clickable(role = Role.Button, onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(PIcons.puzzle)
            Column(Modifier.weight(1f)) {
                Text(owner, fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Stars(repo.stars)
        }
        if (repo.description.isNotBlank()) Text(repo.description, fontSize = 13.5.sp, lineHeight = 19.sp, color = P.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Meta(repo.updatedAt, repo.license)
    }
}

/** The round mark every skill row starts with. */
@Composable
private fun Mark(icon: IconSpec, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(P.highlight).border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
        PIcon(icon, size = size * 0.47f, tint = P.sub)
    }
}

@Composable
private fun Stars(stars: Long) {
    val text = compactCount(stars)
    val label = stringResource(R.string.skills_stars_cd, text)
    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PIcon(PIcons.star, size = 14.dp, tint = P.sub)
        Text(text, fontSize = 12.5.sp, color = P.sub, style = TextStyle(fontFeatureSettings = P.tabular))
    }
}

@Composable
private fun Meta(updatedAt: String?, license: String?) {
    val updated = updatedAt?.let { iso -> runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() }
        ?.let { stringResource(R.string.skills_updated, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString()) }
    val line = listOfNotNull(updated, license?.takeIf { it.isNotBlank() && it != "NOASSERTION" }).joinToString(" · ")
    if (line.isNotEmpty()) Text(line, fontSize = 11.5.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

internal fun compactCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 10_000 -> "${n / 1_000}k"
    n >= 1_000 -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
    else -> n.toString()
}

@Composable
private fun Installed(groups: List<Pair<String?, List<InstalledSkill>>>?, onOpen: (InstalledSkill) -> Unit) {
    val container = LocalContext.current.container
    if (groups == null) return
    if (groups.isEmpty()) return PEmpty(stringResource(R.string.skills_installed_empty_title), stringResource(R.string.skills_installed_empty_body), icon = PIcons.puzzle)
    val always = remember(groups) { container.installedSkills.always() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(groups, key = { it.first ?: "\u0000global" }) { (place, skills) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.padding(start = 6.dp)) {
                    Text(place ?: stringResource(R.string.skills_scope_global), Modifier.semantics { heading() }, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
                    Text(
                        if (place == null) stringResource(R.string.skills_scope_global_body) else stringResource(R.string.skills_scope_project_body, place),
                        fontSize = 12.sp, color = P.muted,
                    )
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2))) {
                    skills.forEachIndexed { i, skill ->
                        if (i > 0) Box(Modifier.padding(start = 66.dp).fillMaxWidth().height(1.dp).background(P.line2))
                        InstalledRow(skill, skill.name in always) { onOpen(skill) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledRow(skill: InstalledSkill, always: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Mark(PIcons.puzzle)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(skill.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (skill.description.isNotBlank()) Text(skill.description, fontSize = 12.5.sp, lineHeight = 17.sp, color = P.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (always || skill.hasScripts || skill.sourceRepo != null) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (always) Tag(stringResource(R.string.skills_always_tag), Tone.Good)
                if (skill.hasScripts) Tag(stringResource(R.string.skills_scripts_tag), Tone.Warn)
                skill.sourceRepo?.let { Text(stringResource(R.string.skills_from, it), fontSize = 11.5.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        PIcon(PIcons.chevronRight, Modifier.padding(top = 8.dp), size = 18.dp, tint = P.muted)
    }
}

/** One installed skill: what it is, where it came from, whether agents always use it, and Remove. */
@Composable
private fun InstalledSkillSheet(skill: InstalledSkill, onDismiss: () -> Unit, onChanged: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var always by remember(skill) { mutableStateOf(skill.name in container.installedSkills.always()) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mark(PIcons.puzzle, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(skill.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
                    skill.sourceRepo?.let { Text(stringResource(R.string.skills_from, it), fontSize = 12.5.sp, color = P.sub) }
                }
            }
            if (skill.description.isNotBlank()) Text(skill.description, fontSize = 14.sp, lineHeight = 21.sp, color = P.ink)
            if (skill.hasScripts) Callout(stringResource(R.string.skills_scripts_note, skill.scripts.take(4).joinToString(", ") + if (skill.scripts.size > 4) "…" else ""), Tone.Warn)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.skills_folder), fontSize = 11.5.sp, color = P.muted)
                Text(skill.folder, fontFamily = P.mono, fontSize = 12.5.sp, color = P.sub)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).clickable(role = Role.Switch) {
                    val next = !always
                    runCatching { container.installedSkills.setAlways(if (next) container.installedSkills.always() + skill.name else container.installedSkills.always() - skill.name) }
                        .onSuccess { always = next }.onFailure { error = it.message }
                }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.skills_always), fontSize = 15.sp, color = P.ink)
                    Text(stringResource(R.string.skills_always_body), fontSize = 12.sp, lineHeight = 17.sp, color = P.sub)
                }
                Switch(always, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = P.accent2, checkedThumbColor = P.base, uncheckedTrackColor = P.base2, uncheckedBorderColor = P.line))
            }
            error?.let { Callout(it, Tone.Danger) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                skill.sourceUrl?.let { url ->
                    PButton(stringResource(R.string.skills_view_github), {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.external)
                }
                PButton(stringResource(R.string.skills_remove), { confirm = true }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.trash)
            }
        }
    }
    if (confirm) PConfirm(
        stringResource(R.string.skills_remove_title, skill.name), stringResource(R.string.skills_remove_body), stringResource(R.string.skills_remove),
        onConfirm = {
            confirm = false
            scope.launch {
                val target = skill.projectId?.let { app.umbra.agent.skills.Target.Project(it) } ?: app.umbra.agent.skills.Target.Global
                runCatching { container.installedSkills.remove(skill.name, target) }.onSuccess { onChanged() }.onFailure { error = it.message }
            }
        },
        onDismiss = { confirm = false },
    )
}

/** One GitHub repository's skills, each with what it does, its size, its SKILL.md and Install. */
@Composable
fun SkillRepoScreen(repo: String, onBack: () -> Unit, onOpenAgent: (String) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val loaded by produceState<Result<Pair<SkillRepo, List<SkillEntry>>>?>(null, repo, attempt) {
        value = null
        value = try {
            val r = container.skillHub.repository(repo)
            Result.success(r to container.skillHub.skills(r))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    var installing by remember { mutableStateOf<SkillEntry?>(null) }
    var reading by remember { mutableStateOf<SkillEntry?>(null) }
    PorcelainPage(repo.substringAfter('/'), onBack) {
        val result = loaded
        when {
            result == null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(120.dp).clip(SURFACE).background(pc(0xFFF6F8F2)))
                repeat(3) { Box(Modifier.fillMaxWidth().height(132.dp).clip(SURFACE).background(pc(0xFFF6F8F2))) }
            }
            result.isFailure -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val e = result.exceptionOrNull()!!
                if (e is SkillRateLimitException) Callout(stringResource(R.string.skills_rate_limited_soon), Tone.Warn)
                else Callout(stringResource(R.string.skills_failed, e.message ?: e.javaClass.simpleName), Tone.Danger)
                PButton(stringResource(R.string.skills_retry), { attempt++ }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.refresh)
            }
            else -> {
                val (info, skills) = result.getOrThrow()
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "repo") {
                        Column(Modifier.fillMaxWidth().clip(SURFACE).background(pc(0xFFF6F8F2)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(info.fullName.substringBefore('/'), fontSize = 12.5.sp, color = P.sub)
                            Text(info.fullName.substringAfter('/'), Modifier.semantics { heading() }, fontFamily = P.serif, fontSize = 26.sp, lineHeight = 30.sp, color = P.ink)
                            if (info.description.isNotBlank()) Text(info.description, fontSize = 14.sp, lineHeight = 20.sp, color = P.sub)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Stars(info.stars)
                                Box(Modifier.weight(1f)) { Meta(info.updatedAt, info.license) }
                            }
                            PButton(stringResource(R.string.skills_view_github), {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.external)
                        }
                    }
                    item(key = "head") {
                        Text(stringResource(R.string.skills_repo_skills, skills.size), Modifier.padding(start = 6.dp, top = 6.dp).semantics { heading() },
                            fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
                    }
                    if (skills.isEmpty()) item(key = "none") { Callout(stringResource(R.string.skills_repo_none), Tone.Neutral) }
                    items(skills, key = { it.path.ifEmpty { "/" } }) { entry ->
                        SkillCard(entry, onInstall = { installing = entry }, onRead = { reading = entry })
                    }
                }
            }
        }
    }
    installing?.let { entry -> InstallSheet(entry, onDismiss = { installing = null }, onOpenAgent = { installing = null; onOpenAgent(it) }) }
    reading?.let { entry -> SkillMarkdownSheet(entry) { reading = null } }
}

@Composable
private fun SkillCard(entry: SkillEntry, onInstall: () -> Unit, onRead: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(SURFACE).background(pc(0xFFF6F8F2)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mark(PIcons.puzzle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(entry.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink)
                Text(entry.description, fontSize = 13.5.sp, lineHeight = 19.sp, color = P.sub, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.skills_files, entry.fileCount, Formatter.formatShortFileSize(context, entry.totalSize)) + (if (entry.path.isNotEmpty()) " · " + entry.path else ""),
                    fontSize = 11.5.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(R.string.skills_install), onInstall, tiny = true, icon = PIcons.download)
            PButton(stringResource(R.string.skills_read), onRead, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.book)
        }
    }
}

@Composable
private fun SkillMarkdownSheet(entry: SkillEntry, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val text by produceState<Result<String>?>(null, entry) {
        value = try { Result.success(container.skillHub.markdown(entry)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(entry.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Text("SKILL.md", fontFamily = P.mono, fontSize = 12.sp, color = P.muted)
            val t = text
            when {
                t == null -> Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(16.dp)).background(P.base2))
                t.isFailure -> Callout(stringResource(R.string.skills_markdown_failed, t.exceptionOrNull()?.message.orEmpty()), Tone.Danger)
                else -> MarkdownText(t.getOrThrow(), Modifier.fillMaxWidth(), color = P.ink)
            }
        }
    }
}

/**
 * Where a skill should go and which agent installs it. The user's choice of place is handed to the install tool as a
 * one-time permission, so an agent can install into the user's own project only because the user asked here.
 */
@Composable
private fun InstallSheet(entry: SkillEntry, onDismiss: () -> Unit, onOpenAgent: (String) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    val agents = profiles.filter { it.running }
    val projects by produceState<List<Workspace>>(emptyList()) {
        value = withContext(Dispatchers.IO) { runCatching { skillProjects(container, MAX_PLACES) }.getOrDefault(emptyList()) }
    }
    var place by remember { mutableStateOf<String?>(null) }
    var agentId by remember(agents) { mutableStateOf(agents.firstOrNull()?.id) }
    var agentMenu by remember { mutableStateOf(false) }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    val globalText = stringResource(R.string.skills_msg_global)
    val projectText = stringResource(R.string.skills_msg_project, projects.firstOrNull { it.id == place }?.name.orEmpty(), place.orEmpty())
    val rootText = stringResource(R.string.skills_msg_root)
    val message = stringResource(R.string.skills_install_message, entry.name, entry.repo, entry.path.ifEmpty { rootText }, entry.ref, if (place == null) globalText else projectText)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val agentName = sentTo?.let { id -> profiles.firstOrNull { it.id == id }?.name }
            if (agentName != null) {
                // Sent: say who has it and where to watch for the report.
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(P.good.copy(alpha = .14f)), contentAlignment = Alignment.Center) { PIcon(PIcons.check, size = 26.dp, tint = P.good) }
                    Text(stringResource(R.string.skills_sent_title, agentName), fontFamily = P.serif, fontSize = 24.sp, color = P.ink)
                    Text(stringResource(if (queued) R.string.skills_sent_queued else R.string.skills_sent_body, agentName), fontSize = 14.sp, lineHeight = 20.sp, color = P.sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton(stringResource(R.string.skills_open_chat), { onOpenAgent(sentTo!!) }, icon = PIcons.message)
                        PButton(stringResource(R.string.skills_done), onDismiss, style = ButtonStyle.Ghost)
                    }
                }
                return@Column
            }
            Text(stringResource(R.string.skills_install_title, entry.name), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Text(stringResource(R.string.skills_install_body), fontSize = 13.5.sp, lineHeight = 20.sp, color = P.sub)
            Label(stringResource(R.string.skills_where))
            PChoice(
                listOf(Triple<String?, String, String>(null, stringResource(R.string.skills_where_global), stringResource(R.string.skills_where_global_body))) +
                    projects.map { Triple<String?, String, String>(it.id, it.name, stringResource(R.string.skills_where_project_body)) },
                place, { place = it },
            )
            Label(stringResource(R.string.skills_agent))
            if (agents.isEmpty()) Callout(stringResource(R.string.skills_no_agent), Tone.Warn)
            else Box {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.base2).clickable(role = Role.Button, enabled = agents.size > 1) { agentMenu = true }
                        .padding(horizontal = 14.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(agents.firstOrNull { it.id == agentId }?.name.orEmpty(), Modifier.weight(1f), fontSize = 15.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (agents.size > 1) PIcon(PIcons.chevronDown, size = 18.dp, tint = P.sub)
                }
                DropdownMenu(agentMenu, { agentMenu = false }) {
                    agents.forEach { a -> DropdownMenuItem(text = { Text(a.name) }, onClick = { agentMenu = false; agentId = a.id }) }
                }
            }
            error?.let { Callout(it, Tone.Danger) }
            val name = agents.firstOrNull { it.id == agentId }?.name
            PButton(
                if (name != null) stringResource(R.string.skills_send, name) else stringResource(R.string.skills_install),
                {
                    val id = agentId ?: return@PButton
                    busy = true; error = null
                    scope.launch {
                        try {
                            container.skillInstallTools.allow(entry.repo, entry.path, place)
                            // The request goes out in the app's scope: an agent still starting gets it when it is ready,
                            // after this sheet is gone; a failure then shows as a toast.
                            val delivery = container.scope.async { container.agents.talk(id, message) }
                            delivery.invokeOnCompletion { e ->
                                if (e != null && e !is CancellationException) android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(context.applicationContext, e.message ?: e.javaClass.simpleName, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                            val outcome = kotlinx.coroutines.withTimeoutOrNull(6_000) { runCatching { delivery.await() } }
                            if (outcome?.isFailure == true) throw outcome.exceptionOrNull()!!
                            queued = outcome == null
                            sentTo = id
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                        finally { busy = false }
                    }
                },
                Modifier.fillMaxWidth(), enabled = agentId != null && !busy, icon = PIcons.send,
            )
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, Modifier.padding(top = 4.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)

/**
 * Which installed skills a coding session may use: all of them (omp's own default), a chosen few, or none. [current]
 * null means all. [projectId] is the session's project folder, whose own skills are offered with the global ones.
 */
@Composable
fun SkillPickerSheet(projectId: String?, current: List<String>?, existing: Boolean, onDismiss: () -> Unit, onSave: (List<String>?) -> Unit) {
    val container = LocalContext.current.container
    val installed by produceState<List<InstalledSkill>?>(null, projectId) {
        value = runCatching { container.installedSkills.scan(projectId) }.getOrDefault(emptyList())
    }
    var mode by remember(current) { mutableIntStateOf(when { current == null -> 0; current.isEmpty() -> 2; else -> 1 }) }
    var chosen by remember(current) { mutableStateOf(current.orEmpty().toSet()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.skills_session_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Text(stringResource(R.string.skills_session_body), fontSize = 13.5.sp, lineHeight = 20.sp, color = P.sub)
            val list = installed
            if (list != null && list.isEmpty()) {
                Callout(stringResource(R.string.skills_nothing_installed), Tone.Neutral)
            } else {
                Segmented(
                    listOf(0 to stringResource(R.string.skills_mode_all), 1 to stringResource(R.string.skills_mode_choose), 2 to stringResource(R.string.skills_mode_none)),
                    mode, { mode = it }, Modifier.fillMaxWidth(), fill = true,
                )
                when (mode) {
                    0 -> Text(stringResource(R.string.skills_mode_all_body, list?.size ?: 0), fontSize = 13.sp, color = P.sub)
                    2 -> Text(stringResource(R.string.skills_mode_none_body), fontSize = 13.sp, color = P.sub)
                    else -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.base2)) {
                        list.orEmpty().distinctBy { it.name }.forEach { skill ->
                            val on = skill.name in chosen
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { chosen = if (on) chosen - skill.name else chosen + skill.name }.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = P.accent2, uncheckedColor = P.line, checkmarkColor = P.base))
                                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                                    Text(skill.name, fontSize = 14.5.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (skill.description.isNotBlank()) Text(skill.description, fontSize = 12.sp, lineHeight = 16.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            if (existing) Text(stringResource(R.string.skills_next_start), fontSize = 12.sp, color = P.muted)
            PButton(stringResource(R.string.skills_save), {
                onSave(when (mode) { 0 -> null; 2 -> emptyList(); else -> chosen.toList().sorted() })
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = mode != 1 || chosen.isNotEmpty())
        }
    }
}

/** The label of the button that opens [SkillPickerSheet]. */
@Composable
fun skillsChipText(skills: List<String>?): String = when {
    skills == null -> stringResource(R.string.skills_chip_all)
    skills.isEmpty() -> stringResource(R.string.skills_chip_none)
    else -> stringResource(R.string.skills_chip_some, skills.size)
}
