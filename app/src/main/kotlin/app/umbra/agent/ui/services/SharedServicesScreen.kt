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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.PollWhileStarted
import app.umbra.agent.ui.porcelain.Disclosure
import app.umbra.agent.ui.breakerRes
import app.umbra.agent.ui.displayDetail
import app.umbra.agent.ui.displayName
import app.umbra.agent.ui.displaySingleton
import app.umbra.agent.ui.healthLevelRes
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
import kotlinx.coroutines.launch

/**
 * Shared Services (architecture spec §9): every service Umbra shares between agents and terminals, probed at four
 * levels without side effects, with the references that hold it, its admission (draining closes it first), its
 * persisted circuit breaker and restart budget, and who owns its recovery. Nothing on this screen stops a process.
 */
@Composable
fun SharedServicesScreen(onOpenSession: (String) -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
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
    PollWhileStarted(Unit, periodMs = 10_000) {
        runCatching { container.serviceMonitor.refresh() }
    }

    fun consumerName(consumer: String): String = when {
        consumer.startsWith("session:") -> sessions.firstOrNull { it.id == consumer.removePrefix("session:") }?.let { resources.getString(R.string.svc_session, it.title) } ?: consumer
        consumer.startsWith("terminal:") -> terminals.firstOrNull { it.record.id == consumer.removePrefix("terminal:") }?.let { resources.getString(R.string.svc_terminal, it.record.title) } ?: consumer
        consumer == "laya" -> resources.getString(R.string.svc_laya)
        consumer == "linux" -> resources.getString(R.string.svc_linux)
        consumer == "decision-router" -> resources.getString(R.string.svc_router)
        else -> consumer
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
        Eyebrow(stringResource(R.string.svc_eyebrow))
        Headline(stringResource(R.string.svc_headline), stringResource(R.string.svc_headline_tail), Modifier.padding(top = 6.dp, bottom = 12.dp), size = 26)
        message?.let { Callout(it, Tone.Neutral); Gap(10.dp) }

        catalog.forEach { service ->
            val record = records[service.id]
            val probe = health[service.id]
            val held = refs.values.filter { it.serviceId == service.id }.sortedBy { it.sinceMs }
            PCard {
                // A service nothing needs and that is not running is idle, not down.
                val idle = probe != null && !probe.passes(HealthLevel.L1) && held.isEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.displayName(resources), Modifier.weight(1f), fontSize = 17.sp, color = P.ink)
                    Tag(
                        stringResource(when { probe == null -> R.string.svc_not_probed; probe.ready -> R.string.svc_ready; idle -> R.string.svc_not_running; probe.passes(HealthLevel.L1) -> R.string.svc_alive; else -> R.string.svc_down }),
                        when { probe == null || idle -> Tone.Neutral; probe.ready -> Tone.Good; else -> Tone.Warn },
                    )
                }
                Subtle(service.displayDetail(resources), Modifier.padding(top = 4.dp))
                Text(
                    if (held.isEmpty()) stringResource(R.string.svc_unused) else pluralStringResource(R.plurals.svc_used_by, held.size, held.size),
                    Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P.sub,
                )
                if (record?.admission == Admission.DRAINING) Text(stringResource(R.string.svc_draining_note), Modifier.padding(top = 2.dp), fontSize = 12.sp, color = P.amber)
                if (record?.breaker == Breaker.OPEN) Text(stringResource(R.string.svc_paused_note), Modifier.padding(top = 2.dp), fontSize = 12.sp, color = P.amber)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (service.supervised && probe?.ready != true && !idle) PButton(stringResource(R.string.svc_recover), {
                        scope.launch {
                            runCatching { container.health.checkNow(service.id) }
                            val st = container.health.state(service.id)
                            message = when {
                                st.health.isHealthy -> resources.getString(R.string.svc_healthy, service.displayName(resources))
                                st.heldBy != null -> resources.getString(R.string.svc_recovery_held, service.displayName(resources), st.heldBy.toString())
                                st.needsUser != null -> st.needsUser
                                else -> "${service.displayName(resources)}: ${st.health}"
                            }
                        }
                    }, tiny = true)
                    if (record?.admission == Admission.DRAINING) PButton(stringResource(R.string.svc_open), { container.services.resume(service.id); message = resources.getString(R.string.svc_takes_again, service.displayName(resources)) }, style = ButtonStyle.Alt, tiny = true)
                }
                Disclosure(stringResource(R.string.details)) {
                    Kicker(stringResource(R.string.svc_health))
                    HealthLevel.entries.forEach { level ->
                        val p = probe?.levels?.get(level)
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(level.name, Modifier.width(30.dp), fontSize = 12.sp, color = P.sub)
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(healthLevelRes(level)), fontSize = 12.sp, color = P.ink)
                                p?.detail?.let { Text(it, fontSize = 11.sp, color = P.sub, maxLines = 2) }
                            }
                            Tag(
                                stringResource(when (p?.result) { ProbeResult.PASS -> R.string.svc_pass; ProbeResult.FAIL -> R.string.svc_fail; else -> R.string.svc_unknown }),
                                when (p?.result) { ProbeResult.PASS -> Tone.Good; ProbeResult.FAIL -> Tone.Danger; else -> Tone.Neutral },
                            )
                        }
                    }
                    probe?.let { Subtle(stringResource(R.string.svc_probed, DateUtils.getRelativeTimeSpanString(it.atMs).toString()), Modifier.padding(top = 4.dp)) }
                    record?.instance?.let { Subtle(stringResource(R.string.svc_instance, it), Modifier.padding(top = 2.dp)) }

                    Gap(10.dp)
                    Kicker(stringResource(R.string.svc_refs, held.size))
                    if (held.isEmpty()) Subtle(stringResource(R.string.svc_refs_none), Modifier.padding(top = 4.dp))
                    held.forEach { r ->
                        val sessionId = r.consumer.removePrefix("session:").takeIf { r.consumer.startsWith("session:") }
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(consumerName(r.consumer), Modifier.weight(1f), fontSize = 13.sp, color = P.ink, maxLines = 1)
                            Text(DateUtils.getRelativeTimeSpanString(r.sinceMs).toString(), fontSize = 11.sp, color = P.sub)
                            if (sessionId != null) PButton(stringResource(R.string.svc_open_session), { onOpenSession(sessionId) }, Modifier.padding(start = 6.dp), style = ButtonStyle.Ghost, tiny = true)
                        }
                    }

                    Gap(10.dp)
                    record?.let { BreakerAndAdmission(it, service, supervisor[service.id]?.heldBy, container.services.policy) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record != null && record.admission != Admission.DRAINING) {
                            PButton(stringResource(R.string.svc_drain), {
                                val report = container.services.drain(service.id, "drained by the user")
                                message = if (report.references.isEmpty()) resources.getString(R.string.svc_drained_0, service.displayName(resources))
                                else resources.getString(R.string.svc_drained_n, service.displayName(resources), report.references.size)
                            }, style = ButtonStyle.Alt, tiny = true)
                        }
                        if (record != null && record.breaker != Breaker.CLOSED) PButton(stringResource(R.string.svc_reset), {
                            container.services.resetBreaker(service.id, by = "user", reason = "reset from Shared Services")
                            message = resources.getString(R.string.svc_breaker_closed, service.displayName(resources))
                        }, style = ButtonStyle.Ghost, tiny = true)
                    }
                }
            }
            Gap(12.dp)
        }

        PCard(style = CardStyle.Soft) {
            Kicker(stringResource(R.string.svc_how))
            Subtle(stringResource(R.string.svc_how_body), Modifier.padding(top = 6.dp))
            Disclosure(stringResource(R.string.details)) {
                val ramping = records.values.firstOrNull { container.services.reattaching(it) }
                Steps(
                    listOf(
                        StepItem(stringResource(R.string.svc_step_owner), stringResource(R.string.svc_step_owner_d), StepState.Done),
                        StepItem(stringResource(R.string.svc_step_demand), stringResource(R.string.svc_step_demand_d), StepState.Done),
                        StepItem(stringResource(R.string.svc_step_ready), stringResource(R.string.svc_step_ready_d), StepState.Done),
                        StepItem(
                            stringResource(R.string.svc_step_stages),
                            ramping?.let { stringResource(R.string.svc_step_stages_now, it.serviceId, it.reattached) } ?: stringResource(R.string.svc_step_stages_d),
                            if (ramping != null) StepState.Active else StepState.Done,
                        ),
                        StepItem(stringResource(R.string.svc_step_breaker), stringResource(R.string.svc_step_breaker_d), StepState.Done),
                    ),
                )
                Kicker(stringResource(R.string.svc_singletons), Modifier.padding(top = 6.dp))
                catalog.forEach { s -> PSetting(s.displayName(resources), s.displaySingleton(resources), divider = s != catalog.last()) { Tag(stringResource(R.string.svc_singleton), Tone.Neutral) } }
                Gap(8.dp)
                journal.takeLast(8).reversed().forEach { e ->
                    Text("${DateUtils.formatDateTime(container.context, e.timeMs, DateUtils.FORMAT_SHOW_TIME)} ${e.kind.removePrefix("service.")} ${e.data?.toString()?.take(110).orEmpty()}", Modifier.padding(top = 4.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
                }
            }
        }
        Gap(28.dp)
    }
}

