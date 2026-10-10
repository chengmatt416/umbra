package app.umbra.agent.ui.security

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.AnchorCheck
import app.umbra.core.control.EvidenceAnchor
import app.umbra.core.control.EvidenceLevel
import app.umbra.core.control.IncidentState
import app.umbra.core.control.JournalCheck
import app.umbra.core.control.PromotionState
import app.umbra.core.control.SecurityIncident
import app.umbra.core.control.TrustBoundary
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Security & Evidence (architecture spec §13): every control journal's hash chain, the evidence digest anchored on a
 * paired node and the journals checked against the node's copy, the exposure map, the incidents you record (nothing
 * acts by itself: each incident points at the controls to use), and an encrypted evidence export.
 */
@Composable
fun SecurityEvidenceScreen(onGo: (String) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val incidents by container.security.allIncidents.collectAsStateWithLifecycle()
    val anchors by container.security.allAnchors.collectAsStateWithLifecycle()
    val nodes by container.nodes.all.collectAsStateWithLifecycle()
    val credentials by container.vault.all.collectAsStateWithLifecycle()
    val nodeJobs by container.nodes.allJobs.collectAsStateWithLifecycle()
    val promotions by container.commits.all.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    var checks by remember { mutableStateOf<List<JournalCheck>>(emptyList()) }
    var digest by remember { mutableStateOf("") }
    var secretScan by remember { mutableStateOf<String?>(null) }
    var anchorResult by remember { mutableStateOf<AnchorCheck?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri != null && bytes != null) scope.launch {
            message = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }; "Evidence exported (${bytes.size} bytes, encrypted). Read it with: umbra-node evidence-decrypt FILE" }.getOrElse { "Could not write it: ${it.message}" }
        }
    }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val c = container.security.check()
            checks = c
            digest = container.security.combinedDigest(c)
            // No stored secret value may appear in any journal: checked, not assumed.
            val texts = File(container.control.root.toFile(), "journals").listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.map { it.readText() }
            val values = container.secrets.names().mapNotNull { container.secrets.get(it) }.filter { it.length >= 6 }
            val found = values.count { v -> texts.any { v in it } }
            secretScan = if (found == 0) "No stored secret value appears in any of ${texts.size} journals (${values.size} secret(s) checked)." else "$found secret value(s) found in a journal: rotate them."
        }
    }
    LaunchedEffect(incidents.size, anchors.size) { refresh() }

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch { message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Security incident response")
        Headline("Trust is a state,", "not a switch.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Evidence chain · ${checks.size} journals", Modifier.weight(1f))
                Tag(if (checks.all { it.intact }) "All intact" else "${checks.count { !it.intact }} broken", if (checks.all { it.intact }) Tone.Good else Tone.Danger)
            }
            checks.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(c.name, Modifier.weight(1f), fontSize = 12.sp, fontFamily = P.mono, color = P.ink)
                    Text("${c.entries} · " + if (c.intact) "intact" else "broken at #${c.firstBadSeq}", fontSize = 12.sp, color = if (c.intact) P.sub else P.danger)
                }
            }
            Subtle("Combined digest ${digest.take(16)}…", Modifier.padding(top = 6.dp))
            secretScan?.let { Subtle(it, Modifier.padding(top = 2.dp)) }
            PSetting("A local hash chain", "Shows tampering by someone without full write access only; anchor it on an independent node to catch the rest", divider = false) { Tag("Not proof alone", Tone.Warn) }
        }
        Gap(12.dp)

        PCard {
            Kicker("Independent digest anchoring")
            if (nodes.isEmpty()) Subtle("Pair a node (Distributed Nodes) to keep a copy of the digest outside this phone.", Modifier.padding(top = 6.dp))
            nodes.values.forEach { n ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n.name, Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1)
                    PButton("Anchor", {
                        act("Anchoring on ${n.name}") {
                            val heads = withContext(Dispatchers.IO) { container.security.heads() }
                            val a = EvidenceAnchor("anc-" + System.currentTimeMillis().toString(36), n.id, digest, heads, System.currentTimeMillis())
                            container.nodeConnections.anchor(n.id, a)
                            container.security.anchored(a)
                            "Digest ${digest.take(12)}… and ${heads.size} journal heads anchored on ${n.name}. Only a digest leaves the phone, no log content."
                        }
                    }, style = ButtonStyle.Alt, tiny = true)
                    PButton("Verify", {
                        act("Checking against ${n.name}'s copy") {
                            val copies = container.nodeConnections.anchors(n.id)
                            if (copies.isEmpty()) "${n.name} holds no anchor from this phone."
                            else withContext(Dispatchers.IO) {
                                val results = copies.map { container.security.verify(it.id, it.heads) }
                                anchorResult = results.last()
                                val bad = results.filterNot { it.ok }
                                if (bad.isEmpty()) "All ${results.size} anchor(s) on ${n.name} match: no anchored history was rewritten or removed."
                                else "Mismatch: " + bad.joinToString("; ") { r -> "${r.anchorId} rewritten ${r.rewritten.joinToString()} missing ${r.missing.joinToString()}" }
                            }
                        }
                    }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            anchors.values.sortedByDescending { it.atMs }.take(5).forEach { a ->
                Subtle("${DateUtils.getRelativeTimeSpanString(a.atMs)} on ${nodes[a.nodeId]?.name ?: a.nodeId}: ${a.digest.take(16)}… · ${a.heads.size} heads", Modifier.padding(top = 4.dp))
            }
            anchorResult?.let { r -> Subtle("Last check (${r.anchorId}): ${r.intact.size} intact, ${r.rewritten.size} rewritten, ${r.missing.size} missing", Modifier.padding(top = 4.dp)) }
        }
        Gap(12.dp)

        PCard {
            Kicker("Security exposure map")
            credentials.values.forEach { c ->
                PSetting("${c.label} (${c.name})", "Keystore → ${c.exposures} hand-over(s)" + (c.lastExposedTo?.let { " · last to ${recipient(it, sessions)}" } ?: "") + " · scope ${c.scope.label.lowercase()}") {
                    Tag(if (c.exposures == 0L) "Unexposed" else "Potentially exposed", if (c.exposures == 0L) Tone.Good else Tone.Warn)
                }
            }
            nodes.values.forEach { n ->
                val js = nodeJobs.values.filter { it.nodeId == n.id }
                PSetting(n.name, "Node · ${n.trust.label.lowercase()} trust · ${js.size} job(s) · ${js.sumOf { it.manifestBytes }} bytes sent · no secrets") { Tag("Paired", Tone.Neutral) }
            }
            val published = promotions.values.filter { it.state == PromotionState.COMMITTED }
            PSetting("Commit gate", if (published.isEmpty()) "Nothing published" else published.joinToString { "${it.remote}/${it.branch} ← ${it.candidate.take(8)}" }, divider = false) { Tag("${published.size} write(s)", Tone.Neutral) }
            Subtle("“Potentially exposed” means the value was handed to a process; it is not a finding that it leaked.", Modifier.padding(top = 6.dp))
        }
        Gap(12.dp)

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Incidents · ${incidents.size}", Modifier.weight(1f))
                PButton("Record an incident", { recording = true }, style = ButtonStyle.Alt, tiny = true)
            }
            if (incidents.isEmpty()) Subtle("None recorded. Recording one changes nothing by itself: it keeps the evidence and points at the controls.", Modifier.padding(top = 6.dp))
            incidents.values.sortedByDescending { it.updatedAtMs }.forEach { i -> IncidentCard(i, onGo, onAdvance = { act("Updating") { container.security.advance(i.id, null).let { "Now ${it.state.label.lowercase()} (version ${it.version})." } } },
                onFalsePositive = { act("Closing") { container.security.falsePositive(i.id, "reviewed as a false positive").let { "Closed as a false positive; nothing it led to is undone by itself." } } },
                onReopen = { act("Reopening") { container.security.reopen(i.id, null).let { "Reopened (version ${it.version})." } } }) }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker("Privacy-tiered evidence custody")
            PSetting("Local", "Journals stay on this phone, in the app's private storage; secret values never enter them (checked above)") { Tag("Default", Tone.Good) }
            PSetting("Digest anchor", "Only a digest and journal heads go to a paired node you choose; no log content") { Tag("Opt-in", Tone.Neutral) }
            PSetting("Encrypted export", "Every journal, the incidents and the anchors, AES-256-GCM under your passphrase, to a file you choose", divider = false) {
                PButton("Export…", { exporting = true }, style = ButtonStyle.Alt, tiny = true)
            }
        }
        Gap(28.dp)
    }

    if (recording) {
        var title by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var level by remember { mutableStateOf(EvidenceLevel.L1) }
        var boundary by remember { mutableStateOf(TrustBoundary.OTHER) }
        var menu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { recording = false },
            title = { Text("Record an incident") },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text("What happened") }, singleLine = true)
                    Gap(6.dp)
                    Segmented(EvidenceLevel.entries.map { it to it.name }, level, { level = it })
                    Text(level.meaning, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P.sub)
                    Box(Modifier.padding(top = 6.dp)) {
                        PButton("Boundary: ${boundary.label}", { menu = true }, style = ButtonStyle.Ghost, tiny = true)
                        DropdownMenu(menu, { menu = false }) { TrustBoundary.entries.forEach { b -> DropdownMenuItem(text = { Text(b.label) }, onClick = { boundary = b; menu = false }) } }
                    }
                    OutlinedTextField(note, { note = it }, Modifier.padding(top = 6.dp), label = { Text("Note (optional)") })
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { container.security.record(title.trim(), level, boundary, note); recording = false; message = "Recorded. Use the steps it lists; nothing acts by itself." }) { Text("Record") } },
            dismissButton = { TextButton(onClick = { recording = false }) { Text("Cancel") } },
        )
    }
    if (exporting) {
        var pass by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text("Export evidence") },
            text = {
                Column {
                    Text("Encrypted with a key derived from this passphrase. Without it the file cannot be read, by anyone.", fontSize = 13.sp)
                    OutlinedTextField(pass, { pass = it }, Modifier.padding(top = 8.dp), label = { Text("Passphrase (10+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(again, { again = it }, Modifier.padding(top = 6.dp), label = { Text("Again") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = pass.length >= 10 && pass == again, onClick = {
                    exporting = false
                    act("Encrypting") {
                        pendingExport = withContext(Dispatchers.Default) { container.security.export(pass.toCharArray()) }
                        save.launch("umbra-evidence-${System.currentTimeMillis() / 1000}.umbra-evidence")
                        "Choose where to save it."
                    }
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text("Cancel") } },
        )
    }
}

