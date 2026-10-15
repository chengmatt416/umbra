package app.umbra.agent.ui.vault

import app.umbra.agent.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import app.umbra.agent.ui.credentialScopeRes
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
    val resources = androidx.compose.ui.platform.LocalResources.current
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

    val sessionLabel = stringResource(R.string.vault_session, "%s")
    fun recipient(to: String): String =
        if (to.startsWith("session ")) sessions.firstOrNull { it.id == to.removePrefix("session ") }?.let { sessionLabel.format(it.title) } ?: to else to

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.vault_eyebrow))
        Headline(stringResource(R.string.vault_headline), stringResource(R.string.vault_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker(stringResource(R.string.vault_custody))
                    Text(stringResource(R.string.vault_device_bound), Modifier.padding(top = 4.dp), fontSize = 18.sp, color = P.ink)
                }
                custody?.getOrNull()?.let { Tag(if (it.hardwareBacked) stringResource(R.string.vault_hw) else stringResource(R.string.vault_sw), if (it.hardwareBacked) Tone.Good else Tone.Warn) }
            }
            when (val c = custody) {
                null -> Subtle(stringResource(R.string.vault_asking), Modifier.padding(top = 6.dp))
                else -> c.fold(
                    onSuccess = { k ->
                        PSetting(stringResource(R.string.vault_protecting), stringResource(R.string.vault_key_desc, k.alias, k.algorithm, k.keySize)) {}
                        PSetting(stringResource(R.string.vault_where), stringResource(R.string.vault_level_desc, k.securityLevel)) {}
                        PSetting(stringResource(R.string.vault_unlock), if (k.userAuthentication) stringResource(R.string.vault_needs_auth) else stringResource(R.string.vault_usable), divider = false) {}
                    },
                    onFailure = { Callout(stringResource(R.string.vault_keystore_failed, it.message.orEmpty()), Tone.Warn) },
                )
            }
            Subtle(stringResource(R.string.vault_each_secret), Modifier.padding(top = 6.dp))
        }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.vault_federation))
            Gap(6.dp)
            PObject(stringResource(R.string.vault_local_broker), pluralStringResource(R.plurals.vault_local_desc, records.size, records.size), PIcons.phone, trailing = { Tag(stringResource(R.string.vault_active), Tone.Good) })
            Gap(6.dp)
            val git by container.gitCredentials.all.collectAsStateWithLifecycle()
            val noRoot = app.umbra.agent.integration.brokerUnavailable(container)
            PObject(
                stringResource(R.string.vault_git_broker),
                when {
                    git.isEmpty() -> stringResource(R.string.vault_git_none)
                    noRoot != null -> pluralStringResource(R.plurals.vault_git_noroot, git.size, git.size)
                    else -> pluralStringResource(R.plurals.vault_git_desc, git.size, git.size)
                },
                PIcons.git,
                trailing = { Tag(if (git.isEmpty()) stringResource(R.string.vault_not_set_up) else if (noRoot != null) stringResource(R.string.vault_needs_root) else stringResource(R.string.vault_active), if (git.isNotEmpty() && noRoot == null) Tone.Good else Tone.Neutral) },
            )
            Gap(6.dp)
            PObject(stringResource(R.string.vault_cloud_broker), stringResource(R.string.vault_cloud_none), PIcons.key, trailing = { Tag(stringResource(R.string.vault_not_set_up), Tone.Neutral) })
            Subtle(stringResource(R.string.vault_no_copy), Modifier.padding(top = 8.dp))
        }
        Gap(12.dp)

        GitCredentialsCard { message = it }
        Gap(12.dp)

        RecoveryCard { message = it }
        Gap(12.dp)

        PCard {
            Kicker(stringResource(R.string.vault_credentials, records.size))
            if (records.isEmpty()) Subtle(stringResource(R.string.vault_none), Modifier.padding(top = 6.dp))
            records.values.sortedBy { it.label }.forEach { r ->
                Gap(10.dp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontSize = 15.sp, color = P.ink)
                        Text(r.name, fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                    }
                    Tag(if (r.exposures == 0L) stringResource(R.string.vault_never_out) else stringResource(R.string.vault_handed_out, r.exposures.toInt()), if (r.exposures == 0L) Tone.Good else Tone.Neutral)
                }
                Subtle(
                    stringResource(R.string.vault_added, DateUtils.getRelativeTimeSpanString(r.createdAtMs).toString()) +
                        (r.rotatedAtMs?.let { " · " + stringResource(R.string.vault_rotated_at, DateUtils.getRelativeTimeSpanString(it).toString()) } ?: "") +
                        (r.lastExposedAtMs?.let { " · " + stringResource(R.string.vault_last_to, recipient(r.lastExposedTo.orEmpty()), DateUtils.getRelativeTimeSpanString(it).toString()) } ?: ""),
                    Modifier.padding(top = 2.dp),
                )
                Gap(6.dp)
                Segmented(CredentialScope.entries.map { it to resources.getString(credentialScopeRes(it)) }, r.scope, { scope ->
                    container.vault.setScope(r.name, scope)
                    message = resources.getString(
                        when (scope) {
                            CredentialScope.ANY_SESSION -> R.string.vault_scope_any
                            CredentialScope.ITS_PROVIDER_ONLY -> R.string.vault_scope_provider
                            CredentialScope.NOT_FOR_AGENTS -> R.string.vault_scope_none
                        },
                        r.label, resources.getString(credentialScopeRes(scope)).lowercase(),
                    )
                })
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.vault_rotate), { rotating = r }, style = ButtonStyle.Alt, tiny = true)
                    PButton(stringResource(R.string.vault_revoke_here), { revoking = r }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
        }
        Gap(12.dp)

        PCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker(stringResource(R.string.vault_exposure_record), Modifier.weight(1f))
                chain?.let { Tag(if (it.intact) stringResource(R.string.commit_chain_intact, it.entries) else stringResource(R.string.commit_chain_broken, it.firstBadSeq ?: 0), if (it.intact) Tone.Good else Tone.Danger) }
            }
            Subtle(stringResource(R.string.vault_exposure_body), Modifier.padding(top = 4.dp))
            if (exposures.isEmpty()) Subtle(stringResource(R.string.vault_nothing_left), Modifier.padding(top = 6.dp))
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
            Kicker(stringResource(R.string.vault_safety))
            PSetting(stringResource(R.string.vault_model_keys), stringResource(R.string.vault_model_keys_d)) { Tag(stringResource(R.string.vault_recorded), Tone.Warn) }
            PSetting(stringResource(R.string.vault_git_credentials), stringResource(R.string.vault_git_d)) { Tag(stringResource(R.string.vault_brokered), Tone.Good) }
            PSetting(stringResource(R.string.vault_separate), stringResource(R.string.vault_separate_d)) { Tag(stringResource(R.string.vault_independent), Tone.Neutral) }
            PSetting(stringResource(R.string.vault_recovery), stringResource(R.string.vault_recovery_d), divider = false) { Tag(stringResource(R.string.vault_code_sealed), Tone.Neutral) }
        }
        Gap(28.dp)
    }

    rotating?.let { r ->
        var value by remember(r.name) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { rotating = null },
            title = { Text(stringResource(R.string.vault_rotate_title, r.label)) },
            text = {
                Column {
                    Text(stringResource(R.string.vault_rotate_body), fontSize = 13.sp)
                    OutlinedTextField(value, { value = it }, Modifier.padding(top = 10.dp), label = { Text(stringResource(R.string.vault_new_value)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = value.isNotBlank(), onClick = {
                    container.secrets.put(r.name, value.trim())
                    container.vault.rotated(r.name)
                    message = resources.getString(R.string.vault_msg_rotated, r.label)
                    rotating = null
                }) { Text(stringResource(R.string.vault_rotate)) }
            },
            dismissButton = { TextButton(onClick = { rotating = null }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
    revoking?.let { r ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            title = { Text(stringResource(R.string.vault_revoke_title, r.label)) },
            text = { Text(stringResource(R.string.vault_revoke_body), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    container.vault.revoked(r.name)
                    container.secrets.remove(r.name)
                    message = resources.getString(R.string.vault_msg_deleted, r.label)
                    revoking = null
                }) { Text(stringResource(R.string.vault_revoke_here)) }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(R.string.vault_cancel)) } },
        )
    }
}
