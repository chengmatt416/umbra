package app.umbra.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.GuestManager
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.runtime.linux.LinuxMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-run set-up (UX report X-01): the three things Umbra cannot work without, as numbered steps with their own
 * buttons, then everything optional folded under one line. Umbra chooses chroot or proot by itself; the choice is
 * explained inside the Linux step's details, not asked.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val act: (CapabilityAction) -> Unit = { action ->
        scope.launch {
            when (action) {
                CapabilityAction.INSTALL_LINUX -> container.guest.installBundled()
                CapabilityAction.ADD_PROVIDER -> onModels()
                else -> probe.perform(action)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            tick++
        }
    }

    val essentials = listOf("accessibility", "linux", "model").mapNotNull { id -> rows.firstOrNull { it.id == id } }
    val ready = essentials.count { it.status == CapabilityStatus.OK }
    val optional = rows.filter { !it.essential }

    PorcelainPage(
        stringResource(R.string.dest_linux), onBack,
        actions = { PIconButton(PIcons.refresh, stringResource(R.string.action_check_again), { tick++ }, size = 36.dp) },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Eyebrow(stringResource(R.string.setup_eyebrow))
                SerifTitle(stringResource(R.string.setup_title), Modifier.padding(top = 8.dp), size = 28)
                if (essentials.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PBar(ready / essentials.size.toFloat(), Modifier.weight(1f))
                        Text(stringResource(R.string.setup_progress, ready, essentials.size), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.sub)
                    }
                    Subtle(stringResource(if (ready == essentials.size) R.string.setup_ready else R.string.setup_required_note), Modifier.padding(top = 6.dp))
                }
            }
            essentials.forEachIndexed { index, row ->
                item(key = row.id) {
                    StepCard(index + 1, row, onAction = act) {
                        when (row.id) {
                            "accessibility" -> if (row.status != CapabilityStatus.OK) RestrictedSteps(row.restricted)
                            "linux" -> LinuxStep(container.guest, guestState, prefs.rootfsUrl, onUrl = { url -> container.settings.update { it.copy(rootfsUrl = url) } }, onPick = { picker.launch(arrayOf("*/*")) })
                        }
                    }
                }
            }
            item(key = "packs") { PackList(container.guest) }
            item(key = "advanced") {
                PCard(style = CardStyle.Soft) {
                    Text(stringResource(R.string.setup_advanced), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                    Subtle(stringResource(R.string.setup_advanced_body), Modifier.padding(top = 4.dp))
                    Disclosure(stringResource(R.string.details)) {
                        optional.forEachIndexed { i, row -> OptionalRow(row, divider = i < optional.lastIndex, onAction = act) }
                        Gap(8.dp)
                        StrategyCard(container)
                    }
                }
            }
        }
    }
}

/** One required item: its number (a check when done), what it is for, and the button that does it. */
@Composable
private fun StepCard(number: Int, row: CapabilityRow, onAction: (CapabilityAction) -> Unit, extra: @Composable () -> Unit) {
    val done = row.status == CapabilityStatus.OK
    PCard(style = if (done) CardStyle.Soft else CardStyle.Ceramic) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (done) Color(0xFFE5F0DF) else Color(0xFFDCEBD6)).border(1.dp, if (done) Color(0xFFB6CCB0) else Color(0xFF82A185), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) PIcon(PIcons.check, size = 14.dp, tint = Color(0xFF57835A))
                else Text("$number", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = P.accent2)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(row.title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                    if (done) Tag(stringResource(R.string.setup_done), Tone.Good)
                }
                Text(row.detail, Modifier.padding(top = 3.dp), fontSize = 13.sp, lineHeight = 19.sp, color = if (row.status == CapabilityStatus.MISSING) P.sub else P.muted)
                // The Linux step carries its own install control; the others get their action here.
                if (!done && row.id != "linux") row.action?.let { action ->
                    Box(Modifier.padding(top = 10.dp)) { PButton(stringResource(action.label), { onAction(action) }, tiny = true) }
                }
            }
        }
        extra()
    }
}

/**
 * Android 13+ greys out the accessibility switch of a sideloaded app until "restricted settings" is allowed (UX
 * report S-01). When Android says so, the three steps are shown open; otherwise they wait behind one line.
 */
