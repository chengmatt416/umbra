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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
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
    val container = LocalContext.current.container
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
        Eyebrow("Repository hub")
        Headline("Every repository,", "one entry point.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Repositories · ${repos.size}", Modifier.weight(1f))
                PButton("Clone…", { cloning = true }, style = ButtonStyle.Alt, tiny = true, enabled = guest == GuestManager.State.Ready)
            }
            if (repos.isEmpty()) Subtle("None registered. Register a checkout found in the workspace, or clone one.", Modifier.padding(top = 6.dp))
            repos.values.sortedBy { it.addedAtMs }.forEach { r ->
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 15.sp, color = P.ink)
                        Text(r.location, fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 2)
                    }
                    r.preflight?.let { Tag(if (it.ok) "Preflight passed" else "Preflight: ${it.checks.count { c -> !c.passed }} issue(s)", if (it.ok) Tone.Good else Tone.Warn) }
                }
                r.origin?.let { Subtle("Cloned from $it", Modifier.padding(top = 2.dp)) }
                r.preflight?.let { pf ->
                    pf.checks.forEach { c ->
                        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
                            Text(if (c.passed) "✓" else "✗", fontSize = 12.sp, color = if (c.passed) P.good else P.danger)
                            Text("  ${c.name}: ${c.detail}", fontSize = 12.sp, color = P.sub, maxLines = 2)
                        }
                    }
                    Subtle("Checked ${DateUtils.getRelativeTimeSpanString(pf.atMs)}", Modifier.padding(top = 2.dp))
                }
                Gap(6.dp)
                Segmented(IntegrationPolicy.entries.map { it to it.label }, r.policy, { container.repositories.setPolicy(r.id, it) })
                Gap(6.dp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (r.projectIds.isEmpty()) "No project linked" else "Projects: " + r.projectIds.joinToString { projects[it]?.name ?: it }, Modifier.weight(1f), fontSize = 12.sp, color = P.sub)
                    var menu by remember(r.id) { mutableStateOf(false) }
                    Box {
                        PButton("Link…", { menu = true }, style = ButtonStyle.Ghost, tiny = true, enabled = projects.isNotEmpty())
                        DropdownMenu(menu, { menu = false }) {
                            projects.values.forEach { p ->
                                val linked = p.id in r.projectIds
                                DropdownMenuItem(text = { Text((if (linked) "Unlink " else "Link ") + p.name) }, onClick = {
                                    menu = false
                                    if (linked) container.repositories.unlink(r.id, p.id) else container.repositories.link(r.id, p.id)
                                })
                            }
                        }
                    }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PButton("Preflight", { act("Checking ${r.name}") { container.repositories.preflight(r.id, guestGit(container)).preflight!!.let { if (it.ok) "${r.name}: preflight passed." else "${r.name}: " + it.checks.filterNot { c -> c.passed }.joinToString("; ") { c -> "${c.name} — ${c.detail}" } } } }, style = ButtonStyle.Alt, tiny = true, enabled = guest == GuestManager.State.Ready)
                    PButton("Publish…", onCommit, style = ButtonStyle.Ghost, tiny = true)
                    PButton("Forget", { container.repositories.forget(r.id); message = "${r.name} forgotten here; its files stay in ${r.location}." }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
        }
        Gap(12.dp)

        val unregistered = found.filter { f -> repos.values.none { it.location == f.path } }
        PCard {
            Kicker("Found in the workspace · not registered")
            if (unregistered.isEmpty()) Subtle(if (guest == GuestManager.State.Ready) "Every checkout under /workspace is registered." else "The Linux environment is not ready.", Modifier.padding(top = 6.dp))
            unregistered.forEach { f ->
                Gap(8.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.path, fontSize = 12.sp, fontFamily = P.mono, color = P.ink, maxLines = 2)
                        Subtle(listOfNotNull(f.branch?.let { "on $it" }, f.head, f.remotes.joinToString().ifEmpty { "no remote" }, f.session?.let { "session · $it" }).joinToString(" · "))
                    }
                    PButton("Register", { act("Registering") { container.repositories.register(f.path, guestGit(container)).let { "${it.name} registered (fast-forward only until you change it)." } } }, style = ButtonStyle.Alt, tiny = true)
                }
            }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker("Checkouts, worktrees and clones")
            PSetting("This hub", "Each repository here is one checkout in the workspace; its location is its identity") { Tag("Checkout", Tone.Neutral) }
            PSetting("Worktrees and shared refs", "Umbra makes none: an agent works in the checkout its session is in") { Tag("Not made", Tone.Neutral) }
            PSetting("A second clone", "An independent copy with its own objects and refs; publishing from it goes through the same commit gate", divider = false) { Tag("Independent", Tone.Neutral) }
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
        AlertDialog(
            onDismissRequest = { cloning = false },
            title = { Text("Clone a repository") },
            text = {
                Column {
                    Text("Into /workspace/repos/<name>, with the Linux environment's own git credentials (no password prompt).", fontSize = 13.sp)
                    OutlinedTextField(url, { url = it.trim() }, Modifier.padding(top = 8.dp), label = { Text("URL (https://, ssh://, git@host:path)") }, singleLine = true)
                    OutlinedTextField(name, { name = it.trim() }, Modifier.padding(top = 6.dp), label = { Text("Folder name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank() && name.isNotBlank(), onClick = {
                    cloning = false
                    act("Cloning") {
                        container.guest.runtime.exec(listOf("mkdir", "-p", "/workspace/repos"))
                        container.repositories.clone(url, "/workspace/repos", name, guestGit(container)).let { "${it.name} cloned to ${it.location} and registered." }
                    }
                }) { Text("Clone") }
            },
            dismissButton = { TextButton(onClick = { cloning = false }) { Text("Cancel") } },
        )
    }
}
