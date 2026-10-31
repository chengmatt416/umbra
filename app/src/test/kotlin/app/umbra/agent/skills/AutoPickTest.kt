package app.umbra.agent.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AutoPickTest {
    private fun skill(name: String, description: String) = InstalledSkill(name, description, SkillScope.GLOBAL, folder = "/workspace/.omp/skills/$name")

    @Test fun ranksRelevantNamesAndDescriptionsAndIgnoresGenericOverlap() {
        val installed = listOf(skill("android", "Build and test Android Kotlin apps"), skill("spreadsheets", "Analyze financial spreadsheets"),
            skill("helper", "Use this skill to help with your task"), skill("mobile", "Kotlin apps and their build setup"))
        assertEquals(listOf("android", "mobile"), InstalledSkills.autoPick("Build Android Kotlin apps", installed))
        assertEquals(emptyList(), InstalledSkills.autoPick("Please help with this task", installed))
        assertEquals(emptyList(), InstalledSkills.autoPick("Plan dinner", installed))
    }

    @Test fun cjkTasksShareTheEvidenceRankerTokenizer() {
        assertEquals(listOf("database"), InstalledSkills.autoPick("請幫我優化資料庫查詢", listOf(skill("database", "資料庫查詢效能優化"))))
    }

    @Test fun alwaysSkillsSurviveTheCapAndUnknownPreferencesAreIgnored() {
        val installed = listOf(skill("android", "Android Kotlin"), skill("review", "Review changes"), skill("safety", "Check results"))
        assertEquals(listOf("review", "safety"), InstalledSkills.autoPick("Android Kotlin", installed, max = 1, always = setOf("safety", "review", "missing")))
        assertEquals(listOf("review", "android"), InstalledSkills.autoPick("Android Kotlin", installed, max = 2, always = setOf("review")))
        assertEquals(emptyList(), InstalledSkills.autoPick("Android", installed, max = 0))
        assertFailsWith<IllegalArgumentException> { InstalledSkills.autoPick("Android", installed, max = -1) }
    }

    @Test fun duplicatesAndTiesAreStable() {
        val a = skill("alpha", "Android Kotlin")
        val b = skill("beta", "Android Kotlin")
        assertEquals(listOf("alpha", "beta"), InstalledSkills.autoPick("Android Kotlin", listOf(b, a, a.copy(scope = SkillScope.PROJECT))))
        assertEquals(listOf("alpha"), InstalledSkills.autoPick("Android Kotlin", listOf(b, a), max = 1))
    }

    @Test fun installationNamesCannotInjectGlobsOrArguments() {
        listOf("*", "a?", "[abc]", "../name", "a,b", "--no-skills", "a b", "a\nb", "a\\b", "").forEach {
            assertFailsWith<IllegalArgumentException>(it) { InstalledSkills.validateName(it) }
        }
        listOf("android", "kotlin-review", "資料庫", "name_2.v1").forEach(InstalledSkills::validateName)
    }

    @Test fun aProjectsDescriptionWinsWhenItsSkillOverridesAGlobalName() {
        val global = skill("review", "Review financial spreadsheets")
        val project = global.copy(description = "Android Kotlin review", scope = SkillScope.PROJECT, projectId = "android")
        assertEquals(listOf("review"), InstalledSkills.autoPick("Android Kotlin", listOf(global, project)))
        assertEquals(emptyList(), InstalledSkills.autoPick("Financial spreadsheets", listOf(global, project)))
    }
}
