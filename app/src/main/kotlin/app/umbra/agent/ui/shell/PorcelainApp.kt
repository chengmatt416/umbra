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
import app.umbra.agent.ui.SettingsScreen
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

/** A place in the app. [tab] places sit in the bottom bar; the others are in the drawer, under their [group]. */
enum class Dest(val route: String, @param:StringRes val title: Int, val icon: IconSpec, @param:StringRes val group: Int? = null, val tab: Boolean = false) {
    Home("home", R.string.dest_home, PIcons.home, tab = true),
    Sessions("sessions", R.string.dest_sessions, PIcons.layers, tab = true),
    Current("current", R.string.dest_current, PIcons.activity, tab = true),
    Inbox("inbox", R.string.dest_inbox, PIcons.shield, tab = true),

    Workspaces("workspaces", R.string.dest_workspaces, PIcons.grid, R.string.group_product),
    Repositories("repositories", R.string.dest_repositories, PIcons.box, R.string.group_product),
    Commit("commit", R.string.dest_commit, PIcons.git, R.string.group_product),

    Planning("planning", R.string.dest_planning, PIcons.workflow, R.string.group_operations),
    Resources("resources", R.string.dest_resources, PIcons.sliders, R.string.group_operations),
    Runtime("runtime", R.string.dest_runtime, PIcons.server, R.string.group_operations),
    Services("services", R.string.dest_services, PIcons.recycle, R.string.group_operations),
    Nodes("nodes", R.string.dest_nodes, PIcons.cloud, R.string.group_operations),
    Terminals("terminals", R.string.dest_terminals, PIcons.terminal, R.string.group_operations),

    Vault("vault", R.string.dest_vault, PIcons.key, R.string.group_trust),
    Security("security", R.string.dest_security, PIcons.shield, R.string.group_trust),
    Emergency("emergency", R.string.dest_emergency, PIcons.alert, R.string.group_trust),

    Linux("linux", R.string.dest_linux, PIcons.terminal, R.string.group_system),
    Models("models", R.string.dest_models, PIcons.spark, R.string.group_system),
    Displays("displays", R.string.dest_displays, PIcons.monitor, R.string.group_system),
    Processes("processes", R.string.dest_processes, PIcons.activity, R.string.group_system),
    ApprovalHistory("approvals", R.string.dest_approvals, PIcons.check, R.string.group_system),
    Diagnostics("diagnostics", R.string.dest_diagnostics, PIcons.info, R.string.group_system),
    Settings("settings", R.string.dest_settings, PIcons.settings, R.string.group_system),
    Appearance("appearance", R.string.dest_appearance, PIcons.drop, R.string.group_system),
    Reference("reference", R.string.dest_reference, PIcons.book, R.string.group_system),
}

