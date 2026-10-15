package app.umbra.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.ui.emergency.StopAllConfirm
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PChoice
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PLink
import app.umbra.agent.ui.porcelain.PToggle
import app.umbra.agent.ui.porcelain.SectionHead
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.safety.ApprovalMode
import kotlinx.coroutines.launch

/**
 * Settings, in the order people look for them: where things are, when Umbra asks, what every task follows, what
 * reaches you, how to stop and take back, and the advanced parts (each marked when it is not verified on a phone).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit, onModels: () -> Unit, onSetup: () -> Unit, onApprovalHistory: () -> Unit, onDiagnostics: () -> Unit,
    onWorkspaces: () -> Unit, onProcesses: () -> Unit, onDisplays: () -> Unit,
) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var helperMessage by remember { mutableStateOf<String?>(null) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var instructions by remember(prefs.standingInstructions) { mutableStateOf(prefs.standingInstructions) }

    PorcelainPage(stringResource(R.string.dest_settings), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
            PCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                PLink(stringResource(R.string.dest_models), prefs.defaultModel?.let { "${it.provider}/${it.modelId}" } ?: stringResource(R.string.set_models_none), icon = PIcons.spark, onClick = onModels)
                PLink(stringResource(R.string.dest_linux), stringResource(R.string.set_setup_summary), icon = PIcons.check, onClick = onSetup)
                PLink(stringResource(R.string.dest_workspaces), stringResource(R.string.set_workspaces_summary), icon = PIcons.folder, onClick = onWorkspaces)
                PLink(stringResource(R.string.dest_processes), stringResource(R.string.set_processes_summary), icon = PIcons.activity, onClick = onProcesses)
                PLink(stringResource(R.string.dest_displays), stringResource(R.string.set_displays_summary), icon = PIcons.monitor, onClick = onDisplays)
                PLink(stringResource(R.string.dest_diagnostics), stringResource(R.string.set_diagnostics_summary), icon = PIcons.info, onClick = onDiagnostics)
                PLink(stringResource(R.string.set_language), stringResource(R.string.set_language_body), icon = PIcons.book, divider = false) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }

            SectionHead(stringResource(R.string.set_section_ask), Modifier.padding(top = 16.dp), body = stringResource(R.string.set_ask_now))
            PChoice(
                listOf(
                    Triple(ApprovalMode.CAUTIOUS, stringResource(R.string.mode_cautious), stringResource(R.string.mode_cautious_body)),
                    Triple(ApprovalMode.BALANCED, stringResource(R.string.mode_balanced), stringResource(R.string.mode_balanced_body)),
                    Triple(ApprovalMode.AUTONOMOUS, stringResource(R.string.mode_autonomous), stringResource(R.string.mode_autonomous_body)),
                ),
                prefs.approvalMode, { mode -> container.settings.update { it.copy(approvalMode = mode) } },
            )
            Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.set_ask_detail)) }
            PLink(stringResource(R.string.dest_approvals), stringResource(R.string.set_history_summary), icon = PIcons.check, divider = false, onClick = onApprovalHistory)

            SectionHead(stringResource(R.string.set_section_instructions), Modifier.padding(top = 12.dp))
            PField(instructions, { instructions = it }, placeholder = stringResource(R.string.set_instructions_hint), minLines = 3, maxLines = 8)
            Box(Modifier.padding(top = 8.dp)) {
                PButton(stringResource(R.string.action_save), { container.settings.update { it.copy(standingInstructions = instructions.trim()) } }, style = ButtonStyle.Alt, tiny = true, enabled = instructions.trim() != prefs.standingInstructions)
            }

            SectionHead(stringResource(R.string.set_section_notifications), Modifier.padding(top = 16.dp))
            PToggle(stringResource(R.string.set_notify_approvals), stringResource(R.string.set_notify_approvals_body), prefs.notifyOnApproval, { on -> container.settings.update { it.copy(notifyOnApproval = on) } })
            PToggle(stringResource(R.string.set_capsule), stringResource(R.string.set_capsule_body), prefs.overlayEnabled, { on ->
                container.settings.update { it.copy(overlayEnabled = on) }
                if (on && !Settings.canDrawOverlays(context)) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, divider = false)
            Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.set_capsule_detail)) }
            PToggle(stringResource(R.string.set_live_context), stringResource(R.string.set_live_context_body), prefs.liveContext, { on -> container.settings.update { it.copy(liveContext = on) } }, divider = false)
            Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.set_live_context_detail)) }

            SectionHead(stringResource(R.string.set_section_stop), Modifier.padding(top = 12.dp), body = stringResource(R.string.set_stop_body))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.stop_all), { confirm = "stop" }, style = ButtonStyle.Warn, tiny = true, icon = PIcons.stop)
                PButton(stringResource(R.string.set_revoke), { confirm = "revoke" }, style = ButtonStyle.Alt, tiny = true)
                PButton(stringResource(R.string.set_screen_off), {
                    scope.launch {
                        val off = runCatching { container.grants.disableAccessibility() }.getOrDefault(false)
                        if (off) accessMessage = resources.getString(R.string.set_screen_off_done)
                        else context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }, style = ButtonStyle.Alt, tiny = true)
                PButton(stringResource(R.string.set_delete_all), { confirm = "delete" }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.trash)
            }
            accessMessage?.let { Box(Modifier.padding(top = 10.dp)) { Callout(it, Tone.Neutral) } }

            SectionHead(stringResource(R.string.set_section_advanced), Modifier.padding(top = 20.dp))
            PCard(style = app.umbra.agent.ui.porcelain.CardStyle.Soft) {
                Text(stringResource(R.string.set_helper), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = P.ink)
                Subtle(stringResource(R.string.set_helper_body), Modifier.padding(top = 4.dp))
                Box(Modifier.padding(top = 10.dp)) {
                    PButton(stringResource(R.string.set_helper_start), {
                        scope.launch {
                            helperMessage = resources.getString(if (container.helper.startWithRoot(container.rootShell)) R.string.set_helper_running else R.string.set_helper_failed)
                        }
                    }, style = ButtonStyle.Alt, tiny = true)
                }
                helperMessage?.let { Subtle(it, Modifier.padding(top = 6.dp)) }
                Disclosure(stringResource(R.string.set_helper_adb)) {
                    SelectionContainer {
                        Text("adb shell '${container.helper.adbCommand()}'", fontFamily = P.mono, fontSize = 11.sp, lineHeight = 16.sp, color = P.sub)
                    }
                }
            }
            Gap(10.dp)
            PCard(style = app.umbra.agent.ui.porcelain.CardStyle.Soft) { LayaSection() }

            SectionHead(stringResource(R.string.set_section_about), Modifier.padding(top = 20.dp))
            Text(stringResource(R.string.set_version, context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()), fontSize = 14.sp, color = P.ink)
            Subtle(stringResource(R.string.set_license), Modifier.padding(top = 2.dp))
            Gap(32.dp)
        }
    }

    when (confirm) {
        "stop" -> StopAllConfirm(onDismiss = { confirm = null }, onDone = { accessMessage = it })
        "revoke" -> PConfirm(
            title = stringResource(R.string.set_revoke_title),
            body = stringResource(R.string.set_revoke_body),
            confirmLabel = stringResource(R.string.set_revoke_confirm),
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                scope.launch {
                    container.helper.revoke()
                    accessMessage = resources.getString(R.string.set_revoked)
                }
            },
            extra = {
                listOf(R.string.set_revoke_effect_displays, R.string.set_revoke_effect_shell, R.string.set_revoke_effect_running).forEach {
                    Text("• " + stringResource(it), fontSize = 13.sp, lineHeight = 19.sp, color = P.ink)
                }
            },
        )
        "delete" -> PConfirm(
            title = stringResource(R.string.set_delete_all_title),
            body = stringResource(R.string.set_delete_all_body),
            confirmLabel = stringResource(R.string.action_delete),
            onDismiss = { confirm = null },
            onConfirm = {
                confirm = null
                scope.launch {
                    container.supervisor.deleteAll()
                    accessMessage = resources.getString(R.string.set_delete_all_done)
                }
            },
        )
    }
}

/** A path that works on the development emulator but has not been tried on a real phone (UX report F-04). */
@Composable
fun UnverifiedTag() = Tag(stringResource(R.string.unverified), Tone.Warn)

