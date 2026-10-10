package app.umbra.agent.ui.integration

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.Promotion
import app.umbra.core.control.PromotionState
import kotlinx.coroutines.launch

/**
 * Integration & Commit Authority (architecture spec §11): a repository's commit is published to a remote branch only
 * through the commit gate. You see what would be published (commits, diff, the tree it records, whether it is a
 * fast-forward), authorize it here, and the gate writes it conditionally and reads it back. Agents may compute
 * candidates; only this screen publishes them.
 */
@Composable
fun CommitScreen(onOpenVault: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val promotions by container.commits.all.collectAsStateWithLifecycle()
    val journal by container.commits.recent.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    var repos by remember { mutableStateOf<List<Repository>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var authorizing by remember { mutableStateOf<Promotion?>(null) }
    LaunchedEffect(guest, promotions.size) {
        repos = if (guest == GuestManager.State.Ready) runCatching { findRepositories(container) }.getOrElse { message = it.message; emptyList() } else emptyList()
    }

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch {
            message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Integration & commit authority")
        Headline("Proof before", "publication.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Kicker("Repositories in the workspace")
            when {
                guest != GuestManager.State.Ready -> Subtle("The Linux environment is not ready.", Modifier.padding(top = 6.dp))
                repos == null -> Subtle("Looking…", Modifier.padding(top = 6.dp))
                repos!!.isEmpty() -> Subtle("No git repository under /workspace.", Modifier.padding(top = 6.dp))
            }
            repos.orEmpty().forEach { r ->
                Gap(10.dp)
                Text(r.path, fontSize = 13.sp, fontFamily = P.mono, color = P.ink)
                Subtle(listOfNotNull(r.branch?.let { "on $it" } ?: "detached HEAD", r.head, r.remotes.joinToString().ifEmpty { "no remote" }, r.session?.let { "session · $it" }).joinToString(" · "), Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.remotes.take(3).forEach { remote ->
                        PButton("Propose to $remote", {
                            act("Validating") {
                                val p = container.commits.propose(r.path, remote, r.branch)
                                if (p.state == PromotionState.VALIDATED) "Validated: review it below, then authorize." else "Refused: ${p.problems.joinToString("; ")}"
                            }
                        }, style = ButtonStyle.Alt, tiny = true, enabled = busy == null && r.branch != null)
                    }
                }
            }
            Subtle("Validation reads the candidate commit itself (its tree is what is published); no separate staging worktree is made.", Modifier.padding(top = 10.dp))
        }
        Gap(12.dp)

        promotions.values.sortedByDescending { it.createdAtMs }.take(8).forEach { p ->
            PromotionCard(p, busy == null, onAuthorize = { authorizing = p }, onVerify = {
                act("Reading the remote") { container.commits.verify(p.id).let { "${stateLabel(it.state)}: ${it.note.orEmpty()}" } }
            }, onAbandon = { container.commits.abandon(p.id) })
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker("Commit authority boundary")
            PSetting("Execution authority", "Agents may compute a candidate: commit in their workspace") { Tag("Granted", Tone.Good) }
            PSetting("Promotion authorization", "Only you, here, for one promotion; a candidate is not a push") { Tag("Separate", Tone.Neutral) }
            PSetting("Resource commit gate", "The write is conditional on the remote ref, of the current fence epoch, with a single-use capability, and read back") { Tag("Enforced", Tone.Good) }
            PSetting("Credential custody", "The push uses the repository's own git credentials in Linux; Umbra holds and brokers none (yet)", divider = false) {
                PButton("Vault", onOpenVault, style = ButtonStyle.Ghost, tiny = true)
            }
            Subtle("External side effects are not exactly-once: an unknown outcome is settled by reading the remote, never by sending again.", Modifier.padding(top = 8.dp))
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Promotion journal", Modifier.weight(1f))
                val check = remember(journal.size) { runCatching { container.commits.verifyJournal() }.getOrNull() }
                check?.let { Tag(if (it.intact) "Chain intact · ${it.entries}" else "Broken at #${it.firstBadSeq}", if (it.intact) Tone.Good else Tone.Danger) }
            }
            journal.takeLast(8).reversed().forEach { e ->
                Text("${DateUtils.formatDateTime(context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind.removePrefix("promotion.")}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(28.dp)
    }

    authorizing?.let { p ->
        var rewrite by remember(p.id) { mutableStateOf(false) }
        val ff = p.evidence?.fastForward ?: false
        AlertDialog(
            onDismissRequest = { authorizing = null },
            title = { Text("Publish to ${p.remote}/${p.branch}?") },
            text = {
                Column {
                    Text("Writes ${p.candidate.take(12)} to ${p.remote}'s ${p.branch}, only if it is still ${p.expectedOld?.take(12) ?: "absent"} there. ${p.evidence?.commits?.size ?: 0} commit(s).", fontSize = 13.sp)
                    if (!ff) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rewrite, { rewrite = it })
                        Text("Allow rewriting ${p.branch}'s history on ${p.remote} (not a fast-forward: commits there would be dropped)", fontSize = 12.sp, color = P.danger)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = ff || rewrite, onClick = {
                    authorizing = null
                    act("Publishing") {
                        val done = container.commits.promote(p.id, authorizedBy = "user", allowRewrite = rewrite)
                        "${stateLabel(done.state)}" + (done.note?.let { ": $it" } ?: "") + if (done.problems.isNotEmpty() && done.state == PromotionState.REFUSED) ": ${done.problems.last()}" else ""
                    }
                }) { Text("Authorize & publish") }
            },
            dismissButton = { TextButton(onClick = { authorizing = null }) { Text("Cancel") } },
        )
    }
}

private fun stateLabel(s: PromotionState) = when (s) {
    PromotionState.VALIDATED -> "Validated"
    PromotionState.REFUSED -> "Refused"
    PromotionState.PREPARED -> "Prepared"
    PromotionState.COMMITTING -> "Writing"
    PromotionState.COMMITTED -> "Committed and verified"
    PromotionState.REJECTED -> "Not written"
    PromotionState.OUTCOME_UNKNOWN -> "Outcome unknown"
    PromotionState.ABANDONED -> "Abandoned"
}

@Composable
private fun PromotionCard(p: Promotion, enabled: Boolean, onAuthorize: () -> Unit, onVerify: () -> Unit, onAbandon: () -> Unit) {
    val context = LocalContext.current
    PCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker("${p.remote} · ${p.branch}")
                Text(p.repo, Modifier.padding(top = 4.dp), fontSize = 12.sp, fontFamily = P.mono, color = P.ink, maxLines = 2)
            }
            Tag(stateLabel(p.state), when (p.state) {
                PromotionState.COMMITTED -> Tone.Good
                PromotionState.VALIDATED, PromotionState.PREPARED, PromotionState.COMMITTING -> Tone.Neutral
                PromotionState.OUTCOME_UNKNOWN -> Tone.Warn
                else -> Tone.Danger
            })
        }
        Subtle("${p.candidate.take(12)} over ${p.expectedOld?.take(12) ?: "a new branch"} · ${DateUtils.getRelativeTimeSpanString(p.updatedAtMs)}", Modifier.padding(top = 2.dp))
        p.evidence?.let { ev ->
            Gap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(if (ev.fastForward) "Fast-forward" else "Rewrites history", if (ev.fastForward) Tone.Good else Tone.Warn)
                Tag("${ev.commits.size} commit(s)", Tone.Neutral)
            }
            ev.commits.take(5).forEach { Text(it, Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 1) }
            Text(ev.diffStat.lines().takeLast(1).joinToString(), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            Text("tree ${ev.tree.take(12)}", fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        }
        p.problems.forEach { Gap(6.dp); Callout(it, Tone.Warn) }
        Gap(8.dp)
        val order = listOf(PromotionState.VALIDATED, PromotionState.PREPARED, PromotionState.COMMITTING, PromotionState.COMMITTED)
        val reached = order.indexOf(p.state).let { if (it < 0) (if (p.state == PromotionState.OUTCOME_UNKNOWN || (p.state == PromotionState.REJECTED && p.capability?.used == true)) 2 else if (p.capability != null) 1 else 0) else it }
        fun st(i: Int) = when {
            p.state == PromotionState.COMMITTED -> StepState.Done
            i < reached -> StepState.Done
            i == reached -> if (p.state == PromotionState.REJECTED || p.state == PromotionState.REFUSED) StepState.Failed else StepState.Active
            else -> StepState.Wait
        }
        Steps(
            listOf(
                StepItem("Validate", "Candidate, remote ref, fast-forward, evidence", st(0)),
                StepItem("Prepare · fence", p.capability?.let { "Durable intent; epoch ${it.epoch}, single-use until ${DateUtils.formatDateTime(context, it.expiresAtMs, DateUtils.FORMAT_SHOW_TIME)}" } ?: "Durable intent and a new fence epoch", st(1)),
                StepItem("Commit", "Conditional write (lease on the old value)", st(2)),
                StepItem("Verify · finalize", p.observed?.let { "Remote read back: ${it.take(12)}" } ?: "Read the remote back; receipt in the journal", st(3)),
            ),
        )
        p.note?.let { Subtle(it, Modifier.padding(top = 4.dp)) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.state == PromotionState.VALIDATED) PButton("Authorize & publish", onAuthorize, tiny = true, enabled = enabled)
            if (p.state == PromotionState.OUTCOME_UNKNOWN) PButton("Read the remote", onVerify, tiny = true, enabled = enabled)
            if (p.state == PromotionState.VALIDATED || p.state == PromotionState.REFUSED || p.state == PromotionState.REJECTED) PButton("Abandon", onAbandon, style = ButtonStyle.Ghost, tiny = true)
        }
    }
}
