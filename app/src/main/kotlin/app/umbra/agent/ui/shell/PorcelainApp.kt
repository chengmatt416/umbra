package app.umbra.agent.ui.shell

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
import app.umbra.agent.ui.sessions.SessionsScreen
import kotlinx.coroutines.launch

/** A place in the app. [tab] places sit in the bottom bar; the others are in the drawer (Power Studio). */
enum class Dest(val route: String, val title: String, val icon: IconSpec, val group: String? = null, val tab: Boolean = false) {
    Home("home", "Home", PIcons.home, tab = true),
    Sessions("sessions", "Sessions", PIcons.layers, tab = true),
    Current("current", "Current", PIcons.activity, tab = true),
    Inbox("inbox", "Inbox", PIcons.shield, tab = true),

    Workspaces("workspaces", "Workspaces", PIcons.grid, "PRODUCT"),

    Planning("planning", "Planning & Agents", PIcons.workflow, "OPERATIONS"),
    Resources("resources", "Resource Manager", PIcons.sliders, "OPERATIONS"),
    Terminals("terminals", "Terminals", PIcons.terminal, "OPERATIONS"),

    Emergency("emergency", "Emergency Control", PIcons.alert, "TRUST & SAFETY"),

    Linux("linux", "Linux & add-ons", PIcons.terminal, "SYSTEM"),
    Models("models", "Models & accounts", PIcons.spark, "SYSTEM"),
    Displays("displays", "Displays", PIcons.monitor, "SYSTEM"),
    Processes("processes", "Processes", PIcons.activity, "SYSTEM"),
    ApprovalHistory("approvals", "Approval history", PIcons.check, "SYSTEM"),
    Diagnostics("diagnostics", "Diagnostics", PIcons.info, "SYSTEM"),
    Settings("settings", "Settings", PIcons.settings, "SYSTEM"),
}

/** The app: Porcelain shell, bottom bar, drawer, and every screen. [openSession] deep-links into a task. */
@Composable
fun PorcelainApp(openSession: String?, onOpened: () -> Unit) {
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

    LaunchedEffect(openSession) {
        if (openSession != null) {
            openSessionNow(openSession)
            onOpened()
        }
    }

    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val attention = approvals.size + questions.size

    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    val openEmergency: () -> Unit = { go(Dest.Emergency) }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Color(0xFFE6ECE2), drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                DrawerContent(current = route, onGo = ::go)
            }
        },
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (Dest.entries.any { it.tab && route.startsWith(it.route) }) BottomBar(route, attention, ::go)
            },
            modifier = Modifier.background(P.page),
        ) { padding ->
            Box(Modifier.fillMaxSize().background(P.page).padding(padding)) {
              androidx.compose.runtime.CompositionLocalProvider(LocalOpenEmergency provides openEmergency) {
                NavHost(nav, startDestination = Dest.Home.route) {
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
                    composable(Dest.Models.route) { ModelsScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Displays.route) { DisplaysScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Processes.route) { ProcessesScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.ApprovalHistory.route) { ApprovalHistoryScreen(onBack = { nav.popBackStack() }) }
                    composable(Dest.Diagnostics.route) { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
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
                        )
                    }
                }
              }
            }
        }
    }
}

/** A bottom-bar page: the phone app bar (spark badge, name, menu) above the content. */
@Composable
fun TabPage(onMenu: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        AppBar(onMenu, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
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
        Text("Umbra", Modifier.padding(start = 10.dp).weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = P.ink)
        actions()
        Spacer(Modifier.width(8.dp))
        PIconButton(if (locked) PIcons.alert else PIcons.shield, "Emergency Control", LocalOpenEmergency.current, size = 36.dp, tint = if (locked) P.danger else P.accent2)
        Spacer(Modifier.width(8.dp))
        PIconButton(PIcons.menu, "Open the menu", onMenu, size = 36.dp)
    }
    if (locked) {
        Text(
            "Input lockdown in force · agents cannot change anything",
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFF4E1DD)).clickable(onClick = LocalOpenEmergency.current).padding(horizontal = 12.dp, vertical = 7.dp),
            fontSize = 12.sp, color = Color(0xFF97564F), fontWeight = FontWeight.SemiBold,
        )
    }
    }
}

@Composable
private fun BottomBar(route: String, attention: Int, onGo: (Dest) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFFE0E9DF))
            .border(width = 1.dp, color = Color(0x5CA9B9A7), shape = RoundedCornerShape(0.dp))
            .navigationBarsPadding()
            .height(64.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dest.entries.filter { it.tab }.forEach { d ->
            val on = route.startsWith(d.route)
            val color = if (on) Color(0xFF44694B) else Color(0xFF839683)
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Tab, onClickLabel = d.title) { onGo(d) }.padding(horizontal = 14.dp, vertical = 6.dp),
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
                Text(d.title, Modifier.padding(top = 4.dp), fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = color)
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
                Text("PORCELAIN IN MOTION", fontSize = 9.sp, letterSpacing = 2.sp, color = P.muted)
            }
        }
        DrawerItem(Dest.Home, current.startsWith(Dest.Home.route), onGo)
        Dest.entries.filter { it.group != null }.groupBy { it.group!! }.forEach { (group, dests) ->
            Text(group, Modifier.padding(start = 12.dp, top = 18.dp, bottom = 6.dp), fontSize = 10.sp, letterSpacing = 2.4.sp, fontWeight = FontWeight.Bold, color = P.eyebrow)
            dests.forEach { DrawerItem(it, current.startsWith(it.route), onGo) }
        }
    }
}

@Composable
private fun DrawerItem(d: Dest, active: Boolean, onGo: (Dest) -> Unit) {
    val shape = RoundedCornerShape(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .let { if (active) it.background(P.ceramic).border(1.dp, Color(0xA8FFFFFF), shape) else it }
            .clickable(role = Role.Button) { onGo(d) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PIcon(d.icon, size = 18.dp, tint = if (active) Color(0xFF395941) else Color(0xFF647965))
        Text(d.title, Modifier.padding(start = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (active) Color(0xFF395941) else Color(0xFF647965))
    }
}

/** Navigates to [dest] from a nested screen. */
fun NavHostController.go(dest: Dest) = navigate(dest.route) { launchSingleTop = true }
