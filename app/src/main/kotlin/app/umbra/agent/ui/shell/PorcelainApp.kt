package app.umbra.agent.ui.shell

import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.GlassKind
import app.umbra.agent.ui.porcelain.OverlayHost
import app.umbra.agent.ui.porcelain.OverlayScrim
import app.umbra.agent.ui.porcelain.GlassHost
import app.umbra.agent.ui.porcelain.LocalGlassHost
import app.umbra.agent.ui.porcelain.LiquidLightSource
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import app.umbra.agent.ui.porcelain.GooeyDrop
import app.umbra.agent.ui.porcelain.LiquidBackdrop
import app.umbra.agent.ui.porcelain.PageBackdrop
import app.umbra.agent.ui.porcelain.Theme
import app.umbra.agent.ui.porcelain.hsl
import app.umbra.agent.ui.porcelain.jelly
import app.umbra.agent.ui.porcelain.liquidGlass
import app.umbra.agent.ui.porcelain.liquidPress
import app.umbra.agent.ui.porcelain.rememberPress
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInParent
import app.umbra.agent.ui.sessionsFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.ApprovalHistoryScreen
import app.umbra.agent.ui.DiagnosticsScreen
import app.umbra.agent.ui.DisplaysScreen
import app.umbra.agent.ui.ModelsScreen
import app.umbra.agent.ui.ProcessesScreen
import app.umbra.agent.ui.SetupScreen
import app.umbra.agent.ui.WorkspaceBrowserScreen
import app.umbra.agent.ui.WorkspacesScreen
import app.umbra.agent.ui.current.CurrentScreen
import app.umbra.agent.ui.home.HomeScreen
import app.umbra.agent.ui.inbox.InboxScreen
import app.umbra.agent.ui.porcelain.BadgeIcon
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIconButton
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.hasCjk
import app.umbra.agent.ui.sessions.SessionsScreen
import kotlinx.coroutines.launch

/** A place in the app. [group] files the original pages under the Developer hub (Dev mode). */
enum class Dest(val route: String, @param:StringRes val title: Int, val icon: IconSpec, @param:StringRes val group: Int? = null) {
    Home("home", R.string.dest_home, PIcons.home),
    Current("current", R.string.dest_current, PIcons.activity),
    Accounts("accounts", R.string.r_acc_title, PIcons.key),
    Messages("messages", R.string.ag_message_apps, PIcons.message),
    Notifications("notifications", R.string.ag_ch_notifications, PIcons.bell),
    Screens("screens", R.string.r_screens_title, PIcons.monitor),
    Safety("safety", R.string.r_safety_title, PIcons.shield),
    Developer("developer", R.string.r_dev_title, PIcons.code),

    Sessions("sessions", R.string.dest_sessions, PIcons.layers, R.string.r_dev_product),
    Inbox("inbox", R.string.dest_inbox, PIcons.shield, R.string.r_dev_product),
    Workspaces("workspaces", R.string.dest_workspaces, PIcons.grid, R.string.r_dev_product),
    Repositories("repositories", R.string.dest_repositories, PIcons.box, R.string.r_dev_product),
    Commit("commit", R.string.dest_commit, PIcons.git, R.string.r_dev_product),
    Terminals("terminals", R.string.dest_terminals, PIcons.terminal, R.string.r_dev_product),

    Planning("planning", R.string.dest_planning, PIcons.workflow, R.string.r_dev_ops),
    Resources("resources", R.string.dest_resources, PIcons.sliders, R.string.r_dev_ops),
    Runtime("runtime", R.string.dest_runtime, PIcons.server, R.string.r_dev_ops),
    Services("services", R.string.dest_services, PIcons.recycle, R.string.r_dev_ops),
    Nodes("nodes", R.string.dest_nodes, PIcons.cloud, R.string.r_dev_ops),
    Displays("displays", R.string.dest_displays, PIcons.monitor, R.string.r_dev_ops),
    Processes("processes", R.string.dest_processes, PIcons.activity, R.string.r_dev_ops),