/** The app: Porcelain shell, bottom bar, drawer, and every screen. [openSession] deep-links into a task, [openPage] to a page. */
@Composable
fun PorcelainApp(openSession: String?, openPage: String? = null, onOpened: () -> Unit) {
    val container = LocalContext.current.container
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()

    fun go(dest: Dest) {
        scope.launch { drawer.close() }
        nav.navigate(dest.route) {
            popUpTo(Dest.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openSessionNow(id: String) {
        container.ui.focus(id)
        go(Dest.Current)
    }

    var stopAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(openSession, openPage) {
        if (openSession != null) {
            openSessionNow(openSession)
            onOpened()
        } else if (openPage != null) {
            if (openPage == app.umbra.agent.AgentService.PAGE_STOP_ALL) stopAll = true
            else Dest.entries.firstOrNull { it.route == openPage }?.let { go(it) }
            onOpened()
        }
    }
    if (stopAll) app.umbra.agent.ui.emergency.StopAllConfirm(onDismiss = { stopAll = false })

    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val attention = approvals.size + questions.size

    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val openEmergency: () -> Unit = { go(Dest.Emergency) }
    val liquid = Theme.liquid
    val view = androidx.compose.ui.platform.LocalView.current
    // Liquid glass refracts the live backdrop: the page's for the pages, the drawer's own for the drawer.
    val pageGlass = rememberLayerBackdrop()
    val drawerGlass = rememberLayerBackdrop()
    // Apple's bars and sidebar refract the pages themselves, recorded live as they scroll
    val contentGlass = rememberLayerBackdrop()
    var contentOrigin by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val apple = Theme.apple
    val appleGlass = apple && app.umbra.agent.ui.porcelain.appleGlassActive()
    LiquidLightSource(active = (liquid || appleGlass) && Theme.current.motion && androidx.compose.runtime.remember(Theme.current) { app.umbra.agent.ui.porcelain.motionAllowed(view.context) })
    // dialogs and sheets are glass in the app's own window: the whole app is recorded live for them to refract
    val appGlass = rememberLayerBackdrop()
    val styled = !Theme.current.porcelain
    Box(Modifier.fillMaxSize()) {
    Box(Modifier.fillMaxSize().then(if (styled) Modifier.layerBackdrop(appGlass) else Modifier)) {
    PageBackdrop(if (liquid) Modifier.layerBackdrop(pageGlass) else Modifier)
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassHost provides if (liquid) GlassHost(pageGlass, view) else null, LocalPageGlass provides if (liquid) pageGlass else null) {
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            val sheetShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
            ModalDrawerSheet(
                drawerContainerColor = when {
                    liquid || appleGlass -> Color.Transparent
                    apple -> P.base
                    else -> pc(0xFFE6ECE2)
                },
                drawerShape = sheetShape,
            ) {
                if (appleGlass) {
                    // the sidebar is a sheet of glass over the page it slid across
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .drawBackdrop(
                                backdrop = contentGlass,
                                shape = { sheetShape },
                                effects = {
                                    vibrancy()
                                    blur(18.dp.toPx())
                                    lens(20.dp.toPx(), 36.dp.toPx())
                                },
                                highlight = { app.umbra.agent.ui.porcelain.appleRim() },
                                shadow = { app.umbra.agent.ui.porcelain.appleShadow(24.dp, if (Theme.dark) 0.45f else 0.16f) },
                                onDrawSurface = { drawRect(if (Theme.dark) Color.Black.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.56f)) },
                            ),
                    ) { DrawerContent(current = route, onGo = ::go) }
                } else if (liquid) {
                    Box(Modifier.fillMaxHeight()) {
                        // flows only while the drawer is out; closed, it holds a still frame
                        LiquidBackdrop(Modifier.matchParentSize().layerBackdrop(drawerGlass), Theme.current.let { it.copy(motion = it.motion && drawer.currentValue == DrawerValue.Open) }, seed = 40f)
                        Box(Modifier.matchParentSize().background(if (Theme.dark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.38f)))
                        androidx.compose.runtime.CompositionLocalProvider(LocalGlassHost provides GlassHost(drawerGlass, view)) {
                            DrawerContent(current = route, onGo = ::go)
                        }
                    }
                } else {
                    DrawerContent(current = route, onGo = ::go)
                }
            }
        },
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            // The keyboard covers the tab bar anyway; keeping its space under the keyboard would count it twice
            // (the pages pad for the whole keyboard), so it steps aside while one is up.
            bottomBar = {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                val keyboard = WindowInsets.isImeVisible
                if (!keyboard && Dest.entries.any { it.tab && route.startsWith(it.route) }) BottomBar(route, attention, ::go, contentGlass) { contentOrigin }
            },
            modifier = Modifier.background(P.page),
        ) { padding ->
            // Porcelain keeps its bar beside the pages; Liquid's and Apple's bars float, and the pages scroll under them
            // (the tab pages pad their own ends by LocalChromeBottom; Current keeps its composer clear of the bar).
            val floating = !Theme.current.porcelain
            val barInset = padding.calculateBottomPadding()
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(bottom = if (!floating || route.startsWith(Dest.Current.route)) barInset else 0.dp)
                    .then(if (apple) Modifier.layerBackdrop(contentGlass) else Modifier)
                    .onGloballyPositioned { contentOrigin = it.positionInRoot() }
                    .background(P.page),
            ) {
              androidx.compose.runtime.CompositionLocalProvider(LocalOpenEmergency provides openEmergency, LocalChromeBottom provides if (floating) barInset else 0.dp) {
                NavHost(
                    nav, startDestination = Dest.Home.route,
                    // Liquid pages surface like a drop rising to meet the glass; Porcelain keeps the default cross-fade.
                    // Apple pushes in from the right and pops back the same way (the page underneath parallaxes a third and
                    // dims), on a critically damped spring; switching tabs only cross-fades.
                    enterTransition = {
                        when {
                            Theme.apple -> if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeIn(tween(160)) else slideInHorizontally(appleSlide) { it }
                            Theme.liquid -> fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 0.94f)
                            tabSwitch(initialState.destination.route, targetState.destination.route) -> fadeIn(tween(180))
                            else -> fadeIn(tween(700))
                        }
                    },
                    exitTransition = {
                        when {
                            Theme.apple -> if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeOut(tween(120)) else slideOutHorizontally(appleSlide) { -it / 3 } + fadeOut(tween(380), targetAlpha = 0.6f)
                            Theme.liquid -> fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 1.03f)
                            tabSwitch(initialState.destination.route, targetState.destination.route) -> fadeOut(tween(120))
                            else -> fadeOut(tween(700))
                        }
                    },
                    popEnterTransition = {
                        when {
                            Theme.apple -> if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeIn(tween(160)) else slideInHorizontally(appleSlide) { -it / 3 } + fadeIn(tween(380), initialAlpha = 0.6f)
                            Theme.liquid -> fadeIn(tween(240)) + scaleIn(spring(dampingRatio = 0.72f, stiffness = 420f), initialScale = 1.04f)
                            tabSwitch(initialState.destination.route, targetState.destination.route) -> fadeIn(tween(180))
                            else -> fadeIn(tween(700))
                        }
                    },
                    popExitTransition = {
                        when {
                            Theme.apple -> if (tabSwitch(initialState.destination.route, targetState.destination.route)) fadeOut(tween(120)) else slideOutHorizontally(appleSlide) { it }
                            Theme.liquid -> fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.94f)
                            tabSwitch(initialState.destination.route, targetState.destination.route) -> fadeOut(tween(120))
                            else -> fadeOut(tween(700))
                        }
                    },
                ) {
                    composable(Dest.Home.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) {
                            HomeScreen(onOpenSession = ::openSessionNow, onSetup = { go(Dest.Linux) }, onInbox = { go(Dest.Inbox) })
                        }
                    }
                    composable(Dest.Sessions.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { SessionsScreen(onOpenSession = ::openSessionNow) }
                    }
                    composable(Dest.Current.route) {
                        CurrentScreen(onMenu = { scope.launch { drawer.open() } }, onOpenSession = ::openSessionNow, onSessions = { go(Dest.Sessions) })
                    }
                    composable(Dest.Inbox.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { InboxScreen(onOpenSession = ::openSessionNow, onHistory = { go(Dest.ApprovalHistory) }) }
                    }
                    composable(Dest.Runtime.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.runtime.RuntimeScreen(onOpenSession = ::openSessionNow) }
                    }
                    composable(Dest.Nodes.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.nodes.NodesScreen() }
                    }
                    composable(Dest.Repositories.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.integration.RepositoryHubScreen(onCommit = { go(Dest.Commit) }) }
                    }
                    composable(Dest.Commit.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.integration.CommitScreen(onOpenVault = { go(Dest.Vault) }) }
                    }
                    composable(Dest.Security.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) {
                            app.umbra.agent.ui.security.SecurityEvidenceScreen(onGo = { route -> Dest.entries.firstOrNull { it.route == route }?.let { go(it) } })
                        }
                    }
                    composable(Dest.Vault.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.vault.CredentialVaultScreen() }
                    }
                    composable(Dest.Services.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.services.SharedServicesScreen(onOpenSession = ::openSessionNow) }
                    }
                    composable(Dest.Resources.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.resources.ResourceScreen() }
                    }
                    composable(Dest.Planning.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.planning.PlanningScreen(onOpenSession = ::openSessionNow) }
                    }
                    composable(Dest.Emergency.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.emergency.EmergencyScreen() }
                    }
                    composable(Dest.Terminals.route) {
                        TabPage(onMenu = { scope.launch { drawer.open() } }) { app.umbra.agent.ui.terminal.TerminalsScreen() }
                    }
                    composable(Dest.Workspaces.route) { WorkspacesScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("workspace/$it") }) }
                    composable("workspace/{id}") { e -> WorkspaceBrowserScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
                    composable(Dest.Linux.route) { SetupScreen(onBack = { nav.popBackStack() }, onModels = { go(Dest.Models) }) }
                    composable(Dest.Models.route) { ModelsScreen(onBack = { nav.popBackStack() }, onSetup = { go(Dest.Linux) }) }
                    composable(Dest.Displays.route) { DisplaysScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Processes.route) { ProcessesScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.ApprovalHistory.route) { ApprovalHistoryScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Diagnostics.route) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Appearance.route) { app.umbra.agent.ui.AppearanceScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Reference.route) { app.umbra.agent.ui.reference.ReferenceScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Settings.route) {
                        SettingsScreen(
                            onBack = { nav.popBackStack() },
                            onModels = { go(Dest.Models) },
                            onSetup = { go(Dest.Linux) },
                            onApprovalHistory = { go(Dest.ApprovalHistory) },
                            onDiagnostics = { go(Dest.Diagnostics) },
                            onWorkspaces = { go(Dest.Workspaces) },
                            onProcesses = { go(Dest.Processes) },
                            onDisplays = { go(Dest.Displays) },
                            onAppearance = { go(Dest.Appearance) },
                        )
                    }
                }
              }
            }
        }
    }
    }
    OverlayScrim()
    }
    if (styled) OverlayHost(appGlass) { androidx.compose.ui.geometry.Offset.Zero }
    }
}

