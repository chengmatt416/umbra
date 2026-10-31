package app.umbra.core.session

import app.umbra.core.model.UmbraJson
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

class SessionSkillsTest {
    @TempDir
    lateinit var dir: Path

    @Test fun sessionsStoredBeforeSkillSelectionKeepAllDiscoveredSkills() {
        val old = """{"id":"old","title":"Old","createdAtMs":1,"request":"Task"}"""
        assertNull(UmbraJson.decodeFromString(Session.serializer(), old).skills)
    }

    @Test fun nullEmptyAndSelectedSkillsSurviveSerializationStorageAndDuplication(): Unit = runBlocking {
        val memory = InMemorySessionStore()
        val disk = FileSessionStore(dir)
        listOf(null, emptyList(), listOf("android", "review")).forEachIndexed { index, skills ->
            val session = Session("s$index", "Task", createdAtMs = 1, request = "Task", skills = skills)
            val encoded = UmbraJson.encodeToString(Session.serializer(), session)
            assertEquals(skills, UmbraJson.decodeFromString(Session.serializer(), encoded).skills)
            for (store in listOf(memory, disk)) {
                store.create(session)
                assertEquals(skills, store.get(session.id)?.skills)
                assertEquals(skills, store.duplicate(session.id, "copy$index")?.skills)
                store.update(session.id) { it.copy(title = "Updated") }
                assertEquals(skills, store.get(session.id)?.skills)
            }
            assertEquals(skills, FileSessionStore(dir).get(session.id)?.skills)
        }
    }
}
