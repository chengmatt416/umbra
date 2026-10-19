package app.umbra.agent.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Something attached to the next message: a screen selection (its cropped picture and the accessibility text inside
 * it), or another piece of context. [imagePath] is a private file that is deleted with the chip.
 */
data class ContextChip(
    val id: String,
    val label: String,
    val kind: String,
    val imagePath: String? = null,
    val text: String? = null,
    val capturedAtMs: Long = 0,
) {
    companion object {
        const val SNAPSHOT = "snapshot"
    }
}

/**
 * Presentation state (architecture spec §8.1): which session the Current tab shows and the shared composer draft.
 * It is never taken as evidence that anything runs (INV-001): screens read run state from the runners.
 */
class UiState(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    /** Where screen selections are kept until they are sent or removed. Nothing survives a restart of the app. */
    val snapshotDir: File = File(context.cacheDir, "snapshots").apply { deleteRecursively(); mkdirs() }

    private val focused = MutableStateFlow(prefs.getString(KEY_FOCUS, null))
    val focusedSession: StateFlow<String?> = focused.asStateFlow()

    fun focus(sessionId: String?) {
        focused.value = sessionId
        prefs.edit().putString(KEY_FOCUS, sessionId).apply()
    }

    /** One draft shared by every composer (Home, the Capsule's, the bottom Quick Composer): §3.1. */
    val draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val draftContext = MutableStateFlow<List<ContextChip>>(emptyList())

    /** A composer on Home is listening (its field became the voice panel); Home steps back around it. */
    val listening = MutableStateFlow(false)

    /** A sheet Home should open ("agent", "coding"), asked for from elsewhere (the Capsule, a link). */
    val homeSheet = MutableStateFlow<String?>(null)

    fun saveDraft(text: String) {
        draft.value = text
        prefs.edit().putString(KEY_DRAFT, text).apply()
    }

    /** Attaches [chip]. One snapshot at a time (§3.1): a new selection replaces the previous one. */
    @Synchronized
    fun addContext(chip: ContextChip) {
        val replaced = if (chip.kind == ContextChip.SNAPSHOT) draftContext.value.filter { it.kind == ContextChip.SNAPSHOT } else emptyList()
        replaced.forEach { delete(it) }
        draftContext.value = draftContext.value - replaced.toSet() + chip
    }

    @Synchronized
    fun removeContext(id: String) {
        draftContext.value.filter { it.id == id }.forEach { delete(it) }
        draftContext.value = draftContext.value.filterNot { it.id == id }
    }

    /** After a send: the message took the context with it. */
    @Synchronized
    fun clearSent(sent: List<ContextChip>) {
        sent.forEach { delete(it) }
        draftContext.value = draftContext.value - sent.toSet()
    }

    private fun delete(chip: ContextChip) {
        chip.imagePath?.let { File(it).delete() }
    }

    /** Whether Umbra's own screen is in front: the Capsule then stays collapsed, the app shows the same things. */
    val appVisible = MutableStateFlow(false)

    /** Where a Capsule composer's message goes: a session id, or null for new work. Not persisted. */
    val composerTarget = MutableStateFlow<String?>(null)

    /** The project new work goes to from Home; null = an independent session. */
    val homeProject = MutableStateFlow(prefs.getString(KEY_PROJECT, null))

    fun setHomeProject(id: String?) {
        homeProject.value = id
        prefs.edit().putString(KEY_PROJECT, id).apply()
    }

    /** The Home filter (Power and Dev); links to the old Inbox open it on "needs you". */
    val homeFilter = MutableStateFlow(HomeFilter.ALL)

    private companion object {
        const val KEY_FOCUS = "focused_session"
        const val KEY_DRAFT = "draft"
        const val KEY_PROJECT = "home_project"
    }
}

/** What the Home task list shows. The old Inbox is the "needs you" filter. */
enum class HomeFilter { ALL, NEEDS, RUNNING, QUEUED }
