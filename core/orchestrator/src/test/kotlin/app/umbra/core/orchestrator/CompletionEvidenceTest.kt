package app.umbra.core.orchestrator

import app.umbra.core.session.*
import kotlin.test.*
import kotlinx.serialization.json.*

class CompletionEvidenceTest {
    private val session = Session("s1", "Task", createdAtMs = 1, request = "Build a feature")
    private fun events(vararg events: SessionEvent) = events.mapIndexed { i, event -> EventRecord(i.toLong(), i.toLong(), event) }
    private fun call(id: String, tool: String, command: String? = null) = SessionEvent.ToolCall(id, tool, "omp", command?.let { buildJsonObject { put("command", it) } })
    private fun verified(bundle: JsonObject) = (bundle["verification"] as JsonObject)["verified"] == JsonPrimitive(true)
    @Test fun newUserTurnsDoNotRepublishAnEarlierArtifactOrItsReview() {
        val declared = buildJsonObject {
            put("turnStartSeq", 0)
            put("artifacts", buildJsonArray { add(buildJsonObject { put("title", "Previous report") }) })
            put("gitSummary", "Previous change")
            put("verification", buildJsonObject { put("residualRisks", buildJsonArray { add("Previous warning") }) })
        }
        val owner = session.copy(attachments = mapOf(CompletionEvidence.KEY to declared))
        val first = events(SessionEvent.UserMessage("First report"))
        val receipt = CompletionEvidence.bundle(owner, first, "First result")
        assertEquals(1, receipt["artifacts"]?.jsonArray?.size)
        val next = CompletionEvidence.bundle(owner.copy(attachments = mapOf(CompletionEvidence.KEY to receipt)), first + EventRecord(1, 1, SessionEvent.UserMessage("New report")), "New result")
        assertEquals(0, next["artifacts"]?.jsonArray?.size)
        assertNull(next["gitSummary"])
        assertFalse(next.toString().contains("Previous warning"))
    }
    @Test fun modelClaimsCannotReplaceRecordedVerification() {
        val claimed = buildJsonObject { put("verification", buildJsonObject { put("verified", true) }) }
        val output = CompletionEvidence.bundle(session.copy(attachments = mapOf(CompletionEvidence.KEY to claimed)), events(SessionEvent.UserMessage("Do it")), "Done")
        assertFalse(verified(output))
        assertTrue(output.toString().contains("No independent verification"))
    }
    @Test fun failedTruncatedOrStaleChecksAreNotSuccessfulReceipts() {
        val first = events(SessionEvent.UserMessage("Do it"), call("t", "bash", "./gradlew :app:testDebugUnitTest"), SessionEvent.ToolResult("t", "bash", "ok", "tests passed"))
        assertTrue(verified(CompletionEvidence.bundle(session, first, "Done")))
        assertFalse(verified(CompletionEvidence.bundle(session, first + EventRecord(4, 4, call("edit", "write")) + EventRecord(5, 5, SessionEvent.ToolResult("edit", "write", "ok", "Changed source")), "Done")))
        val truncated = first.dropLast(1) + EventRecord(2, 2, SessionEvent.ToolResult("t", "bash", "ok", "partial", data = buildJsonObject { put("truncated", true) }))
        assertFalse(verified(CompletionEvidence.bundle(session, truncated, "Done")))
        val fake = events(SessionEvent.UserMessage("Do it"), call("t", "bash", "printf 'pytest passed'"), SessionEvent.ToolResult("t", "bash", "ok", "pytest passed"))
        assertFalse(verified(CompletionEvidence.bundle(session, fake, "Done")))
    }
    @Test fun visualPlansNeedFinalProofAndFailuresNeedARealSuccessfulRetry() {
        val plan = events(SessionEvent.UserMessage("Open Settings"), call("p", "android_run_plan"), SessionEvent.ToolResult("p", "android_run_plan", "ok", "Verified"))
        assertFalse(verified(CompletionEvidence.bundle(session, plan, "Done")))
        val proof = SessionEvent.Observation("Final screen", screenshotPath = "proof.jpg", screenshotSha256 = "a".repeat(64), screenshotSizeBytes = 100, screenshotMimeType = "image/jpeg")
        val receipt = CompletionEvidence.bundle(session, plan + EventRecord(4, 4, proof), "Done")
        assertTrue(verified(receipt))
        val evidence = receipt["verification"]!!.jsonObject["visualProof"]!!.jsonObject
        assertEquals("a".repeat(64), evidence["sha256"]!!.jsonPrimitive.content)
        assertEquals(100, evidence["sizeBytes"]!!.jsonPrimitive.int)
        for (invalid in listOf(proof.copy(screenshotSha256 = null), proof.copy(screenshotSha256 = "wrong"), proof.copy(screenshotSizeBytes = 0),
            proof.copy(screenshotSizeBytes = 8_000_001), proof.copy(screenshotMimeType = "text/plain"))) {
            assertFalse(verified(CompletionEvidence.bundle(session, plan + EventRecord(4, 4, invalid), "Done")))
        }
        assertFalse(verified(CompletionEvidence.bundle(session, plan + EventRecord(4, 4, SessionEvent.Observation("Legacy proof", screenshotPath = "proof.jpg")), "Done")))
        val retry = events(SessionEvent.UserMessage("Check it"), call("a", "bash", "pytest"), SessionEvent.ToolResult("a", "bash", "failed", "failed"), call("b", "bash", "pytest"), SessionEvent.ToolResult("b", "bash", "ok", "passed"))
        assertTrue(verified(CompletionEvidence.bundle(session, retry, "Fixed")))
    }
    @Test fun trustedFreshChildReceiptsPreserveDelegatedVerificationWithoutTreatingReportsAsUserAuthority() {
        val child = buildJsonObject { put("sessionId", "child"); put("atMs", 10); put("verified", true) }
        val parent = session.copy(attachments = mapOf("child.verification.child" to child))
        val report = listOf(EventRecord(1, 1, SessionEvent.UserMessage("Build it")), EventRecord(2, 11, SessionEvent.UserMessage("Child report", source = "report")))
        assertTrue(verified(CompletionEvidence.bundle(parent, report, "Done")))
        assertFalse(verified(CompletionEvidence.bundle(parent, report + EventRecord(3, 12, SessionEvent.UserMessage("Now change it")), "Done")))
        assertFalse(verified(CompletionEvidence.bundle(parent, report.drop(1), "Done")))
    }

