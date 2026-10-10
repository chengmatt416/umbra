package app.umbra.agent.ui.services

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.services.SharedService
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PSetting
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.control.Admission
import app.umbra.core.control.Breaker
import app.umbra.core.control.HealthLevel
import app.umbra.core.control.ProbeResult
import app.umbra.core.control.ServicePolicy
import app.umbra.core.control.ServiceRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared Services (architecture spec §9): every service Umbra shares between agents and terminals, probed at four
 * levels without side effects, with the references that hold it, its admission (draining closes it first), its
 * persisted circuit breaker and restart budget, and who owns its recovery. Nothing on this screen stops a process.
 */
@Composable
fun SharedServicesScreen(onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val catalog by container.serviceMonitor.catalog.collectAsStateWithLifecycle()
    val records by container.services.all.collectAsStateWithLifecycle()
    val refs by container.services.references.collectAsStateWithLifecycle()
    val health by container.services.health.collectAsStateWithLifecycle()
    val supervisor by container.health.states.collectAsStateWithLifecycle()
    val journal by container.services.recent.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val terminals by container.terminals.terminals.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { container.serviceMonitor.refresh() }
            delay(10_000)
        }
    }

    fun consumerName(consumer: String): String = when {
        consumer.startsWith("session:") -> sessions.firstOrNull { it.id == consumer.removePrefix("session:") }?.let { "Session · ${it.title}" } ?: consumer
        consumer.startsWith("terminal:") -> terminals.firstOrNull { it.record.id == consumer.removePrefix("terminal:") }?.let { "Terminal · ${it.record.title}" } ?: consumer
        consumer == "laya" -> "Laya sidecar (runs in Linux)"
        consumer == "linux" -> "The Linux environment (chroot runs through root)"
        consumer == "decision-router" -> "The decision router"
        else -> consumer
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow("Shared runtime service manager")
        Headline("Shared services,", "without cascading failures.", Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        catalog.forEach { service ->
            val record = records[service.id]
            val probe = health[service.id]
            val held = refs.values.filter { it.serviceId == service.id }.sortedBy { it.sinceMs }
            PCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Kicker(service.id)
                        Text(service.name, Modifier.padding(top = 4.dp), fontSize = 17.sp, color = P.ink)
                    }
                    // A service nothing needs and that is not running is idle, not down.
                    val idle = probe != null && !probe.passes(HealthLevel.L1) && held.isEmpty()
                    Tag(
                        when { probe == null -> "Not probed"; probe.ready -> "Ready"; idle -> "Not running"; probe.passes(HealthLevel.L1) -> "Alive, not ready"; else -> "Down" },
                        when { probe == null || idle -> Tone.Neutral; probe.ready -> Tone.Good; else -> Tone.Warn },
                    )
                }
                Subtle(service.detail + (record?.instance?.let { " · instance $it" } ?: ""), Modifier.padding(top = 4.dp))
                Gap(8.dp)
                HealthLevel.entries.forEach { level ->
                    val p = probe?.levels?.get(level)
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(level.name, Modifier.width(30.dp), fontSize = 12.sp, color = P.sub)
                        Text(level.label, Modifier.width(132.dp), fontSize = 12.sp, color = P.ink)
                        Text(p?.detail ?: "—", Modifier.weight(1f), fontSize = 11.sp, color = P.sub, maxLines = 2)
                        Tag(
                            when (p?.result) { ProbeResult.PASS -> "Pass"; ProbeResult.FAIL -> "Fail"; else -> "Unknown" },
                            when (p?.result) { ProbeResult.PASS -> Tone.Good; ProbeResult.FAIL -> Tone.Danger; else -> Tone.Neutral },
                        )
                    }
                }
                probe?.let { Subtle("Probed ${DateUtils.getRelativeTimeSpanString(it.atMs)}. Each level is judged on its own; no probe changes anything.", Modifier.padding(top = 4.dp)) }

                Gap(10.dp)
                Kicker("Registered references · ${held.size}")
                if (held.isEmpty()) Subtle("None that Umbra knows of. Zero references is not proof that nothing uses it.", Modifier.padding(top = 4.dp))
                held.forEach { r ->
                    val sessionId = r.consumer.removePrefix("session:").takeIf { r.consumer.startsWith("session:") }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(consumerName(r.consumer), Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1)
                        Text(DateUtils.getRelativeTimeSpanString(r.sinceMs).toString(), fontSize = 11.sp, color = P.sub)
                        if (sessionId != null) PButton("Open", { onOpenSession(sessionId) }, Modifier.padding(start = 6.dp), style = ButtonStyle.Ghost, tiny = true)
                    }
                }

                Gap(10.dp)
                record?.let { BreakerAndAdmission(it, service, supervisor[service.id]?.heldBy, container.services.policy) }
                Gap(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (record?.admission == Admission.DRAINING) {
                        PButton("Open admission", { container.services.resume(service.id); message = "${service.name} takes new work again." }, style = ButtonStyle.Alt, tiny = true)
                    } else if (record != null) {
                        PButton("Drain", {
                            val report = container.services.drain(service.id, "drained by the user")
                            message = "${service.name} takes no new work. ${report.references.size} reference(s) still hold it; it is left running for them" +
                                (if (report.references.isEmpty()) " (zero references is not proof that nothing uses it)." else ".")
                        }, style = ButtonStyle.Alt, tiny = true)
                    }
                    if (service.supervised) PButton("Recover now", {
                        scope.launch {
                            runCatching { container.health.checkNow(service.id) }
                            val s = container.health.state(service.id)
                            message = when {
                                s.health.isHealthy -> "${service.name} is healthy."
                                s.heldBy != null -> "Recovery of ${service.name} held back: ${s.heldBy}."
                                s.needsUser != null -> s.needsUser
                                else -> "${service.name}: ${s.health}"
                            }
                        }
                    }, style = ButtonStyle.Alt, tiny = true)
                    if (record != null && record.breaker != Breaker.CLOSED) PButton("Reset breaker", {
                        container.services.resetBreaker(service.id, by = "user", reason = "reset from Shared Services")
                        message = "The breaker of ${service.name} was closed by hand (journaled)."
                    }, style = ButtonStyle.Ghost, tiny = true)
                }
            }
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker("Capability-aware service pool")
            Text("One instance each", Modifier.padding(top = 4.dp), fontSize = 17.sp, color = P.ink)
            Subtle("A service is replicated only when it declares it may be, its placement is safe and the phone can afford it. None of these may:", Modifier.padding(top = 4.dp))
            catalog.forEach { s -> PSetting(s.name, s.singleton, divider = s != catalog.last()) { Tag("Singleton", Tone.Neutral) } }
        }
        Gap(12.dp)
        PCard(style = CardStyle.Soft) {
            Kicker("Service recovery pipeline")
            val ramping = records.values.firstOrNull { container.services.reattaching(it) }
            Steps(
                listOf(
                    StepItem("Single recovery owner", "Each recovery gets a fenced epoch; a late outcome of an older one is ignored", StepState.Done),
                    StepItem("Demand first", "Nothing is restarted that no consumer needs", StepState.Done),
                    StepItem("Readiness, not just a pid", "Alive ≠ transport ≠ ready ≠ functional", StepState.Done),
                    StepItem("Staged reattachment", ramping?.let { "${it.serviceId} is letting work in in stages (${it.reattached} admitted)" } ?: "New work is let in in stages after a recovery", if (ramping != null) StepState.Active else StepState.Done),
                    StepItem("Circuit breaker", "Closed / Open / Half-open; budget and cooldowns persisted", StepState.Done),
                ),
            )
            Gap(8.dp)
            journal.takeLast(8).reversed().forEach { e ->
                Text("${DateUtils.formatDateTime(container.context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind.removePrefix("service.")} ${e.data?.toString()?.take(110).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
            }
        }
        Gap(28.dp)
    }
}