    Vault("vault", R.string.dest_vault, PIcons.key, R.string.r_dev_trust),
    Security("security", R.string.dest_security, PIcons.shield, R.string.r_dev_trust),
    ApprovalHistory("approvals", R.string.dest_approvals, PIcons.check, R.string.r_dev_trust),
    Emergency("emergency", R.string.dest_emergency, PIcons.alert, R.string.r_dev_trust),

    Linux("linux", R.string.dest_linux, PIcons.terminal, R.string.r_dev_system),
    Models("models", R.string.dest_models, PIcons.spark, R.string.r_dev_system),
    Diagnostics("diagnostics", R.string.dest_diagnostics, PIcons.info, R.string.r_dev_system),
    Reference("reference", R.string.dest_reference, PIcons.book, R.string.r_dev_system),
    Settings("settings", R.string.dest_settings, PIcons.settings),
    MoreSettings("more_settings", R.string.r_set_more, PIcons.sliders),
    Appearance("appearance", R.string.dest_appearance, PIcons.drop),
}

/**
 * The app (docs/design/redesign-2026-10.md): Home with the tasks, the message field and the menu button; every other
 * place opens from that menu (what it holds depends on the mode) and goes back with Back. No tab bar, no drawer.
 * [openSession] deep-links into a task, [openPage] to a page.
 */
