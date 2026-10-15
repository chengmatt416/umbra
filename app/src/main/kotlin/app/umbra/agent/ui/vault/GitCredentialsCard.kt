package app.umbra.agent.ui.vault

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.Disclosure
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
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val credentials by container.gitCredentials.all.collectAsStateWithLifecycle()
    val journal by container.gitCredentials.recent.collectAsStateWithLifecycle()
    val brokerState by container.gitCredentials.stateFlow.collectAsStateWithLifecycle()
    val suspended = brokerState["state"]?.takeIf { it.suspended }
    var adding by remember { mutableStateOf(false) }
    var suspending by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf<GitCredential?>(null) }
    var revoking by remember { mutableStateOf<GitCredential?>(null) }
    val unavailable = brokerUnavailable(container)

    PCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(stringResource(R.string.gc_kicker, credentials.size), Modifier.weight(1f))
            PButton(stringResource(R.string.gc_add), { adding = true }, style = ButtonStyle.Alt, tiny = true)
        }
        unavailable?.let { Callout(it, Tone.Warn, Modifier.padding(top = 6.dp)) }
        if (suspended != null) {
            Callout(stringResource(R.string.gc_suspended, suspended.sinceMs?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "", suspended.reason?.let { ": $it" } ?: ""), Tone.Warn, Modifier.padding(top = 6.dp))
            PButton(stringResource(R.string.gc_resume), { container.gitCredentials.resumeBroker(); onMessage(resources.getString(R.string.gc_msg_resumed)) }, Modifier.padding(top = 6.dp), style = ButtonStyle.Alt, tiny = true)
        } else if (credentials.isNotEmpty()) {
            PButton(stringResource(R.string.gc_suspend), { suspending = true }, Modifier.padding(top = 6.dp), style = ButtonStyle.Ghost, tiny = true)
        }
        if (credentials.isEmpty()) Subtle(stringResource(R.string.gc_none), Modifier.padding(top = 6.dp))
        credentials.values.sortedBy { it.createdAtMs }.forEach { c ->
            Gap(10.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, fontSize = 15.sp, color = P.ink)
                    Text(c.url, fontSize = 11.sp, fontFamily = P.mono, color = P.sub, maxLines = 2)
                }
                if (c.publishSuspended) Tag(stringResource(R.string.gc_pub_suspended), Tone.Warn) else Tag(stringResource(R.string.gc_never_agents), Tone.Good)
            }
            Subtle(
                stringResource(R.string.gc_as, c.username) + (if (c.caCertificate != null) " · " + stringResource(R.string.gc_own_ca) else "") +
                    " · " + stringResource(R.string.gc_added, DateUtils.getRelativeTimeSpanString(c.createdAtMs).toString()) +
                    (c.rotatedAtMs?.let { " · " + stringResource(R.string.gc_rotated, DateUtils.getRelativeTimeSpanString(it).toString()) } ?: "") +
                    " · " + stringResource(R.string.gc_used, c.uses.toInt()) + (c.lastUse?.let { " · " + stringResource(R.string.gc_last, it) } ?: ""),
                Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                GitOperation.entries.forEach { op ->
                    Checkbox(op in c.operations, { on ->
                        val next = if (on) c.operations + op else c.operations - op
                        if (next.isEmpty()) onMessage(resources.getString(R.string.gc_msg_need_op))
                        else { container.gitCredentials.setOperations(c.id, next); onMessage("${c.label}: ${next.joinToString { it.label.lowercase() }}.") }
                    })
                    Text(op.label, fontSize = 13.sp, color = P.ink)
                }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.publishSuspended) PButton(stringResource(R.string.gc_grant_again), { container.gitCredentials.restorePublish(c.id); onMessage(resources.getString(R.string.gc_msg_publish_again, c.label)) }, tiny = true)
                PButton(stringResource(R.string.gc_rotate), { rotating = c }, style = ButtonStyle.Alt, tiny = true)
                PButton(stringResource(R.string.gc_revoke_here), { revoking = c }, style = ButtonStyle.Ghost, tiny = true)
            }
        }
        // The record in words: what happened, with which credential, when and how it ended (UX report D-01); the
        // raw, hash-chained entries stay one tap away.
        if (journal.isNotEmpty()) {
            val check = remember(journal.size) { runCatching { container.gitCredentials.verifyJournal() }.getOrNull() }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Kicker(stringResource(R.string.vault_log), Modifier.weight(1f))
                check?.let { Tag(if (it.intact) stringResource(R.string.commit_chain_intact, it.entries) else stringResource(R.string.commit_chain_broken, it.firstBadSeq ?: 0), if (it.intact) Tone.Good else Tone.Danger) }
            }
            journal.takeLast(3).reversed().forEach { e ->
                val data = e.data as? kotlinx.serialization.json.JsonObject
                fun field(k: String) = (data?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
                val label = credentials[field("id")]?.label ?: field("url") ?: ""
                val what = when (e.kind) {
                    "git.brokered" -> if (field("operation") == "publish") R.string.vault_kind_publish else R.string.vault_kind_read
                    "git.credential_added" -> R.string.vault_kind_added
                    "git.credential_restored" -> R.string.vault_kind_restored
                    "git.credential_scope" -> R.string.vault_kind_scope
                    "git.credential_rotated" -> R.string.vault_kind_rotated
                    "git.credential_revoked_locally" -> R.string.vault_kind_revoked
                    "git.broker_suspended" -> R.string.vault_kind_suspended
                    "git.broker_resumed" -> R.string.vault_kind_resumed
                    else -> R.string.vault_kind_publish_restored
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(what) + if (label.isNotBlank()) " · $label" else "", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = P.ink, maxLines = 1)
                        (field("outcome") ?: field("reason") ?: field("operations"))?.takeIf { it.isNotBlank() }?.let { Subtle(it, maxLines = 2) }
                    }
                    Text(DateUtils.getRelativeTimeSpanString(e.timeMs).toString(), fontSize = 11.sp, color = P.muted)
                }
            }
            Disclosure(stringResource(R.string.vault_log_all)) {
                journal.takeLast(20).reversed().forEach { e ->
                    Text("${DateUtils.getRelativeTimeSpanString(e.timeMs)} ${e.kind.removePrefix("git.")} ${e.data?.toString()?.take(160).orEmpty()}", Modifier.padding(top = 3.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                }
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
            title = { Text(stringResource(R.string.gc_add_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.gc_add_body), fontSize = 13.sp)
                    OutlinedTextField(label, { label = it }, Modifier.padding(top = 8.dp), label = { Text(stringResource(R.string.gc_name)) }, singleLine = true)
                    OutlinedTextField(url, { url = it.trim() }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.gc_url)) }, singleLine = true)
                    OutlinedTextField(user, { user = it.trim() }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.gc_user)) }, singleLine = true)
                    OutlinedTextField(token, { token = it }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.gc_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(read, { read = it }); Text(stringResource(R.string.gc_read), fontSize = 13.sp)
                        Checkbox(publish, { publish = it }); Text(stringResource(R.string.gc_publish), fontSize = 13.sp)
                    }
                    OutlinedTextField(ca, { ca = it }, Modifier.padding(top = 6.dp), label = { Text(stringResource(R.string.gc_ca_label)) }, minLines = 2, maxLines = 4)
                }
            },
            confirmButton = {
                TextButton(enabled = token.isNotBlank() && url.length > 8 && (read || publish), onClick = {
                    try {
                        val ops = buildSet { if (read) add(GitOperation.READ); if (publish) add(GitOperation.PUBLISH) }
                        val c = container.gitCredentials.add(label, url, user, ops, ca.ifBlank { null })
                        container.gitSecrets.put(gitSecretName(c.id), token.trim())
                        adding = false
                        onMessage(resources.getString(R.string.gc_msg_added, c.label, c.url))
                    } catch (e: Exception) {
                        onMessage(e.message ?: e::class.simpleName.orEmpty())
                    }
                }) { Text(stringResource(R.string.gc_add_go)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.gc_cancel)) } },
        )
    }
    if (suspending) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { suspending = false },
            title = { Text(stringResource(R.string.gc_suspend_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.gc_suspend_body), fontSize = 13.sp)
                    OutlinedTextField(reason, { reason = it }, Modifier.padding(top = 8.dp), label = { Text(stringResource(R.string.gc_why)) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    container.gitCredentials.suspendBroker(reason)
                    suspending = false
                    onMessage(resources.getString(R.string.gc_msg_suspended))
                }) { Text(stringResource(R.string.gc_suspend_go)) }
            },
            dismissButton = { TextButton(onClick = { suspending = false }) { Text(stringResource(R.string.gc_cancel)) } },
        )
    }
    rotating?.let { c ->
        var value by remember(c.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rotating = null },
            title = { Text(stringResource(R.string.vault_rotate_title, c.label)) },
            text = {
                Column {
                    Text(stringResource(R.string.gc_rotate_body), fontSize = 13.sp)
                    OutlinedTextField(value, { value = it }, Modifier.padding(top = 10.dp), label = { Text(stringResource(R.string.gc_new_token)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    container.gitSecrets.put(gitSecretName(c.id), value.trim())
                    container.gitCredentials.rotated(c.id)
                    rotating = null
                    onMessage(resources.getString(R.string.gc_msg_rotated, c.label))
                }) { Text(stringResource(R.string.gc_rotate)) }
            },
            dismissButton = { TextButton(onClick = { rotating = null }) { Text(stringResource(R.string.gc_cancel)) } },
        )
    }
    revoking?.let { c ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.vault_revoke_title, c.label)) },
            text = { Text(stringResource(R.string.gc_revoke_body), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    container.gitSecrets.remove(gitSecretName(c.id))
                    container.gitCredentials.revoked(c.id)
                    revoking = null
                    onMessage(resources.getString(R.string.gc_msg_deleted, c.label))
                }) { Text(stringResource(R.string.gc_revoke_here)) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.gc_cancel)) } },
        )
    }
}
