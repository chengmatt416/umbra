package app.umbra.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.LayaSidecar
import app.umbra.agent.update.AppUpdater
import app.umbra.agent.ui.emergency.StopAllConfirm
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
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
fun MoreSettingsScreen(onBack: () -> Unit, onGo: (app.umbra.agent.ui.shell.Dest) -> Unit) {
    val onModels = { onGo(app.umbra.agent.ui.shell.Dest.Accounts) }
    val onSetup = { onGo(app.umbra.agent.ui.shell.Dest.Linux) }
    val onApprovalHistory = { onGo(app.umbra.agent.ui.shell.Dest.ApprovalHistory) }
    val onDiagnostics = { onGo(app.umbra.agent.ui.shell.Dest.Diagnostics) }
    val onWorkspaces = { onGo(app.umbra.agent.ui.shell.Dest.Workspaces) }
    val onProcesses = { onGo(app.umbra.agent.ui.shell.Dest.Processes) }
    val onDisplays = { onGo(app.umbra.agent.ui.shell.Dest.Displays) }
    val onAppearance = { onGo(app.umbra.agent.ui.shell.Dest.Appearance) }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var helperMessage by remember { mutableStateOf<String?>(null) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var instructions by remember(prefs.standingInstructions) { mutableStateOf(prefs.standingInstructions) }

    PorcelainPage(stringResource(R.string.r_set_more), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
            PCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                PLink(stringResource(R.string.dest_models), prefs.defaultModel?.let { "${it.provider}/${it.modelId}" } ?: stringResource(R.string.set_models_none), icon = PIcons.spark, onClick = onModels)
                PLink(stringResource(R.string.dest_linux), stringResource(R.string.set_setup_summary), icon = PIcons.check, onClick = onSetup)
                PLink(stringResource(R.string.dest_workspaces), stringResource(R.string.set_workspaces_summary), icon = PIcons.folder, onClick = onWorkspaces)
                PLink(stringResource(R.string.dest_processes), stringResource(R.string.set_processes_summary), icon = PIcons.activity, onClick = onProcesses)
                PLink(stringResource(R.string.dest_displays), stringResource(R.string.set_displays_summary), icon = PIcons.monitor, onClick = onDisplays)
                PLink(stringResource(R.string.dest_diagnostics), stringResource(R.string.set_diagnostics_summary), icon = PIcons.info, onClick = onDiagnostics)
                PLink(stringResource(R.string.dest_appearance), stringResource(when (prefs.theme.style) { app.umbra.agent.data.ThemeStyle.LIQUID -> R.string.theme_liquid; app.umbra.agent.data.ThemeStyle.APPLE -> R.string.theme_apple; app.umbra.agent.data.ThemeStyle.MONO -> R.string.theme_mono; else -> R.string.theme_porcelain }), icon = PIcons.drop, onClick = onAppearance)
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
            if (prefs.commandApprovals.isNotEmpty()) Disclosure(stringResource(R.string.appr_saved_prefixes)) {
                prefs.commandApprovals.forEach { rule ->
                    PButton(stringResource(R.string.appr_revoke_prefix, rule.commandPrefix.orEmpty()), {
                        container.settings.update { it.copy(commandApprovals = it.commandApprovals.filterNot { r -> r.id == rule.id }) }
                    }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
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

            SectionHead(stringResource(R.string.set_section_about), Modifier.padding(top = 20.dp))
            UpdateSection()
            Subtle(stringResource(R.string.set_license), Modifier.padding(top = 10.dp))
            LegalSection()
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

/** What the updater is doing, in one sentence: the Updates row in Settings and the update section say the same. */
@Composable
internal fun updateStatusLine(state: AppUpdater.State, prefs: app.umbra.agent.data.AppPrefs): String {
    val context = LocalContext.current
    val mib = { bytes: Long -> "${bytes / (1024 * 1024)} MiB" }
    return when (val s = state) {
        AppUpdater.State.Idle -> stringResource(R.string.upd_idle)
        AppUpdater.State.Checking -> stringResource(R.string.upd_checking)
        is AppUpdater.State.UpToDate -> stringResource(R.string.upd_up_to_date, android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(s.checkedAtMs)))
        is AppUpdater.State.Available -> stringResource(R.string.upd_available, s.update.version, mib(s.update.bytes)) +
            if (prefs.autoUpdate && prefs.updateOnWifiOnly) " " + stringResource(R.string.upd_available_wifi) else ""
        is AppUpdater.State.Downloading -> stringResource(R.string.upd_downloading, s.update.version, (s.bytes * 100 / s.update.bytes.coerceAtLeast(1)).toInt())
        is AppUpdater.State.Ready -> stringResource(R.string.upd_ready, s.update.version) + " " + stringResource(if (prefs.autoUpdate) R.string.upd_ready_when else R.string.upd_ready_manual)
        is AppUpdater.State.Installing -> stringResource(R.string.upd_installing, s.update.version)
        is AppUpdater.State.Confirm -> stringResource(R.string.upd_confirm, s.update.version)
        is AppUpdater.State.Failed -> stringResource(R.string.upd_failed, s.message)
    }
}

/** The installed version, what the updater is doing, and its two switches. */
@Composable
internal fun UpdateSection() {
    val context = LocalContext.current
    val container = context.container
    val updater = container.updater
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val state by updater.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.set_version, updater.installedVersionName), fontSize = 14.sp, color = P.ink)
    val line = updateStatusLine(state, prefs)
    Subtle(line, Modifier.padding(top = 2.dp))
    (state as? AppUpdater.State.Downloading)?.let { PBar(it.bytes.toFloat() / it.update.bytes.coerceAtLeast(1), Modifier.padding(top = 8.dp).fillMaxWidth()) }
    Box(Modifier.padding(top = 10.dp)) {
        when (state) {
            is AppUpdater.State.Available -> PButton(stringResource(R.string.upd_download_now), { updater.checkNow() }, style = ButtonStyle.Alt, tiny = true)
            is AppUpdater.State.Ready, is AppUpdater.State.Confirm -> PButton(stringResource(R.string.upd_install_now), { updater.installNow() }, tiny = true)
            AppUpdater.State.Checking, is AppUpdater.State.Downloading, is AppUpdater.State.Installing -> Unit
            else -> PButton(stringResource(R.string.upd_check_now), { updater.checkNow() }, style = ButtonStyle.Alt, tiny = true)
        }
    }
    PToggle(stringResource(R.string.set_updates_auto), stringResource(R.string.set_updates_auto_body), prefs.autoUpdate, { on -> container.settings.update { it.copy(autoUpdate = on) } }, Modifier.padding(top = 6.dp))
    PToggle(stringResource(R.string.set_updates_wifi), stringResource(R.string.set_updates_wifi_body), prefs.updateOnWifiOnly, { on -> container.settings.update { it.copy(updateOnWifiOnly = on) } }, divider = false)
    Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.upd_detail)) }
}