@Composable
fun PorcelainApp(openSession: String?, openPage: String? = null, onOpened: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val nav = rememberNavController()
    val prefs by container.settings.flow.collectAsStateWithLifecycle()

    fun go(dest: Dest) {
        // The old Inbox is the "needs you" filter of Home.
        if (dest == Dest.Inbox) {
            container.ui.homeFilter.value = app.umbra.agent.data.HomeFilter.NEEDS
            nav.navigate(Dest.Home.route) { popUpTo(Dest.Home.route) { inclusive = false }; launchSingleTop = true }
            return
        }
        nav.navigate(dest.route) { launchSingleTop = true }
    }

    fun openSessionNow(id: String) {
        val agent = container.agents.store.bySession(id)
        if (agent != null) nav.navigate("agent/${agent.id}") { launchSingleTop = true }
        else {
            container.ui.focus(id)
            go(Dest.Current)
        }
    }

    var stopAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(openSession, openPage) {
        if (openSession != null) {
            openSessionNow(openSession)
            onOpened()
        } else if (openPage != null) {
            if (openPage == app.umbra.agent.AgentService.PAGE_STOP_ALL) stopAll = true
            else if (openPage.startsWith("agent/")) nav.navigate(openPage) { launchSingleTop = true }
            else if (openPage.startsWith("messages/") && app.umbra.agent.agents.Channels.app(openPage.removePrefix("messages/")) != null) nav.navigate(openPage) { launchSingleTop = true }
            else if (openPage.matches(Regex("workspace/[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) nav.navigate(openPage) { launchSingleTop = true }
            else if (openPage == "new-coding" || openPage == "new-agent") {
                container.ui.homeSheet.value = openPage.removePrefix("new-")
                nav.navigate(Dest.Home.route) { popUpTo(Dest.Home.route) { inclusive = false }; launchSingleTop = true }
            }
            else Dest.entries.firstOrNull { it.route == openPage }?.let { go(it) }
            onOpened()
        }
    }
    if (stopAll) app.umbra.agent.ui.emergency.StopAllConfirm(onDismiss = { stopAll = false })

    val selectScreen: () -> Unit = {
        if (!android.provider.Settings.canDrawOverlays(context) && !app.umbra.platform.android.UmbraAccessibilityService.connected.value) {
            go(Dest.Settings)
        } else {
            (context as? android.app.Activity)?.moveTaskToBack(true)
            container.capsule.startSelection(delayMs = 450)
        }
    }
    val select = stringResource(R.string.r_menu_select)
    val accountsLabel = stringResource(R.string.r_menu_accounts)
    val settingsLabel = stringResource(R.string.r_menu_settings)
    val workspacesLabel = stringResource(R.string.r_menu_workspaces)
    val screensLabel = stringResource(R.string.r_menu_screens)
    val nodesLabel = stringResource(R.string.r_menu_nodes)
    val safetyLabel = stringResource(R.string.r_menu_safety)
    val developerLabel = stringResource(R.string.r_menu_developer)
    val entries = when (prefs.uiMode) {
        app.umbra.agent.data.UiMode.SIMPLE -> listOf(
            MenuEntry(PIcons.target, select, selectScreen),
            MenuEntry(PIcons.key, accountsLabel) { go(Dest.Accounts) },
            MenuEntry(PIcons.cloud, nodesLabel) { go(Dest.Nodes) },
            MenuEntry(PIcons.settings, settingsLabel) { go(Dest.Settings) },
        )
        app.umbra.agent.data.UiMode.POWER -> listOf(
            MenuEntry(PIcons.target, select, selectScreen),
            MenuEntry(PIcons.grid, workspacesLabel) { go(Dest.Workspaces) },
            MenuEntry(PIcons.monitor, screensLabel) { go(Dest.Screens) },
            MenuEntry(PIcons.cloud, nodesLabel) { go(Dest.Nodes) },
            MenuEntry(PIcons.shield, safetyLabel) { go(Dest.Safety) },
            MenuEntry(PIcons.settings, settingsLabel) { go(Dest.Settings) },
        )
        app.umbra.agent.data.UiMode.DEV -> listOf(
            MenuEntry(PIcons.target, select, selectScreen),
            MenuEntry(PIcons.grid, workspacesLabel) { go(Dest.Workspaces) },
            MenuEntry(PIcons.monitor, screensLabel) { go(Dest.Screens) },
            MenuEntry(PIcons.cloud, nodesLabel) { go(Dest.Nodes) },
            MenuEntry(PIcons.code, developerLabel) { go(Dest.Developer) },
            MenuEntry(PIcons.settings, settingsLabel) { go(Dest.Settings) },
        )
    }

    val openEmergency: () -> Unit = { go(Dest.Emergency) }
    val liquid = Theme.liquid
    val view = androidx.compose.ui.platform.LocalView.current
    val pageGlass = rememberLayerBackdrop()
    val apple = Theme.apple
    val appleGlass = apple && app.umbra.agent.ui.porcelain.appleGlassActive()
    LiquidLightSource(active = (liquid || appleGlass) && Theme.current.motion && androidx.compose.runtime.remember(Theme.current) { app.umbra.agent.ui.porcelain.motionAllowed(view.context) })
    // dialogs and sheets are glass in the app's own window: the whole app is recorded live for them to refract
    val appGlass = rememberLayerBackdrop()
    val styled = !Theme.current.porcelain
    // Someone who used the app before this design has providers already; first-run setup is for a new install.
    val onboarding = !prefs.onboarded && prefs.providers.isEmpty()
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(if (styled) Modifier.layerBackdrop(appGlass) else Modifier)) {
    PageBackdrop(if (liquid) Modifier.layerBackdrop(pageGlass) else Modifier)
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassHost provides if (liquid) GlassHost(pageGlass, view) else null, LocalPageGlass provides if (liquid) pageGlass else null) {
        if (onboarding) {
            app.umbra.agent.ui.onboarding.Onboarding(onAccounts = { })
        } else Box(Modifier.fillMaxSize().background(P.page)) {
          androidx.compose.runtime.CompositionLocalProvider(LocalOpenEmergency provides openEmergency, LocalChromeBottom provides 0.dp) {
            NavHost(
                nav, startDestination = Dest.Home.route,
                // Liquid pages surface like a drop rising to meet the glass; Apple pushes in from the right on a
                // critically damped spring; Porcelain keeps its cross-fade.
                enterTransition = {
                    when {
                        Theme.apple -> slideInHorizontally(appleSlide) { it }
                        Theme.liquid -> fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.94f)
                        else -> fadeIn(tween(260))
                    }
                },
                exitTransition = {
                    when {
                        Theme.apple -> slideOutHorizontally(appleSlide) { -it / 3 } + fadeOut(tween(380), targetAlpha = 0.6f)
                        Theme.liquid -> fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 1.03f)
                        else -> fadeOut(tween(200))
                    }
                },
                popEnterTransition = {
                    when {
                        Theme.apple -> slideInHorizontally(appleSlide) { -it / 3 } + fadeIn(tween(380), initialAlpha = 0.6f)
                        Theme.liquid -> fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 1.04f)
                        else -> fadeIn(tween(260))
                    }
                },
                popExitTransition = {
                    when {
                        Theme.apple -> slideOutHorizontally(appleSlide) { it }
                        Theme.liquid -> fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.94f)
                        else -> fadeOut(tween(200))
                    }
                },
            ) {
                val back: () -> Unit = { if (!nav.popBackStack()) go(Dest.Home) }
                composable(Dest.Home.route) { HomeScreen(onOpenSession = ::openSessionNow, onOpenAgent = { nav.navigate("agent/$it") { launchSingleTop = true } }, entries = entries, onAccounts = { go(Dest.Accounts) }, onComputers = { go(Dest.Nodes) }) }
                // Agents (docs/design/agents-2026-10.md, Figma 11): the chat, the agent's page and what hangs off it.
                composable("agent/{id}") { e ->
                    val id = e.arguments?.getString("id").orEmpty()
                    app.umbra.agent.ui.agents.AgentChatScreen(id, onBack = back, onProfile = { nav.navigate("agent/$id/profile") }, onOpenSession = ::openSessionNow)
                }
                composable("agent/{id}/profile") { e ->
                    app.umbra.agent.ui.agents.AgentProfileScreen(
                        e.arguments?.getString("id").orEmpty(), onBack = back, onGo = { route -> nav.navigate(route) }, onAccounts = { go(Dest.Accounts) },
                        onDeleted = { nav.popBackStack(Dest.Home.route, inclusive = false) },
                    )
                }
                composable("agent/{id}/soul") { e -> app.umbra.agent.ui.agents.SoulScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable("agent/{id}/memory") { e -> app.umbra.agent.ui.agents.MemoryScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable("agent/{id}/timers") { e -> app.umbra.agent.ui.agents.TimersScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable("agent/{id}/wake") { e -> app.umbra.agent.ui.agents.WakeScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable("agent/{id}/channels") { e -> app.umbra.agent.ui.agents.ChannelsScreen(e.arguments?.getString("id").orEmpty(), onBack = back, onGo = { route -> nav.navigate(route) }) }
                composable("agent/{id}/telegram") { e -> app.umbra.agent.ui.agents.TelegramScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable("messages") { app.umbra.agent.ui.agents.MessageAppsScreen(onBack = back, onGo = { nav.navigate(it) }) }
                composable("messages/{app}") { e -> app.umbra.agent.ui.agents.MessageAppScreen(e.arguments?.getString("app").orEmpty(), onBack = back, onGo = { nav.navigate(it) }) }
                composable("notifications") { app.umbra.agent.ui.agents.NotificationReaderScreen(onBack = back) }
                composable(Dest.Current.route) { CurrentScreen(onBack = back, onOpenSession = ::openSessionNow, onAccounts = { go(Dest.Accounts) }, onCommit = { go(Dest.Commit) }, onComputers = { go(Dest.Nodes) }) }
                composable(Dest.Accounts.route) { app.umbra.agent.ui.accounts.AccountsScreen(onBack = back, onGo = ::go) }
                composable(Dest.Screens.route) { app.umbra.agent.ui.displays.ScreensScreen(onBack = back) }
                composable(Dest.Safety.route) { app.umbra.agent.ui.hubs.SafetyHub(onBack = back, onGo = ::go) }
                composable(Dest.Developer.route) { app.umbra.agent.ui.hubs.DeveloperHub(onBack = back, onGo = ::go) }
                composable(Dest.Inbox.route) { Page(Dest.Inbox, back) { InboxScreen(onOpenSession = ::openSessionNow, onHistory = { go(Dest.ApprovalHistory) }) } }
                composable(Dest.Sessions.route) { Page(Dest.Sessions, back) { SessionsScreen(onOpenSession = ::openSessionNow) } }
                composable(Dest.Runtime.route) { Page(Dest.Runtime, back) { app.umbra.agent.ui.runtime.RuntimeScreen(onOpenSession = ::openSessionNow) } }
                composable(Dest.Nodes.route) { app.umbra.agent.ui.nodes.NodesScreen(onBack = back) }
                composable(Dest.Repositories.route) { Page(Dest.Repositories, back) { app.umbra.agent.ui.integration.RepositoryHubScreen(onCommit = { go(Dest.Commit) }) } }
                composable(Dest.Commit.route) { Page(Dest.Commit, back) { app.umbra.agent.ui.integration.CommitScreen(onOpenVault = { go(Dest.Vault) }) } }
                composable(Dest.Security.route) {
                    Page(Dest.Security, back) { app.umbra.agent.ui.security.SecurityEvidenceScreen(onGo = { route -> Dest.entries.firstOrNull { it.route == route }?.let { go(it) } }) }
                }
                composable(Dest.Vault.route) { Page(Dest.Vault, back) { app.umbra.agent.ui.vault.CredentialVaultScreen() } }
                composable(Dest.Services.route) { Page(Dest.Services, back) { app.umbra.agent.ui.services.SharedServicesScreen(onOpenSession = ::openSessionNow) } }
                composable(Dest.Resources.route) { Page(Dest.Resources, back) { app.umbra.agent.ui.resources.ResourceScreen() } }
                composable(Dest.Planning.route) { Page(Dest.Planning, back) { app.umbra.agent.ui.planning.PlanningScreen(onOpenSession = ::openSessionNow) } }
                composable(Dest.Emergency.route) { Page(Dest.Emergency, back) { app.umbra.agent.ui.emergency.EmergencyScreen() } }
                composable(Dest.Terminals.route) { Page(Dest.Terminals, back) { app.umbra.agent.ui.terminal.TerminalsScreen() } }
                composable(Dest.Workspaces.route) { WorkspacesScreen(onBack = back, onOpen = { nav.navigate("workspace/$it") }) }
                composable("workspace/{id}") { e -> WorkspaceBrowserScreen(e.arguments?.getString("id").orEmpty(), onBack = back) }
                composable(Dest.Linux.route) { SetupScreen(onBack = back, onModels = { go(Dest.Accounts) }) }
                composable(Dest.Models.route) { ModelsScreen(onBack = back, onSetup = { go(Dest.Linux) }) }
                composable(Dest.Displays.route) { DisplaysScreen(onBack = back) }
                composable(Dest.Processes.route) { ProcessesScreen(onBack = back) }
                composable(Dest.ApprovalHistory.route) { ApprovalHistoryScreen(onBack = back) }
                composable(Dest.Diagnostics.route) { DiagnosticsScreen(onBack = back) }
                composable(Dest.Appearance.route) { app.umbra.agent.ui.AppearanceScreen(onBack = back) }
                composable(Dest.Reference.route) { app.umbra.agent.ui.reference.ReferenceScreen(onBack = back) }
                composable(Dest.Settings.route) { app.umbra.agent.ui.settings.SettingsScreen(onBack = back, onGo = ::go) }
                composable(Dest.MoreSettings.route) { app.umbra.agent.ui.MoreSettingsScreen(onBack = back, onGo = ::go) }
            }
          }
        }
    }
    app.umbra.agent.ui.update.UpdatePrompt()
    OverlayScrim()
    }
    if (styled) OverlayHost(appGlass) { androidx.compose.ui.geometry.Offset.Zero }
    }
}

/** One of the original pages, under the one header every page has (back, its name, the emergency shield). */
@Composable
private fun Page(dest: Dest, onBack: () -> Unit, content: @Composable () -> Unit) = PorcelainPage(stringResource(dest.title), onBack, content = content)

/** Opens Emergency Control from any app bar. */
val LocalOpenEmergency = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun AppBar(onMenu: () -> Unit, actions: @Composable () -> Unit = {}) {
    val container = LocalContext.current.container
    val emergency by container.emergency.state.collectAsStateWithLifecycle()
    val locked = emergency.level == app.umbra.core.control.EmergencyLevel.LOCKDOWN
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BadgeIcon(PIcons.spark, size = 30.dp)
        if (Theme.apple) Text("Umbra", Modifier.padding(start = 10.dp).weight(1f), fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = P.ink, fontFamily = P.serif)
        else Text("Umbra", Modifier.padding(start = 10.dp).weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = P.ink)
        actions()
        Spacer(Modifier.width(8.dp))
        PIconButton(if (locked) PIcons.alert else PIcons.shield, stringResource(R.string.dest_emergency), LocalOpenEmergency.current, size = 36.dp, tint = if (locked) P.danger else P.accent2)
        Spacer(Modifier.width(8.dp))
        PIconButton(PIcons.menu, stringResource(R.string.open_menu), onMenu, size = 36.dp)
    }
    if (locked) LockdownBanner()
    }
}