/** A bottom-bar page: the phone app bar (spark badge, name, menu) above the content. */
@Composable
fun TabPage(onMenu: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppBar(onMenu, actions)
        Box(Modifier.weight(1f).fillMaxWidth().scrollEdge()) { content() }
    }
}

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

@Composable
private fun BottomBar(route: String, attention: Int, onGo: (Dest) -> Unit, content: com.kyant.backdrop.backdrops.LayerBackdrop, contentOrigin: () -> androidx.compose.ui.geometry.Offset) {
    if (Theme.liquid) return LiquidBottomBar(route, attention, onGo, LocalPageGlass.current)
    if (Theme.apple) {
        val tabs = Dest.entries.filter { it.tab }
        val labels = tabs.map { stringResource(it.title) }
        return app.umbra.agent.ui.porcelain.AppleTabBar(
            tabs.mapIndexed { i, d -> app.umbra.agent.ui.porcelain.AppleTab(d.icon, labels[i], if (d == Dest.Inbox) attention else 0) },
            tabs.indexOfFirst { route.startsWith(it.route) }.coerceAtLeast(0),
            { i -> onGo(tabs[i]) },
            content,
            contentOrigin,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(pc(0xFFE0E9DF))
            .border(width = 1.dp, color = pc(0x5CA9B9A7), shape = RoundedCornerShape(0.dp))
            .navigationBarsPadding()
            .height(64.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dest.entries.filter { it.tab }.forEach { d ->
            val on = route.startsWith(d.route)
            val color = if (on) pc(0xFF44694B) else pc(0xFF839683)
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Tab, onClickLabel = stringResource(d.title)) { onGo(d) }.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    PIcon(d.icon, size = 20.dp, tint = color)
                    if (d == Dest.Inbox && attention > 0) {
                        Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(CircleShape).background(P.amber), contentAlignment = Alignment.Center) {
                            Text("${attention.coerceAtMost(9)}", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(stringResource(d.title), Modifier.padding(top = 4.dp), fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = color)
            }
        }
    }
}

@Composable
private fun DrawerContent(current: String, onGo: (Dest) -> Unit) {
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 18.dp)) {
            BadgeIcon(PIcons.spark, size = 44.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text("Umbra", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = P.ink)
                Text(stringResource(R.string.drawer_tagline).uppercase(), fontSize = 9.sp, letterSpacing = 2.sp, color = P.muted)
            }
        }
        DrawerItem(Dest.Home, current.startsWith(Dest.Home.route), onGo)
        Dest.entries.filter { it.group != null }.groupBy { it.group!! }.forEach { (group, dests) ->
            Text(stringResource(group).uppercase(), Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp), fontSize = 10.sp, letterSpacing = if (stringResource(group).hasCjk()) 0.8.sp else 2.4.sp, fontWeight = FontWeight.Bold, color = P.eyebrow)
            dests.forEach { DrawerItem(it, current.startsWith(it.route), onGo) }
        }
    }
}