@Composable
private fun BreakerAndAdmission(record: ServiceRecord, service: SharedService, heldBy: String?, policy: ServicePolicy) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val now = System.currentTimeMillis()
    PSetting(stringResource(R.string.svc_admission), record.admissionReason ?: stringResource(if (record.admission == Admission.OPEN) R.string.svc_takes_work else R.string.svc_draining)) {
        Tag(stringResource(if (record.admission == Admission.OPEN) R.string.svc_open_tag else R.string.svc_draining), if (record.admission == Admission.OPEN) Tone.Good else Tone.Warn)
    }
    PSetting(
        stringResource(R.string.svc_breaker),
        buildString {
            append(stringResource(R.string.svc_breaker_d, record.attempts.count { now - it < policy.windowMs }, policy.restartBudget, (policy.windowMs / 60_000).toInt()))
            record.openUntilMs?.takeIf { record.breaker == Breaker.OPEN }?.let { append(" · " + stringResource(R.string.svc_paused_until, DateUtils.formatDateTime(context, it, DateUtils.FORMAT_SHOW_TIME))) }
            if (record.breaker == Breaker.HALF_OPEN) append(" · " + stringResource(R.string.svc_toward_closing, record.stableProbes, policy.stability))
            record.lastOutcome?.let { append(" · " + stringResource(R.string.svc_last, it)) }
        },
    ) {
        Tag(stringResource(breakerRes(record.breaker)), when (record.breaker) { Breaker.CLOSED -> Tone.Good; Breaker.HALF_OPEN -> Tone.Warn; Breaker.OPEN -> Tone.Danger })
    }
    PSetting(
        stringResource(R.string.svc_recovery),
        when {
            !service.supervised -> stringResource(R.string.svc_not_auto, stringResource(when (service.id) {
                "root-helper" -> R.string.svc_why_root_helper
                "x-desktop" -> R.string.svc_why_desktop
                "laya" -> R.string.svc_why_laya
                else -> R.string.svc_why_tmux
            }))
            record.recoveryOwner != null -> stringResource(R.string.svc_owned_by, record.recoveryOwner.orEmpty(), record.recoveryEpoch)
            heldBy != null -> stringResource(R.string.svc_held_back, heldBy)
            else -> stringResource(R.string.svc_by_supervisor, record.recoveryEpoch)
        },
        divider = false,
    ) {}
}
