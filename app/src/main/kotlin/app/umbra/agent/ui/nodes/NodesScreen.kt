package app.umbra.agent.ui.nodes

import android.os.Build
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
import app.umbra.agent.ui.porcelain.AlertDialog
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.nodeTrustRes
import app.umbra.agent.ui.porcelain.DisabledReason
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.PConfirm
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
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.NodeJob
import app.umbra.core.control.NodeRevocation
import app.umbra.core.control.NodeTrust
import app.umbra.core.control.PairedNode
import app.umbra.core.nodes.JobState
import app.umbra.core.nodes.Manifests
import app.umbra.core.nodes.Rsp
import app.umbra.core.nodes.shortFingerprint
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Distributed Nodes (architecture spec §10): computers you own, paired by a one-time code and pinned certificates,
 * running jobs the placement policy allows, with only the files a manifest lists, each for at most its offline lease;
 * what happened while the phone was away comes from the node's own account at rejoin.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NodesScreen() {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val nodes by container.nodes.all.collectAsStateWithLifecycle()
    val jobs by container.nodes.allJobs.collectAsStateWithLifecycle()
    val policies by container.nodes.policyFlow.collectAsStateWithLifecycle()
    val journal by container.nodes.recent.collectAsStateWithLifecycle()
    val policy = policies["policy"] ?: container.nodes.policy()
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<PairedNode?>(null) }
    var revoking by remember { mutableStateOf<PairedNode?>(null) }
    var unpairing by remember { mutableStateOf<PairedNode?>(null) }
    var redispatching by remember { mutableStateOf<NodeJob?>(null) }

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch { message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.nodes_eyebrow))
        Headline(stringResource(R.string.nodes_headline), stringResource(R.string.nodes_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Kicker(stringResource(R.string.nodes_exec))
            Gap(8.dp)
            PObject(stringResource(R.string.nodes_this_device), stringResource(R.string.nodes_this_device_body, Build.VERSION.RELEASE, Build.MODEL), PIcons.phone, trailing = { Tag(stringResource(R.string.nodes_local), Tone.Good) })
            nodes.values.sortedBy { it.pairedAtMs }.forEach { n ->
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontSize = 15.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${n.host}:${n.port} · ${shortFingerprint(n.fingerprint)}", fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                    }
                    val rev = n.revocation
                    when {
                        rev == null -> Tag(
                            if (container.nodeConnections.connected(n.id)) stringResource(R.string.nodes_connected)
                            else n.lastSeenMs?.let { stringResource(R.string.nodes_seen, DateUtils.getRelativeTimeSpanString(it).toString()) } ?: stringResource(R.string.nodes_not_seen),
                            if (container.nodeConnections.connected(n.id)) Tone.Good else Tone.Neutral,
                        )
                        rev.pending -> Tag(stringResource(R.string.nodes_revocation_pending), Tone.Warn)
                        else -> Tag(stringResource(R.string.nodes_revoked_ack), Tone.Neutral)
                    }
                }
                val revocation = n.revocation
                if (revocation != null) {
                    RevocationDetail(n, revocation, onRetry = { act(resources.getString(R.string.nodes_telling, n.name)) { if (container.nodeConnections.notifyRevoked(n.id)) resources.getString(R.string.nodes_revoke_answered, n.name) else resources.getString(R.string.nodes_revoke_unreachable, n.name) } },
                        onForget = { container.nodes.unpair(n.id); message = resources.getString(R.string.nodes_record_gone, n.name) })
                } else {
                    n.info?.let { i -> Subtle(stringResource(R.string.nodes_info, i.os, i.arch, i.cpus, i.memoryMb / 1024), Modifier.padding(top = 2.dp)) }
                    Gap(6.dp)
                    Segmented(NodeTrust.entries.map { it to trustLabel(it) }, n.trust, { t -> container.nodes.setTrust(n.id, t); message = null }, fill = true)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PButton(stringResource(R.string.nodes_run), { running = n }, tiny = true)
                        PButton(stringResource(R.string.nodes_preflight), { act(resources.getString(R.string.busy_working)) { container.nodeConnections.preflight(n.id).let { resources.getString(R.string.nodes_preflight_result, n.name, it.os, it.cpus, it.tools.keys.joinToString()) } } }, style = ButtonStyle.Alt, tiny = true)
                        PButton(stringResource(R.string.nodes_rejoin), { act(resources.getString(R.string.busy_working)) { container.nodeConnections.reconcile(n.id); resources.getString(R.string.nodes_rejoin_ok, n.name) } }, style = ButtonStyle.Ghost, tiny = true)
                    }
                    Disclosure(stringResource(R.string.details)) {
                        n.info?.let { i ->
                            Subtle(i.tools.keys.joinToString().ifEmpty { "—" })
                            Subtle(stringResource(R.string.np_runner_epoch, i.epoch.take(24), i.workspace), Modifier.padding(top = 2.dp))
                        }
                        Text("${n.host}:${n.port} · ${shortFingerprint(n.fingerprint)}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PButton(stringResource(R.string.nodes_unpair), { unpairing = n }, style = ButtonStyle.Ghost, tiny = true)
                            PButton(stringResource(R.string.nodes_revoke), { revoking = n }, style = ButtonStyle.Ghost, tiny = true)
                        }
                    }
                }
            }
            if (nodes.isEmpty()) Subtle(stringResource(R.string.nodes_none), Modifier.padding(top = 8.dp))
            // Pairing needs a terminal and Java on a computer: it is offered, labelled for developers, behind one line.
            Box(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Disclosure(stringResource(R.string.nodes_dev_title)) {
                    Tag(stringResource(R.string.nodes_dev_tag), Tone.Neutral)
                    Subtle(stringResource(R.string.nodes_dev_body), Modifier.padding(top = 6.dp))
                    Box(Modifier.padding(top = 8.dp)) { PButton(stringResource(R.string.nodes_pair), { pairing = true }, style = ButtonStyle.Alt, tiny = true) }
                }
            }
        }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.nodes_jobs))
            val list = jobs.values.sortedByDescending { it.startedAtMs }.take(10)
            if (list.isEmpty()) Subtle(stringResource(R.string.nodes_no_jobs), Modifier.padding(top = 6.dp))
            list.forEach { j ->
                val node = nodes[j.nodeId]
                // Lost, or out of reach with its lease run out: nothing moves it by itself (ADR 0018 §8), so say so here.
                val stranded = node?.revocation == null && (j.state == JobState.LOST || (j.state == JobState.RUNNING && !container.nodeConnections.connected(j.nodeId) && j.leaseUntilMs < System.currentTimeMillis()))
                JobRow(
                    j, node?.name ?: j.nodeId, revoked = node?.revocation != null, stranded = stranded,
                    onCancel = { act(resources.getString(R.string.busy_working)) { container.nodeConnections.cancel(j.nodeId, j.id); resources.getString(R.string.nodes_cancelled_there) } },
                    onForget = { act(resources.getString(R.string.busy_working)) { container.nodeConnections.forget(j.nodeId, j.id); resources.getString(R.string.nodes_job_folder_deleted) } },
                    onRedispatch = { redispatching = j },
                )
            }
        }
        Gap(12.dp)

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(stringResource(R.string.np_policy), Modifier.weight(1f))
                Tag(stringResource(R.string.np_policy_rev, policy.revision), Tone.Neutral)
            }
            PSetting(stringResource(R.string.np_who), stringResource(R.string.np_who_d)) {
                Segmented(NodeTrust.entries.map { it to trustLabel(it) }, policy.minimumTrust, { t -> container.nodes.updatePolicy { it.copy(minimumTrust = t) } })
            }
            PSetting(stringResource(R.string.np_upload), stringResource(R.string.np_upload_d, (policy.maxUploadBytes / (1024 * 1024)).toInt())) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PButton("−", { container.nodes.updatePolicy { it.copy(maxUploadBytes = (it.maxUploadBytes / 2).coerceAtLeast(1024 * 1024)) } }, style = ButtonStyle.Ghost, tiny = true)
                    PButton("+", { container.nodes.updatePolicy { it.copy(maxUploadBytes = (it.maxUploadBytes * 2).coerceAtMost(1024L * 1024 * 1024)) } }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            PSetting(stringResource(R.string.np_lease), stringResource(R.string.np_lease_d, policy.offlineLeaseMinutes.toInt())) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PButton("−", { container.nodes.updatePolicy { it.copy(offlineLeaseMinutes = (it.offlineLeaseMinutes / 2).coerceAtLeast(1)) } }, style = ButtonStyle.Ghost, tiny = true)
                    PButton("+", { container.nodes.updatePolicy { it.copy(offlineLeaseMinutes = (it.offlineLeaseMinutes * 2).coerceAtMost(24 * 60)) } }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            PSetting(stringResource(R.string.np_secrets), stringResource(R.string.np_secrets_d), divider = false) { Tag(stringResource(R.string.np_never), Tone.Good) }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.np_contract))
            PSetting(stringResource(R.string.np_partition), stringResource(R.string.np_partition_d)) { Tag(stringResource(R.string.np_lease_bound), Tone.Neutral) }
            PSetting(stringResource(R.string.np_failover), stringResource(R.string.np_failover_d)) { Tag(stringResource(R.string.np_manual), Tone.Neutral) }
            PSetting(stringResource(R.string.np_identity), stringResource(R.string.np_identity_d)) { Tag(stringResource(R.string.np_pinned), Tone.Good) }
            PSetting(stringResource(R.string.np_execution), stringResource(R.string.np_execution_d), divider = false) { Tag(stringResource(R.string.np_user_level), Tone.Warn) }
            Gap(6.dp)
            journal.takeLast(6).reversed().forEach { e ->
                Text("${DateUtils.formatDateTime(context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind.removePrefix("nodes.")} ${e.data?.toString()?.take(90).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(28.dp)
    }

    if (pairing) {
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf(Rsp.DEFAULT_PORT.toString()) }
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pairing = false },
            title = { Text(stringResource(R.string.np_pair_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.np_pair_body), fontSize = 13.sp)
                    OutlinedTextField(host, { host = it.trim() }, Modifier.padding(top = 8.dp), label = { Text(stringResource(R.string.np_address)) }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.np_port)) }, singleLine = true)
                    OutlinedTextField(code, { code = it }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.np_code)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank() && Pairing12(code) && port.isNotBlank(), onClick = {
                    pairing = false
                    act(resources.getString(R.string.nodes_pairing)) { container.nodeConnections.pair(host, port.toInt(), code, "${Build.MANUFACTURER} ${Build.MODEL}").let { resources.getString(R.string.nodes_paired, it.name, shortFingerprint(it.fingerprint)) } }
                }) { Text(stringResource(R.string.np_pair)) }
            },
            dismissButton = { TextButton(onClick = { pairing = false }) { Text(stringResource(R.string.np_cancel)) } },
        )
    }
    unpairing?.let { n ->
        app.umbra.agent.ui.porcelain.PConfirm(
            title = stringResource(R.string.nodes_unpair_title, n.name),
            body = stringResource(R.string.nodes_unpair_body, n.name),
            confirmLabel = stringResource(R.string.nodes_unpair),
            onDismiss = { unpairing = null },
            onConfirm = {
                unpairing = null
                act(resources.getString(R.string.nodes_unpairing, n.name)) {
                    if (container.nodeConnections.unpair(n.id)) resources.getString(R.string.nodes_unpaired_both, n.name)
                    else resources.getString(R.string.nodes_forgotten_here, n.name)
                }
            },
        )
    }
    revoking?.let { n ->
        var reason by remember(n.id) { mutableStateOf("") }
        val mine = jobs.values.filter { it.nodeId == n.id }
        val live = mine.filter { it.state == JobState.RUNNING }
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.np_revoke_title, n.name)) },
            text = {
                Column {
                    Text(stringResource(R.string.np_revoke_body), fontSize = 13.sp)
                    Text(
                        (if (live.isEmpty()) stringResource(R.string.np_no_job_there) else pluralStringResource(R.plurals.np_jobs_there, live.size, live.size)) + " " +
                            stringResource(R.string.np_already_sent, mine.sumOf { it.manifestFiles }, android.text.format.Formatter.formatShortFileSize(context, mine.sumOf { it.manifestBytes })),
                        Modifier.padding(top = 6.dp), fontSize = 13.sp, color = P.sub,
                    )
                    OutlinedTextField(reason, { reason = it }, Modifier.padding(top = 8.dp), label = { Text(stringResource(R.string.np_why)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    act(resources.getString(R.string.nodes_revoking, n.name)) {
                        if (container.nodeConnections.revoke(n.id, reason)) resources.getString(R.string.nodes_revoked_told, n.name)
                        else resources.getString(R.string.nodes_revoked_untold, n.name)
                    }
                }) { Text(stringResource(R.string.np_revoke)) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.np_cancel)) } },
        )
    }
    redispatching?.let { j ->
        val candidates = nodes.values.filter { it.revocation == null && container.nodes.placementProblem(it.id, 0) == null }.sortedBy { it.pairedAtMs }
        var target by remember(j.id) { mutableStateOf(candidates.firstOrNull { it.id != j.nodeId }?.id ?: candidates.firstOrNull()?.id) }
        val firstNode = nodes[j.nodeId]?.name ?: j.nodeId
        PConfirm(
            title = stringResource(R.string.job_redispatch_title),
            body = stringResource(R.string.job_redispatch_body, firstNode),
            confirmLabel = stringResource(R.string.job_redispatch_go),
            danger = false,
            confirmEnabled = target != null,
            onDismiss = { redispatching = null },
            onConfirm = {
                val to = target ?: return@PConfirm
                redispatching = null
                act(resources.getString(R.string.job_placing)) {
                    val again = container.nodeConnections.run(to, j.argv)
                    resources.getString(R.string.job_placed, nodes[to]?.name ?: to, again.id, DateUtils.formatDateTime(context, again.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME))
                }
            },
            extra = {
                Text(j.argv.drop(if (j.argv.firstOrNull() == "sh") 2 else 0).joinToString(" "), fontFamily = P.mono, fontSize = 12.sp, color = P.ink, maxLines = 3)
                if (j.manifestFiles > 0) Subtle(stringResource(R.string.job_redispatch_files))
                if (candidates.isEmpty()) DisabledReason(stringResource(R.string.job_redispatch_none))
                else app.umbra.agent.ui.porcelain.PChoice(
                    candidates.map { Triple(it.id, it.name, if (container.nodeConnections.connected(it.id)) resources.getString(R.string.nodes_connected) else "${it.host}:${it.port}") },
                    target ?: "", { target = it },
                )
            },
        )
    }
    running?.let { n ->
        var command by remember(n.id) { mutableStateOf("uname -a") }
        var folder by remember(n.id) { mutableStateOf("") }
        var files by remember(n.id) { mutableStateOf("") }
        var preview by remember(n.id) { mutableStateOf<String?>(null) }
        val root = container.workspaceLayout.hostRoot.toFile()
        AlertDialog(
            onDismissRequest = { running = null },
            title = { Text(stringResource(R.string.np_run_title, n.name)) },
            text = {
                Column {
                    OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.np_command)) })
                    OutlinedTextField(folder, { folder = it.trim(); preview = null }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.np_folder)) }, placeholder = { Text("projects/demo-repo") }, singleLine = true)
                    OutlinedTextField(files, { files = it; preview = null }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.np_files)) }, singleLine = true)
                    preview?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub) }
                    TextButton(onClick = {
                        scope.launch {
                            preview = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (folder.isBlank() || files.isBlank()) resources.getString(R.string.np_nothing_leaves)
                                    else Manifests.build(File(root, folder).toPath(), files.split(',').map { it.trim() }.filter { it.isNotEmpty() }, policy.maxUploadBytes).let { m ->
                                        Rsp.json.encodeToString(app.umbra.core.nodes.DataManifest.serializer(), m).take(800)
                                    }
                                }.getOrElse { resources.getString(R.string.np_refused, it.message.orEmpty()) }
                            }
                        }
                    }) { Text(stringResource(R.string.np_show_manifest)) }
                }
            },
            confirmButton = {
                TextButton(enabled = command.isNotBlank(), onClick = {
                    running = null
                    act(resources.getString(R.string.nodes_placing)) {
                        val dir = if (folder.isNotBlank()) File(root, folder) else null
                        val j = container.nodeConnections.run(n.id, listOf("sh", "-c", command), dir, files.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                        resources.getString(R.string.nodes_running_on, n.name, j.id, DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME))
                    }
                }) { Text(stringResource(R.string.np_place)) }
            },
            dismissButton = { TextButton(onClick = { running = null }) { Text(stringResource(R.string.np_cancel)) } },
        )
    }
}