private fun recipient(to: String, sessions: List<app.umbra.core.session.Session>) =
    if (to.startsWith("session ")) sessions.firstOrNull { it.id == to.removePrefix("session ") }?.title ?: to else to

@Composable
private fun IncidentCard(i: SecurityIncident, onGo: (String) -> Unit, onAdvance: () -> Unit, onFalsePositive: () -> Unit, onReopen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(i.title, fontSize = 14.sp, color = P.ink)
                Text("${i.level.name} ${i.level.label} · ${i.boundary.label} · version ${i.version}", fontSize = 11.sp, color = P.sub)
            }
            Tag(if (i.falsePositive) "False positive" else i.state.label, when { i.falsePositive -> Tone.Neutral; i.state == IncidentState.RESOLVED -> Tone.Good; i.level >= EvidenceLevel.L3 -> Tone.Danger; else -> Tone.Warn })
        }
        Subtle(i.level.meaning, Modifier.padding(top = 2.dp))
        val steps = when (i.boundary) {
            TrustBoundary.CREDENTIAL -> listOf("vault" to "Rotate or revoke it in the vault, then revoke it at its issuer")
            TrustBoundary.GIT -> listOf("commit" to "Read the remote refs back in Commit & Integration; revoke git credentials at the provider")
            TrustBoundary.NODE -> listOf("nodes" to "Lower the node's trust or unpair it; its running job ends with its lease, which cannot be confirmed until it reconnects")
            TrustBoundary.ROOT -> listOf("emergency" to "Lock down input; recover from an independent trusted environment — this phone's own logs cannot prove it is safe")
            TrustBoundary.OTHER -> listOf("emergency" to "Lock down input if agents should stop acting while you look")
        }
        steps.forEach { (route, text) ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), fontSize = 12.sp, color = P.ink)
                PButton("Open", { onGo(route) }, style = ButtonStyle.Ghost, tiny = true)
            }
        }
        i.notes.takeLast(2).forEach { Subtle("${DateUtils.getRelativeTimeSpanString(it.atMs)} · ${it.text}", Modifier.padding(top = 2.dp)) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i.state != IncidentState.RESOLVED) {
                PButton("Move to ${IncidentState.entries[i.state.ordinal + 1].label.lowercase()}", onAdvance, style = ButtonStyle.Alt, tiny = true)
                PButton("False positive", onFalsePositive, style = ButtonStyle.Ghost, tiny = true)
            } else PButton("Reopen", onReopen, style = ButtonStyle.Ghost, tiny = true)
        }
        if (i.state == IncidentState.RESOLVED && !i.falsePositive) Subtle("Resolved resumes nothing: paused or locked-down work stays as it is until you resume it.", Modifier.padding(top = 4.dp))
    }
}
