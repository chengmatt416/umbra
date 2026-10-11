package app.umbra.agent.ui.vault

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.integration.brokerUnavailable
import app.umbra.agent.integration.gitSecretName
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.GitCredential
import app.umbra.core.control.GitOperation

/**
 * Git credentials the broker holds (architecture spec §12, AC-015, ADR 0022): each bound to one https URL and to the
 * operations the user allows; the token is used only by root in a staging repository, never given to an agent.
 */
@Composable
fun GitCredentialsCard(onMessage: (String) -> Unit) {
    val container = LocalContext.current.container
    val credentials by container.gitCredentials.all.collectAsStateWithLifecycle()
    val journal by container.gitCredentials.recent.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf<GitCredential?>(null) }
    var revoking by remember { mutableStateOf<GitCredential?>(null) }
    val unavailable = brokerUnavailable(container)

    PCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("Git credentials · brokered · ${credentials.size}", Modifier.weight(1f))
            PButton("Add…", { adding = true }, style = ButtonStyle.Alt, tiny = true)
        }
        unavailable?.let { Callout(it, Tone.Warn, Modifier.padding(top = 6.dp)) }
        if (credentials.isEmpty()) Subtle("None. A token added here is used only by root, for the one repository URL it is bound to; the agent never holds it.", Modifier.padding(top = 6.dp))
        credentials.values.sortedBy { it.createdAtMs }.forEach { c ->
            Gap(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 15.sp, color = P.ink)
                    Text(c.url, fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 2)
                }
                if (c.publishSuspended) Tag("Publishing suspended", Tone.Warn) else Tag("Never to agents", Tone.Good)
            }
            Subtle(
                "As ${c.username}" + (if (c.caCertificate != null) " · own CA" else "") + " · added ${DateUtils.getRelativeTimeSpanString(c.createdAtMs)}" +
                    (c.rotatedAtMs?.let { " · rotated ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "") +
                    " · used ${c.uses}×" + (c.lastUse?.let { " · last: $it" } ?: ""),
                Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GitOperation.entries.forEach { op ->
                    Checkbox(op in c.operations, { on ->
                        val next = if (on) c.operations + op else c.operations - op
                        if (next.isEmpty()) onMessage("A credential needs at least one operation; revoke it instead.")
                        else { container.gitCredentials.setOperations(c.id, next); onMessage("${c.label}: ${next.joinToString { it.label.lowercase() }}.") }
                    })
                    Text(op.label, fontSize = 13.sp, color = P.ink)
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.publishSuspended) PButton("Grant publishing again", { container.gitCredentials.restorePublish(c.id); onMessage("${c.label} may publish again.") }, tiny = true)
                PButton("Rotate", { rotating = c }, style = ButtonStyle.Alt, tiny = true)
                PButton("Revoke here", { revoking = c }, style = ButtonStyle.Ghost, tiny = true)
            }
        }
        if (journal.isNotEmpty()) {
            val check = remember(journal.size) { runCatching { container.gitCredentials.verifyJournal() }.getOrNull() }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Subtle("Broker journal", Modifier.weight(1f))
                check?.let { Tag(if (it.intact) "Chain intact · ${it.entries}" else "Broken at #${it.firstBadSeq}", if (it.intact) Tone.Good else Tone.Danger) }
            }
            journal.takeLast(5).reversed().forEach { e ->
                Text("${DateUtils.getRelativeTimeSpanString(e.timeMs)} ${e.kind.removePrefix("git.")} ${e.data?.toString()?.take(100).orEmpty()}", Modifier.padding(top = 3.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
    }

    if (adding) {
        var label by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var user by remember { mutableStateOf("x-access-token") }
        var token by remember { mutableStateOf("") }
        var read by remember { mutableStateOf(true) }
        var publish by remember { mutableStateOf(true) }
        var ca by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a git credential") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("For one repository. Give it the least the issuer allows (a fine-grained token for this repository only).", fontSize = 13.sp)
                    OutlinedTextField(label, { label = it }, Modifier.padding(top = 8.dp), label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(url, { url = it.trim() }, Modifier.padding(top = 6.dp), label = { Text("Repository URL (https)") }, singleLine = true)
                    OutlinedTextField(user, { user = it.trim() }, Modifier.padding(top = 6.dp), label = { Text("User name") }, singleLine = true)
                    OutlinedTextField(token, { token = it }, Modifier.padding(top = 6.dp), label = { Text("Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(read, { read = it }); Text("Read", fontSize = 13.sp)
                        Checkbox(publish, { publish = it }); Text("Publish (commit gate)", fontSize = 13.sp)
                    }
                    OutlinedTextField(ca, { ca = it }, Modifier.padding(top = 6.dp), label = { Text("CA certificate, PEM (only for a self-hosted server)") }, minLines = 2, maxLines = 4)
                }
            },
            confirmButton = {
                TextButton(enabled = token.isNotBlank() && url.length > 8 && (read || publish), onClick = {
                    try {
                        val ops = buildSet { if (read) add(GitOperation.READ); if (publish) add(GitOperation.PUBLISH) }
                        val c = container.gitCredentials.add(label, url, user, ops, ca.ifBlank { null })
                        container.gitSecrets.put(gitSecretName(c.id), token.trim())
                        adding = false
                        onMessage("${c.label} added for ${c.url}. Propose to that URL in Commit & Integration to publish through the broker.")
                    } catch (e: Exception) {
                        onMessage(e.message ?: e::class.simpleName.orEmpty())
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
    rotating?.let { c ->
        var value by remember(c.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rotating = null },
            title = { Text("Rotate ${c.label}") },
            text = {
                Column {
                    Text("Create a new token at the issuer, enter it here, then revoke the old one there.", fontSize = 13.sp)
                    OutlinedTextField(value, { value = it }, Modifier.padding(top = 10.dp), label = { Text("New token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    container.gitSecrets.put(gitSecretName(c.id), value.trim())
                    container.gitCredentials.rotated(c.id)
                    rotating = null
                    onMessage("${c.label} rotated here. Revoke the old token at its issuer.")
                }) { Text("Rotate") }
            },
            dismissButton = { TextButton(onClick = { rotating = null }) { Text("Cancel") } },
        )
    }
    revoking?.let { c ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${c.label} here?") },
            text = { Text("Umbra's copy is deleted and nothing is published with it again. The token stays valid at its issuer until you revoke it there.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    container.gitSecrets.remove(gitSecretName(c.id))
                    container.gitCredentials.revoked(c.id)
                    revoking = null
                    onMessage("${c.label}: Umbra's copy deleted. Revoke the token at its issuer too.")
                }) { Text("Revoke here") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}
