package app.umbra.agent.ui.accounts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.MainActivity
import app.umbra.agent.R
import app.umbra.agent.capsule.RestingCompanion
import app.umbra.agent.container
import app.umbra.agent.data.AppPrefs
import app.umbra.agent.data.GuestManager
import app.umbra.agent.data.KeyCheck
import app.umbra.agent.data.OmpLogin
import app.umbra.agent.data.ProviderConfig
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.Dest
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import kotlinx.coroutines.launch

/**
 * A provider as the person sees it. [oauthId] is omp's id for signing in with the account (null: keys only), [keyId]
 * and [keyEnv] the provider and secret a key is stored under (null: accounts only).
 */
internal class Account(val name: String, val letter: String, val tone: Color, val oauthId: String?, val keyId: String?, val keyEnv: String?, val signIn: Int, val risk: Int?)

@Composable
internal fun accounts(): List<Account> = listOf(
    Account("OpenAI", "O", app.umbra.agent.ui.porcelain.pcHue(0xFF3A4A42), "openai-codex", "openai", "OPENAI_API_KEY", R.string.r_acc_sign_in_openai, null),
    Account("Anthropic", "A", app.umbra.agent.ui.porcelain.pcHue(0xFFC0705F), "anthropic", "anthropic", "ANTHROPIC_API_KEY", R.string.r_acc_sign_in_anthropic, R.string.r_acc_risk_anthropic),
    Account("Google Antigravity", "G", app.umbra.agent.ui.porcelain.pcHue(0xFF5E86C2), "google-antigravity", null, null, R.string.r_acc_sign_in_google, R.string.r_acc_risk_google),
)

/** How the account is connected, in a line: by account (and whose), by key (its last four), or not. */
@Composable
internal fun connection(a: Account, prefs: AppPrefs, container: AppContainer): String? {
    prefs.providers.firstOrNull { it.oauth && it.id == a.oauthId }?.let { p ->
        return p.account?.let { stringResource(R.string.r_acc_account, it) } ?: stringResource(R.string.r_acc_account_plain)
    }
    val key = prefs.providers.firstOrNull { !it.oauth && it.id == a.keyId && it.apiKeyEnv != null }?.apiKeyEnv?.let { container.secrets.get(it) }
    return key?.let { stringResource(R.string.r_acc_key, it.takeLast(4)) }
}

/** Accounts and models: each provider in a row; a row opens its sheet, where it connects with the account or a key. */
@Composable
fun AccountsScreen(onBack: () -> Unit, onGo: (Dest) -> Unit = {}) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Account?>(null) }
    // Signing in runs omp, which lives in the Linux environment that comes with the app: it is set up on the way here.
    LaunchedEffect(guest) { if (guest == GuestManager.State.NotInstalled) container.guest.installBundled() }
    PorcelainPage(stringResource(R.string.r_acc_title), onBack) {
        PageColumn {
            if (guest !is GuestManager.State.Ready && guest !is GuestManager.State.Installed && guest !is GuestManager.State.Checking) Callout(stringResource(R.string.r_acc_needs_linux), Tone.Neutral)
            RowGroup(stringResource(R.string.r_acc_sources)) {
                accounts().forEach { a ->
                    val status = connection(a, prefs, container)
                    ProviderRow(a, status ?: stringResource(R.string.r_acc_not_connected), status != null) { open = a }
                }
                NavRow(PIcons.plus, stringResource(R.string.r_acc_other), stringResource(R.string.r_acc_other_body)) { onGo(Dest.Models) }
            }
        }
    }
    open?.let { a -> AccountSheet(a, onDismiss = { open = null }) }
}

@Composable
private fun ProviderRow(a: Account, status: String, connected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Monogram(a)
        Column(Modifier.weight(1f)) {
            Text(a.name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connected) Box(Modifier.size(7.dp).clip(CircleShape).background(P.good))
                Text(status, fontSize = 12.5.sp, color = if (connected) P.sub else P.muted, maxLines = 1)
            }
        }
        PIcon(PIcons.chevronRight, size = 18.dp, tint = P.muted)
    }
}

@Composable
internal fun Monogram(a: Account, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(a.tone), contentAlignment = Alignment.Center) {
        Text(a.letter, fontSize = (size * 0.4).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    }
}

/** One provider: sign in with the account (the browser comes back by itself) or paste a key, checked before saving. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSheet(a: Account, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Monogram(a)
                Text(a.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            }
            AccountBody(a, onDone = onDismiss)
        }
    }
}

/** The body of a provider's sheet, also used by first-run setup. */
@Composable
internal fun AccountBody(a: Account, onDone: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val keyConnected = prefs.providers.any { !it.oauth && it.id == a.keyId && it.apiKeyEnv != null }
    var tab by remember { mutableStateOf(if (a.oauthId == null || (keyConnected && prefs.providers.none { it.oauth && it.id == a.oauthId })) 1 else 0) }
    if (a.oauthId != null && a.keyId != null) {
        Segmented(listOf(0 to stringResource(R.string.r_acc_tab_account), 1 to stringResource(R.string.r_acc_tab_key)), tab, { tab = it }, fill = true)
    }
    if (tab == 0 && a.oauthId != null) SignIn(a, onDone) else KeyEntry(a, onDone)
}