    @Test fun aSuccessfulCheckCannotVerifyAParentWhileAnyDelegateIsUnfinished() {
        val history = events(SessionEvent.UserMessage("Build it"), call("check", "bash", "pytest"), SessionEvent.ToolResult("check", "bash", "ok", "passed"))
        val children = listOf(
            session.copy(id = "first", spawnedBy = session.id, state = SessionState.COMPLETED),
            session.copy(id = "second", spawnedBy = "first", state = SessionState.PAUSED),
            session.copy(id = "research", spawnedBy = session.id, state = SessionState.RUNNING, kind = SessionKind.AGENT, turnMode = ProactiveTurnMode.READ_ONLY_DISCOVERY),
            session.copy(id = "routine", spawnedBy = session.id, state = SessionState.RUNNING, kind = SessionKind.AGENT, routineRunId = "independent-run"),
            session.copy(id = "routine-child", spawnedBy = "routine", state = SessionState.RUNNING),
            session.copy(id = "unrelated", state = SessionState.RUNNING))
        val pending = CompletionEvidence.pendingDelegates(session.id, children)
        assertEquals(listOf("second"), pending)
        assertTrue(CompletionEvidence.awaitingDelegates(session.copy(state = SessionState.COMPLETED), children))
        assertFalse(CompletionEvidence.awaitingDelegates(session.copy(state = SessionState.FAILED), children))
        val receipt = CompletionEvidence.bundle(session, history, "Waiting for another delegate", pending)
        assertFalse(verified(receipt))
        assertTrue(CompletionEvidence.awaitingDelegateReport(session.copy(attachments = mapOf(CompletionEvidence.KEY to receipt))))
        assertEquals(listOf("second"), receipt["verification"]!!.jsonObject["pendingDelegates"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(verified(CompletionEvidence.bundle(session, history, "Done", CompletionEvidence.pendingDelegates(session.id,
            children.map { if (it.id == "second") it.copy(state = SessionState.COMPLETED) else it }))))
        val settled = CompletionEvidence.bundle(session.copy(attachments = mapOf(CompletionEvidence.KEY to receipt)), history, "Done")
        assertTrue(verified(settled))
        assertFalse(CompletionEvidence.awaitingDelegateReport(session.copy(attachments = mapOf(CompletionEvidence.KEY to settled))))
        assertTrue(CompletionEvidence.awaitingDelegates(session.copy(state = SessionState.COMPLETED, attachments = mapOf(CompletionEvidence.KEY to receipt)), emptyList()))
        assertFalse(CompletionEvidence.awaitingDelegates(session.copy(state = SessionState.COMPLETED, attachments = mapOf(CompletionEvidence.KEY to settled)), emptyList()))
        assertFalse(settled.toString().contains("Delegated work is still active"))
    }

    @Test fun delegateTraversalIncludesQueuedTasksAndRefusesCyclesOrExcessiveDepth() {
        for (state in SessionState.entries) {
            val child = session.copy(id = "child", spawnedBy = session.id, state = state)
            assertEquals(if (state.isTerminal) emptyList() else listOf("child"), CompletionEvidence.pendingDelegates(session.id, listOf(child)))
        }
        val root = session.copy(spawnedBy = "child", state = SessionState.COMPLETED)
        val child = session.copy(id = "child", spawnedBy = session.id, state = SessionState.COMPLETED)
        assertTrue(CompletionEvidence.pendingDelegates(session.id, listOf(root, child)).isNotEmpty())
        val deep = (1..33).map { n -> session.copy(id = "d$n", spawnedBy = if (n == 1) session.id else "d${n - 1}", state = SessionState.COMPLETED) }
        assertTrue(CompletionEvidence.pendingDelegates(session.id, deep).isNotEmpty())
        assertTrue(CompletionEvidence.pendingDelegates(session.id, deep.dropLast(1)).isEmpty())
    }
}
