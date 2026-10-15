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
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.agent.integration.Repository
import app.umbra.agent.integration.findRepositories
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.DisabledReason
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.PConfirm
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
import app.umbra.runtime.linux.LinuxMode
import kotlinx.coroutines.launch

/**
 * Integration & Commit Authority (architecture spec §11): a repository's commit is published to a remote branch only
 * through the commit gate. Each candidate says in one sentence whether it can be published directly or would rewrite
 * the remote's history; the protocol (check, prepare and fence, write, read back) is under its details. Agents may
 * compute candidates; only this screen publishes them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommitScreen(onOpenVault: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val promotions by container.commits.all.collectAsStateWithLifecycle()
    val journal by container.commits.recent.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    val gitCredentials by container.gitCredentials.all.collectAsStateWithLifecycle()
    val proot = guest == GuestManager.State.Ready && container.guest.mode == LinuxMode.PROOT
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
        Eyebrow(stringResource(R.string.commit_eyebrow))
        Headline(stringResource(R.string.commit_headline), stringResource(R.string.commit_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        if (proot) {
            PCard(style = CardStyle.Soft) {
                Text(stringResource(R.string.commit_proot_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                Subtle(stringResource(R.string.commit_proot_body), Modifier.padding(top = 6.dp))
            }
            Gap(12.dp)
        }
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Kicker(stringResource(R.string.commit_repos))
            when {
                guest != GuestManager.State.Ready -> Subtle(stringResource(R.string.commit_linux_not_ready), Modifier.padding(top = 6.dp))
                repos == null -> Subtle(stringResource(R.string.commit_looking), Modifier.padding(top = 6.dp))
                repos!!.isEmpty() -> Subtle(stringResource(R.string.commit_no_repo), Modifier.padding(top = 6.dp))
            }
            repos.orEmpty().forEach { r ->
                Gap(12.dp)
                Text(r.path, fontSize = 13.sp, fontFamily = P.mono, color = P.ink)
                Subtle(
                    listOfNotNull(
                        r.branch?.let { stringResource(R.string.commit_on_branch, it) } ?: stringResource(R.string.commit_detached), r.head,
                        r.remotes.joinToString().ifEmpty { stringResource(R.string.commit_no_remote) }, r.session?.let { stringResource(R.string.commit_session, it) },
                    ).joinToString(" · "),
                    Modifier.padding(top = 2.dp),
                )
                val checked = stringResource(R.string.commit_validated)
                val refused = stringResource(R.string.commit_refused, "%s")
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.remotes.take(3).forEach { remote ->
                        PButton(stringResource(R.string.commit_propose_to, remote), {
                            act(resources.getString(R.string.commit_validating)) {
                                val p = container.commits.propose(r.path, remote, r.branch)
                                if (p.state == PromotionState.VALIDATED) checked else refused.format(p.problems.joinToString("; "))
                            }
                        }, style = ButtonStyle.Alt, tiny = true, enabled = busy == null && r.branch != null)
                    }
                    // Through the broker: the remote is the credential's URL, whatever the checkout's own configuration says.
                    if (!proot) gitCredentials.values.forEach { c ->
                        PButton(stringResource(R.string.commit_propose_via, c.label), {
                            act(resources.getString(R.string.commit_validating_broker)) {
                                val p = container.commits.propose(r.path, c.url, r.branch)
                                if (p.state == PromotionState.VALIDATED) resources.getString(R.string.commit_validated_broker, c.url) else refused.format(p.problems.joinToString("; "))
                            }
                        }, style = ButtonStyle.Alt, tiny = true, enabled = busy == null && r.branch != null && app.umbra.core.control.GitOperation.PUBLISH in c.operations)
                        PButton(stringResource(R.string.commit_fetch), {
                            act(resources.getString(R.string.commit_fetching)) {
                                val branch = r.branch ?: "main"
                                val sha = container.gitBroker.fetchThroughBroker(r.path, c.id, branch)
                                resources.getString(R.string.commit_fetched, c.url, branch, sha.take(12), r.path)
                            }
                        }, style = ButtonStyle.Ghost, tiny = true, enabled = busy == null && app.umbra.core.control.GitOperation.READ in c.operations)
                    }
                }
                if (r.branch == null) DisabledReason(stringResource(R.string.commit_needs_branch))
                else if (!proot && gitCredentials.values.any { app.umbra.core.control.GitOperation.PUBLISH !in it.operations }) DisabledReason(stringResource(R.string.commit_needs_publish))
            }
            Subtle(stringResource(R.string.commit_validation_note), Modifier.padding(top = 12.dp))
        }
        Gap(12.dp)

        promotions.values.sortedByDescending { it.createdAtMs }.take(8).forEach { p ->
            val reading = stringResource(R.string.commit_reading_remote)
            PromotionCard(p, busy == null, onAuthorize = { authorizing = p }, onVerify = {
                act(reading) { container.commits.verify(p.id).let { "${resources.getString(promotionRes(it.state))}: ${it.note.orEmpty()}" } }
            }, onAbandon = { container.commits.abandon(p.id) })
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.commit_who))
            Subtle(stringResource(R.string.commit_who_body), Modifier.padding(top = 6.dp))
            Disclosure(stringResource(R.string.details)) {
                PSetting(stringResource(R.string.commit_auth_exec), stringResource(R.string.commit_auth_exec_body)) { Tag(stringResource(R.string.commit_granted), Tone.Good) }
                PSetting(stringResource(R.string.commit_auth_promo), stringResource(R.string.commit_auth_promo_body)) { Tag(stringResource(R.string.commit_separate), Tone.Neutral) }
                PSetting(stringResource(R.string.commit_auth_gate), stringResource(R.string.commit_auth_gate_body)) { Tag(stringResource(R.string.commit_enforced), Tone.Good) }
                PSetting(stringResource(R.string.commit_auth_custody), stringResource(if (gitCredentials.isEmpty()) R.string.commit_auth_custody_none else R.string.commit_auth_custody_broker), divider = false) {
                    PButton(stringResource(R.string.commit_vault), onOpenVault, style = ButtonStyle.Ghost, tiny = true)
                }
                Subtle(stringResource(R.string.commit_not_exactly_once), Modifier.padding(top = 8.dp))
            }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(stringResource(R.string.commit_journal), Modifier.weight(1f))
                val check = remember(journal.size) { runCatching { container.commits.verifyJournal() }.getOrNull() }
                check?.let { Tag(if (it.intact) stringResource(R.string.commit_chain_intact, it.entries) else stringResource(R.string.commit_chain_broken, it.firstBadSeq ?: 0), if (it.intact) Tone.Good else Tone.Danger) }
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
        val n = p.evidence?.commits?.size ?: 0
        val publishing = stringResource(R.string.commit_publishing)
        PConfirm(
            title = stringResource(R.string.commit_publish_title, p.remote, p.branch),
            body = pluralStringResource(R.plurals.commit_publish_body, n, p.branch, p.remote, n) + "\n\n" +
                stringResource(if (ff) R.string.commit_verdict_ff else R.string.commit_verdict_rewrite, p.branch),
            confirmLabel = stringResource(R.string.commit_publish),
            danger = !ff,
            confirmEnabled = ff || rewrite,
            onDismiss = { authorizing = null },
            onConfirm = {
                authorizing = null
                act(publishing) {
                    val done = container.commits.promote(p.id, authorizedBy = "user", allowRewrite = rewrite)
                    resources.getString(promotionRes(done.state)) + (done.note?.let { ": $it" } ?: "") + if (done.problems.isNotEmpty() && done.state == PromotionState.REFUSED) ": ${done.problems.last()}" else ""
                }
            },
            extra = if (ff) null else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rewrite, { rewrite = it })
                        Text(stringResource(R.string.commit_rewrite_confirm, p.branch, p.remote), fontSize = 12.sp, lineHeight = 17.sp, color = P.danger)
                    }
                }
            },
        )
    }
}

@StringRes
private fun promotionRes(s: PromotionState) = when (s) {
    PromotionState.VALIDATED -> R.string.ps_validated
    PromotionState.REFUSED -> R.string.ps_refused
    PromotionState.PREPARED -> R.string.ps_prepared
    PromotionState.COMMITTING -> R.string.ps_committing
    PromotionState.COMMITTED -> R.string.ps_committed
    PromotionState.REJECTED -> R.string.ps_rejected
    PromotionState.OUTCOME_UNKNOWN -> R.string.ps_unknown
    PromotionState.ABANDONED -> R.string.ps_abandoned
}

@Composable
private fun promotionLabel(s: PromotionState) = stringResource(promotionRes(s))

@Composable
private fun PromotionCard(p: Promotion, enabled: Boolean, onAuthorize: () -> Unit, onVerify: () -> Unit, onAbandon: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    PCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${p.remote} · ${p.branch}", fontSize = 11.sp, fontFamily = P.mono, color = P.accent, maxLines = 2)
                Text(p.repo, Modifier.padding(top = 4.dp), fontSize = 12.sp, fontFamily = P.mono, color = P.ink, maxLines = 2)
            }
            Tag(promotionLabel(p.state), when (p.state) {
                PromotionState.COMMITTED -> Tone.Good
                PromotionState.VALIDATED, PromotionState.PREPARED, PromotionState.COMMITTING -> Tone.Neutral
                PromotionState.OUTCOME_UNKNOWN -> Tone.Warn
                else -> Tone.Danger
            })
        }
        Subtle(
            stringResource(R.string.commit_over, p.candidate.take(12), p.expectedOld?.take(12) ?: stringResource(R.string.commit_new_branch), DateUtils.getRelativeTimeSpanString(p.updatedAtMs).toString()),
            Modifier.padding(top = 2.dp),
        )
        // The decision in one sentence: publishable as is, or a rewrite that needs its own confirmation.
        p.evidence?.let { ev ->
            Gap(10.dp)
            Callout(stringResource(if (ev.fastForward) R.string.commit_verdict_ff else R.string.commit_verdict_rewrite, p.branch), if (ev.fastForward) Tone.Good else Tone.Warn)
        }
        p.problems.forEach { Gap(6.dp); Callout(it, Tone.Warn) }
        p.note?.let { Subtle(it, Modifier.padding(top = 6.dp)) }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.state == PromotionState.VALIDATED) PButton(stringResource(R.string.commit_publish), onAuthorize, tiny = true, enabled = enabled)
            if (p.state == PromotionState.OUTCOME_UNKNOWN) PButton(stringResource(R.string.commit_read_remote), onVerify, tiny = true, enabled = enabled)
            if (p.state == PromotionState.VALIDATED || p.state == PromotionState.REFUSED || p.state == PromotionState.REJECTED) PButton(stringResource(R.string.commit_abandon), onAbandon, style = ButtonStyle.Ghost, tiny = true)
        }
        Disclosure(stringResource(R.string.details)) {
            p.evidence?.let { ev ->
                Tag(pluralStringResource(R.plurals.commit_count, ev.commits.size, ev.commits.size), Tone.Neutral)
                ev.commits.take(5).forEach { Text(it, Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 1) }
                Text(ev.diffStat.lines().takeLast(1).joinToString(), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                Text(stringResource(R.string.commit_tree, ev.tree.take(12)), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
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
                    StepItem(stringResource(R.string.commit_step_validate), stringResource(R.string.commit_step_validate_d), st(0)),
                    StepItem(
                        stringResource(R.string.commit_step_prepare),
                        p.capability?.let { stringResource(R.string.commit_step_prepare_cap, it.epoch, DateUtils.formatDateTime(context, it.expiresAtMs, DateUtils.FORMAT_SHOW_TIME)) } ?: stringResource(R.string.commit_step_prepare_d),
                        st(1),
                    ),
                    StepItem(stringResource(R.string.commit_step_commit), stringResource(R.string.commit_step_commit_d), st(2)),
                    StepItem(stringResource(R.string.commit_step_verify), p.observed?.let { stringResource(R.string.commit_step_verify_seen, it.take(12)) } ?: stringResource(R.string.commit_step_verify_d), st(3)),
                ),
            )
        }
    }
}
