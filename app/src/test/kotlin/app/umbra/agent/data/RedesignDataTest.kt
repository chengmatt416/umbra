package app.umbra.agent.data

import app.umbra.core.model.UmbraJson
import app.umbra.core.session.ModelRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RedesignDataTest {
    private val loginOutput = """
        Open this URL in your browser:
        https://auth.openai.com/oauth/authorize?client_id=app_x&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback
        Local shortcut (this machine only): http://localhost:1455/launch

        Waiting for browser authentication...
    """.trimIndent()

    @Test
    fun takesTheFullSignInUrlNotTheLocalShortcut() {
        assertEquals("https://auth.openai.com/oauth/authorize?client_id=app_x&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback", OmpLogin.parseUrl(loginOutput))
        assertNull(OmpLogin.parseDone(loginOutput))
        assertNull(OmpLogin.parseFailure(loginOutput))
    }

    @Test
    fun readsWhoSignedInOrWhyItFailed() {
        assertEquals("pat@example.com (Acme)", OmpLogin.parseDone("$loginOutput\n\nLogged in to OpenAI Codex as pat@example.com (Acme)\nCredentials saved to /x/agent.db"))
        assertEquals("", OmpLogin.parseDone("Logged in to Google Antigravity\n"))
        assertEquals("Port 1455 is in use", OmpLogin.parseFailure("Login failed: Port 1455 is in use\n"))
    }

    @Test
    fun autoPicksTheRecommendedModelOfTheFirstConnectedProvider() {
        val directory = UmbraJson.decodeFromString(Directory.serializer(), File(siteFile()).readText())
        val none = AppPrefs()
        assertNull(ModelDirectory.pick(directory, none))
        val anthropic = AppPrefs(providers = listOf(ProviderConfig("anthropic", "Anthropic", apiKeyEnv = "ANTHROPIC_API_KEY")))
        val pick = ModelDirectory.pick(directory, anthropic)!!
        assertEquals("anthropic", pick.provider)
        assertTrue(directory.providers.first { it.id == "anthropic" }.models.first { it.id == pick.modelId }.recommended)
        // A provider the directory does not list (a custom one) gives no automatic pick.
        assertNull(ModelDirectory.pick(directory, AppPrefs(providers = listOf(ProviderConfig("lan", "LAN", custom = true)))))
    }

    @Test
    fun theDirectoryOnTheSiteAndInTheAppAreTheSame() {
        assertEquals(File(siteFile()).readText(), File(siteFile()).parentFile!!.resolve("../../app/src/main/assets/models.json").readText())
    }

    @Test
    fun aChosenModelSavedBeforeAutoStaysChosen() {
        val chosen = AppPrefs(defaultModel = ModelRef("anthropic", "claude-opus-5-5"), autoModel = false)
        val text = UmbraJson.encodeToString(AppPrefs.serializer(), chosen)
        assertTrue("\"autoModel\"" in text, "autoModel must always be written: $text")
        assertTrue("\"autoModel\"" in UmbraJson.encodeToString(AppPrefs.serializer(), AppPrefs()))
    }

}

internal fun siteFile(): String = generateSequence(File("").absoluteFile) { it.parentFile }.map { it.resolve("website/public/models.json") }.first { it.isFile }.path