@Composable
private fun DrawerItem(d: Dest, active: Boolean, onGo: (Dest) -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    val press = rememberPress()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .liquidPress(press, 0.5f)
            .let {
                when {
                    !active -> it.clip(shape)
                    Theme.apple -> it.clip(RoundedCornerShape(12.dp)).background(Theme.current.accentColor)
                    Theme.liquid -> it.liquidGlass(shape, GlassKind.Control, 4.dp)
                    else -> it.clip(shape).background(P.ceramic).border(1.dp, pc(0xA8FFFFFF), shape)
                }
            }
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button) { onGo(d) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (active) (if (Theme.apple) Color.White else if (Theme.liquid) P.accent2 else pc(0xFF395941)) else if (Theme.apple) P.ink else pc(0xFF647965)
        PIcon(d.icon, size = 18.dp, tint = tint)
        Text(stringResource(d.title), Modifier.padding(start = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

/**
 * The Liquid tab bar: a glass capsule floating above the backdrop. The chosen tab sits in a drop of the accent that
 * flows to the next tab (stretching on the way), and the tab's icon bounces as the drop arrives.
 */
@Composable
private fun LiquidBottomBar(route: String, attention: Int, onGo: (Dest) -> Unit, backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?) {
    val tabs = Dest.entries.filter { it.tab }
    val index = tabs.indexOfFirst { route.startsWith(it.route) }
    val drop = remember { GooeyDrop() }
    val bounds = remember { androidx.compose.runtime.mutableStateListOf<Pair<Float, Float>>().apply { repeat(tabs.size) { add(0f to 0f) } } }
    val target = bounds.getOrNull(index)?.takeIf { it.second > it.first }
    LaunchedEffect(index, target) { if (target != null) drop.moveTo(target.first, target.second) }
    val spec = Theme.current
    // the bar's tint follows the drops passing under it: over a dark one it turns dark glass with light ink
    val tone = app.umbra.agent.ui.porcelain.rememberBackdropTone(backdrop?.graphicsLayer, { androidx.compose.ui.geometry.Offset.Zero }, spec.dark, spec.tokens.base)
    val lightTokens = remember(spec.hue) { app.umbra.agent.ui.porcelain.Tokens.liquid(spec.hue, false) }
    val darkTokens = remember(spec.hue) { app.umbra.agent.ui.porcelain.Tokens.liquid(spec.hue, true) }
    val dropColor = androidx.compose.ui.graphics.lerp(hsl(spec.hue, 0.75f, 0.62f, 0.22f), hsl(spec.hue, 0.70f, 0.62f, 0.30f), tone.value)
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 14.dp, end = 14.dp, bottom = 10.dp, top = 4.dp).then(tone.modifier)) {
        Row(
            Modifier
                .fillMaxWidth()
                .liquidGlass(shape, GlassKind.Bar, 16.dp, tone = tone.value)
                .padding(6.dp)
                .drawBehind {
                    if (target == null) return@drawBehind
                    val l = drop.left.value
                    val r = drop.right.value
                    val st = drop.stretch(target.second - target.first)
                    val h = size.height * (1f - 0.2f * st)
                    val top = (size.height - h) / 2f
                    drawRoundRect(dropColor, topLeft = androidx.compose.ui.geometry.Offset(l, top), size = androidx.compose.ui.geometry.Size(r - l, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f))
                }
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, d ->
                val on = i == index
                val press = rememberPress()
                val onColor = androidx.compose.ui.graphics.lerp(lightTokens.accent2, darkTokens.accent2, tone.value)
                val offColor = androidx.compose.ui.graphics.lerp(lightTokens.muted, darkTokens.muted, tone.value)
                val color by androidx.compose.animation.animateColorAsState(if (on) onColor else offColor, tween(240), label = "tab")
                val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
                LaunchedEffect(on) {
                    if (on) {
                        bounce.snapTo(0.82f)
                        bounce.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = 380f))
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .onPlaced { c -> val x = c.positionInParent().x; bounds[i] = x to x + c.size.width }
                        .jelly(press, 0.8f, glow = RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(interactionSource = press, indication = null, role = Role.Tab, onClickLabel = stringResource(d.title)) { onGo(d) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }) {
                        PIcon(d.icon, size = 20.dp, tint = color)
                        if (d == Dest.Inbox && attention > 0) {
                            Box(Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(CircleShape).background(P.amber), contentAlignment = Alignment.Center) {
                                Text("${attention.coerceAtMost(9)}", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Text(stringResource(d.title), Modifier.padding(top = 3.dp), fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = color, maxLines = 1)
                }
            }
        }
    }
}

/** The Liquid backdrop's live layer, for the tab bar to read its tone from. */
private val LocalPageGlass = androidx.compose.runtime.staticCompositionLocalOf<com.kyant.backdrop.backdrops.LayerBackdrop?> { null }

/** How far the floating tab bar reaches into the page: tab pages pad the end of their scrolling content by it. */
val LocalChromeBottom = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

private val tabRoutes = Dest.entries.filter { it.tab }.map { it.route }.toSet()

private fun tabSwitch(from: String?, to: String?) = from in tabRoutes && to in tabRoutes

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
