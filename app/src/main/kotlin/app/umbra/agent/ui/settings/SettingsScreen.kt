package app.umbra.agent.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.data.ThemeStyle
import app.umbra.agent.data.UiMode
import app.umbra.agent.ui.models.ModelPicker
import app.umbra.agent.ui.models.modelLabel
import app.umbra.agent.ui.onboarding.PermissionsSheet
import app.umbra.agent.ui.onboarding.permissionsGranted
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.shell.Dest
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import app.umbra.agent.ui.shell.SwitchRow

/**
 * Settings: the mode first, then what people change — accounts, model, look, which hand, the Capsule — then Laya,
 * updates and permissions. Everything else (when to ask, standing instructions, stopping, the helper) is one row away.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onGo: (Dest) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val laya by container.laya.state.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var permissions by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { container.laya.refresh() }
    PorcelainPage(stringResource(R.string.r_set_title), onBack) {
        PageColumn {
            Segmented(
                listOf(UiMode.SIMPLE to stringResource(R.string.r_mode_simple), UiMode.POWER to stringResource(R.string.r_mode_power), UiMode.DEV to stringResource(R.string.r_mode_dev)),
                prefs.uiMode, { mode -> container.settings.update { it.copy(uiMode = mode) } }, fill = true,
            )
            RowGroup {
                NavRow(PIcons.key, stringResource(R.string.r_menu_accounts), stringResource(R.string.r_set_accounts_body, prefs.providers.size)) { onGo(Dest.Accounts) }
                NavRow(PIcons.spark, stringResource(R.string.r_model_title), value = modelLabel(prefs, container.modelDirectory)) { picker = true }
                NavRow(
                    PIcons.drop, stringResource(R.string.r_set_appearance),
                    value = stringResource(when (prefs.theme.style) { ThemeStyle.LIQUID -> R.string.theme_liquid; ThemeStyle.APPLE -> R.string.theme_apple; else -> R.string.theme_porcelain }),
                ) { onGo(Dest.Appearance) }
                NavRow(PIcons.hand, stringResource(R.string.r_set_hand), value = stringResource(if (prefs.leftHanded) R.string.r_set_hand_left else R.string.r_set_hand_right)) {
                    container.settings.update { it.copy(leftHanded = !it.leftHanded) }
                }
                SwitchRow(PIcons.target, stringResource(R.string.r_set_capsule), stringResource(R.string.r_set_capsule_body), prefs.overlayEnabled) { on ->
                    container.settings.update { it.copy(overlayEnabled = on) }
                    if (on && !Settings.canDrawOverlays(context)) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }
            RowGroup {
                val layaOn = prefs.layaWanted && laya != LayaSidecar.State.NOT_INSTALLED && laya != LayaSidecar.State.NEEDS_LINUX
                NavRow(PIcons.grid, stringResource(R.string.r_set_laya), stringResource(if (layaOn) R.string.r_set_laya_on else R.string.r_set_laya_off)) {
                    if (!prefs.layaWanted) { container.settings.update { it.copy(layaWanted = true, layaOffered = true) }; container.laya.download() } else onGo(Dest.MoreSettings)
                }
                NavRow(PIcons.refresh, stringResource(R.string.r_set_update), stringResource(R.string.r_set_update_body, container.updater.installedVersionName), value = stringResource(R.string.r_set_update_check)) {
                    container.updater.updateNow()
                }
                NavRow(PIcons.shield, stringResource(R.string.r_set_permissions), value = "${permissionsGranted(context)}/3") { permissions = true }
            }
            RowGroup {
                if (prefs.uiMode != UiMode.SIMPLE) NavRow(PIcons.terminal, stringResource(R.string.r_set_linux)) { onGo(Dest.Linux) }
                NavRow(PIcons.sliders, stringResource(R.string.r_set_more)) { onGo(Dest.MoreSettings) }
            }
        }
    }
    if (picker) ModelPicker(onDismiss = { picker = false }, onConnect = { picker = false; onGo(Dest.Accounts) })
    if (permissions) PermissionsSheet(onDismiss = { permissions = false })
}

