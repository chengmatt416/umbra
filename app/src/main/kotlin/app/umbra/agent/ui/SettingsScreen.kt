package app.umbra.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.core.safety.ApprovalMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit, onModels: () -> Unit, onSetup: () -> Unit, onApprovalHistory: () -> Unit, onDiagnostics: () -> Unit,
    onWorkspaces: () -> Unit, onProcesses: () -> Unit, onDisplays: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var helperMessage by remember { mutableStateOf<String?>(null) }
    var accessMessage by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var instructions by remember(prefs.standingInstructions) { mutableStateOf(prefs.standingInstructions) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingLink("Models", prefs.defaultModel?.let { "${it.provider}/${it.modelId}" } ?: "Choose a provider and model", onModels)
            SettingLink("Set up", "Accessibility, root, Linux environment, permissions", onSetup)
            SettingLink("Workspaces", "Browse and share the files tasks produced", onWorkspaces)
            SettingLink("Running now", "Processes and apps the agent started", onProcesses)
            SettingLink("Displays", "Watch the screens the agent works on", onDisplays)
            SettingLink("Diagnostics", "Test that screen control, Linux, displays and the model really work", onDiagnostics)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("When to ask you")
            ApprovalMode.entries.forEach { mode ->
                Row(
                    Modifier.fillMaxWidth().clickable { container.settings.update { it.copy(approvalMode = mode) } }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(selected = prefs.approvalMode == mode, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(
                            when (mode) {
                                ApprovalMode.CAUTIOUS -> "Cautious"
                                ApprovalMode.BALANCED -> "Balanced"
                                ApprovalMode.AUTONOMOUS -> "Autonomous"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            when (mode) {
                                ApprovalMode.CAUTIOUS -> "Ask before anything that changes something, even small things."
                                ApprovalMode.BALANCED -> "Ask before risky actions. Routine steps just happen. Recommended."
                                ApprovalMode.AUTONOMOUS -> "Ask only for the most dangerous actions. Payments, messages, deleting data and security changes always ask."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SettingLink("Approval history", "Everything Umbra asked about and what happened", onApprovalHistory)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Instructions for every task")
            OutlinedTextField(
                instructions, { instructions = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("For example: reply in Chinese; never open banking apps; prefer dark mode.") },
                minLines = 3, maxLines = 8,
            )
            OutlinedButton(onClick = { container.settings.update { it.copy(standingInstructions = instructions.trim()) } }, Modifier.padding(16.dp, 8.dp), enabled = instructions.trim() != prefs.standingInstructions) { Text("Save") }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Notifications")
            SettingSwitch("Approval requests", "Notify me when a task needs a decision.", prefs.notifyOnApproval) { on -> container.settings.update { it.copy(notifyOnApproval = on) } }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SettingSwitch(
                "Capsule",
                "A capsule at the camera shows what agents are doing, answers approvals and opens the composer over any app. Long-press it to select from the screen. Needs the draw-over-apps permission; with Umbra's accessibility service on it sits at the camera itself.",
                prefs.overlayEnabled,
            ) { on ->
                container.settings.update { it.copy(overlayEnabled = on) }
                if (on && !android.provider.Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")))
                }
            }
            SettingSwitch(
                "Live context",
                "Off: only a screen selection you make is sent. On: each message from the Capsule also carries what the app in front shows at that moment (its text, read through the accessibility service; never password fields).",
                prefs.liveContext,
            ) { on -> container.settings.update { it.copy(liveContext = on) } }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Privileged helper")
            Text(
                "A small process with shell-level rights. It lets Umbra work in background displays and change system settings without root.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        helperMessage = if (container.helper.startWithRoot(container.rootShell)) "Helper is running." else "Could not start it with root. Use the adb command below."
                    }
                }) { Text("Start with root") }
            }
            helperMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            Text("Without root, run this once over adb after each reboot:", Modifier.padding(16.dp, 8.dp, 16.dp, 0.dp), style = MaterialTheme.typography.bodySmall)
            SelectionContainer {
                Text(
                    "adb shell '${container.helper.adbCommand()}'",
                    Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Access and data")
            Text(
                "Take back what Umbra can reach, or clear what it kept. Root itself is granted in your root manager; Shizuku in the Shizuku app.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        container.supervisor.stopAll()
                        accessMessage = "Every task was stopped."
                    }
                }) { Text("Stop all tasks") }
                OutlinedButton(onClick = {
                    scope.launch {
                        container.helper.revoke()
                        accessMessage = "The privileged helpers were stopped and forgotten. Background displays and shell-level tools are off until you start one again."
                    }
                }) { Text("Revoke helpers") }
            }
            Row(Modifier.padding(16.dp, 0.dp, 16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val off = runCatching { container.grants.disableAccessibility() }.getOrDefault(false)
                        if (off) accessMessage = "Screen control is off."
                        else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text("Turn off screen control") }
                OutlinedButton(onClick = { confirmDeleteAll = true }) { Text("Delete all history") }
            }
            accessMessage?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("Fast decisions")
            LayaSection()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle("About")
            Text("Umbra ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            Text("Free software under the GNU GPL v3.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(24.dp))
        }
    }

    if (confirmDeleteAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all history?") },
            text = { Text("Every task is stopped and deleted with its timeline and its private files. Workspaces and the Linux environment stay.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteAll = false
                    scope.launch {
                        container.supervisor.deleteAll()
                        accessMessage = "All task history was deleted."
                    }
                }) { Text("Delete") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingLink(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LayaSection() {
    val container = LocalContext.current.container
    val state by container.laya.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { container.laya.refresh() }
    Text(
        "Laya is a small model that answers quick either/or questions (which button, did that work) in a fraction of a second, so the agent's main model is used for real thinking. Optional: without it, built-in rules decide small things.",
        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val label = when (state) {
        app.umbra.agent.data.LayaSidecar.State.NEEDS_LINUX -> "Needs the Linux environment first."
        app.umbra.agent.data.LayaSidecar.State.NOT_INSTALLED -> "Not installed. Downloads about 2 GB (the model and its runtime)."
        app.umbra.agent.data.LayaSidecar.State.INSTALLING -> "Installing… this takes a while."
        app.umbra.agent.data.LayaSidecar.State.STOPPED -> "Installed, not running."
        app.umbra.agent.data.LayaSidecar.State.STARTING -> "Starting… the first start downloads the model."
        app.umbra.agent.data.LayaSidecar.State.RUNNING -> "Running. Quick decisions use Laya."
        app.umbra.agent.data.LayaSidecar.State.FAILED -> "Failed: ${container.laya.message ?: "see the log"}"
    }
    Text(label, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            app.umbra.agent.data.LayaSidecar.State.NOT_INSTALLED, app.umbra.agent.data.LayaSidecar.State.FAILED -> OutlinedButton(onClick = { container.laya.install() }, enabled = true) { Text("Install") }
            app.umbra.agent.data.LayaSidecar.State.STOPPED -> OutlinedButton(onClick = { container.laya.start() }) { Text("Start") }
            app.umbra.agent.data.LayaSidecar.State.RUNNING -> OutlinedButton(onClick = { container.laya.stop() }) { Text("Stop") }
            else -> Unit
        }
    }
}
