package app.umbra.agent.ui.vault

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
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
import app.umbra.agent.container
import app.umbra.agent.integration.gitSecretName
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.control.BackedUpGitCredential
import app.umbra.core.control.BackedUpSecret
import app.umbra.core.control.CredentialScope
import app.umbra.core.control.VaultBackup
import app.umbra.core.control.VaultBackupBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Recovery (architecture spec §12.3, AC-018): a backup of the vault sealed under a recovery code the phone shows once
 * and never keeps; restoring needs the file and the code, keeps whatever is already stored, and brings secrets back
 * without the authority to publish.
 */
@Composable
fun RecoveryCard(onMessage: (String) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var opening by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<VaultBackupBody?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val bytes = pending
        pending = null
        if (uri == null || bytes == null) { onMessage("Not saved: no backup was written."); return@rememberLauncherForActivityResult }
        scope.launch {
            onMessage(runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) } }
                val digest = app.umbra.core.control.ControlStore.sha256(String(bytes)).take(16)
                container.control.journal("credentials").append("vault.backup_exported", buildJsonObject { put("bytes", bytes.size); put("file_digest", digest) })
                "Backup saved (${bytes.size} bytes, file fingerprint $digest). It opens only with the recovery code you wrote down."
            }.getOrElse { "Could not write it: ${it.message}" })
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            opening = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } } }
                .getOrElse { onMessage("Could not read it: ${it.message}"); null }
        }
    }

    PCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("Recovery", Modifier.weight(1f))
            Tag("Code-sealed backup", Tone.Neutral)
        }
        Subtle("A backup of the stored keys and git credentials, sealed under a recovery code shown once and kept nowhere on this phone. Anyone with the file and the code has the keys; neither alone is enough.", Modifier.padding(top = 6.dp))
        Subtle("Restoring keeps what is already stored and brings back no authority: git credentials return unable to publish until you grant it again; no promotion or capability is in a backup.", Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp)) {
            PButton("Make a backup…", { code = VaultBackup.newRecoveryCode() }, style = ButtonStyle.Alt, tiny = true)
            PButton("Restore…", { open.launch(arrayOf("*/*")) }, Modifier.padding(start = 8.dp), style = ButtonStyle.Ghost, tiny = true)
        }
    }

    code?.let { c ->
        AlertDialog(
            onDismissRequest = { code = null },
            title = { Text("Your recovery code") },
            text = {
                Column {
                    Text("Write it down now, away from this phone. It is not stored anywhere and cannot be shown again; without it the backup cannot be opened.", fontSize = 13.sp)
                    SelectionContainer { Text(c, Modifier.padding(top = 10.dp), fontSize = 20.sp, fontFamily = P.mono, color = P.ink) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    code = null
                    scope.launch {
                        val body = withContext(Dispatchers.IO) {
                            VaultBackupBody(
                                createdAtMs = System.currentTimeMillis(),
                                secrets = container.secrets.names().sorted().mapNotNull { n ->
                                    val v = container.secrets.get(n) ?: return@mapNotNull null
                                    val (label, provider, sc) = container.vault.accountOf(n) ?: Triple(n, null, CredentialScope.ANY_SESSION)
                                    BackedUpSecret(n, v, label, provider, sc)
                                },
                                git = container.gitCredentials.list().mapNotNull { g ->
                                    val t = container.gitSecrets.get(gitSecretName(g.id)) ?: return@mapNotNull null
                                    BackedUpGitCredential(g.label, g.url, g.username, g.operations, g.caCertificate, t)
                                },
                            )
                        }
                        pending = withContext(Dispatchers.Default) { VaultBackup.seal(body, c) }
                        onMessage("Sealed ${body.secrets.size} key(s) and ${body.git.size} git credential(s). Choose where to save the file.")
                        save.launch("umbra-vault-${System.currentTimeMillis() / 1000}.umbra-vault")
                    }
                }) { Text("I wrote it down") }
            },
            dismissButton = { TextButton(onClick = { code = null }) { Text("Cancel") } },
        )
    }

    opening?.let { bytes ->
        var typed by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { opening = null; preview = null },
            title = { Text(if (preview == null) "Open the backup" else "Restore from the backup?") },
            text = {
                Column {
                    val p = preview
                    if (p == null) {
                        Text("Enter its recovery code. A wrong code or a changed file does not open.", fontSize = 13.sp)
                        OutlinedTextField(typed, { typed = it; problem = null }, Modifier.padding(top = 8.dp), label = { Text("Recovery code") }, singleLine = true)
                        problem?.let { Text(it, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P.danger) }
                    } else {
                        val stored = container.secrets.names()
                        Text("Made ${android.text.format.DateUtils.getRelativeTimeSpanString(p.createdAtMs)}. The file opened and is unchanged.", fontSize = 13.sp)
                        p.secrets.forEach { s -> Text("${s.label} (${s.name}): " + if (s.name in stored) "kept, one is stored" else "restored, ${s.scope.label.lowercase()}", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P.sub) }
                        p.git.forEach { g -> Text("${g.label} (${g.url}): " + if (container.gitCredentials.forRemote(g.url) != null) "kept, one is held" else "restored, unable to publish until granted", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P.sub) }
                    }
                }
            },
            confirmButton = {
                if (preview == null) TextButton(enabled = typed.isNotBlank(), onClick = {
                    scope.launch {
                        preview = withContext(Dispatchers.Default) { runCatching { VaultBackup.open(bytes, typed) }.getOrElse { problem = "It does not open: a wrong code, or the file was changed."; null } }
                    }
                }) { Text("Open") }
                else TextButton(onClick = {
                    val p = preview!!
                    opening = null
                    preview = null
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            VaultBackup.restore(p, container.secrets.names(), { n, v -> container.secrets.put(n, v) }, container.vault, container.gitCredentials,
                                { id, t -> container.gitSecrets.put(gitSecretName(id), t) }, container.control.journal("credentials"))
                        }
                        onMessage("Restored ${r.restored.size} key(s) and ${r.gitRestored.size} git credential(s); kept ${r.kept.size + r.gitKept.size} already here. Restored git credentials cannot publish until you grant it in the git card.")
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { opening = null; preview = null }) { Text("Cancel") } },
        )
    }
}
