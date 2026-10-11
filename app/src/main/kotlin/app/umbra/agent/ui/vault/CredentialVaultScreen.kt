package app.umbra.agent.ui.vault

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
import androidx.compose.runtime.LaunchedEffect
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
import app.umbra.agent.data.SecretStore
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
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.ChainCheck
import app.umbra.core.control.CredentialRecord
import app.umbra.core.control.CredentialScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Credential Vault (architecture spec §12): the key that protects every secret and how the phone holds it, which
 * agents each credential may reach, every time one left the vault (in a chain that shows tampering), rotation and
 * revocation as separate acts, and what recovery there is (none: Umbra keeps no copy off the phone). Values never
 * appear here.
 */
@Composable
fun CredentialVaultScreen() {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val records by container.vault.all.collectAsStateWithLifecycle()
    val exposures by container.vault.recentExposures.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    var custody by remember { mutableStateOf<Result<SecretStore.Custody>?>(null) }
    var chain by remember { mutableStateOf<ChainCheck?>(null) }
    var rotating by remember { mutableStateOf<CredentialRecord?>(null) }
    var revoking by remember { mutableStateOf<CredentialRecord?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exposures.size) {
        custody = withContext(Dispatchers.IO) { runCatching { container.secrets.custody() } }
        chain = withContext(Dispatchers.IO) { runCatching { container.vault.verifyExposures() }.getOrNull() }
    }

    fun recipient(to: String): String =
        if (to.startsWith("session ")) sessions.firstOrNull { it.id == to.removePrefix("session ") }?.let { "Session · ${it.title}" } ?: to else to

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Secure credential architecture")
        Headline("Authority without", "exposing secrets.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("Key custody")
                    Text("Device-bound", Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                custody?.getOrNull()?.let { Tag(if (it.hardwareBacked) "Hardware-backed" else "Software", if (it.hardwareBacked) Tone.Good else Tone.Warn) }
            }
            when (val c = custody) {
                null -> Subtle("Asking the keystore…", Modifier.padding(top = 6.dp))
                else -> c.fold(
                    onSuccess = { k ->
                        PSetting("Protecting key", "${k.alias} · ${k.algorithm} ${k.keySize}-bit · never leaves the keystore") {}
                        PSetting("Where it lives", k.securityLevel + " (as the keystore reports it; no attestation chain is verified)") {}
                        PSetting("Unlock", if (k.userAuthentication) "Needs the user's authentication" else "Usable while the phone runs, without asking", divider = false) {}
                    },
                    onFailure = { Callout("The keystore did not answer: ${it.message}", Tone.Warn) },
                )
            }
            Subtle("Each secret is AES-GCM encrypted under this key. The key cannot be exported, so nothing can be backed up by exporting it.", Modifier.padding(top = 6.dp))
        }
        Gap(12.dp)

        PCard {
            Kicker("Broker federation")
            Gap(6.dp)
            PObject("Local Identity Broker", "Android Keystore · this phone · ${records.size} credential(s)", PIcons.phone, trailing = { Tag("Active", Tone.Good) })
            Gap(6.dp)
            val git by container.gitCredentials.all.collectAsStateWithLifecycle()
            val noRoot = app.umbra.agent.integration.brokerUnavailable(container)
            PObject(
                "Git Credential Broker",
                when {
                    git.isEmpty() -> "Not set up: no git credential held; other remotes use the workspace's own git configuration"
                    noRoot != null -> "${git.size} credential(s) held, unusable here: brokering needs root"
                    else -> "${git.size} credential(s), each bound to one URL, used only by root in a staging repository"
                },
                PIcons.git,
                trailing = { Tag(if (git.isEmpty()) "Not set up" else if (noRoot != null) "Needs root" else "Active", if (git.isNotEmpty() && noRoot == null) Tone.Good else Tone.Neutral) },
            )
            Gap(6.dp)
            PObject("Cloud / Signing Broker", "Not set up: no deployment or signing material is held", PIcons.key, trailing = { Tag("Not set up", Tone.Neutral) })
            Subtle("No secret is copied to another device: no node is paired with this phone.", Modifier.padding(top = 8.dp))
        }
        Gap(12.dp)

        GitCredentialsCard { message = it }
        Gap(12.dp)

        PCard {
            Kicker("Credentials · ${records.size}")
            if (records.isEmpty()) Subtle("None. Keys added in Models & accounts appear here.", Modifier.padding(top = 6.dp))
            records.values.sortedBy { it.label }.forEach { r ->
                Gap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontSize = 15.sp, color = P.ink)
                        Text(r.name, fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                    }
                    Tag(if (r.exposures == 0L) "Never handed out" else "Handed out ${r.exposures}×", if (r.exposures == 0L) Tone.Good else Tone.Neutral)
                }
                Subtle(
                    "Added ${DateUtils.getRelativeTimeSpanString(r.createdAtMs)}" +
                        (r.rotatedAtMs?.let { " · rotated ${DateUtils.getRelativeTimeSpanString(it)}" } ?: "") +
                        (r.lastExposedAtMs?.let { " · last to ${recipient(r.lastExposedTo.orEmpty())} ${DateUtils.getRelativeTimeSpanString(it)}" } ?: ""),
                    Modifier.padding(top = 2.dp),
                )
                Gap(6.dp)
                Segmented(CredentialScope.entries.map { it to it.label }, r.scope, { scope ->
                    container.vault.setScope(r.name, scope)
                    message = "${r.label}: ${scope.label.lowercase()}" + when (scope) {
                        CredentialScope.ANY_SESSION -> " — every session may switch to its models."
                        CredentialScope.ITS_PROVIDER_ONLY -> " — only sessions on its provider get it; others cannot switch to it."
                        CredentialScope.NOT_FOR_AGENTS -> " — no session gets it, and its models are not offered."
                    }
                })
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton("Rotate", { rotating = r }, style = ButtonStyle.Alt, tiny = true)
                    PButton("Revoke here", { revoking = r }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
        }
        Gap(12.dp)

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Exposure record", Modifier.weight(1f))
                chain?.let { Tag(if (it.intact) "Chain intact · ${it.entries}" else "Broken at #${it.firstBadSeq}", if (it.intact) Tone.Good else Tone.Danger) }
            }
            Subtle("Every time a credential leaves the vault, to which process and why. What was handed out cannot be shown as not copied; it is never hidden.", Modifier.padding(top = 4.dp))
            if (exposures.isEmpty()) Subtle("Nothing has left the vault yet.", Modifier.padding(top = 6.dp))
            exposures.takeLast(12).reversed().forEach { e ->
                val d = e.data as? JsonObject
                fun f(k: String) = (d?.get(k) as? JsonPrimitive)?.content.orEmpty()
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("${f("name")} → ${recipient(f("to"))}", fontSize = 13.sp, color = P.ink, maxLines = 1)
                    Text("${DateUtils.formatDateTime(context, e.timeMs, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE)} · ${f("purpose")}", fontSize = 11.sp, color = P.sub)
                }
            }
        }
        Gap(12.dp)

        PCard(style = CardStyle.Soft) {
            Kicker("Vault safety")
            PSetting("Model keys and the agent", "omp needs its provider key to call the model, so the key is in omp's environment, where the agent's own commands may be able to read it. Scopes narrow which sessions get which key; each hand-over is recorded.") { Tag("Recorded", Tone.Warn) }
            PSetting("Git credentials", "Given to no agent: root uses a token for its one URL, in a staging repository, reading the candidate from a bundle; without root they are not used at all") { Tag("Brokered", Tone.Good) }
            PSetting("Revoke · rotate · node trust", "Separate acts. Revoking here deletes Umbra's copy only; the key stays valid at its issuer until revoked there.") { Tag("Independent", Tone.Neutral) }
            PSetting("Recovery", "None: Umbra keeps no copy of a credential off this phone. A lost phone means creating new keys at each issuer and revoking the old ones there.", divider = false) { Tag("No backup", Tone.Neutral) }
        }
        Gap(28.dp)
    }

    rotating?.let { r ->
        var value by remember(r.name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rotating = null },
            title = { Text("Rotate ${r.label}") },
            text = {
                Column {
                    Text("Create a new key at its issuer first, enter it here, then revoke the old one there. The old value is overwritten here at once.", fontSize = 13.sp)
                    OutlinedTextField(value, { value = it }, Modifier.padding(top = 10.dp), label = { Text("New value") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    container.secrets.put(r.name, value.trim())
                    container.vault.rotated(r.name)
                    message = "${r.label} rotated. Running sessions keep the old value until they restart; revoke the old key at its issuer."
                    rotating = null
                }) { Text("Rotate") }
            },
            dismissButton = { TextButton(onClick = { rotating = null }) { Text("Cancel") } },
        )
    }
    revoking?.let { r ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text("Revoke ${r.label} here?") },
            text = { Text("Umbra's copy is deleted and no new session will get it. The key itself stays valid at its issuer until you revoke it there, and any copy a process already received is beyond recall.", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    container.vault.revoked(r.name)
                    container.secrets.remove(r.name)
                    message = "${r.label}: Umbra's copy deleted. Revoke it at its issuer too."
                    revoking = null
                }) { Text("Revoke here") }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text("Cancel") } },
        )
    }
}