@Composable
private fun LayaSection() {
    val container = LocalContext.current.container
    val state by container.laya.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.laya.refresh() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.set_laya), Modifier.weight(1f), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = P.ink)
        UnverifiedTag()
    }
    Subtle(stringResource(R.string.set_laya_body), Modifier.padding(top = 4.dp))
    val label = when (state) {
        LayaSidecar.State.NEEDS_LINUX -> stringResource(R.string.laya_needs_linux)
        LayaSidecar.State.NOT_INSTALLED -> stringResource(R.string.laya_not_installed)
        LayaSidecar.State.INSTALLING -> stringResource(R.string.laya_installing)
        LayaSidecar.State.STOPPED -> stringResource(R.string.laya_stopped)
        LayaSidecar.State.STARTING -> stringResource(R.string.laya_starting)
        LayaSidecar.State.RUNNING -> stringResource(R.string.laya_running)
        LayaSidecar.State.FAILED -> stringResource(R.string.laya_failed, container.laya.message ?: stringResource(R.string.laya_see_log))
    }
    Text(label, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = P.ink)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            LayaSidecar.State.NOT_INSTALLED, LayaSidecar.State.FAILED -> PButton(stringResource(R.string.laya_install), { container.laya.install() }, style = ButtonStyle.Alt, tiny = true)
            LayaSidecar.State.STOPPED -> PButton(stringResource(R.string.laya_start), { container.laya.start() }, style = ButtonStyle.Alt, tiny = true)
            LayaSidecar.State.RUNNING -> PButton(stringResource(R.string.laya_stop), { container.laya.stop() }, style = ButtonStyle.Alt, tiny = true)
            else -> Unit
        }
    }
    Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.unverified_detail)) }
}