private fun Pairing12(code: String) = app.umbra.core.nodes.Pairing.normalize(code).length == 12

/**
 * One job in one line (its command and its state), the node and time under it, and the rest (lease, policy revision,
 * what was sent, its output) behind Details. A stranded job says it needs to be run again by hand, with the button.
 */
@Composable
private fun JobRow(j: NodeJob, nodeName: String, revoked: Boolean, stranded: Boolean, onCancel: () -> Unit, onForget: () -> Unit, onRedispatch: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(j.argv.drop(if (j.argv.firstOrNull() == "sh") 2 else 0).joinToString(" ").take(80), Modifier.weight(1f), fontSize = 13.sp, fontFamily = P.mono, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (revoked && j.state == JobState.RUNNING) Tag(stringResource(R.string.job_not_confirmed), Tone.Warn) else Tag(
                when (j.state) {
                    JobState.RUNNING -> stringResource(R.string.job_running)
                    JobState.EXITED -> stringResource(R.string.job_exit, j.exit ?: -1)
                    JobState.LEASE_EXPIRED -> stringResource(R.string.job_lease_out)
                    JobState.CANCELLED -> stringResource(R.string.job_cancelled)
                    JobState.FAILED_TO_START -> stringResource(R.string.job_not_started)
                    JobState.LOST -> stringResource(R.string.job_lost)
                },
                when (j.state) { JobState.RUNNING -> if (stranded) Tone.Warn else Tone.Neutral; JobState.EXITED -> if (j.exit == 0) Tone.Good else Tone.Warn; else -> Tone.Warn },
            )
        }
        Subtle(stringResource(R.string.job_on, nodeName, DateUtils.getRelativeTimeSpanString(j.startedAtMs).toString()), Modifier.padding(top = 2.dp))
        if (stranded) {
            Gap(6.dp)
            Callout(stringResource(R.string.job_manual), Tone.Warn)
            Box(Modifier.padding(top = 6.dp)) { PButton(stringResource(R.string.job_redispatch), onRedispatch, style = ButtonStyle.Alt, tiny = true, icon = PIcons.refresh) }
        }
        if (revoked && j.state == JobState.RUNNING) Subtle(stringResource(R.string.job_revoked_note, DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME)), Modifier.padding(top = 2.dp))
        Disclosure(stringResource(R.string.details)) {
            Subtle(
                listOfNotNull(
                    stringResource(R.string.job_policy, j.id, j.policyRevision),
                    if (j.manifestFiles > 0) pluralStringResource(R.plurals.job_sent, j.manifestFiles, j.manifestFiles, android.text.format.Formatter.formatShortFileSize(context, j.manifestBytes)) else stringResource(R.string.job_no_files),
                    if (j.state == JobState.RUNNING) stringResource(R.string.job_lease_until, DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME)) else j.note,
                    j.reconciledAtMs?.let { stringResource(R.string.job_reconciled, DateUtils.getRelativeTimeSpanString(it).toString()) },
                ).filter { it.isNotBlank() }.joinToString(" · "),
            )
            if (j.outputTail.isNotBlank()) Text(j.outputTail.trimEnd().lines().takeLast(8).joinToString("\n"), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            if (!revoked) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (j.state == JobState.RUNNING) PButton(stringResource(R.string.job_cancel), onCancel, style = ButtonStyle.Ghost, tiny = true)
                else PButton(stringResource(R.string.job_forget), onForget, style = ButtonStyle.Ghost, tiny = true)
            }
        }
    }
}