@Composable
private fun SignIn(a: Account, onDone: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val states by container.ompLogin.states.collectAsStateWithLifecycle()
    val id = a.oauthId!!
    val signedIn = prefs.providers.firstOrNull { it.oauth && it.id == id }
    val state = states[id] ?: OmpLogin.State.Idle
    val guest by container.guest.state.collectAsStateWithLifecycle()
    val linuxReady = guest == GuestManager.State.Ready || guest == GuestManager.State.Installed
    when {
        signedIn == null && !linuxReady && state !is OmpLogin.State.Browser -> {
            // omp, which signs in, lives in the Linux environment that comes with the app; it is being set up.
            val progress = (guest as? GuestManager.State.Installing)?.let { if (it.total > 0) " · ${it.bytes * 100 / it.total}%" else null }.orEmpty()
            Waiting(stringResource(R.string.r_acc_needs_linux) + progress, null)
        }
        state is OmpLogin.State.Starting || state is OmpLogin.State.Browser -> {
            Waiting(stringResource(if (state is OmpLogin.State.Browser) R.string.r_acc_waiting else R.string.r_acc_starting), if (state is OmpLogin.State.Browser) stringResource(R.string.r_acc_waiting_body) else null)
            PButton(stringResource(R.string.action_cancel), { container.scope.launch { container.ompLogin.cancel(id) } }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
        }
        signedIn != null -> {
            Connected(signedIn.account)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.r_acc_sign_out), { signOut(container, id) }, Modifier.weight(1f), style = ButtonStyle.Ghost)
                PButton(stringResource(R.string.r_acc_done), onDone, Modifier.weight(1f))
            }
        }
        else -> {
            if (state is OmpLogin.State.Failed) Callout(state.message, Tone.Danger)
            PButton(
                stringResource(if (state is OmpLogin.State.Failed) R.string.r_acc_retry else a.signIn),
                { startSignIn(container, context, a) }, Modifier.fillMaxWidth(), icon = PIcons.arrowRight,
            )
        }
    }
    a.risk?.let { risk ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PIcon(PIcons.info, size = 16.dp, tint = P.amber)
            Text(stringResource(risk), fontSize = 12.5.sp, lineHeight = 19.sp, color = P.amber)
        }
    }
}

/** Starts `omp login` in the app's scope (it outlives this sheet while the browser is in front). */
internal fun startSignIn(container: AppContainer, context: Context, a: Account) {
    val id = a.oauthId ?: return
    container.scope.launch {
        val account = runCatching {
            container.ompLogin.login(id) { url ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }.getOrElse { return@launch }
        container.settings.update { p -> p.copy(providers = p.providers.filterNot { it.id == id } + ProviderConfig(id, a.name, oauth = true, account = account)) }
        // Back to Umbra by itself once the browser has handed the sign-in over.
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        container.modelDirectory.refresh()
    }
}

private fun signOut(container: AppContainer, id: String) {
    container.scope.launch {
        container.ompLogin.logout(id)
        container.settings.update { p -> p.copy(providers = p.providers.filterNot { it.oauth && it.id == id }, defaultModel = p.defaultModel?.takeIf { it.provider != id }) }
    }
}

@Composable
private fun KeyEntry(a: Account, onDone: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val stored = prefs.providers.firstOrNull { !it.oauth && it.id == a.keyId && it.apiKeyEnv != null }
    var key by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    if (stored != null && key.isEmpty()) {
        Connected(stored.apiKeyEnv?.let { container.secrets.get(it) }?.let { stringResource(R.string.r_acc_key, it.takeLast(4)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(R.string.r_acc_remove_key), {
                stored.apiKeyEnv?.let { container.secrets.remove(it) }
                container.settings.update { p -> p.copy(providers = p.providers - stored, defaultModel = p.defaultModel?.takeIf { it.provider != stored.id }) }
            }, Modifier.weight(1f), style = ButtonStyle.Ghost)
            PButton(stringResource(R.string.r_acc_done), onDone, Modifier.weight(1f))
        }
        return
    }
    PField(key, { key = it.trim(); problem = null }, label = stringResource(R.string.r_acc_key_hint), singleLine = true, mono = true, visualTransformation = PasswordVisualTransformation())
    problem?.let { Callout(it, Tone.Danger) }
    PButton(
        stringResource(if (checking) R.string.r_acc_checking else R.string.r_acc_save_key),
        {
            val keyId = a.keyId ?: return@PButton
            val env = a.keyEnv ?: return@PButton
            checking = true
            scope.launch {
                val bad = KeyCheck.problem(keyId, key)
                checking = false
                if (bad != null) { problem = bad; return@launch }
                container.secrets.put(env, key)
                container.settings.update { p -> p.copy(providers = p.providers.filterNot { it.id == keyId } + ProviderConfig(keyId, a.name, apiKeyEnv = env)) }
                key = ""
                onDone()
            }
        },
        Modifier.fillMaxWidth(), enabled = key.length >= 8 && !checking, icon = PIcons.check,
    )
}

@Composable
private fun Waiting(title: String, body: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pc(0xFFF6F8F2)).padding(vertical = 22.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RestingCompanion(size = 32.dp)
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = P.ink)
        if (body != null) Text(body, fontSize = 13.5.sp, color = P.sub)
    }
}

@Composable
private fun Connected(who: String?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pc(0xFFF6F8F2)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(P.good), contentAlignment = Alignment.Center) { PIcon(PIcons.check, size = 18.dp, tint = Color.White) }
        Column {
            Text(stringResource(R.string.r_acc_connected), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink)
            if (who != null) Text(who, fontSize = 12.5.sp, color = P.sub)
        }
    }
}