/** A path that works on the development emulator but has not been tried on a real phone (UX report F-04). */
@Composable
fun UnverifiedTag() = Tag(stringResource(R.string.unverified), Tone.Warn)

private fun layaSize(bytes: Long): String =
    if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes shr 20} MB"

@Composable
private fun layaBackend(backend: String?) = stringResource(when (backend) {
    "npu" -> R.string.laya_backend_npu
    "gpu" -> R.string.laya_backend_gpu
    else -> R.string.laya_backend_cpu
})

@Composable
internal fun LayaSection() {
    val container = LocalContext.current.container
    val state by container.laya.state.collectAsStateWithLifecycle()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.laya.refresh() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.set_laya), Modifier.weight(1f), fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = P.ink)
        UnverifiedTag()
    }
    Subtle(stringResource(R.string.set_laya_body), Modifier.padding(top = 4.dp))
    val backend by container.laya.backend.collectAsStateWithLifecycle()
    val progress by container.laya.progress.collectAsStateWithLifecycle()
    val label = when (state) {
        LayaSidecar.State.NEEDS_LINUX, LayaSidecar.State.NOT_INSTALLED ->
            stringResource(R.string.laya_not_installed, layaSize(container.laya.downloadSize()))
        LayaSidecar.State.INSTALLING -> stringResource(R.string.laya_installing,
            progress?.let { stringResource(R.string.laya_progress, layaSize(it.have), layaSize(it.total)) }.orEmpty())
        LayaSidecar.State.STOPPED -> stringResource(R.string.laya_stopped)
        LayaSidecar.State.STARTING -> stringResource(R.string.laya_starting)
        LayaSidecar.State.RUNNING -> stringResource(R.string.laya_running, layaBackend(backend))
        LayaSidecar.State.FAILED -> stringResource(R.string.laya_failed, container.laya.message ?: stringResource(R.string.laya_see_log))
    }
    Text(label, Modifier.padding(top = 8.dp), fontSize = 13.sp, color = P.ink)
    LayaProcessors(state, backend)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            LayaSidecar.State.NEEDS_LINUX, LayaSidecar.State.NOT_INSTALLED, LayaSidecar.State.FAILED ->
                PButton(stringResource(R.string.laya_install), { container.laya.install() }, style = ButtonStyle.Alt, tiny = true)
            LayaSidecar.State.STOPPED -> PButton(stringResource(R.string.laya_start), { container.laya.start() }, style = ButtonStyle.Alt, tiny = true)
            LayaSidecar.State.RUNNING -> PButton(stringResource(R.string.laya_stop), { container.laya.stop() }, style = ButtonStyle.Alt, tiny = true)
            else -> Unit
        }
        if (state != LayaSidecar.State.RUNNING && prefs.layaWanted) {
            PButton(stringResource(R.string.laya_stop), { container.laya.stop() }, style = ButtonStyle.Alt, tiny = true)
        }
        if (state == LayaSidecar.State.STOPPED || (state == LayaSidecar.State.FAILED && !prefs.layaWanted)) {
            PButton(stringResource(R.string.laya_remove), { container.laya.remove() }, style = ButtonStyle.Ghost, tiny = true)
        }
    }
    Disclosure(stringResource(R.string.details)) { Subtle(stringResource(R.string.unverified_detail)) }
}

