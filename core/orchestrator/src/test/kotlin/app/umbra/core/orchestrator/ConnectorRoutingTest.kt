package app.umbra.core.orchestrator

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class ConnectorRoutingTest {
    @Test fun webAppsOfEnabledConnectorsAreRoutedAndOthersAreNot() {
        val code = "await tab.goto(\"https://web.whatsapp.com/\"); await tab.click('#side')"
        assertEquals("whatsapp", ConnectorRouting.route(code, setOf("whatsapp", "email"))?.connector)
        assertNull(ConnectorRouting.route(code, setOf("email")))
        assertNull(ConnectorRouting.route("https://example.com/whatsapp.com", setOf("whatsapp")))
        assertEquals("email", ConnectorRouting.route("open https://MAIL.google.com/mail/u/0/#inbox", setOf("email"))?.connector)
        assertEquals("x", ConnectorRouting.route("https://mobile.twitter.com/home", setOf("x"))?.connector)
        assertNull(ConnectorRouting.route("https://notwhatsapp.com", setOf("whatsapp")))
        assertNull(ConnectorRouting.route("https://web.whatsapp.com", emptySet()))
    }

    @Test fun hostCallsRouteAppLaunchesAndUiToolsOnly() {
        val launch = buildJsonObject { put("package", "org.telegram.messenger") }
        assertEquals("telegram", ConnectorRouting.routeHostCall("android_launch", launch, setOf("telegram"))?.connector)
        assertNull(ConnectorRouting.routeHostCall("android_launch", launch, setOf("whatsapp")))
        assertNull(ConnectorRouting.routeHostCall("messages_send", buildJsonObject { put("text", "https://web.telegram.org") }, setOf("telegram")))
        assertEquals("calendar", ConnectorRouting.routeHostCall("gui_open", buildJsonObject { put("url", "https://calendar.google.com/r/week") }, setOf("calendar"))?.connector)
        assertTrue(ConnectorRouting.drivesUi("browser"))
        assertFalse(ConnectorRouting.drivesUi("bash"))
    }

    @Test fun theFirstCallIsRefusedAndADeliberateRepeatProceedsAndIsNoted() = runBlocking {
        val guard = ConnectorRouting.Guard()
        val route = ConnectorRouting.route("https://web.whatsapp.com", setOf("whatsapp"))
        val fallbacks = mutableListOf<String>()
        assertNotNull(guard.check(route) { fallbacks += it.connector })
        assertNull(guard.check(route) { fallbacks += it.connector })
        assertEquals(listOf("whatsapp"), fallbacks)
        assertNull(guard.check(null))
        assertTrue(route!!.message.contains("messages_send"))
    }
}
