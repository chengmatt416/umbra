package app.umbra.agent.ui.onboarding

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.umbra.agent.R
import app.umbra.agent.capsule.RestingCompanion
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.accounts.Account
import app.umbra.agent.ui.accounts.AccountSheet
import app.umbra.agent.ui.accounts.Monogram
import app.umbra.agent.ui.accounts.accounts
import app.umbra.agent.ui.accounts.connection
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.RowGroup
import app.umbra.agent.ui.shell.SwitchRow
import app.umbra.platform.android.UmbraAccessibilityService
import kotlinx.coroutines.launch

/**
 * First-run setup, four short steps: how much of the app to show, an account to work with, Laya (asked once), and the
 * three permissions the app needs. The Linux environment that comes with the app is set up behind them from the start,
 * since signing in and Laya both run in it.
 */
@Composable
fun Onboarding(onAccounts: () -> Unit) {
    val container = LocalContext.current.container
    val guest by container.guest.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(guest) { if (guest == GuestManager.State.NotInstalled) container.guest.installBundled() }
    Box(Modifier.fillMaxSize().background(P.page).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Progress(step, Modifier.padding(start = 8.dp, top = 20.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> ModeStep { step = 1 }
                    1 -> ConnectStep { step = 2 }
                    2 -> LayaStep { step = 3 }
                    else -> PermissionStep { container.settings.update { it.copy(onboarded = true) } }
                }
            }
        }
    }
}

@Composable
private fun Progress(step: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { i -> Box(Modifier.height(8.dp).width(if (i == step) 28.dp else 8.dp).clip(RoundedCornerShape(4.dp)).background(if (i <= step) P.accent2 else P.line)) }
    }
}

@Composable
private fun Title(text: String, modifier: Modifier = Modifier) = Text(text, modifier.padding(start = 8.dp), fontFamily = P.serif, fontSize = 28.sp, lineHeight = 36.sp, color = P.ink)

