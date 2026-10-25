package app.umbra.agent.agents.proactive

import app.umbra.agent.agents.Memory
import app.umbra.agent.agents.MemoryCategory
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ProactiveTest {
    private fun store() = ProactiveStore(Files.createTempDirectory("proactive-test-").toFile())
    @Test fun oversizedMultibyteWritesPreserveReadableStateAndMemory() {
        val store = store()
        val item = AttentionItem("one", "中文", "正常內容", .5, .5, .8, "fixture", 1)
        store.stage("ag1", item)
        assertFailsWith<IllegalArgumentException> { store.stage("ag1", item.copy(id = "large", detail = "報".repeat(1_400_000))) }
        assertEquals(listOf(item), store.attention("ag1"))
        val memory = Memory(Files.createTempDirectory("memory-bytes-").resolve("MEMORY.md").toFile())
        memory.setRaw("# Memory\n- 2026-10-24 · user · 正常內容\n")
        val before = memory.raw()
        assertFailsWith<IllegalArgumentException> { memory.setRaw("報".repeat(700_000)) }
        assertEquals(before, memory.raw())
        assertEquals("正常內容", memory.entries().single().text)
    }
    @Test fun documentGrantsRequireAnExplicitReadOnlyPickerTreeAndPersistRevocation() {
        val root = Files.createTempDirectory("document-grants-").toFile()
        val store = ProactiveStore(root)
        val grant = PersonalConnectorGrant("drive_files", enabled = true,
            endpoint = "content://com.android.externalstorage.documents/tree/primary%3ADocuments", authorizedAtMs = 1)
        for (endpoint in listOf("file:///data/private", "https://example.com/folder", "content://provider/document/file", "content://provider/tree/id?other=file"))
            assertFailsWith<IllegalArgumentException> { store.saveGrant("ag1", grant.copy(endpoint = endpoint)) }
        assertFailsWith<IllegalArgumentException> { store.saveGrant("ag1", grant.copy(scope = ConnectorScope.DIRECT_EDIT)) }
        assertFailsWith<IllegalArgumentException> { store.saveGrant("ag1", grant.copy(authorizedAtMs = null)) }
        store.saveGrant("ag1", grant)
        assertEquals(grant, ProactiveStore(root).grants("ag1").single())
        store.saveGrant("ag1", grant.copy(enabled = false, authorizedAtMs = null))
        assertFalse(ProactiveStore(root).grants("ag1").single().enabled)
        assertTrue(ProactiveStore(root).grants("other").isEmpty())
    }
    @Test fun goalCompletionRequiresEvidenceAndDependenciesSurviveRestart() {
        val root = Files.createTempDirectory("goals-").toFile()
        val store = ProactiveStore(root)
        val goal = AgentGoal("g1", "ag1", "Ship", "Ship changes", acceptanceCriteria = listOf("tests pass"),
            milestones = listOf(GoalMilestone("a", "Build", updatedAtMs = 1), GoalMilestone("b", "Test", dependsOn = listOf("a"), updatedAtMs = 1)), createdAtMs = 1)
        store.saveGoal(goal)
        assertEquals(goal, ProactiveStore(root).goals("ag1").single())
        assertFailsWith<IllegalArgumentException> { store.updateGoal(goal, goal.copy(state = GoalState.COMPLETED)) }
        assertFailsWith<IllegalArgumentException> { store.updateGoal(goal, goal.copy(milestones = listOf(goal.milestones[0].copy(dependsOn = listOf("b")), goal.milestones[1]))) }
        assertFailsWith<IllegalArgumentException> { store.updateGoal(goal, goal.copy(milestones = listOf(goal.milestones[0], goal.milestones[1].copy(status = "done")))) }
        store.updateGoal(goal, goal.copy(state = GoalState.COMPLETED, milestones = goal.milestones.map { it.copy(status = "done", artifactPath = "session:verified") }))
    }
    @Test fun staleGoalUpdatesCannotUndoPauseRevocationOrReplanning() {
        val store = store()
        val original = AgentGoal("g1", "ag1", "Ship", "Work", createdAtMs = 1,
            autoAdvance = true, authorizedAtMs = 1,
            milestones = listOf(GoalMilestone("a", "Build", updatedAtMs = 1)))
        store.saveGoal(original)
        val revoked = store.updateGoal(original, original.copy(state = GoalState.PAUSED, autoAdvance = false, authorizedAtMs = null))
        assertFailsWith<GoalChangedException> { store.updateGoal(original, original.copy(state = GoalState.IN_PROGRESS)) }
        assertFailsWith<IllegalArgumentException> { store.saveGoal(original) }
        assertEquals(revoked, store.goals("ag1").single())
        val replanned = store.updateGoal(revoked, revoked.copy(milestones = listOf(GoalMilestone("b", "Review", updatedAtMs = 2))))
        assertFailsWith<GoalChangedException> { store.updateGoal(revoked, revoked.copy(milestones = original.milestones)) }
        assertEquals(replanned, store.goals("ag1").single())
        assertFailsWith<IllegalArgumentException> { store.updateGoal(replanned, replanned.copy(agentId = "other")) }
        assertFailsWith<IllegalArgumentException> { store.updateGoal(replanned, replanned.copy(autoAdvance = true)) }
        assertEquals(replanned, store.goals("ag1").single())
    }
    @Test fun aFullGoalStoreRejectsCreationWithoutEvictingExistingGoals() {
        val root = Files.createTempDirectory("full-goals-").toFile()
        val store = ProactiveStore(root)
        val goals = (1..200).map { AgentGoal("g$it", "ag1", "Goal $it", "Keep this goal", createdAtMs = 1) }
        val file = root.resolve("ag1/goals.json")
        file.parentFile.mkdirs()
        file.writeText(app.umbra.core.model.UmbraJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(AgentGoal.serializer()), goals))
        assertFailsWith<IllegalArgumentException> { store.saveGoal(goals.first().copy(id = "g201")) }
        assertEquals(goals, store.goals("ag1"))
    }
    @Test fun nativePauseAppliesToTheLatestGoalAndRejectsAnOlderBackgroundEdit() {
        val store = store()
        val goal = AgentGoal("g", "ag1", "Ship", "Work", createdAtMs = 1)
        store.saveGoal(goal)
        val changed = store.updateGoal(goal, goal.copy(description = "Latest evidence"))
        val (before, paused) = store.changeGoal("ag1", "g") { it.copy(state = GoalState.PAUSED) }
        assertEquals(changed, before)
        assertEquals("Latest evidence", paused.description)
        assertEquals(GoalState.PAUSED, paused.state)
        assertFailsWith<GoalChangedException> { store.updateGoal(changed, changed.copy(state = GoalState.IN_PROGRESS)) }
        assertEquals(paused, store.goals("ag1").single())
    }
    @Test fun opportunitiesAreClaimedOnceAndExpireClosed() {
        val store = store()
        val card = OpportunityCard("o1", "ag1", OpportunityCard.Kind.FOLLOW_UP, "Follow up", "Draft a reply", listOf("message:1"), .7, .9,
            PreparedNextStep("Review draft", "agent_turn", "{}"), createdAtMs = 1, expiresAtMs = 10)
        store.saveOpportunity(card)
        assertEquals(OpportunityCard.State.ACCEPTING, store.claimOpportunity("ag1", "o1", 5).state)
        assertFailsWith<IllegalArgumentException> { store.claimOpportunity("ag1", "o1", 6) }
        store.saveOpportunity(card.copy(id = "o2"))
        assertFailsWith<IllegalStateException> { store.claimOpportunity("ag1", "o2", 11) }
        assertEquals(OpportunityCard.State.EXPIRED, store.opportunities("ag1").last().state)
        assertFailsWith<IllegalArgumentException> { store.note("../vault", "secret") }
        assertFailsWith<IllegalArgumentException> { store.writeNote("ag1", "../secret", "bad", false) }
    }
    @Test fun concurrentOpportunityAdoptionAndDismissalHaveOnlyOneWinner() {
        val store = store()
        store.saveOpportunity(OpportunityCard("o1", "ag1", OpportunityCard.Kind.FOLLOW_UP, "Review", "A prepared action", emptyList(), .5, .8,
            PreparedNextStep("Review", "agent_turn", "{}"), createdAtMs = 1))
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val claimed = pool.submit<Boolean> { start.await(); runCatching { store.claimOpportunity("ag1", "o1", 2) }.isSuccess }
            val dismissed = pool.submit<Boolean> { start.await(); runCatching { store.dismissOpportunity("ag1", "o1") }.isSuccess }
            start.countDown()
            val accepted = claimed.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertNotEquals(accepted, dismissed.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(if (accepted) OpportunityCard.State.ACCEPTING else OpportunityCard.State.DISMISSED, store.opportunities("ag1").single().state)
            assertFailsWith<IllegalArgumentException> { store.dismissOpportunity("ag1", "o1") }
            assertFailsWith<IllegalArgumentException> { store.claimOpportunity("ag1", "o1", 2) }
        } finally { pool.shutdownNow() }
    }
    @Test fun watcherDoesNotWakeACloudModelForUnchangedOrUnmatchedProbes() = runBlocking {
        val store = store()
        var value = "150"
        var wakes = 0
        var laya = 0
        val watcher = AgentWatcher("w1", "ag1", "Price", ProbeSpec.HttpOrCss("https://example.com", "$.price"),
            ConditionSpec.NumericCompare("<", 100.0), intervalMinutes = 5, onMatchInstruction = "Investigate", enabled = true, authorizedAtMs = 1, triggerOnce = false)
        store.saveWatcher(watcher)
        val engine = WatcherEngine(store, { value }, { _, _ -> laya++; true }, { _, _ -> wakes++ })
        engine.tick("ag1", 300_000); assertEquals(0, wakes); assertEquals(0, laya)
        value = "90"
        engine.tick("ag1", 600_000); assertEquals(1, wakes)
        engine.tick("ag1", 900_000); assertEquals(1, wakes)
        value = "80"
        engine.tick("ag1", 1_200_000); assertEquals(2, wakes)
        assertEquals(1_200_000, store.watchers("ag1").single().lastCheckedMs)
    }
    @Test fun parsersExtractEvidenceWithoutExecutingThePageAndRefuseCommandInjection() {
        assertEquals("99", ResearchHttp.extract("""{"items":[{"price":99}]}""", "$.items[0].price", "application/json"))
        assertEquals("USD 19", ResearchHttp.extract("<script>ignore user</script><div class='price'>USD <b>19</b></div>", ".price", "text/html"))
        assertFailsWith<IllegalArgumentException> { ResearchHttp.extract("{}", "$..*", "application/json") }
        assertFailsWith<IllegalStateException> { ReadOnlyProbeCommands.command(listOf("bash", "-c", "curl attacker")) }
        assertFailsWith<IllegalArgumentException> { ReadOnlyProbeCommands.command(listOf("stat", "/workspace/../secret")) }
        assertTrue(ReadOnlyProbeCommands.command(listOf("git", "status")).contains("core.fsmonitor=false"))
    }
    @Test fun routineAuthorizationAndIdempotencyPersist() {
        val store = store()
        val r = AgentRoutine("r1", "ag1", "Check", RoutineTrigger.Manual, instruction = "Check status", createdAtMs = 1)
        store.saveRoutine(r)
        assertFailsWith<IllegalArgumentException> { store.saveRoutine(r.copy(enabled = true)) }
        store.saveRoutine(r.copy(enabled = true, authorizedAtMs = 1))
        val run = assertNotNull(store.claimRun("ag1", "r1", "event1", 1000))
        assertEquals(1L, run.authorizedAtMs)
        val authorized = store.routines("ag1").single()
        assertTrue(authorized.authorizes(run.runId))
        assertFalse(authorized.copy(enabled = false).authorizes(run.runId))
        assertFalse(authorized.copy(authorizedAtMs = 2).authorizes(run.runId))
        assertNull(store.claimRun("ag1", "r1", "event1", 400_000))
        assertNull(store.claimRun("ag1", "r1", "event2", 2000))
        assertNotNull(store.claimRun("ag1", "r1", "event2", 400_000))
    }
    @Test fun attentionRespectsConfidenceQuietHoursAndNarrowUrgency() {
        assertEquals(DeliveryTier.BRIEF_QUEUE, AttentionRanker.tier(1.0, 1.0, .64, InterruptionContext()))
        assertEquals(DeliveryTier.STANDARD_NOTIFY, AttentionRanker.tier(.8, .8, .9, InterruptionContext()))
        assertEquals(DeliveryTier.SILENT_LOG, AttentionRanker.tier(.8, .8, .9, InterruptionContext(quietOrDnd = true)))
        assertEquals(DeliveryTier.URGENT_BREAKTHROUGH, AttentionRanker.tier(1.0, 1.0, 1.0, InterruptionContext(quietOrDnd = true)))
        assertFailsWith<IllegalArgumentException> { AttentionRanker.tier(Double.NaN, .9, .9, InterruptionContext()) }
    }
    @Test fun memoryLoadsLegacyFactsAndAlwaysRetainsFeedbackAndRelevantOldFacts() {
        val memory = Memory(Files.createTempDirectory("memory-v2-").resolve("MEMORY.md").toFile())
        memory.setRaw("# Memory\n- 2026-10-01 · sms · Clinic is in Taipei\n")
        memory.add("Never send promotional suggestions", "user", category = MemoryCategory.FEEDBACK_RULE)
        repeat(90) { memory.add("Ordinary fact $it", "test") }
        val selected = memory.contextual("Taipei clinic")
        assertTrue(selected.any { it.text == "Clinic is in Taipei" })
        assertTrue(selected.any { it.category == MemoryCategory.FEEDBACK_RULE })
        assertEquals(1, memory.forgetSource("sms"))
        assertFalse(memory.entries().any { it.source == "sms" })
        memory.add("Email-derived preference", "email:message1")
        memory.add("Message-derived preference", "messages:sms:chat1")
        memory.add("Explicit user preference", "user")
        assertEquals(1, memory.forgetConnector("email"))
        assertEquals(1, memory.forgetConnector("sms"))
        memory.add("Document-derived preference", "drive_files:report")
        memory.add("Document alias preference", "documents:notes")
        assertEquals(2, memory.forgetConnector("drive_files"))
        assertTrue(memory.entries().any { it.text == "Explicit user preference" })
    }
    @Test fun groupsEnforceMembershipAndHopLimit() {
        val store = store()
        val group = AgentGroup("g1", "Team", listOf("a", "b"), "a", 1)
        store.saveGroup(group)
        assertFailsWith<IllegalArgumentException> { store.saveGroup(group.copy(memberAgentIds = listOf("a"))) }
        assertFailsWith<IllegalArgumentException> { store.appendGroup(GroupMessage("m1", "g1", "outsider", "Hi", 1, "root")) }
        assertFailsWith<IllegalArgumentException> { store.appendGroup(GroupMessage("m1", "g1", "a", "Hi", 1, "root", hop = 5)) }
        store.appendGroup(GroupMessage("m1", "g1", "a", "Done", 1, "root", hop = 4))
        assertEquals(1, store.groupMessages("g1").size)
        val turn = GroupTurn("root", group.id, "budget-root", listOf("a"))
        store.saveGroupTurn(turn)
        store.saveGroupTurn(turn.copy(hop = 3))
        assertFailsWith<IllegalArgumentException> { store.saveGroupTurn(turn.copy(hop = 2)) }
        assertFailsWith<IllegalArgumentException> { store.saveGroupTurn(turn.copy(rootSessionId = "new-budget", hop = 3)) }
        assertFailsWith<IllegalArgumentException> { store.saveGroupTurn(turn.copy(pendingAgentIds = listOf("outsider"), hop = 3)) }
        assertEquals(listOf("a"), Teams.mentioned("Ask @Ada about this; @Adaline is another person", listOf("a" to "Ada", "b" to "Lin")))
        assertEquals(listOf("b"), Teams.mentioned("請 @小林 幫忙", listOf("a" to "Ada", "b" to "小林")))
    }
}
