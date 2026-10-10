package app.umbra.agent.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Something attached to the next message: a screen snapshot, a selection, a file. */
data class ContextChip(val id: String, val label: String, val kind: String, val imagePath: String? = null, val text: String? = null)

/**
 * Presentation state (architecture spec §8.1): which session the Current tab shows and the shared composer draft.
 * It is never taken as evidence that anything runs (INV-001): screens read run state from the runners.
 */
class UiState(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val focused = MutableStateFlow(prefs.getString(KEY_FOCUS, null))
    val focusedSession: StateFlow<String?> = focused.asStateFlow()

    fun focus(sessionId: String?) {
        focused.value = sessionId
        prefs.edit().putString(KEY_FOCUS, sessionId).apply()
    }

    /** One draft shared by every composer that starts new work (Home, Capsule): §3.1. */
    val draft = MutableStateFlow(prefs.getString(KEY_DRAFT, "").orEmpty())
    val draftContext = MutableStateFlow<List<ContextChip>>(emptyList())

    fun saveDraft(text: String) {
        draft.value = text
        prefs.edit().putString(KEY_DRAFT, text).apply()
    }

    /** The project new work goes to from Home; null = an independent session. */
    val homeProject = MutableStateFlow(prefs.getString(KEY_PROJECT, null))

    fun setHomeProject(id: String?) {
        homeProject.value = id
        prefs.edit().putString(KEY_PROJECT, id).apply()
    }

    private companion object {
        const val KEY_FOCUS = "focused_session"
        const val KEY_DRAFT = "draft"
        const val KEY_PROJECT = "home_project"
    }
}