@Composable
private fun BottomActions(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** Simple or Power; Dev is a setting, not a first question. */
@Composable
private fun ModeStep(onNext: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        Title(stringResource(R.string.r_ob_mode), Modifier.padding(bottom = 24.dp))
        BottomActions {
            Choice(stringResource(R.string.r_mode_simple), stringResource(R.string.r_mode_simple_body), prefs.uiMode == UiMode.SIMPLE) { container.settings.update { it.copy(uiMode = UiMode.SIMPLE) } }
            Choice(stringResource(R.string.r_mode_power), stringResource(R.string.r_mode_power_body), prefs.uiMode != UiMode.SIMPLE) { container.settings.update { it.copy(uiMode = UiMode.POWER) } }
            PButton(stringResource(R.string.r_ob_continue), onNext, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Choice(title: String, body: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(pc(0xFFF6F8F2)).then(if (on) Modifier.border(2.dp, P.accent2, shape) else Modifier)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Text(body, Modifier.padding(top = 4.dp), fontSize = 13.5.sp, lineHeight = 20.sp, color = P.sub)
        }
        Box(Modifier.size(22.dp).clip(CircleShape).then(if (on) Modifier.background(P.accent2) else Modifier.border(2.dp, P.muted, CircleShape)))
    }
}

@Composable
private fun ConnectStep(onNext: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<Account?>(null) }
    Column(Modifier.fillMaxSize()) {
        Title(stringResource(R.string.r_ob_connect), Modifier.padding(top = 28.dp, bottom = 20.dp))
        RowGroup {
            accounts().forEach { a ->
                val status = connection(a, prefs, container)
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { open = a }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Monogram(a)
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = P.ink)
                        Text(status ?: stringResource(R.string.r_acc_not_connected), fontSize = 12.5.sp, color = if (status != null) P.good else P.muted)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BottomActions {
            if (prefs.providers.isEmpty()) PButton(stringResource(R.string.r_ob_later), onNext, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
            PButton(stringResource(R.string.r_ob_continue), onNext, Modifier.fillMaxWidth(), enabled = prefs.providers.isNotEmpty())
        }
    }
    open?.let { a -> AccountSheet(a, onDismiss = { open = null }) }
}

@Composable
private fun LayaStep(onNext: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val laya by container.laya.state.collectAsStateWithLifecycle()
    val guest by container.guest.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Title(stringResource(R.string.r_ob_laya), Modifier.padding(top = 28.dp, bottom = 12.dp))
        Text(stringResource(R.string.r_ob_laya_body), Modifier.padding(horizontal = 8.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = P.sub)
        if (prefs.layaWanted) {
            Row(
                Modifier.padding(top = 20.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RestingCompanion(size = 22.dp)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.r_ob_laya_downloading), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink)
                    val ready = guest == GuestManager.State.Ready || guest == GuestManager.State.Installed
                    Text(
                        when {
                            !ready -> stringResource(R.string.r_ob_laya_wait_linux)
                            laya == LayaSidecar.State.FAILED -> container.laya.message ?: stringResource(R.string.laya_failed)
                            laya == LayaSidecar.State.STOPPED || laya == LayaSidecar.State.RUNNING -> stringResource(R.string.r_set_laya_on)
                            else -> stringResource(R.string.r_ob_laya_background)
                        },
                        Modifier.padding(top = 2.dp), fontSize = 12.5.sp, color = if (laya == LayaSidecar.State.FAILED) P.danger else P.sub,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        BottomActions {
            if (!prefs.layaWanted) {
                PButton(stringResource(R.string.r_ob_later), { container.settings.update { it.copy(layaOffered = true, layaWanted = false) }; onNext() }, Modifier.fillMaxWidth(), style = ButtonStyle.Ghost)
                PButton(stringResource(R.string.r_ob_laya_download), {
                    container.settings.update { it.copy(layaOffered = true, layaWanted = true) }
                    container.laya.download()
                }, Modifier.fillMaxWidth())
            } else PButton(stringResource(R.string.r_ob_continue), onNext, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionStep(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Title(stringResource(R.string.r_ob_perm), Modifier.padding(top = 28.dp, bottom = 20.dp))
        PermissionRows()
        Spacer(Modifier.weight(1f))
        BottomActions { PButton(stringResource(R.string.r_ob_done), onDone, Modifier.fillMaxWidth()) }
    }
}

/** How many of the three permissions are on. */
fun permissionsGranted(context: Context): Int =
    listOf(UmbraAccessibilityService.connected.value, Settings.canDrawOverlays(context), context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()).count { it }

/** The three permissions as switches; each turns on directly when the helper or root can, else opens its system page. */
@Composable
fun PermissionRows() {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val access by UmbraAccessibilityService.connected.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notify by remember { mutableStateOf(context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
    // Coming back from a system page: read them again.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            overlay = Settings.canDrawOverlays(context)
            notify = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        }
    }
    RowGroup {
        SwitchRow(PIcons.hand, stringResource(R.string.r_ob_perm_access), stringResource(R.string.r_ob_perm_access_body), access) {
            scope.launch {
                val on = runCatching { container.grants.enableAccessibility() }.getOrDefault(false)
                if (!on) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        SwitchRow(PIcons.phone, stringResource(R.string.r_ob_perm_capsule), stringResource(R.string.r_ob_perm_capsule_body), overlay && prefs.overlayEnabled) { want ->
            container.settings.update { it.copy(overlayEnabled = want) }
            if (want && !overlay) scope.launch {
                val on = runCatching { container.grants.allowAppOp("SYSTEM_ALERT_WINDOW") }.getOrDefault(false)
                if (on) overlay = Settings.canDrawOverlays(context)
                else context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        SwitchRow(PIcons.info, stringResource(R.string.r_ob_perm_notify), stringResource(R.string.r_ob_perm_notify_body), notify) {
            scope.launch {
                val on = runCatching { container.grants.grantPermission("android.permission.POST_NOTIFICATIONS") }.getOrDefault(false)
                if (on) notify = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** The permissions in a sheet, from Settings. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PermissionsSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.r_set_permissions), Modifier.padding(start = 6.dp), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            PermissionRows()
        }
    }
}
