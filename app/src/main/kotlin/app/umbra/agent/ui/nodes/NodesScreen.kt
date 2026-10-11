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
import androidx.compose.material3.AlertDialog
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
@Composable
fun NodesScreen() {
    val container = LocalContext.current.container
    val context = LocalContext.current
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

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch { message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Distributed execution")
        Headline("Your devices,", "one governed runtime.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Execution nodes", Modifier.weight(1f))
                PButton("Pair a node", { pairing = true }, style = ButtonStyle.Alt, tiny = true)
            }
            Gap(8.dp)
            PObject("This device", "Android ${Build.VERSION.RELEASE} · ${Build.MODEL} · Debian in Linux", PIcons.phone, trailing = { Tag("Local", Tone.Good) })
            nodes.values.sortedBy { it.pairedAtMs }.forEach { n ->
                Gap(12.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(n.name, fontSize = 15.sp, color = P.ink)
                        Text("${n.host}:${n.port} · ${shortFingerprint(n.fingerprint)}", fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                    }
                    val rev = n.revocation
                    when {
                        rev == null -> Tag(if (container.nodeConnections.connected(n.id)) "Connected" else n.lastSeenMs?.let { "Seen ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "Not seen", if (container.nodeConnections.connected(n.id)) Tone.Good else Tone.Neutral)
                        rev.pending -> Tag("Revocation pending", Tone.Warn)
                        else -> Tag("Revoked · acknowledged", Tone.Neutral)
                    }
                }
                val revocation = n.revocation
                if (revocation != null) {
                    RevocationDetail(n, revocation, onRetry = { act("Telling ${n.name}") { if (container.nodeConnections.notifyRevoked(n.id)) "${n.name} answered: it says it has forgotten this phone." else "${n.name} could not be reached; the revocation stays pending." } },
                        onForget = { container.nodes.unpair(n.id); message = "The record of ${n.name} is gone; its certificate stays refused (nothing pins it)." })
                } else {
                    n.info?.let { i ->
                        Subtle("${i.os} · ${i.arch} · ${i.cpus} CPUs · ${i.memoryMb / 1024} GB · ${i.tools.keys.joinToString().ifEmpty { "no tools found" }}", Modifier.padding(top = 2.dp))
                        Subtle("Runner epoch ${i.epoch.take(24)} · jobs in ${i.workspace}", Modifier.padding(top = 2.dp))
                    }
                    Gap(6.dp)
                    Segmented(NodeTrust.entries.map { it to it.label }, n.trust, { t -> container.nodes.setTrust(n.id, t) })
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PButton("Preflight", { act("Asking ${n.name}") { container.nodeConnections.preflight(n.id).let { "${n.name}: ${it.os}, ${it.cpus} CPUs, tools ${it.tools.keys.joinToString()}" } } }, style = ButtonStyle.Alt, tiny = true)
                        PButton("Run…", { running = n }, tiny = true)
                        PButton("Rejoin", { act("Reconciling with ${n.name}") { container.nodeConnections.reconcile(n.id); "${n.name}'s account of its jobs is now the record." } }, style = ButtonStyle.Ghost, tiny = true)
                        PButton("Unpair", { container.nodeConnections.disconnect(n.id); container.nodes.unpair(n.id); message = "Forgotten here. Unpair this phone on ${n.name} too (umbra-node unpair)." }, style = ButtonStyle.Ghost, tiny = true)
                    }
                    PButton("Revoke…", { revoking = n }, Modifier.padding(top = 6.dp), style = ButtonStyle.Ghost, tiny = true)
                }
            }
            if (nodes.isEmpty()) Subtle("No node is paired. On a computer you own: umbra-node serve --pair, then Pair a node here.", Modifier.padding(top = 8.dp))
        }
        Gap(12.dp)

        PCard {
            Kicker("Jobs")
            val list = jobs.values.sortedByDescending { it.startedAtMs }.take(10)
            if (list.isEmpty()) Subtle("No job has been placed on a node.", Modifier.padding(top = 6.dp))
            list.forEach { j -> JobRow(j, nodes[j.nodeId]?.name ?: j.nodeId, revoked = nodes[j.nodeId]?.revocation != null, onCancel = { act("Cancelling") { container.nodeConnections.cancel(j.nodeId, j.id); "Cancelled on the node." } }, onForget = { act("Forgetting") { container.nodeConnections.forget(j.nodeId, j.id); "The node deleted the job's folder." } }) }
        }
        Gap(12.dp)

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Placement policy", Modifier.weight(1f))
                Tag("Revision ${policy.revision}", Tone.Neutral)
            }
            PSetting("Nodes that get work", "A node must be trusted at least this far") {
                Segmented(NodeTrust.entries.map { it to it.label }, policy.minimumTrust, { t -> container.nodes.updatePolicy { it.copy(minimumTrust = t) } })
            }
            PSetting("Data leaving the phone", "At most ${policy.maxUploadBytes / (1024 * 1024)} MB per job, only the files its manifest lists") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PButton("−", { container.nodes.updatePolicy { it.copy(maxUploadBytes = (it.maxUploadBytes / 2).coerceAtLeast(1024 * 1024)) } }, style = ButtonStyle.Ghost, tiny = true)
                    PButton("+", { container.nodes.updatePolicy { it.copy(maxUploadBytes = (it.maxUploadBytes * 2).coerceAtMost(1024L * 1024 * 1024)) } }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            PSetting("Offline lease", "A job runs ${policy.offlineLeaseMinutes} min without this phone; while connected the phone renews it") {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PButton("−", { container.nodes.updatePolicy { it.copy(offlineLeaseMinutes = (it.offlineLeaseMinutes / 2).coerceAtLeast(1)) } }, style = ButtonStyle.Ghost, tiny = true)
                    PButton("+", { container.nodes.updatePolicy { it.copy(offlineLeaseMinutes = (it.offlineLeaseMinutes * 2).coerceAtMost(24 * 60)) } }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            PSetting("Secrets", "None go to a node; the node also drops its own environment's secrets for jobs", divider = false) { Tag("Never", Tone.Good) }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker("Offline contract · failover · trust")
            PSetting("Network partition", "The node ends a job when its lease runs out, connected or not; the phone cannot confirm when it happened until it rejoins. Offline is not dead.") { Tag("Lease-bound", Tone.Neutral) }
            PSetting("Failover", "Not offered in this version: a lost node's job is left to its lease; a new attempt elsewhere is started by hand, and no two nodes get the same write.") { Tag("Manual", Tone.Neutral) }
            PSetting("Identity", "Pairing proves the node knew the code and pins its certificate; it is not hardware attestation.") { Tag("Pinned", Tone.Good) }
            PSetting("Execution", "A paired node runs jobs as its own user in its jobs folder; what a command may touch on that computer is that user's.", divider = false) { Tag("User-level", Tone.Warn) }
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
            title = { Text("Pair a node") },
            text = {
                Column {
                    Text("On the computer: umbra-node serve --pair. It prints a code valid for ten minutes.", fontSize = 13.sp)
                    OutlinedTextField(host, { host = it.trim() }, Modifier.padding(top = 8.dp), label = { Text("Address") }, singleLine = true)
                    OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.padding(top = 6.dp), label = { Text("Port") }, singleLine = true)
                    OutlinedTextField(code, { code = it }, Modifier.padding(top = 6.dp), label = { Text("Pairing code") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = host.isNotBlank() && Pairing12(code) && port.isNotBlank(), onClick = {
                    pairing = false
                    act("Pairing") { container.nodeConnections.pair(host, port.toInt(), code, "${Build.MANUFACTURER} ${Build.MODEL}").let { "Paired with ${it.name} (${shortFingerprint(it.fingerprint)}). It starts with limited trust: raise it to give it work." } }
                }) { Text("Pair") }
            },
            dismissButton = { TextButton(onClick = { pairing = false }) { Text("Cancel") } },
        )
    }
    revoking?.let { n ->
        var reason by remember(n.id) { mutableStateOf("") }
        val mine = jobs.values.filter { it.nodeId == n.id }
        val live = mine.filter { it.state == JobState.RUNNING }
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${n.name}?") },
            text = {
                Column {
                    Text("This phone refuses it from now on: no work, no files, no lease renewal, its certificate not accepted. Then the node is told, if it can be reached.", fontSize = 13.sp)
                    Text(
                        (if (live.isEmpty()) "No job is running there." else "${live.size} job(s) running there end when their leases do, only if the node keeps its contract; cancel them first if they must stop sooner.") +
                            " Already sent to it: ${mine.sumOf { it.manifestFiles }} file(s), ${mine.sumOf { it.manifestBytes }} bytes; revoking does not take those back.",
                        Modifier.padding(top = 6.dp), fontSize = 13.sp, color = P.sub,
                    )
                    OutlinedTextField(reason, { reason = it }, Modifier.padding(top = 8.dp), label = { Text("Why (kept in the journal)") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    revoking = null
                    act("Revoking ${n.name}") {
                        if (container.nodeConnections.revoke(n.id, reason)) "${n.name} is revoked. It answered: it says it has forgotten this phone (its word, not proof)."
                        else "${n.name} is revoked on this phone. It could not be told, so the revocation is pending."
                    }
                }) { Text("Revoke") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
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
            title = { Text("Run on ${n.name}") },
            text = {
                Column {
                    OutlinedTextField(command, { command = it }, label = { Text("Command (run with sh -c)") })
                    OutlinedTextField(folder, { folder = it.trim(); preview = null }, Modifier.padding(top = 6.dp), label = { Text("Workspace folder to send from (optional)") }, placeholder = { Text("projects/demo-repo") }, singleLine = true)
                    OutlinedTextField(files, { files = it; preview = null }, Modifier.padding(top = 6.dp), label = { Text("Files or folders in it to send, comma separated") }, singleLine = true)
                    preview?.let { Text(it, Modifier.padding(top = 8.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub) }
                    TextButton(onClick = {
                        scope.launch {
                            preview = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (folder.isBlank() || files.isBlank()) "Nothing leaves the phone."
                                    else Manifests.build(File(root, folder).toPath(), files.split(',').map { it.trim() }.filter { it.isNotEmpty() }, policy.maxUploadBytes).let { m ->
                                        Rsp.json.encodeToString(app.umbra.core.nodes.DataManifest.serializer(), m).take(800)
                                    }
                                }.getOrElse { "Refused: ${it.message}" }
                            }
                        }
                    }) { Text("Show the data manifest") }
                }
            },
            confirmButton = {
                TextButton(enabled = command.isNotBlank(), onClick = {
                    running = null
                    act("Placing the job") {
                        val dir = if (folder.isNotBlank()) File(root, folder) else null
                        val j = container.nodeConnections.run(n.id, listOf("sh", "-c", command), dir, files.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                        "Running on ${n.name} as ${j.id}, lease until ${DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME)} (renewed while this phone is connected)."
                    }
                }) { Text("Place job") }
            },
            dismissButton = { TextButton(onClick = { running = null }) { Text("Cancel") } },
        )
    }
}

private fun Pairing12(code: String) = app.umbra.core.nodes.Pairing.normalize(code).length == 12

@Composable
private fun JobRow(j: NodeJob, nodeName: String, revoked: Boolean, onCancel: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(j.argv.drop(if (j.argv.firstOrNull() == "sh") 2 else 0).joinToString(" ").take(80), fontSize = 13.sp, fontFamily = P.mono, color = P.ink, maxLines = 1)
                Text("$nodeName · ${j.id} · policy rev. ${j.policyRevision}" + if (j.manifestFiles > 0) " · ${j.manifestFiles} file(s), ${j.manifestBytes} bytes sent" else " · no files sent", fontSize = 11.sp, color = P.sub)
            }
            if (revoked && j.state == JobState.RUNNING) Tag("Not confirmed stopped", Tone.Warn) else Tag(
                when (j.state) { JobState.RUNNING -> "Running"; JobState.EXITED -> "Exit ${j.exit}"; JobState.LEASE_EXPIRED -> "Lease ran out"; JobState.CANCELLED -> "Cancelled"; JobState.FAILED_TO_START -> "Did not start"; JobState.LOST -> "Lost" },
                when (j.state) { JobState.RUNNING -> Tone.Neutral; JobState.EXITED -> if (j.exit == 0) Tone.Good else Tone.Warn; else -> Tone.Warn },
            )
        }
        Subtle(
            (if (j.state == JobState.RUNNING) "Lease until ${DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME)}" else j.note.orEmpty()) +
                (j.reconciledAtMs?.let { " · reconciled ${DateUtils.getRelativeTimeSpanString(it)}" } ?: ""),
            Modifier.padding(top = 2.dp),
        )
        if (j.outputTail.isNotBlank()) Text(j.outputTail.trimEnd().lines().takeLast(8).joinToString("\n"), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        if (revoked) {
            if (j.state == JobState.RUNNING) Subtle("Last reported running; the node is revoked, so nothing here can confirm it stopped. Its lease ended or ends ${DateUtils.formatDateTime(context, j.leaseUntilMs, DateUtils.FORMAT_SHOW_TIME)}.", Modifier.padding(top = 2.dp))
        } else {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (j.state == JobState.RUNNING) PButton("Cancel", onCancel, style = ButtonStyle.Ghost, tiny = true)
                else PButton("Forget (node deletes its files)", onForget, style = ButtonStyle.Ghost, tiny = true)
            }
        }
    }
}

