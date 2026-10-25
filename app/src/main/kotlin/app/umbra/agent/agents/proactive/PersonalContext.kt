package app.umbra.agent.agents.proactive

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import app.umbra.agent.AppContainer
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import app.umbra.core.orchestrator.ToolAuthority
import app.umbra.core.model.action.ActionRequest
import app.umbra.core.model.action.Operation
import app.umbra.core.safety.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** Every source is opt-in per agent, in addition to Android's own runtime permission. */
class PersonalContext(private val container: AppContainer) {
    private val emailLock = Mutex()
    private val calendarLock = Mutex()
    private fun grant(agentId: String, connector: String): PersonalConnectorGrant? = container.agents.proactive.store.grants(agentId)
        .firstOrNull { it.connectorId == connector && it.enabled && it.authorizedAtMs != null }
    private fun has(permission: String) = container.context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    suspend fun writableCalendars(agentId: String): List<Pair<Long, String>> = withContext(Dispatchers.IO) {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "calendar" in it } != false)
        require(grant(agentId, "calendar") != null && has(Manifest.permission.READ_CALENDAR)) { "Connect calendar first" }
        container.context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null)?.use { cursor ->
            buildList { while (size < 100 && cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1).orEmpty().take(500)) }
        }.orEmpty()
    }
    suspend fun writeCalendar(agentId: String, draft: CalendarDraft, calendarId: Long?): String = calendarLock.withLock {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "calendar" in it } != false)
        draft.validate()
        val access = requireNotNull(grant(agentId, "calendar")) { "Connect calendar first" }
        require(access.scope in setOf(ConnectorScope.PROPOSE_EVENT, ConnectorScope.WRITE_EVENTS)) { "This agent has read-only calendar permission" }
        OutboundDlpGuard(4) { container.protectedValues() }.requireSafe(draft.review())
        val session = requireNotNull(container.sessionStore.get(draft.sessionId))
        require(session.agentId == agentId)
        if (calendarId == null) {
            container.agents.proactive.store.saveCalendarDraft(agentId, draft)
            return@withLock "Proposed ${draft.id}. The user can open this draft in Personal context and save it in their calendar. No calendar event was created."
        }
        require(access.scope == ConnectorScope.WRITE_EVENTS && has(Manifest.permission.WRITE_CALENDAR)) { "Explicit event-write permission is required" }
        require(writableCalendars(agentId).any { it.first == calendarId }) { "Choose an explicitly writable calendar from calendars_list" }
        val origin = container.sessionStore.tail(session.id, 10_000).lastOrNull { (it.event as? app.umbra.core.session.SessionEvent.UserMessage)?.source == "user" }?.seq
            ?: error("Start a fresh task before creating a calendar event")
        val key = "outbound.calendar." + SkillStore.hash("$origin:$calendarId:${draft.review()}")
        (session.attachments[key] as? JsonObject)?.let { old ->
            return@withLock (old["id"] as? JsonPrimitive)?.content ?: error("This event may already exist. Inspect the calendar before making a new user request.")
        }
        val epoch = container.emergency.current().generation
        val outcome = container.safetyGate.check(ActionRequest(java.util.UUID.randomUUID().toString(), Operation.TypeText(draft.description), sessionId = session.id),
            ActionContext(toolName = "calendar_write", recipientOrDomain = "calendar:$calendarId", outboundText = draft.review(), actionDescription = "Create calendar event in calendar $calendarId\n${draft.review()}"),
            additionalAssessment = RiskAssessment.of(RiskFinding(RiskCategory.SYSTEM_CONFIGURATION, RiskLevel.HIGH, "Creates this event in a potentially synchronized calendar")))
        check(outcome is SafetyGate.Outcome.Proceed) { "The event was not approved" }
        container.agents.proactive.checkAuthority(requireNotNull(container.sessionStore.get(session.id)))
        check(container.emergency.lockdownReason() == null && container.emergency.current().generation == epoch && grant(agentId, "calendar") == access && has(Manifest.permission.WRITE_CALENDAR)) { "Calendar authorization changed" }
        require(writableCalendars(agentId).any { it.first == calendarId }) { "Calendar write access changed" }
        container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "pending") })) }
        val uri = withContext(Dispatchers.IO) { container.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId); put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.startMs); put(CalendarContract.Events.DTEND, draft.endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, draft.zoneId); put(CalendarContract.Events.EVENT_LOCATION, draft.location)
            put(CalendarContract.Events.DESCRIPTION, draft.description)
        }) } ?: error("The calendar provider did not confirm creation. Inspect the calendar before retrying.")
        val id = ContentUris.parseId(uri).toString()
        container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "done"); put("id", id) })) }
        "Created event $id in calendar $calendarId. Read the event back to verify it."
    }
    /** An existing event as the provider holds it, for review and for the unchanged-before-write check. */
    internal data class EventRow(val calendarId: Long, val title: String, val startMs: Long, val endMs: Long, val zoneId: String?,
        val location: String, val description: String, val recurring: Boolean, val guests: Int) {
        fun review() = "Title: $title\nStart: ${java.time.Instant.ofEpochMilli(startMs)}\nEnd: ${java.time.Instant.ofEpochMilli(endMs)}\nLocation: $location\n$description"
    }

    private suspend fun event(eventId: Long): EventRow? = withContext(Dispatchers.IO) {
        val resolver = container.context.contentResolver
        val row = resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events.CALENDAR_ID, CalendarContract.Events.TITLE, CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.RRULE, CalendarContract.Events.RDATE, CalendarContract.Events.ORIGINAL_ID, CalendarContract.Events.DELETED),
            null, null, null)?.use { c ->
            if (!c.moveToFirst() || c.getInt(10) == 1) null
            else EventRow(c.getLong(0), c.getString(1).orEmpty().take(500), c.getLong(2), if (c.isNull(3)) c.getLong(2) else c.getLong(3), c.getString(4),
                c.getString(5).orEmpty().take(1000), c.getString(6).orEmpty().take(10_000),
                recurring = !c.getString(7).isNullOrBlank() || !c.getString(8).isNullOrBlank() || !c.isNull(9), guests = 0)
        } ?: return@withContext null
        // Anyone besides the organizer would get an update or cancellation from their calendar server.
        val guests = resolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees._ID),
            "${CalendarContract.Attendees.EVENT_ID} = ? AND ${CalendarContract.Attendees.ATTENDEE_RELATIONSHIP} != ?",
            arrayOf(eventId.toString(), CalendarContract.Attendees.RELATIONSHIP_ORGANIZER.toString()), null)?.use { it.count } ?: 0
        row.copy(guests = guests)
    }

    /**
     * Changes ([update]) or deletes (null) one existing event, on the same path as creation: per-agent WRITE_EVENTS grant,
     * Android permission, a writable calendar, protected-data check, the approval gate with the before/after shown, an
     * authority and lockdown recheck, and at most once per user turn. Recurring events and events with other guests are
     * left to the user, so an agent never sends updates or cancellations to other people.
     */
    suspend fun changeCalendarEvent(agentId: String, sessionId: String, eventId: Long, update: CalendarDraft?): String = calendarLock.withLock {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "calendar" in it } != false)
        val access = requireNotNull(grant(agentId, "calendar")) { "Connect calendar first" }
        require(access.scope == ConnectorScope.WRITE_EVENTS && has(Manifest.permission.WRITE_CALENDAR)) { "Explicit event-write permission is required" }
        val session = requireNotNull(container.sessionStore.get(sessionId))
        require(session.agentId == agentId)
        val before = requireNotNull(event(eventId)) { "No such event. Read the calendar again for its current event IDs." }
        require(writableCalendars(agentId).any { it.first == before.calendarId }) { "This event's calendar is not writable for this agent" }
        require(!before.recurring) { "Recurring events are changed by the user in their calendar app" }
        require(before.guests == 0) { "This event has other guests; the user changes it, so no update or cancellation is sent by an agent" }
        update?.validate()
        val after = update?.review()
        after?.let { OutboundDlpGuard(4) { container.protectedValues() }.requireSafe(it) }
        val origin = container.sessionStore.tail(session.id, 10_000).lastOrNull { (it.event as? app.umbra.core.session.SessionEvent.UserMessage)?.source == "user" }?.seq
            ?: error("Start a fresh task before changing a calendar event")
        val key = "outbound.calendar.change." + SkillStore.hash("$origin:$eventId:${after ?: "delete"}")
        (session.attachments[key] as? JsonObject)?.let { old ->
            return@withLock if ((old["status"] as? JsonPrimitive)?.content == "done") "Already done in this turn." else error("This change may already have reached the calendar. Read the event back before a new request.")
        }
        val description = if (after == null) "Delete calendar event $eventId\n${before.review()}" else "Change calendar event $eventId\nBefore:\n${before.review()}\nAfter:\n$after"
        val epoch = container.emergency.current().generation
        val outcome = container.safetyGate.check(ActionRequest(java.util.UUID.randomUUID().toString(), Operation.TypeText(after ?: "delete event $eventId"), sessionId = session.id),
            ActionContext(toolName = if (after == null) "calendar_delete" else "calendar_update", recipientOrDomain = "calendar:${before.calendarId}", outboundText = after ?: before.review(), actionDescription = description),
            additionalAssessment = RiskAssessment.of(if (after == null) RiskFinding(RiskCategory.IMPORTANT_DATA_DELETION, RiskLevel.HIGH, "Deletes this event from a potentially synchronized calendar")
                else RiskFinding(RiskCategory.SYSTEM_CONFIGURATION, RiskLevel.HIGH, "Changes this event in a potentially synchronized calendar")))
        check(outcome is SafetyGate.Outcome.Proceed) { "The calendar change was not approved" }
        container.agents.proactive.checkAuthority(requireNotNull(container.sessionStore.get(session.id)))
        check(container.emergency.lockdownReason() == null && container.emergency.current().generation == epoch && grant(agentId, "calendar") == access && has(Manifest.permission.WRITE_CALENDAR)) { "Calendar authorization changed" }
        check(event(eventId) == before) { "The event changed while waiting for approval. Read it again and ask for a fresh review." }
        container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "pending") })) }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val changed = withContext(Dispatchers.IO) {
            if (update == null) container.context.contentResolver.delete(uri, null, null)
            else container.context.contentResolver.update(uri, ContentValues().apply {
                put(CalendarContract.Events.TITLE, update.title); put(CalendarContract.Events.DTSTART, update.startMs); put(CalendarContract.Events.DTEND, update.endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, update.zoneId); put(CalendarContract.Events.EVENT_LOCATION, update.location)
                put(CalendarContract.Events.DESCRIPTION, update.description)
            }, null, null)
        }
        check(changed == 1) { "The calendar provider did not confirm the change. Read the event back before retrying." }
        container.sessionStore.update(session.id) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "done"); put("id", eventId.toString()) })) }
        if (update == null) "Deleted event $eventId. Read the calendar back to verify it." else "Changed event $eventId. Read it back to verify."
    }

    /** The current fields of [eventId] when the agent may change it, for filling in what an update leaves out. */
    internal suspend fun eventForUpdate(eventId: Long): EventRow? = event(eventId)

    suspend fun tasks(agentId: String): List<PersonalTask> {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "tasks" in it } != false)
        require(grant(agentId, "tasks") != null) { "Connect local tasks for this agent first" }
        return container.agents.proactive.store.tasks(agentId)
    }
    suspend fun saveTask(agentId: String, task: PersonalTask) {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "tasks" in it } != false)
        require(grant(agentId, "tasks")?.scope == ConnectorScope.WRITE_TASKS) { "This agent has no task-write permission" }
        OutboundDlpGuard(4) { container.protectedValues() }.requireSafe(task.title + "\n" + task.note)
        container.agents.proactive.store.saveTask(agentId, task)
    }
    suspend fun composeEmail(agentId: String, sessionId: String, to: String, subject: String, text: String, send: Boolean): String = emailLock.withLock {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "email" in it } != false)
        val access = requireNotNull(grant(agentId, "email")) { "Connect email first" }
        require(access.scope == ConnectorScope.SEND_WITH_APPROVAL || !send && access.scope == ConnectorScope.DRAFT_ONLY) { "This email scope does not permit that operation" }
        GmailContextApi.message(to, subject, text)
        val content = "To: $to\nSubject: $subject\n$text"
        OutboundDlpGuard(4) { container.protectedValues() }.requireSafe(content)
        val token = access.secretName?.let { container.secrets.get(it) } ?: error("Reconnect email in Personal context")
        val session = requireNotNull(container.sessionStore.get(sessionId))
        require(session.agentId == agentId)
        val origin = container.sessionStore.tail(sessionId, 10_000).lastOrNull { (it.event as? app.umbra.core.session.SessionEvent.UserMessage)?.source == "user" }?.seq
            ?: error("The originating user turn is unavailable. Start a fresh task before sending email.")
        val key = "outbound.email." + SkillStore.hash("$origin:$send:$content")
        (session.attachments[key] as? JsonObject)?.let { previous ->
            return@withLock (previous["id"] as? JsonPrimitive)?.content ?: error("This write may already have reached Gmail. Inspect Drafts or Sent before issuing a new user request.")
        }
        val epoch = container.emergency.current().generation
        val outcome = container.safetyGate.check(ActionRequest(java.util.UUID.randomUUID().toString(), Operation.TypeText(text), sessionId = sessionId),
            ActionContext(toolName = if (send) "email_send" else "email_draft", recipientOrDomain = to, outboundText = content, actionDescription = "${if (send) "Send email" else "Create Gmail draft"}\n$content"),
            additionalAssessment = RiskAssessment.of(RiskFinding(if (send) RiskCategory.SENDS_MESSAGE else RiskCategory.SYSTEM_CONFIGURATION, if (send) RiskLevel.HIGH else RiskLevel.MEDIUM, if (send) "Sends this email to another person" else "Creates an unsent draft in the connected Gmail account")))
        check(outcome is SafetyGate.Outcome.Proceed) { "Email was not approved or requires user takeover" }
        container.agents.proactive.checkAuthority(requireNotNull(container.sessionStore.get(sessionId)))
        check(container.emergency.lockdownReason() == null && container.emergency.current().generation == epoch && grant(agentId, "email") == access && container.secrets.get(requireNotNull(access.secretName)) == token) { "Email authorization changed; review a fresh action" }
        container.sessionStore.update(sessionId) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "pending") })) }
        val id = GmailContextApi().compose(token, to, subject, text, send)
        container.sessionStore.update(sessionId) { it.copy(attachments = it.attachments + (key to buildJsonObject { put("status", "done"); put("id", id) })) }
        id
    }
    suspend fun calendar(agentId: String, from: Long, to: Long): List<CalendarItem> = withContext(Dispatchers.IO) {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "calendar" in it } != false) { "Calendar is outside this routine's scope" }
        if (grant(agentId, "calendar") == null || !has(Manifest.permission.READ_CALENDAR)) return@withContext emptyList()
        require(to > from && to - from <= 366 * 86_400_000L)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        container.context.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
            buildList {
                while (size < 300 && cursor.moveToNext()) add(CalendarItem(cursor.getLong(0).toString(), cursor.getString(1).orEmpty().take(500), cursor.getLong(2), cursor.getLong(3), cursor.getString(4).orEmpty().take(500)))
            }
        }.orEmpty()
    }
    suspend fun contacts(agentId: String, query: String): List<ContextEvidence> = withContext(Dispatchers.IO) {
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "contacts" in it } != false) { "Contacts are outside this routine's scope" }
        if (grant(agentId, "contacts") == null || !has(Manifest.permission.READ_CONTACTS)) return@withContext emptyList()
        val uri = ContactsContract.CommonDataKinds.Email.CONTENT_URI
        container.context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS), null, null, null)?.use { cursor ->
            buildList {
                var scanned = 0
                while (size < 50 && scanned++ < 10_000 && cursor.moveToNext()) {
                    val name = cursor.getString(1).orEmpty(); val address = cursor.getString(2).orEmpty()
                    if (query.isBlank() || EvidenceRanker.tokens(query).any { it in "$name $address".lowercase() }) add(ContextEvidence("contacts", cursor.getLong(0).toString(), "$name · $address", System.currentTimeMillis(), "person"))
                }
            }
        }.orEmpty()
    }
    suspend fun resolve(agentId: String, query: String, sources: Set<String>, days: Int, entity: String): List<ContextEvidence> = coroutineScope {
        val profile = requireNotNull(container.agents.store.get(agentId))
        val scope = currentCoroutineContext()[ToolAuthority]?.connectorScope
        val now = System.currentTimeMillis()
        val from = now - days.coerceIn(1, 3650) * 86_400_000L
        val memory = async(Dispatchers.IO) {
            if ("memory" !in sources) emptyList() else (container.agents.store.memory(agentId).entries() + container.agents.store.teamMemory().entries()).map { e ->
                ContextEvidence("memory", "${e.source}:${e.index}", e.text, java.time.LocalDate.parse(e.date).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), e.category.name.lowercase())
            }
        }
        val messages = async {
            if ("messages" !in sources) emptyList() else profile.channels.filter { it != "notifications" && (scope == null || it in scope) }.flatMap { container.connectors.messages.read(it, null, from) }
                .map { ContextEvidence("messages:${it.app}", it.id, "${it.sender}: ${it.text}", it.atMs) }
        }
        val notifications = async(Dispatchers.IO) {
            if ("notifications" !in sources || "notifications" !in profile.channels || scope?.let { "notifications" !in it } == true) emptyList() else container.notificationReader.records(from)
                .map { ContextEvidence("notifications:${it.packageName}", it.id, "${it.title}: ${it.text}", it.atMs) }
        }
        val events = async { if ("calendar" !in sources || scope?.let { "calendar" !in it } == true) emptyList() else calendar(agentId, now, now + 90 * 86_400_000L).map { ContextEvidence("calendar", it.id, "${it.title} · ${Instant.ofEpochMilli(it.startMs)} · ${it.location}", now, "appointment") } }
        val people = async { if ("contacts" !in sources || scope?.let { "contacts" !in it } == true) emptyList() else contacts(agentId, query) }
        val email = async {
            val grant = grant(agentId, "email")
            if ("email" !in sources || grant == null || scope?.let { "email" !in it } == true) emptyList() else {
                val key = grant.secretName?.let { container.secrets.get(it) } ?: return@async emptyList()
                GmailContextApi().search(key, query, days)
            }
        }
        val localTasks = async(Dispatchers.IO) {
            if ("tasks" !in sources || grant(agentId, "tasks") == null || scope?.let { "tasks" !in it } == true) emptyList() else tasks(agentId).map {
                ContextEvidence("tasks", it.id, "${it.title}: ${it.note} · ${if (it.completed) "completed" else "pending"}${it.dueAtMs?.let { due -> " · due ${Instant.ofEpochMilli(due)}" }.orEmpty()}", it.updatedAtMs)
            }
        }
        val documents = async {
            val access = grant(agentId, "drive_files")
            if (sources.none { it in setOf("documents", "drive_files") } || access == null || scope?.let { "drive_files" !in it } == true) emptyList()
            else {
                require(access.scope == ConnectorScope.READ_ONLY) { "Document context supports read-only access" }
                val uri = android.net.Uri.parse(requireNotNull(access.endpoint) { "Choose a documents folder again" })
                DocumentContext(container.context.contentResolver).search(uri, query, now).also {
                    check(grant(agentId, "drive_files") == access) { "Document access changed during the search" }
                }
            }
        }
        val results = memory.await() + messages.await() + notifications.await() + events.await() + people.await() + email.await() + localTasks.await() + documents.await()
        EvidenceRanker.rank(query, results, now, days).filter { entity == "any" || it.entityType == entity || it.entityType == "any" }
    }
    fun revoke(agentId: String, connector: String, purge: Boolean) {
        val old = grant(agentId, connector) ?: return
        container.agents.proactive.store.saveGrant(agentId, old.copy(enabled = false, authorizedAtMs = null))
        if (purge) {
            container.agents.store.memory(agentId).forgetConnector(connector)
            container.agents.store.teamMemory().forgetConnector(connector)
        }
    }
}