@Composable
private fun trustLabel(t: NodeTrust) = stringResource(nodeTrustRes(t))

/** A revoked node: what this phone enforces, what the node said (if anything), and what it already had. */
@Composable
private fun RevocationDetail(n: PairedNode, rev: NodeRevocation, onRetry: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    Subtle(stringResource(R.string.np_revoked_at, DateUtils.getRelativeTimeSpanString(rev.atMs).toString(), rev.reason), Modifier.padding(top = 4.dp))
    if (rev.pending) {
        val jobs = rev.runningJobs.size
        Callout(
            stringResource(R.string.np_not_confirmed, rev.lastAttemptError?.let { " " + stringResource(R.string.np_last_try, it) } ?: "") + " " +
                (if (jobs == 0) stringResource(R.string.np_no_job_was)
                else pluralStringResource(R.plurals.np_jobs_were, jobs, jobs, rev.leasesEndByMs?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME) } ?: stringResource(R.string.np_their_lease))),
            Tone.Warn, Modifier.padding(top = 6.dp),
        )
    } else {
        Subtle(stringResource(R.string.np_acknowledged, rev.acknowledgedAtMs?.let { DateUtils.getRelativeTimeSpanString(it).toString() }.orEmpty(), rev.nodeReport.orEmpty()), Modifier.padding(top = 4.dp))
    }
    Subtle(
        stringResource(R.string.np_sent_to_it, rev.filesSent, android.text.format.Formatter.formatShortFileSize(context, rev.bytesSent)) +
            if (rev.anchorsThere > 0) " " + pluralStringResource(R.plurals.np_anchors_there, rev.anchorsThere, rev.anchorsThere) else "",
        Modifier.padding(top = 4.dp),
    )
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rev.pending) PButton(stringResource(R.string.np_tell_again), onRetry, style = ButtonStyle.Alt, tiny = true)
        PButton(stringResource(R.string.np_forget_record), onForget, style = ButtonStyle.Ghost, tiny = true)
    }
}
