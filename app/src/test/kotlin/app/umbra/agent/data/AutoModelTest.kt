package app.umbra.agent.data

import app.umbra.core.decision.Question
import app.umbra.core.model.UmbraJson
import app.umbra.core.session.Effort
import app.umbra.core.session.ModelRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoModelTest {
    private val directory = UmbraJson.decodeFromString(Directory.serializer(), File(siteFile()).readText())
    private val openai = ProviderConfig("openai-codex", "OpenAI", oauth = true)
    private val anthropic = ProviderConfig("anthropic", "Anthropic", apiKeyEnv = "ANTHROPIC_API_KEY")

    @Test
    fun offersOnlyConnectedModelsEachAccountsEverydayModelFirst() {
        val choices = AutoModel.options(directory, AppPrefs(providers = listOf(openai, anthropic)), pictures = false)
        assertTrue(choices.size in 2..AutoModel.MAX_CHOICES)
        assertTrue(choices.all { it.ref.provider in setOf("openai-codex", "anthropic") })
        // round-robin: the two everyday models lead, one per account
        assertEquals(setOf(ModelRef("openai-codex", "gpt-6.1-sol"), ModelRef("anthropic", "claude-sonnet-5-5")), choices.take(2).map { it.ref }.toSet())
        assertEquals(choices.size, choices.map { it.id }.toSet().size)
        assertTrue(choices.first().description.contains("everyday model"))
    }

    @Test
    fun nothingConnectedLeavesNothingToChoose() {
        assertTrue(AutoModel.options(directory, AppPrefs(), pictures = false).isEmpty())
    }

    @Test
    fun aTaskWithAPictureOnlyGoesToModelsThatReadPictures() {
        val text = ProviderConfig("local", "Local", custom = true, baseUrl = "http://127.0.0.1:1", models = listOf("text-only"), images = false)
        val choices = AutoModel.options(directory, AppPrefs(providers = listOf(anthropic, text)), pictures = true)
        assertTrue(choices.none { it.ref.provider == "local" })
        assertTrue(AutoModel.options(directory, AppPrefs(providers = listOf(anthropic, text)), pictures = false).any { it.ref == ModelRef("local", "text-only") })
    }

    @Test
    fun theQuestionNamesEveryChoice() {
        val choices = AutoModel.options(directory, AppPrefs(providers = listOf(openai, anthropic)), pictures = false)
        val q = AutoModel.question(choices)
        assertEquals(choices.map { it.id }, q.options.map { it.id })
        assertTrue(AutoModel.state("Fix the failing build", pictures = true).contains("picture"))
        assertTrue(q is Question.Choice)
    }

    @Test
    fun autoEffortMapsLayasClassToALevelTheModelHas() {
        val prefs = AppPrefs()
        assertEquals(Effort("low", auto = true), AutoModel.effort(prefs, "trivial", listOf("low", "medium", "high")))
        assertEquals(Effort("high", auto = true), AutoModel.effort(prefs, "moderate", emptyList()))
        // Gemini 3.1 Pro has only low and high: "hard" (xhigh) becomes high.
        assertEquals(Effort("high", auto = true), AutoModel.effort(prefs, "hard", listOf("low", "high")))
    }

    @Test
    fun withoutLayaAutoEffortIsOmpsOwnJudge() {
        assertEquals(Effort(Effort.OMP_AUTO, auto = true), AutoModel.effort(AppPrefs(), null, listOf("low", "high")))
    }

    @Test
    fun aLevelTheUserSetIsKeptAndNotAuto() {
        assertEquals(Effort("medium"), AutoModel.effort(AppPrefs(autoEffort = false, thinkingLevel = "medium"), "hard", emptyList()))
        assertEquals(null, AutoModel.effort(AppPrefs(autoEffort = false), "hard", emptyList()))
        val q = AutoModel.effortQuestion()
        assertEquals(AutoModel.EFFORT_BY_CLASS.keys, q.options.map { it.id }.toSet())
    }

    @Test
    fun theSiteListGivesEachReasoningModelItsEfforts() {
        val sonnet = directory.providers.first { it.id == "anthropic" }.models.first { it.id == "claude-sonnet-5-5" }
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), sonnet.efforts)
    }

    @Test
    fun modelsTheNewOmpDroppedMoveToTheirReplacement() {
        assertEquals(ModelRef("openai-codex", "gpt-6.1-sol"), ModelRenames.current(ModelRef("openai-codex", "gpt-5.5")))
        assertEquals(ModelRef("google-antigravity", "claude-opus-5-5"), ModelRenames.current(ModelRef("google-antigravity", "claude-opus-5-5-medium")))
        assertEquals(ModelRef("openai", "gpt-5.5"), ModelRenames.current(ModelRef("openai", "gpt-5.5")))
    }
}
