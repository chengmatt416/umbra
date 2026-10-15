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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.credentialScopeRes
import app.umbra.agent.ui.evidenceLevelRes
import app.umbra.agent.ui.evidenceMeaningRes
import app.umbra.agent.ui.incidentStateRes
import app.umbra.agent.ui.nodeTrustRes
import app.umbra.agent.ui.trustBoundaryRes
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
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
    val resources = androidx.compose.ui.platform.LocalResources.current
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
            message = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }; resources.getString(R.string.sec_exported, bytes.size) }.getOrElse { resources.getString(R.string.sec_write_failed, it.message.orEmpty()) }
        }
    }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            val c = container.security.check()
            checks = c
            digest = container.security.combinedDigest(c)
            // No stored secret value may appear in any journal: checked, not assumed.
            val texts = File(container.control.root.toFile(), "journals").listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.map { it.readText() }
            val values = (container.secrets.names().mapNotNull { container.secrets.get(it) } + container.gitSecrets.names().mapNotNull { container.gitSecrets.get(it) }).filter { it.length >= 6 }
            val found = values.count { v -> texts.any { v in it } }
            secretScan = if (found == 0) resources.getString(R.string.sec_scan_ok, texts.size, values.size) else resources.getString(R.string.sec_scan_bad, found)
        }
    }
    LaunchedEffect(incidents.size, anchors.size) { refresh() }

    fun act(label: String, block: suspend () -> String) {
        if (busy != null) return
        busy = label
        scope.launch { message = try { block() } catch (e: Exception) { e.message ?: e::class.simpleName } finally { busy = null } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.sec_eyebrow))
        Headline(stringResource(R.string.sec_headline), stringResource(R.string.sec_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        busy?.let { Callout("$it…", Tone.Neutral); Gap(8.dp) }
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        // The state in one line and the one action that matters here; the evidence itself is under Details (D-04).
        val broken = checks.count { !it.intact }
        PCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (broken == 0) stringResource(R.string.sec_state_ok) else pluralStringResource(R.plurals.sec_state_broken, broken, broken),
                    Modifier.weight(1f), fontFamily = P.serif, fontSize = 21.sp, lineHeight = 25.sp, color = if (broken == 0) P.ink else P.danger,
                )
                PIcon(if (broken == 0) PIcons.check else PIcons.alert, size = 22.dp, tint = if (broken == 0) P.good else P.danger)
            }
            Subtle(pluralStringResource(R.plurals.sec_journals, checks.size, checks.size) + (secretScan?.let { " $it" } ?: ""), Modifier.padding(top = 6.dp))
            Box(Modifier.padding(top = 12.dp)) { PButton(stringResource(R.string.sec_record), { recording = true }, icon = PIcons.alert) }
            Subtle(stringResource(R.string.sec_record_note), Modifier.padding(top = 8.dp))
            Disclosure(stringResource(R.string.details)) {
                checks.forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(c.name, Modifier.weight(1f), fontSize = 12.sp, fontFamily = P.mono, color = P.ink)
                        Text("${c.entries} · " + if (c.intact) stringResource(R.string.sec_intact) else stringResource(R.string.sec_broken_at, c.firstBadSeq ?: 0), fontSize = 12.sp, color = if (c.intact) P.sub else P.danger)
                    }
                }
                Subtle(stringResource(R.string.sec_digest, digest.take(16)), Modifier.padding(top = 6.dp))
                Subtle(stringResource(R.string.sec_chain_note), Modifier.padding(top = 4.dp))
            }
        }
        Gap(12.dp)

        if (incidents.isNotEmpty()) {
            PCard {
                Kicker(stringResource(R.string.sec_incidents, incidents.size))
                incidents.values.sortedByDescending { it.updatedAtMs }.forEach { i -> IncidentCard(i, onGo, onAdvance = { act(resources.getString(R.string.sec_updating)) { container.security.advance(i.id, null).let { resources.getString(R.string.sec_now_state, resources.getString(incidentStateRes(it.state)).lowercase(), it.version.toString()) } } },
                    onFalsePositive = { act(resources.getString(R.string.sec_closing)) { container.security.falsePositive(i.id, "reviewed as a false positive").let { resources.getString(R.string.sec_closed_fp) } } },
                    onReopen = { act(resources.getString(R.string.sec_reopening)) { container.security.reopen(i.id, null).let { resources.getString(R.string.sec_reopened, it.version.toString()) } } }) }
            }
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.sec_exposure))
            val exposed = credentials.values.count { it.exposures > 0 }
            Subtle(if (exposed == 0) stringResource(R.string.sec_exposure_none) else pluralStringResource(R.plurals.sec_exposure_summary, exposed, exposed), Modifier.padding(top = 6.dp))
            Disclosure(stringResource(R.string.details)) {
                credentials.values.forEach { c ->
                    PSetting("${c.label} (${c.name})", pluralStringResource(R.plurals.sec_cred_exposures, c.exposures.toInt(), c.exposures.toInt()) + (c.lastExposedTo?.let { " · " + stringResource(R.string.sec_last_to, recipient(it, sessions)) } ?: "") + " · " + stringResource(R.string.sec_scope, stringResource(credentialScopeRes(c.scope)).lowercase())) {
                        Tag(if (c.exposures == 0L) stringResource(R.string.sec_unexposed) else stringResource(R.string.sec_potentially), if (c.exposures == 0L) Tone.Good else Tone.Warn)
                    }
                }
                nodes.values.forEach { n ->
                    val js = nodeJobs.values.filter { it.nodeId == n.id }
                    PSetting(n.name, stringResource(R.string.sec_node_line, stringResource(nodeTrustRes(n.trust)).lowercase(), js.size, js.sumOf { it.manifestBytes }.toString())) {
                        val rev = n.revocation
                        Tag(if (rev == null) stringResource(R.string.sec_paired) else if (rev.pending) stringResource(R.string.sec_revocation_pending) else stringResource(R.string.sec_revoked), if (rev?.pending == true) Tone.Warn else Tone.Neutral)
                    }
                }
                val published = promotions.values.filter { it.state == PromotionState.COMMITTED }
                PSetting(stringResource(R.string.sec_commit_gate), if (published.isEmpty()) stringResource(R.string.sec_nothing_published) else published.joinToString { "${it.remote}/${it.branch} ← ${it.candidate.take(8)}" }, divider = false) { Tag("${published.size}", Tone.Neutral) }
                Subtle(stringResource(R.string.sec_exposure_note), Modifier.padding(top = 6.dp))
            }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.sec_privacy))
            Subtle(stringResource(R.string.sec_privacy_body), Modifier.padding(top = 6.dp))
            Disclosure(stringResource(R.string.details)) {
                PSetting(stringResource(R.string.sec_tier_local), stringResource(R.string.sec_tier_local_body)) { Tag(stringResource(R.string.sec_tier_default), Tone.Good) }
                PSetting(stringResource(R.string.sec_tier_anchor), stringResource(R.string.sec_tier_anchor_body)) { Tag(stringResource(R.string.sec_tier_optin), Tone.Neutral) }
                if (nodes.isEmpty()) Subtle(stringResource(R.string.sec_pair_first), Modifier.padding(top = 2.dp))
                nodes.values.filter { it.revocation != null }.forEach { n ->
                    Subtle(stringResource(R.string.sec_anchor_revoked, n.name, anchors.values.count { it.nodeId == n.id }), Modifier.padding(top = 8.dp))
                }
                nodes.values.filter { it.revocation == null }.forEach { n ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(n.name, Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1)
                        PButton(stringResource(R.string.sec_anchor), {
                            act(resources.getString(R.string.sec_anchoring, n.name)) {
                                val heads = withContext(Dispatchers.IO) { container.security.heads() }
                                val a = EvidenceAnchor("anc-" + System.currentTimeMillis().toString(36), n.id, digest, heads, System.currentTimeMillis())
                                container.nodeConnections.anchor(n.id, a)
                                container.security.anchored(a)
                                resources.getString(R.string.sec_anchored, digest.take(12), heads.size, n.name)
                            }
                        }, style = ButtonStyle.Alt, tiny = true)
                        PButton(stringResource(R.string.sec_verify), {
                            act(resources.getString(R.string.sec_checking, n.name)) {
                                val copies = container.nodeConnections.anchors(n.id)
                                if (copies.isEmpty()) resources.getString(R.string.sec_no_anchor, n.name)
                                else withContext(Dispatchers.IO) {
                                    val results = copies.map { container.security.verify(it.id, it.heads) }
                                    anchorResult = results.last()
                                    val bad = results.filterNot { it.ok }
                                    if (bad.isEmpty()) resources.getString(R.string.sec_all_match, results.size, n.name)
                                    else resources.getString(R.string.sec_mismatch, bad.joinToString("; ") { r -> resources.getString(R.string.sec_mismatch_item, r.anchorId, r.rewritten.joinToString(), r.missing.joinToString()) })
                                }
                            }
                        }, style = ButtonStyle.Ghost, tiny = true)
                    }
                }
                anchors.values.sortedByDescending { it.atMs }.take(5).forEach { a ->
                    Subtle(stringResource(R.string.sec_anchor_row, DateUtils.getRelativeTimeSpanString(a.atMs).toString(), nodes[a.nodeId]?.name ?: a.nodeId, a.digest.take(16), a.heads.size), Modifier.padding(top = 4.dp))
                }
                anchorResult?.let { r -> Subtle(stringResource(R.string.sec_last_check, r.anchorId, r.intact.size, r.rewritten.size, r.missing.size), Modifier.padding(top = 4.dp)) }
                PSetting(stringResource(R.string.sec_tier_export), stringResource(R.string.sec_tier_export_body), divider = false) {
                    PButton(stringResource(R.string.sec_export), { exporting = true }, style = ButtonStyle.Alt, tiny = true)
                }
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
            title = { Text(stringResource(R.string.sec_record_title)) },
            text = {
                Column {
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.sec_what)) }, singleLine = true)
                    Gap(6.dp)
                    Segmented(EvidenceLevel.entries.map { it to it.name }, level, { level = it })
                    Text(stringResource(evidenceMeaningRes(level)), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P.sub)
                    Box(Modifier.padding(top = 6.dp)) {
                        PButton(stringResource(R.string.sec_boundary, stringResource(trustBoundaryRes(boundary))), { menu = true }, style = ButtonStyle.Ghost, tiny = true)
                        DropdownMenu(menu, { menu = false }) { TrustBoundary.entries.forEach { b -> DropdownMenuItem(text = { Text(stringResource(trustBoundaryRes(b))) }, onClick = { boundary = b; menu = false }) } }
                    }
                    OutlinedTextField(note, { note = it }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.sec_note)) })
                }
            },
            confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { container.security.record(title.trim(), level, boundary, note); recording = false; message = resources.getString(R.string.sec_recorded) }) { Text(stringResource(R.string.sec_record_go)) } },
            dismissButton = { TextButton(onClick = { recording = false }) { Text(stringResource(R.string.sec_cancel)) } },
        )
    }
    if (exporting) {
        var pass by remember { mutableStateOf("") }
        var again by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { exporting = false },
            title = { Text(stringResource(R.string.sec_export_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.sec_export_body), fontSize = 13.sp)
                    OutlinedTextField(pass, { pass = it }, Modifier.padding(top = 8.dp), label = { Text(stringResource(R.string.sec_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(again, { again = it }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.sec_again)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = pass.length >= 10 && pass == again, onClick = {
                    exporting = false
                    act(resources.getString(R.string.sec_encrypting)) {
                        pendingExport = withContext(Dispatchers.Default) { container.security.export(pass.toCharArray()) }
                        save.launch("umbra-evidence-${System.currentTimeMillis() / 1000}.umbra-evidence")
                        resources.getString(R.string.sec_choose_where)
                    }
                }) { Text(stringResource(R.string.sec_export_go)) }
            },
            dismissButton = { TextButton(onClick = { exporting = false }) { Text(stringResource(R.string.sec_cancel)) } },
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
                Text(stringResource(R.string.sec_incident_meta, i.level.name, stringResource(evidenceLevelRes(i.level)), stringResource(trustBoundaryRes(i.boundary)), i.version.toString()), fontSize = 11.sp, color = P.sub)
            }
            Tag(if (i.falsePositive) stringResource(R.string.sec_false_positive) else stringResource(incidentStateRes(i.state)), when { i.falsePositive -> Tone.Neutral; i.state == IncidentState.RESOLVED -> Tone.Good; i.level >= EvidenceLevel.L3 -> Tone.Danger; else -> Tone.Warn })
        }
        Subtle(stringResource(evidenceMeaningRes(i.level)), Modifier.padding(top = 2.dp))
        val steps = when (i.boundary) {
            TrustBoundary.CREDENTIAL -> listOf("vault" to stringResource(R.string.sec_step_vault))
            TrustBoundary.GIT -> listOf("commit" to stringResource(R.string.sec_step_git))
            TrustBoundary.NODE -> listOf("nodes" to stringResource(R.string.sec_step_node))
            TrustBoundary.ROOT -> listOf("emergency" to stringResource(R.string.sec_step_root))
            TrustBoundary.OTHER -> listOf("emergency" to stringResource(R.string.sec_step_other))
        }
        steps.forEach { (route, text) ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, Modifier.weight(1f), fontSize = 12.sp, color = P.ink)
                PButton(stringResource(R.string.sec_open), { onGo(route) }, style = ButtonStyle.Ghost, tiny = true)
            }
        }
        i.notes.takeLast(2).forEach { Subtle("${DateUtils.getRelativeTimeSpanString(it.atMs)} · ${it.text}", Modifier.padding(top = 2.dp)) }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (i.state != IncidentState.RESOLVED) {
                PButton(stringResource(R.string.sec_move_to, stringResource(incidentStateRes(IncidentState.entries[i.state.ordinal + 1])).lowercase()), onAdvance, style = ButtonStyle.Alt, tiny = true)
                PButton(stringResource(R.string.sec_false_positive), onFalsePositive, style = ButtonStyle.Ghost, tiny = true)
            } else PButton(stringResource(R.string.sec_reopen), onReopen, style = ButtonStyle.Ghost, tiny = true)
        }
        if (i.state == IncidentState.RESOLVED && !i.falsePositive) Subtle(stringResource(R.string.sec_resolved_note), Modifier.padding(top = 4.dp))
    }
}