/**
 * NPU, GPU and CPU, always shown: the one Laya answers on is filled in, and each says why it is or is not used (not on
 * this phone, no build for this chip, failed its check and how, skipped for memory). Kept across starts, so it is there
 * while Laya is stopped or after it failed too.
 */
@Composable
private fun LayaProcessors(state: LayaSidecar.State, backend: String?) {
    val container = LocalContext.current.container
    val processors = remember(state, backend) { container.laya.processors() }
    if (processors.isEmpty()) return
    val chip = remember { container.laya.chip() }
    Text(stringResource(R.string.laya_processor) + (chip?.let { " · $it" }.orEmpty()), Modifier.padding(top = 12.dp), fontSize = 12.sp, color = P.sub)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in processors) {
            val inUse = p.status == app.umbra.agent.data.laya.LayaNative.Processor.Status.IN_USE
            val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
            Column(
                Modifier.weight(1f).clip(shape)
                    .background(if (inUse) P.ink else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (inUse) P.ink else P.line, shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(processorDot(p.status, inUse)))
                    Text(layaBackend(p.backend.key), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = if (inUse) P.base else P.ink)
                }
                Text(stringResource(processorWord(p.status)), Modifier.padding(top = 2.dp), fontSize = 11.sp, color = if (inUse) P.base.copy(alpha = 0.8f) else P.sub, maxLines = 1)
            }
        }
    }
    // Why each one is or is not used, in a line each.
    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (p in processors) p.detail?.takeIf { it.isNotBlank() }?.let { Subtle("${layaBackend(p.backend.key)}: $it") }
    }
    if (processors.any { it.status == app.umbra.agent.data.laya.LayaNative.Processor.Status.REFUSED }) {
        PButton(stringResource(R.string.laya_retry_processors), { container.laya.retryProcessors() }, Modifier.padding(top = 6.dp), style = ButtonStyle.Ghost, tiny = true)
    }
}

private fun processorWord(status: app.umbra.agent.data.laya.LayaNative.Processor.Status): Int = when (status) {
    app.umbra.agent.data.laya.LayaNative.Processor.Status.IN_USE -> R.string.laya_proc_in_use
    app.umbra.agent.data.laya.LayaNative.Processor.Status.READY -> R.string.laya_proc_ready
    app.umbra.agent.data.laya.LayaNative.Processor.Status.REFUSED -> R.string.laya_proc_failed
    app.umbra.agent.data.laya.LayaNative.Processor.Status.SKIPPED -> R.string.laya_proc_skipped
    app.umbra.agent.data.laya.LayaNative.Processor.Status.UNAVAILABLE -> R.string.laya_proc_unavailable
    app.umbra.agent.data.laya.LayaNative.Processor.Status.UNKNOWN -> R.string.laya_proc_unknown
}

private fun processorDot(status: app.umbra.agent.data.laya.LayaNative.Processor.Status, inUse: Boolean) = when (status) {
    app.umbra.agent.data.laya.LayaNative.Processor.Status.IN_USE -> P.good
    app.umbra.agent.data.laya.LayaNative.Processor.Status.READY -> P.good.copy(alpha = 0.55f)
    app.umbra.agent.data.laya.LayaNative.Processor.Status.REFUSED -> P.danger
    app.umbra.agent.data.laya.LayaNative.Processor.Status.SKIPPED -> P.amber
    else -> if (inUse) P.base else P.line2
}