@Composable
private fun RestrictedSteps(restriction: Restriction) {
    val context = LocalContext.current
    val steps: @Composable () -> Unit = {
        Subtle(stringResource(R.string.restricted_body), Modifier.padding(top = 4.dp, bottom = 6.dp))
        StepLine(1, stringResource(R.string.restricted_step1)) {
            PButton(stringResource(R.string.restricted_open_info), {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, style = ButtonStyle.Alt, tiny = true)
        }
        StepLine(2, stringResource(R.string.restricted_step2)) {}
        StepLine(3, stringResource(R.string.restricted_step3)) {
            PButton(stringResource(R.string.cap_act_open_settings), {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, style = ButtonStyle.Alt, tiny = true)
        }
    }
    if (restriction != Restriction.NONE) {
        Gap(12.dp)
        Callout(stringResource(if (restriction == Restriction.HELD) R.string.restricted_detected else R.string.restricted_likely), Tone.Warn)
        steps()
    } else {
        Disclosure(stringResource(R.string.restricted_title)) { steps() }
    }
}

@Composable
private fun StepLine(n: Int, text: String, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFFE6EFE1)), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = P.accent2)
        }
        Text(text, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = P.ink)
        action()
    }
}

/** An optional capability: one line of state, its action if it has one, and a mark when it is unverified on phones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionalRow(row: CapabilityRow, divider: Boolean, onAction: (CapabilityAction) -> Unit) {
    PSetting(row.title, row.detail, divider = divider) {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.unverified) UnverifiedTag()
            when {
                row.status == CapabilityStatus.OK -> Tag(stringResource(R.string.setup_done), Tone.Good)
                row.action != null -> PButton(stringResource(row.action.label), { onAction(row.action) }, style = ButtonStyle.Alt, tiny = true)
                else -> Tag(stringResource(R.string.setup_not_set), Tone.Neutral)
            }
        }
    }
}

/** The Linux step's body: progress, install from the app (or another source), update, and a confirmed removal. */
@Composable
private fun LinuxStep(guest: GuestManager, state: GuestManager.State, url: String, onUrl: (String) -> Unit, onPick: () -> Unit) {
    val context = LocalContext.current
    val bundle = guest.bundle
    var otherSources by rememberSaveable { mutableStateOf(bundle == null) }
    var confirmRemove by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is GuestManager.State.Downloading -> Progress(R.string.linux_downloading, R.string.linux_downloading_open, state.bytes, state.total)
            is GuestManager.State.Installing -> Progress(R.string.linux_installing, R.string.linux_installing_open, state.bytes, state.total)
            GuestManager.State.Ready, GuestManager.State.Installed -> {
                if (guest.updateAvailable) {
                    Callout(stringResource(R.string.linux_update_body), Tone.Neutral)
                    PButton(stringResource(R.string.linux_update), { guest.updateBundled() }, tiny = true)
                }
                Disclosure(stringResource(R.string.details)) {
                    Subtle(stringResource(if (guest.mode == LinuxMode.PROOT) R.string.linux_mode_proot else R.string.linux_mode_chroot))
                    Box(Modifier.padding(top = 10.dp)) {
                        PButton(stringResource(R.string.linux_remove), { confirmRemove = true }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.trash)
                    }
                }
            }
            GuestManager.State.NoRoot, GuestManager.State.Checking -> Unit
            else -> {
                if (state is GuestManager.State.Failed) Callout(state.message, Tone.Danger)
                if (bundle != null) {
                    Subtle(stringResource(R.string.linux_bundled_body, (bundle.rootfsBytes * 6 / (1024 * 1024)).toInt()))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton(stringResource(R.string.linux_install), { guest.installBundled() }, tiny = true, icon = PIcons.download)
                        PButton(stringResource(if (otherSources) R.string.linux_hide_sources else R.string.linux_other_sources), { otherSources = !otherSources }, style = ButtonStyle.Ghost, tiny = true)
                    }
                }
                if (otherSources) {
                    Subtle(stringResource(R.string.linux_sources_body))
                    PField(url, onUrl, label = stringResource(R.string.linux_url), singleLine = true, mono = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton(stringResource(R.string.linux_download), { guest.downloadAndInstall(url) }, style = ButtonStyle.Alt, tiny = true, enabled = url.startsWith("https://"))
                        PButton(stringResource(R.string.linux_choose_file), onPick, style = ButtonStyle.Alt, tiny = true)
                    }
                }
            }
        }
    }
    if (confirmRemove) {
        // The image unpacks to roughly six times the archive; without the bundle's figure, say what it usually is.
        val size = bundle?.let { Formatter.formatShortFileSize(context, it.rootfsBytes * 6) } ?: "2 GB"
        PConfirm(
            title = stringResource(R.string.linux_remove_title),
            body = stringResource(R.string.linux_remove_body, size),
            confirmLabel = stringResource(R.string.linux_remove_confirm),
            onDismiss = { confirmRemove = false },
            onConfirm = { confirmRemove = false; guest.uninstall() },
            extra = { Subtle(stringResource(if (bundle != null) R.string.linux_remove_reinstall else R.string.linux_remove_reinstall_url)) },
        )
    }
}

