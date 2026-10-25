package app.umbra.agent.credentials

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.data.SecretStore
import app.umbra.core.model.UmbraJson
import app.umbra.core.orchestrator.ToolAuthority
import app.umbra.core.safety.OutboundDlpGuard
import app.umbra.core.session.ProactiveTurnMode
import app.umbra.core.session.SessionEvent
import app.umbra.core.session.SessionState
import app.umbra.core.session.StoreChange
import app.umbra.platform.api.CredentialTarget
import app.umbra.runtime.omp.*
import app.umbra.tools.HostSideTool
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

@Serializable
data class CredentialRequest(val id: String, val sessionId: String, val service: String, val reason: String,
    val target: CredentialTarget, val createdAtMs: Long, val linkHash: String, val linkUsed: Boolean = false,
    val state: String = "requested") {
    fun acceptsLink(token: String, now: Long): Boolean = !linkUsed && state == "requested" && now - createdAtMs in 0 until TTL_MS &&
        MessageDigest.isEqual(linkHash.toByteArray(), hash(token).toByteArray())
    companion object {
        const val TTL_MS = 10 * 60_000L
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Only native user callbacks can supply a value. The separate cipher store is never exported to agent processes. */
class CredentialHandoff(private val container: AppContainer) {
    private val prefs = container.context.getSharedPreferences("credential-handoff-state", 0)
    private val cipher = SecretStore(container.context, "credential-handoff-values")
    private val ownersSerializer = MapSerializer(String.serializer(), String.serializer())
    private val lock = Mutex()
    @Volatile private var storageError: String? = null
    private val mutable = MutableStateFlow(try { prefs.getString("pending", null)?.let { UmbraJson.decodeFromString(CredentialRequest.serializer(), it) } }
        catch (_: Exception) { storageError = "Secure input state could not be read. Agents remain paused; restore the saved state before continuing."; null })
    val pending = mutable.asStateFlow()
    val problem: String? get() = storageError
    private var started = false
    private val retired = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var retirement: Job? = null
    fun start() {
        if (started) return
        started = true
        container.scope.launch {
            try {
                pending.collectLatest { request ->
                    if (request == null || request.state != "requested") return@collectLatest
                    delay((request.createdAtMs + CredentialRequest.TTL_MS - System.currentTimeMillis()).coerceAtLeast(0))
                    lock.withLock {
                        if (pending.value?.id == request.id && pending.value?.state == "requested") {
                            save(null)
                            container.sessionStore.get(request.sessionId)?.let { container.sessionStore.append(it.id, SessionEvent.Notice("warning", "Secure input expired unanswered. Review the phone before resuming; no credential was injected.", "credential-handoff")) }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageError = "Secure input maintenance failed. Agents remain paused until its saved state can be updated." }
        }
        container.scope.launch {
            try {
                container.sessionStore.changes.collect { change ->
                    if ((change as? StoreChange.SessionChanged)?.session?.state?.isTerminal == true && retired.isNotEmpty()) scheduleRetirement()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageError = "Secure input cleanup failed. Agents remain paused until its saved state can be updated." }
        }
    }
    private fun owners(): Map<String, String> = try {
        UmbraJson.decodeFromString(ownersSerializer, prefs.getString("owners", "{}").orEmpty()).also { entries ->
            require(entries.size <= 64 && entries.all { (name, session) -> name.matches(Regex("HANDOFF_[A-F0-9]{32}")) && session.length in 1..100 })
        }
    } catch (_: Exception) {
        storageError = "Secure input redaction state could not be read. Agents remain paused until it can be restored."
        error(requireNotNull(storageError))
    }
    fun protectedValues() = owners().keys.map { name ->
        cipher.get(name) ?: run {
            storageError = "A secure input redaction value is unavailable. Agents remain paused until its saved state can be restored."
            error(requireNotNull(storageError))
        }
    } + retired.values.toList()
    private fun scheduleRetirement() {
        retirement?.cancel()
        retirement = container.scope.launch {
            delay(35_000)
            try {
                if (pending.value == null && container.sessionStore.list().none { container.supervisor.isProcessAlive(it.id) }) retired.clear()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { storageError = "Secure input cleanup failed. Agents remain paused until its saved state can be updated." }
        }
    }
    private fun save(request: CredentialRequest?) {
        val edit = prefs.edit()
        if (request == null) edit.remove("pending") else edit.putString("pending", UmbraJson.encodeToString(CredentialRequest.serializer(), request))
        check(edit.commit()) { "Secure input state could not be saved" }
        mutable.value = request
        if (request == null) container.context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
    }
    fun fence(sessionId: String, nativeHandoff: Boolean): String? = storageError ?: pending.value?.takeUnless { nativeHandoff && it.sessionId == sessionId }
        ?.let { "Secure credential input is pending. Finish or cancel it in the native phone panel before agents continue." }

    suspend fun request(sessionId: String, service: String, field: String, reason: String): String = lock.withLock {
        check(pending.value == null) { "Finish the current secure input request first" }
        require(service.isNotBlank() && service.length <= 100 && reason.isNotBlank() && reason.length <= 1000)
        OutboundDlpGuard(4) { container.protectedValues() }.requireSafe("$service\n$reason")
        val session = requireNotNull(container.sessionStore.get(sessionId))
        require(session.turnMode != ProactiveTurnMode.READ_ONLY_DISCOVERY && session.state in setOf(SessionState.RUNNING, SessionState.WAITING_APPROVAL))
        require(currentCoroutineContext()[ToolAuthority]?.connectorScope?.let { "android" in it } != false)
        check(container.recorder.state.value == app.umbra.agent.capsule.ScreenRecorder.State.Idle && container.demonstrations.active.value == null) { "Stop recording before requesting secure input" }
        container.agents.proactive.checkAuthority(session)
        val target = container.accessibility.credentialTarget(field)
        val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val request = CredentialRequest(java.util.UUID.randomUUID().toString().replace("-", ""), sessionId, service, reason, target,
            System.currentTimeMillis(), CredentialRequest.hash(token))
        save(request)
        container.takeOver("display:${target.displayId}", sessionId)
        notify(request)
        container.sessionStore.append(sessionId, SessionEvent.Notice("info", "Secure input requested for ${target.packageName}. Values must be entered in the native phone panel, never in chat.", "credential-handoff"))
        val verified = runCatching {
            val manager = container.context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val state = manager?.getDomainVerificationUserState(container.context.packageName)
            state?.isLinkHandlingAllowed == true && state.hostToStateMap[CredentialLinks.HOST] == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }.getOrDefault(false)
        val link = CredentialLinks.panel(token, verified)
        "Secure input is pending on the phone for ${target.packageName}. A one-time panel link is $link . It expires in ten minutes and only opens a user-reviewed panel; it cannot inject a value. Do not ask for a credential in chat."
    }

    suspend fun consumeLink(token: String): String = lock.withLock {
        require(token.length in 32..100)
        val request = requireNotNull(pending.value)
        require(request.acceptsLink(token, System.currentTimeMillis())) { "This secure input link expired or was already used" }
        save(request.copy(linkUsed = true))
        request.id
    }

    suspend fun inject(id: String, value: String) = lock.withLock {
        val request = requireNotNull(pending.value?.takeIf { it.id == id })
        require(request.state == "requested" && System.currentTimeMillis() - request.createdAtMs in 0 until CredentialRequest.TTL_MS) { "Request a fresh secure input panel" }
        require(value.length in 4..4096) { "Enter between four and 4096 characters" }
        check(container.recorder.state.value == app.umbra.agent.capsule.ScreenRecorder.State.Idle && container.demonstrations.active.value == null) { "Stop recording before entering a credential" }
        val session = requireNotNull(container.sessionStore.get(request.sessionId))
        require(!session.state.isTerminal) { "The requesting task ended; cancel this request" }
        container.agents.proactive.checkAuthority(session, nativeHandoff = true)
        container.supervisor.pause(session.id, "The user is entering a credential in a native panel")
        val name = "HANDOFF_" + request.id.uppercase()
        val entries = owners()
        require(entries.size + retired.size < 64 || name in entries) { "Delete old credential-using tasks and finish other running work before adding more temporary redaction entries" }
        cipher.putDurable(name, value)
        check(prefs.edit().putString("owners", UmbraJson.encodeToString(ownersSerializer, entries + (name to session.id))).commit())
        // Persist the uncertain-write state first. A crash or an unconfirmed setter never retries a credential.
        save(request.copy(state = "injected"))
        container.userInput("display:${request.target.displayId}") {
            container.agents.proactive.checkAuthority(requireNotNull(container.sessionStore.get(session.id)), nativeHandoff = true)
            check(container.accessibility.injectCredential(request.target, value)) { "The field did not confirm accepting the input. Inspect or clear it before continuing." }
        }
        container.sessionStore.append(session.id, SessionEvent.Notice("info", "The native field in ${request.target.packageName} accepted credential input. Authentication must still be completed by the user.", "credential-handoff"))
    }

    suspend fun finish(id: String, clearField: Boolean) = lock.withLock {
        val request = requireNotNull(pending.value?.takeIf { it.id == id })
        if (request.state == "injected") container.userInput("display:${request.target.displayId}") {
            check(container.accessibility.credentialFieldClear(request.target, clearField)) { "Keep the target app visible and finish authentication, or clear the original field before returning control." }
            check(container.accessibility.credentialContextClear(request.target, protectedValues())) { "A credential is still visible in the current screen text. Hide or clear it before returning control." }
        }
        save(null)
        if (container.sessionStore.get(request.sessionId) == null) retireSession(request.sessionId)
        container.sessionStore.get(request.sessionId)?.let { container.sessionStore.append(it.id, SessionEvent.Notice("info", "Native secure input ${if (request.state == "injected" && !clearField) "finished" else "cancelled"}; no value was placed in chat.", "credential-handoff")) }
        container.returnControl("display:${request.target.displayId}", request.sessionId)
        if (retired.isNotEmpty()) scheduleRetirement()
    }

    suspend fun removeSession(sessionId: String) = lock.withLock {
        // Keep the redaction value durable across restart until an already-filled field is cleared.
        if (pending.value?.let { it.sessionId == sessionId && it.state == "injected" } == true) return@withLock
        retireSession(sessionId)
        if (pending.value?.sessionId == sessionId) save(null)
    }

    private fun retireSession(sessionId: String) {
        val entries = owners()
        val names = entries.filterValues { it == sessionId }.keys
        names.forEach { name -> cipher.get(name)?.let { retired[name] = it } }
        check(prefs.edit().putString("owners", UmbraJson.encodeToString(ownersSerializer, entries.filterValues { it != sessionId })).commit())
        names.forEach(cipher::removeDurable)
        if (retired.isNotEmpty()) scheduleRetirement()
    }

    private fun notify(request: CredentialRequest) {
        val manager = container.context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, container.context.getString(R.string.credential_title), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(container.context, NOTIFICATION, Intent(container.context, CredentialHandoffActivity::class.java).putExtra("request", request.id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        runCatching { manager.notify(NOTIFICATION, Notification.Builder(container.context, CHANNEL).setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(container.context.getString(R.string.credential_title)).setContentText(request.target.packageName)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()) }
    }
    fun tools(sessionId: String) = listOf(HostSideTool(HostToolDefinition("request_credential_handoff", "Request native Android credential entry into a field from the latest observation. Never ask for passwords, OTPs or API tokens in chat. The user reviews the real target app and enters a value on the phone; agents wait until the field is cleared or authentication finishes. Browser/remote-only targets require ordinary user takeover.", objectSchema {
        string("service_name", required = true); string("target_field_ref", required = true); string("reason", required = true)
    })) { call, _ ->
        fun arg(name: String) = call.arguments[name]?.jsonPrimitive?.content ?: error("$name is required")
        HostToolPayload.text(request(sessionId, arg("service_name"), arg("target_field_ref"), arg("reason")))
    })
    companion object { private const val CHANNEL = "secure-input"; private const val NOTIFICATION = 4110 }
}