@Composable
private fun BreakerAndAdmission(record: ServiceRecord, service: SharedService, heldBy: String?, policy: ServicePolicy) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    PSetting("Admission", record.admissionReason ?: if (record.admission == Admission.OPEN) "Takes new work" else "Draining") {
        Tag(if (record.admission == Admission.OPEN) "Open" else "Draining", if (record.admission == Admission.OPEN) Tone.Good else Tone.Warn)
    }
    PSetting(
        "Circuit breaker",
        buildString {
            append("${record.attempts.count { now - it < policy.windowMs }} of ${policy.restartBudget} recoveries in ${policy.windowMs / 60_000} min")
            record.openUntilMs?.takeIf { record.breaker == Breaker.OPEN }?.let { append(" · paused until ${DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME)}") }
            if (record.breaker == Breaker.HALF_OPEN) append(" · ${record.stableProbes} of ${policy.stability} healthy probes toward closing")
            record.lastOutcome?.let { append(" · last: $it") }
        },
    ) {
        Tag(record.breaker.name.lowercase().replace('_', '-').replaceFirstChar { it.uppercase() }, when (record.breaker) { Breaker.CLOSED -> Tone.Good; Breaker.HALF_OPEN -> Tone.Warn; Breaker.OPEN -> Tone.Danger })
    }
    PSetting(
        "Recovery",
        when {
            !service.supervised -> "Not restarted automatically: " + when (service.id) {
                "root-helper" -> "only adb, Shizuku or a root manager can start it"
                "x-desktop" -> "a desktop task starts it when it needs one"
                "laya" -> "start it from Linux & add-ons"
                else -> "a lost tmux server is reported, not replaced; its terminals' state went with it"
            }
            record.recoveryOwner != null -> "Owned by ${record.recoveryOwner} (epoch ${record.recoveryEpoch})"
            heldBy != null -> "Held back: $heldBy"
            else -> "By the health supervisor, through this breaker, only while something needs it (epoch ${record.recoveryEpoch})"
        },
        divider = false,
    ) {}
}