@Composable
private fun Progress(known: Int, open: Int, bytes: Long, total: Long) {
    val mb = (bytes / (1024 * 1024)).toInt()
    Column {
        Text(if (total > 0) stringResource(known, mb, (total / (1024 * 1024)).toInt()) else stringResource(open, mb), fontSize = 13.sp, color = P.ink)
        Box(Modifier.padding(top = 8.dp)) { PBar(if (total > 0) bytes.toFloat() / total else 0.05f) }
    }
}

@Composable
private fun PackList(guest: GuestManager) {
    val state by guest.state.collectAsStateWithLifecycle()
    val packs by guest.packStates.collectAsStateWithLifecycle()
    if (state != GuestManager.State.Ready && state != GuestManager.State.Installed) return
    val missing = guest.packs.filter { (packs[it.id] ?: GuestManager.PackState.Absent) !is GuestManager.PackState.Installed && packs[it.id] !is GuestManager.PackState.Installing }
    val busy = packs.values.any { it is GuestManager.PackState.Installing }
    PCard(style = CardStyle.Soft) {
        Text(stringResource(R.string.packs_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
        Subtle(stringResource(if (guest.bundle != null) R.string.packs_bundled else R.string.packs_network), Modifier.padding(top = 4.dp, bottom = 4.dp))
        guest.packs.forEachIndexed { i, pack ->
            val packState = packs[pack.id] ?: GuestManager.PackState.Absent
            val names = PACK_NAMES[pack.id]
            PSetting(
                names?.let { stringResource(it.first) } ?: pack.title,
                when (packState) {
                    is GuestManager.PackState.Failed -> packState.message
                    is GuestManager.PackState.Installing -> packState.step
                    else -> stringResource(R.string.packs_size, names?.let { stringResource(it.second) } ?: pack.detail, pack.diskMb)
                },
                divider = i < guest.packs.lastIndex,
            ) {
                when (packState) {
                    GuestManager.PackState.Installed -> Tag(stringResource(R.string.packs_installed), Tone.Good)
                    is GuestManager.PackState.Installing -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = P.accent2)
                    else -> PButton(stringResource(R.string.packs_install), { guest.installPack(pack.id) }, style = ButtonStyle.Alt, tiny = true, enabled = !busy)
                }
            }
        }
        if (missing.size > 1 && !busy) {
            Box(Modifier.padding(top = 8.dp)) {
                PButton(stringResource(R.string.packs_install_all, "%.1f".format(missing.sumOf { it.diskMb } / 1000.0)), { guest.installPacks(missing.map { it.id }) }, tiny = true)
            }
        }
    }
}

/** The add-ons' names and descriptions in the user's language, by pack id (GuestManager keeps the English ones). */
private val PACK_NAMES = mapOf(
    "dev" to (R.string.pack_dev to R.string.pack_dev_d),
    "java" to (R.string.pack_java to R.string.pack_java_d),
    "android" to (R.string.pack_android to R.string.pack_android_d),
    "node" to (R.string.pack_node to R.string.pack_node_d),
    "gui" to (R.string.pack_gui to R.string.pack_gui_d),
)

/** The choices the agent runs with on this phone, and why (spec §29 strategy selection). */
@Composable
private fun StrategyCard(container: app.umbra.agent.AppContainer) {
    val strategy by container.capabilities.strategy.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.strategy_title), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
        Subtle(stringResource(R.string.strategy_screen, strategy.uiBackend?.name?.lowercase()?.replace('_', ' ') ?: stringResource(R.string.strategy_none)))
        Subtle(stringResource(if (strategy.displayMode == app.umbra.core.model.capability.DisplayMode.BACKGROUND_VIRTUAL) R.string.strategy_background_hidden else R.string.strategy_background_screen))
        Subtle(stringResource(R.string.strategy_linux, strategy.linuxProvider.name.lowercase().replace('_', ' ') + if (strategy.guiProvider != app.umbra.core.model.capability.GuiProviderKind.NONE) stringResource(R.string.strategy_desktop, strategy.guiProvider.name.lowercase()) else ""))
        Subtle(stringResource(R.string.strategy_decisions, strategy.decisionBackend.name.lowercase().replace('_', ' ')))
        strategy.notes.forEach { Subtle("• $it") }
    }
}