@Composable
private fun LockdownBanner() {
    Text(
        androidx.compose.ui.res.stringResource(app.umbra.agent.R.string.lockdown_banner),
        Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(10.dp)).background(pc(0xFFF4E1DD)).clickable(onClick = LocalOpenEmergency.current).padding(horizontal = 12.dp, vertical = 7.dp),
        fontSize = 12.sp, color = pc(0xFF97564F), fontWeight = FontWeight.SemiBold,
    )
}

/**
 * A page opened from the drawer or from another page: back, the page's name, its own [actions], and the same
 * emergency shield every bar carries. One header for the whole app.
 */
@Composable
fun PorcelainPage(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val container = LocalContext.current.container
    val emergency by container.emergency.state.collectAsStateWithLifecycle()
    val locked = emergency.level == app.umbra.core.control.EmergencyLevel.LOCKDOWN
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(P.page)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PIconButton(PIcons.chevronLeft, androidx.compose.ui.res.stringResource(app.umbra.agent.R.string.action_back), onBack, size = 36.dp)
            Text(
                title, Modifier.padding(start = 12.dp).weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P.ink,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, letterSpacing = (-0.3).sp,
            )
            actions()
            Spacer(Modifier.width(8.dp))
            PIconButton(
                if (locked) PIcons.alert else PIcons.shield, androidx.compose.ui.res.stringResource(app.umbra.agent.R.string.dest_emergency),
                LocalOpenEmergency.current, size = 36.dp, tint = if (locked) P.danger else P.accent2,
            )
        }
        if (locked) LockdownBanner()
        Box(Modifier.weight(1f).fillMaxWidth().scrollEdge()) { content() }
    }
}

/** The Liquid backdrop's live layer, for the tab bar to read its tone from. */
private val LocalPageGlass = androidx.compose.runtime.staticCompositionLocalOf<com.kyant.backdrop.backdrops.LayerBackdrop?> { null }

/** How far the floating tab bar reaches into the page: tab pages pad the end of their scrolling content by it. */
val LocalChromeBottom = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

/** UINavigationController's push: critically damped, response 0.4 s. */
private val appleSlide = app.umbra.agent.ui.porcelain.appleSpring(1f, 0.4f, androidx.compose.ui.unit.IntOffset.VisibilityThreshold)

/**
 * Apple's scroll edge effect: content softly dissolves where it meets the bar above it instead of a hard line.
 * Other themes are untouched.
 */
@Composable
private fun Modifier.scrollEdge(): Modifier = if (!Theme.apple) this else this
    .graphicsLayer(compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val edge = 14.dp.toPx()
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to Color.Transparent, edge / size.height.coerceAtLeast(1f) to Color.Black),
            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }

/** Navigates to [dest] from a nested screen. */
fun NavHostController.go(dest: Dest) = navigate(dest.route) { launchSingleTop = true }
