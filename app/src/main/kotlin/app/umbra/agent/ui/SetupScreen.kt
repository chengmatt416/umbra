package app.umbra.agent.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onBack: () -> Unit, onModels: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val probe = remember { CapabilityProbe(container) }
    var rows by remember { mutableStateOf<List<CapabilityRow>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val guestState by container.guest.state.collectAsStateWithLifecycle()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()

    LaunchedEffect(tick, guestState, prefs) {
        softly("the set-up check") {
            container.guest.refresh()
            rows = probe.probe()
            container.capabilities.refresh()
        }
    }
    // Settings screens change things behind our back: look again while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            tick++
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            container.guest.installFrom(uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Check again") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(
                    "Umbra needs a few things before it can work on your phone. Items marked required must be green.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(rows, key = { it.id }) { row ->
                CapabilityCard(row) { action ->
                    scope.launch {
                        when (action) {
                            CapabilityAction.INSTALL_LINUX -> Unit // the install section below
                            CapabilityAction.ADD_PROVIDER -> onModels()
                            else -> probe.perform(action)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                        tick++
                    }
                }
            }
            item { StrategyCard(container) }
            item { PackList(container.guest) }
            item { LinuxInstaller(container.guest, guestState, prefs.rootfsUrl, onUrl = { url -> container.settings.update { it.copy(rootfsUrl = url) } }, onPick = { picker.launch(arrayOf("*/*")) }) }
        }
    }
}

@Composable
private fun CapabilityCard(row: CapabilityRow, onAction: (CapabilityAction) -> Unit) {
    val color = when (row.status) {
        CapabilityStatus.OK -> Semantic.ok
        CapabilityStatus.WARN -> Semantic.warn
        CapabilityStatus.MISSING -> if (row.essential) Semantic.danger else Semantic.warn
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (row.essential) Text("  required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            row.action?.let { action ->
                if (row.status != CapabilityStatus.OK) OutlinedButton(onClick = { onAction(action) }) { Text(action.label) }
            }
        }
    }
}

@Composable
private fun LinuxInstaller(
    guest: GuestManager,
    state: GuestManager.State,
    url: String,
    onUrl: (String) -> Unit,
    onPick: () -> Unit,
) {
    val bundle = guest.bundle
    var otherSources by remember { mutableStateOf(bundle == null) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Linux environment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            when (state) {
                is GuestManager.State.Downloading -> Progress("Downloading", state.bytes, state.total)
                is GuestManager.State.Installing -> Progress("Installing", state.bytes, state.total)
                GuestManager.State.Ready, GuestManager.State.Installed -> {
                    Text("Installed and ready.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (guest.mode == app.umbra.runtime.linux.LinuxMode.PROOT) "Runs without root (proot): slower, and not kept apart from the app the way the root mode is."
                        else "Runs with root (chroot): the agent works without privileges and cannot change the system part.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (guest.updateAvailable) {
                        Banner("This version of the app brings a newer Linux environment. Updating keeps your workspaces, settings and sessions, and puts your add-ons back.")
                        Button(onClick = { guest.updateBundled() }) { Text("Update") }
                    }
                    TextButton(onClick = { guest.uninstall() }) { Text("Remove (keeps your workspaces)") }
                }
                GuestManager.State.NoRoot -> Text("Neither root nor proot can run it on this phone.", style = MaterialTheme.typography.bodyMedium)
                GuestManager.State.Checking -> Text("Checking…", style = MaterialTheme.typography.bodyMedium)
                else -> {
                    if (state is GuestManager.State.Failed) Banner(state.message, tone = Semantic.danger)
                    if (bundle != null) {
                        Text(
                            "Debian with bash, git, Python, Bun and omp. It comes with the app: nothing to download. " +
                                "About ${bundle.rootfsBytes * 6 / (1024 * 1024)} MB on the phone." +
                                if (guest.mode == app.umbra.runtime.linux.LinuxMode.PROOT) " This phone gives the app no root, so it will run under proot." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { guest.installBundled() }) { Text("Install") }
                        TextButton(onClick = { otherSources = !otherSources }) { Text(if (otherSources) "Hide other sources" else "Other sources") }
                    }
                    if (otherSources) {
                        Text(
                            "An archive built with runtime-build/rootfs/build.sh, from a file or an https address.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(url, onUrl, Modifier.fillMaxWidth(), label = { Text("Archive URL (https)") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { guest.downloadAndInstall(url) }, enabled = url.startsWith("https://")) { Text("Download") }
                            OutlinedButton(onClick = onPick) { Text("Choose file…") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(label: String, bytes: Long, total: Long) {
    Column {
        Text(
            if (total > 0) "$label… ${bytes / (1024 * 1024)} of ${total / (1024 * 1024)} MB" else "$label… ${bytes / (1024 * 1024)} MB",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (total > 0) LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp))
        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun PackList(guest: GuestManager) {
    val state by guest.state.collectAsStateWithLifecycle()
    val packs by guest.packStates.collectAsStateWithLifecycle()
    if (state != GuestManager.State.Ready && state != GuestManager.State.Installed) return
    val missing = guest.packs.filter { (packs[it.id] ?: GuestManager.PackState.Absent) !is GuestManager.PackState.Installed && packs[it.id] !is GuestManager.PackState.Installing }
    val busy = packs.values.any { it is GuestManager.PackState.Installing }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add-ons for the Linux environment", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (guest.bundle != null) "They come with the app and install without a network; space is used only for the ones you add."
                else "They install from Debian's servers (this build of the app does not carry them).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            guest.packs.forEach { pack ->
                val packState = packs[pack.id] ?: GuestManager.PackState.Absent
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pack.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            when (packState) {
                                is GuestManager.PackState.Failed -> packState.message
                                is GuestManager.PackState.Installing -> packState.step
                                else -> "${pack.detail}. About ${pack.diskMb} MB."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (packState is GuestManager.PackState.Failed) Semantic.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when (packState) {
                        GuestManager.PackState.Installed -> Text("Installed", style = MaterialTheme.typography.labelMedium, color = Semantic.ok)
                        is GuestManager.PackState.Installing -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else -> OutlinedButton(onClick = { guest.installPack(pack.id) }, enabled = !busy) { Text("Install") }
                    }
                }
            }
            if (missing.size > 1 && !busy) {
                Button(onClick = { guest.installPacks(missing.map { it.id }) }) { Text("Install all (about ${missing.sumOf { it.diskMb } / 100 / 10.0} GB)") }
            }
        }
    }
}

/** The choices the agent runs with on this phone, and why (spec §29 strategy selection). */
@Composable
private fun StrategyCard(container: app.umbra.agent.AppContainer) {
    val strategy by container.capabilities.strategy.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("How Umbra will work here", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Screen control: " + (strategy.uiBackend?.name?.lowercase()?.replace('_', ' ') ?: "none"), style = MaterialTheme.typography.bodySmall)
            Text("Background work: " + if (strategy.displayMode == app.umbra.core.model.capability.DisplayMode.BACKGROUND_VIRTUAL) "in a hidden display" else "on the phone screen", style = MaterialTheme.typography.bodySmall)
            Text("Linux: " + strategy.linuxProvider.name.lowercase().replace('_', ' ') + if (strategy.guiProvider != app.umbra.core.model.capability.GuiProviderKind.NONE) ", desktop ${strategy.guiProvider.name.lowercase()}" else "", style = MaterialTheme.typography.bodySmall)
            Text("Quick decisions: " + strategy.decisionBackend.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.bodySmall)
            strategy.notes.forEach { Text("• $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