/** A revoked node: what this phone enforces, what the node said (if anything), and what it already had. */
@Composable
private fun RevocationDetail(n: PairedNode, rev: NodeRevocation, onRetry: () -> Unit, onForget: () -> Unit) {
    val context = LocalContext.current
    Subtle("Revoked ${DateUtils.getRelativeTimeSpanString(rev.atMs)}: ${rev.reason}. This phone refuses it: no work, files or lease renewal go to it.", Modifier.padding(top = 4.dp))
    if (rev.pending) {
        Callout(
            "Not confirmed on the node: it has not answered the revocation" + (rev.lastAttemptError?.let { " (last try: $it)" } ?: "") + ". " +
                (if (rev.runningJobs.isEmpty()) "No job was running there." else "${rev.runningJobs.size} job(s) were running; they end by ${rev.leasesEndByMs?.let { DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME) } ?: "their lease"} only if the node keeps its lease contract. Nothing here confirms they stopped."),
            Tone.Warn, Modifier.padding(top = 6.dp),
        )
    } else {
        Subtle("Acknowledged ${rev.acknowledgedAtMs?.let { DateUtils.getRelativeTimeSpanString(it) }}: ${rev.nodeReport}. That is the node's own account, not proof.", Modifier.padding(top = 4.dp))
    }
    Subtle("Already sent to it: ${rev.filesSent} file(s), ${rev.bytesSent} bytes" + if (rev.anchorsThere > 0) "; ${rev.anchorsThere} evidence anchor(s) there no longer count as independent." else ".", Modifier.padding(top = 4.dp))
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (rev.pending) PButton("Tell the node again", onRetry, style = ButtonStyle.Alt, tiny = true)
        PButton("Forget the record", onForget, style = ButtonStyle.Ghost, tiny = true)
    }
}
