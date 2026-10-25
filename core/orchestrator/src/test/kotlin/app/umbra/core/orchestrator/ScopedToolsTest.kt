package app.umbra.core.orchestrator

import kotlin.test.*

class ScopedToolsTest {
    @Test fun routineCapabilitiesCoverEachRemoteAndPersonalToolWithoutEnablingOtherSources() {
        val remote = setOf("remote_nodes")
        assertTrue(ScopedTools.allows("remote_nodes", remote))
        assertTrue(ScopedTools.allows("node_job", remote))
        assertTrue(ScopedTools.allows("remote_screen", remote))
        assertFalse(ScopedTools.allows("remote_screen", emptySet()))
        assertFalse(ScopedTools.allows("calendar_write", remote))
        assertFalse(ScopedTools.allows("messages_send", remote))
        assertTrue(ScopedTools.allows("handoff_delegate", setOf("agents")))
        assertFalse(ScopedTools.allows("handoff_delegate", setOf("calendar")))
        assertFalse(ScopedTools.allows("timer_add", setOf("calendar", "agents", "coding")))
        assertTrue(ScopedTools.allows("request_credential_handoff", setOf("android")))
        assertFalse(ScopedTools.allows("request_credential_handoff", setOf("coding", "remote_nodes")))
    }
    @Test fun discoveryOnlyAllowsReviewedPureTransformsAndPrivateGroupReads() {
        assertTrue(DiscoveryTools.allows("tool_run"))
        assertTrue(DiscoveryTools.allows("group_read"))
        listOf("tool_synthesize", "skill_test", "skill_extract_from_session", "handoff_delegate", "group_send", "email_send", "calendar_write", "calendar_update", "calendar_delete", "request_credential_handoff", "script_run").forEach { assertFalse(DiscoveryTools.allows(it), it) }
    }
    @Test fun synthesizedScriptsNeedTheLinuxOrToolsScope() {
        assertTrue(ScopedTools.allows("script_run", setOf("linux")))
        assertTrue(ScopedTools.allows("script_run", setOf("tools")))
        assertFalse(ScopedTools.allows("script_run", setOf("calendar", "web", "android")))
    }
}
