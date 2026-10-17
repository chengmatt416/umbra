package app.umbra.agent.ui.integration

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.umbra.agent.ui.porcelain.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.umbra.agent.ui.porcelain.DisabledReason
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.data.GuestManager
import app.umbra.agent.integration.Repository
import app.umbra.agent.integration.findRepositories
import app.umbra.agent.integration.guestGit
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.IntegrationPolicy
import kotlinx.coroutines.launch

/**
 * Repository Hub (architecture spec §2.1, §3.2): the one place repositories are registered or cloned, checked
 * (preflight), given an integration policy (enforced by the commit gate) and linked to projects. Forgetting a
 * repository removes its record, never its files.
 */
@Composable
fun RepositoryHubScreen(onCommit: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val repos by container.repositories.all.collectAsStateWithLifecycle()
    val projects by container.projects.all.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    val journal by container.repositories.recent.collectAsStateWithLifecycle()
    var found by remember { mutableStateOf<List<Repository>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var cloning by remember { mutableStateOf(false) }
    LaunchedEffect(guest, repos.size) {
        if (guest == GuestManager.State.Ready) found = runCatching { findRepositories(container) }.getOrDefault(emptyList())
    }

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch { message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.repo_eyebrow))
        Headline(stringResource(R.string.repo_headline), stringResource(R.string.repo_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(stringResource(R.string.repo_count, repos.size), Modifier.weight(1f))
                PButton(stringResource(R.string.repo_clone), { cloning = true }, style = ButtonStyle.Alt, tiny = true, enabled = guest == GuestManager.State.Ready)
            }
            if (guest != GuestManager.State.Ready) DisabledReason(stringResource(R.string.repo_clone_needs_linux))
            if (repos.isEmpty()) Subtle(stringResource(R.string.repo_none), Modifier.padding(top = 6.dp))
            repos.values.sortedBy { it.addedAtMs }.forEach { r ->
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 15.sp, color = P.ink)
                        Text(r.location, fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 2)
                    }
                    r.preflight?.let {
                        val issues = it.checks.count { c -> !c.passed }
                        Tag(if (it.ok) stringResource(R.string.repo_preflight_ok) else pluralStringResource(R.plurals.repo_preflight_issues, issues, issues), if (it.ok) Tone.Good else Tone.Warn)
                    }
                }
                r.origin?.let { Subtle(stringResource(R.string.repo_cloned_from, it), Modifier.padding(top = 2.dp)) }
                r.preflight?.let { pf ->
                    Disclosure(stringResource(R.string.details)) {
                        pf.checks.forEach { c ->
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PIcon(if (c.passed) PIcons.check else PIcons.close, Modifier.padding(top = 2.dp), size = 13.dp, tint = if (c.passed) P.good else P.danger)
                                Text("${c.name}: ${c.detail}", fontSize = 12.sp, color = P.sub, maxLines = 2)
                            }
                        }
                        Subtle(stringResource(R.string.repo_checked, DateUtils.getRelativeTimeSpanString(pf.atMs).toString()), Modifier.padding(top = 2.dp))
                    }
                }
                Gap(6.dp)
                Segmented(
                    IntegrationPolicy.entries.map { it to stringResource(if (it == IntegrationPolicy.FAST_FORWARD_ONLY) R.string.policy_ff else R.string.policy_rewrite) },
                    r.policy, { container.repositories.setPolicy(r.id, it) }, fill = true,
                )
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (r.projectIds.isEmpty()) stringResource(R.string.repo_no_project) else stringResource(R.string.repo_projects, r.projectIds.joinToString { projects[it]?.name ?: it }), Modifier.weight(1f), fontSize = 12.sp, color = P.sub)
                    var menu by remember(r.id) { mutableStateOf(false) }
                    Box {
                        PButton(stringResource(R.string.repo_link), { menu = true }, style = ButtonStyle.Ghost, tiny = true, enabled = projects.isNotEmpty())
                        DropdownMenu(menu, { menu = false }) {
                            projects.values.forEach { p ->
                                val linked = p.id in r.projectIds
                                DropdownMenuItem(text = { Text(stringResource(if (linked) R.string.repo_unlink else R.string.repo_link_to, p.name)) }, onClick = {
                                    menu = false
                                    if (linked) container.repositories.unlink(r.id, p.id) else container.repositories.link(r.id, p.id)
                                })
                            }
                        }
                    }
                }
                if (projects.isEmpty()) DisabledReason(stringResource(R.string.repo_link_needs_project))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton(stringResource(R.string.repo_preflight), {
                        act(resources.getString(R.string.repo_checking, r.name)) {
                            container.repositories.preflight(r.id, guestGit(container)).preflight!!.let {
                                if (it.ok) resources.getString(R.string.repo_preflight_passed, r.name) else "${r.name}: " + it.checks.filterNot { c -> c.passed }.joinToString("; ") { c -> "${c.name} — ${c.detail}" }
                            }
                        }
                    }, style = ButtonStyle.Alt, tiny = true, enabled = guest == GuestManager.State.Ready)
                    PButton(stringResource(R.string.repo_publish), onCommit, style = ButtonStyle.Ghost, tiny = true)
                    PButton(stringResource(R.string.repo_forget), { container.repositories.forget(r.id); message = resources.getString(R.string.repo_forgotten, r.name, r.location) }, style = ButtonStyle.Ghost, tiny = true)
                }
                if (guest != GuestManager.State.Ready) DisabledReason(stringResource(R.string.repo_preflight_needs_linux))
            }
        }
        Gap(12.dp)

        val unregistered = found.filter { f -> repos.values.none { it.location == f.path } }
        PCard {
            Kicker(stringResource(R.string.repo_found))
            if (unregistered.isEmpty()) Subtle(stringResource(if (guest == GuestManager.State.Ready) R.string.repo_all_registered else R.string.commit_linux_not_ready), Modifier.padding(top = 6.dp))
            unregistered.forEach { f ->
                Gap(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path, fontSize = 12.sp, fontFamily = P.mono, color = P.ink, maxLines = 2)
                        Subtle(listOfNotNull(f.branch?.let { stringResource(R.string.commit_on_branch, it) }, f.head, f.remotes.joinToString().ifEmpty { stringResource(R.string.commit_no_remote) }, f.session?.let { stringResource(R.string.commit_session, it) }).joinToString(" · "))
                    }
                    PButton(stringResource(R.string.repo_register), { act(resources.getString(R.string.repo_registering)) { container.repositories.register(f.path, guestGit(container)).let { resources.getString(R.string.repo_registered, it.name) } } }, style = ButtonStyle.Alt, tiny = true)
                }
            }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.repo_model))
            Disclosure(stringResource(R.string.details)) {
                PSetting(stringResource(R.string.repo_model_hub), stringResource(R.string.repo_model_hub_body)) { Tag(stringResource(R.string.repo_model_hub_tag), Tone.Neutral) }
                PSetting(stringResource(R.string.repo_model_worktrees), stringResource(R.string.repo_model_worktrees_body)) { Tag(stringResource(R.string.repo_model_worktrees_tag), Tone.Neutral) }
                PSetting(stringResource(R.string.repo_model_clone), stringResource(R.string.repo_model_clone_body), divider = false) { Tag(stringResource(R.string.repo_model_clone_tag), Tone.Neutral) }
            }
            Gap(6.dp)
            journal.takeLast(6).reversed().forEach { e ->
                Text("${DateUtils.getRelativeTimeSpanString(e.timeMs)} ${e.kind.removePrefix("repository.")} ${e.data?.toString()?.take(90).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(28.dp)
    }

    if (cloning) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        PConfirm(
            title = stringResource(R.string.repo_clone_title),
            body = stringResource(R.string.repo_clone_body),
            confirmLabel = stringResource(R.string.repo_clone_go),
            danger = false,
            confirmEnabled = url.isNotBlank() && name.isNotBlank(),
            onDismiss = { cloning = false },
            onConfirm = {
                cloning = false
                act(resources.getString(R.string.repo_cloning)) {
                    container.guest.runtime.exec(listOf("mkdir", "-p", "/workspace/repos"))
                    container.repositories.clone(url, "/workspace/repos", name, guestGit(container)).let { resources.getString(R.string.repo_cloned, it.name, it.location) }
                }
            },
            extra = {
                PField(url, { url = it.trim() }, label = stringResource(R.string.repo_clone_url), singleLine = true, mono = true)
                PField(name, { name = it.trim() }, label = stringResource(R.string.repo_clone_folder), singleLine = true)
            },
        )
    }
}
