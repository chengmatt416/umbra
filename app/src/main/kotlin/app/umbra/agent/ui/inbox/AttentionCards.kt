package app.umbra.agent.ui.inbox

import app.umbra.agent.ui.porcelain.pcHue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.annotation.StringRes
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.riskCategoryRes
import app.umbra.agent.ui.riskRes
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.orchestrator.QuestionBroker
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.RiskLevel
import app.umbra.core.safety.ApprovalSurface
import app.umbra.core.safety.ApproveGesture
import app.umbra.core.safety.RememberScope
import app.umbra.core.safety.approveGesture
import app.umbra.core.safety.offeredRememberScope
import kotlinx.coroutines.delay
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse

fun riskTone(level: RiskLevel) = when (level) {
    RiskLevel.NONE, RiskLevel.LOW -> Tone.Good
    RiskLevel.MEDIUM -> Tone.Warn
    RiskLevel.HIGH, RiskLevel.CRITICAL -> Tone.Danger
}

/** Milliseconds left before an unanswered approval is denied (fail closed), ticking once a second. */
@Composable
fun rememberRemainingMs(request: ApprovalRequest): Long {
    val deadline = request.createdAtMs + request.timeoutMs
    val remaining by produceState(deadline - System.currentTimeMillis(), request.id) {
        while (true) {
            value = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            delay(1_000)
        }
    }
    return remaining
}

fun formatRemaining(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** The countdown line every approval surface shows: amber, then clay red in its last minute. */
@Composable
fun ApprovalCountdown(request: ApprovalRequest, modifier: Modifier = Modifier, dark: Boolean = false) {
    val left = rememberRemainingMs(request)
    val urgent = left <= 60_000
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = when {
            dark && urgent -> pcHue(0xFFE7A493)
            dark -> pcHue(0xFFF9E5C3)
            urgent -> P.danger
            else -> P.amber
        }
        PIcon(PIcons.clock, size = 13.dp, tint = color)
        Text(stringResource(R.string.appr_countdown, formatRemaining(left)), fontSize = 12.sp, color = color, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = P.tabular))
    }
}

@StringRes
fun rememberLabel(scope: RememberScope) = when (scope) {
    RememberScope.THIS_COMMAND_FOREVER -> R.string.appr_remember_forever
    RememberScope.THIS_COMMAND_THIS_SESSION -> R.string.appr_remember_command
    RememberScope.THIS_APP_THIS_SESSION -> R.string.appr_remember_app
    RememberScope.THIS_CATEGORY_THIS_SESSION -> R.string.appr_remember_category
}

/**
 * One approval, where the whole request is shown (Inbox, the session): exactly what will happen, why it is risky,
 * how long is left, Deny and "Approve once". CRITICAL asks once more and says what that means. At most one remembered
 * scope is offered, the narrowest the request allows, under the main answer.
 */
@Composable
fun ApprovalPanel(request: ApprovalRequest, sessionTitle: String?, onDecision: (ApprovalDecision) -> Unit, modifier: Modifier = Modifier) {
    val container = LocalContext.current.container
    val level = request.assessment.level
    val gesture = approveGesture(level, ApprovalSurface.FULL)
    val offered = offeredRememberScope(request, ApprovalSurface.FULL)
    var confirming by remember(request.id) { mutableStateOf(false) }
    var confirmPrefix by remember(request.id) { mutableStateOf(false) }
    PCard(modifier, style = CardStyle.Soft) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Kicker(sessionTitle ?: stringResource(R.string.appr_kicker))
                Text(
                    request.description, Modifier.padding(top = 6.dp), fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold, color = P.ink,
                    fontFamily = if (request.toolName == "shell" || request.toolName == "bash") P.mono else null,
                )
            }
            Tag(stringResource(riskRes(level)), riskTone(level))
        }
        app.umbra.agent.data.approvalExecutionDetails(request).takeIf { it.isNotEmpty() }?.let { full ->
            Text(full, fontFamily = P.mono, fontSize = 13.sp, lineHeight = 19.sp, color = P.ink)
        }
        request.assessment.findings.forEach { Subtle("• ${stringResource(riskCategoryRes(it.category))}: ${it.reason}", Modifier.padding(top = 4.dp)) }
        request.details["why"]?.let { Subtle(stringResource(R.string.appr_why, it), Modifier.padding(top = 4.dp)) }
        request.foregroundPackage?.let { Subtle(stringResource(R.string.appr_in_app, it), Modifier.padding(top = 2.dp)) }
        ApprovalCountdown(request, Modifier.padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PButton(stringResource(R.string.appr_deny), { onDecision(ApprovalDecision.Denied("denied by the user")) }, Modifier.weight(1f), style = ButtonStyle.Alt)
            PButton(
                stringResource(if (request.details["checkout_service"] != null) R.string.checkout_approve else R.string.appr_approve_once),
                { if (gesture == ApproveGesture.CONFIRM) confirming = true else onDecision(ApprovalDecision.Approved()) },
                Modifier.weight(1f),
                style = if (level >= RiskLevel.HIGH) ButtonStyle.Warn else ButtonStyle.Primary,
            )
        }
        request.details["checkout_resource"]?.let { resource ->
            PButton(stringResource(R.string.checkout_take_over), {
                container.takeOver(resource, request.sessionId)
                onDecision(ApprovalDecision.Denied("the user took over checkout"))
            }, Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.Alt)
        }
        if (offered != null) {
            PButton(stringResource(rememberLabel(offered)), { if (offered == RememberScope.THIS_COMMAND_FOREVER) confirmPrefix = true else onDecision(ApprovalDecision.Approved(offered)) }, Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.Ghost, tiny = true)
        }
        if (offered == null && request.operation == "shell") {
            PButton(stringResource(R.string.appr_remember_forever), {}, Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.Ghost, tiny = true, enabled = false)
            Subtle(stringResource(R.string.appr_once_required))
        }
        Subtle(stringResource(R.string.appr_once_note), Modifier.padding(top = 10.dp))
    }
    if (confirmPrefix) {
        PConfirm(
            title = stringResource(R.string.appr_remember_forever),
            body = stringResource(R.string.appr_prefix_confirm, request.details["command_prefix"].orEmpty()),
            confirmLabel = stringResource(R.string.appr_remember_forever),
            onDismiss = { confirmPrefix = false },
            onConfirm = { confirmPrefix = false; onDecision(ApprovalDecision.Approved(RememberScope.THIS_COMMAND_FOREVER)) },
        )
    }
    if (confirming) {
        PConfirm(
            title = stringResource(R.string.appr_critical_title),
            body = stringResource(R.string.appr_critical_body),
            confirmLabel = stringResource(R.string.appr_critical_confirm),
            onDismiss = { confirming = false },
            onConfirm = { confirming = false; onDecision(ApprovalDecision.Approved()) },
            extra = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        request.description, Modifier.fillMaxWidth().padding(top = 2.dp), fontSize = 13.sp, lineHeight = 19.sp, color = P.ink,
                        fontFamily = if (request.toolName == "shell" || request.toolName == "bash") P.mono else null,
                    )
                    Text(app.umbra.agent.data.approvalExecutionDetails(request), color = P.ink, fontFamily = P.mono)
                    request.assessment.findings.filter { it.level >= RiskLevel.HIGH }.forEach { Subtle("• ${stringResource(riskCategoryRes(it.category))}: ${it.reason}") }
                }
            },
        )
    }
}

/** A question omp (or one of its extensions) asks: select, confirm, text, editor or a structured ask. */
@Composable
fun QuestionPanel(pending: QuestionBroker.Pending, sessionTitle: String?, onAnswer: (UiResponse) -> Unit, modifier: Modifier = Modifier) {
    PCard(modifier, style = CardStyle.Soft) {
        Kicker(sessionTitle ?: stringResource(R.string.q_kicker))
        Gap(6.dp)
        when (val r = pending.request) {
            is UiRequest.Select -> {
                Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                r.options.forEachIndexed { i, option ->
                    Gap(6.dp)
                    PButton(option + (r.optionDescriptions.getOrNull(i)?.let { " — $it" } ?: ""), { onAnswer(UiResponse.Value(option)) }, Modifier.fillMaxWidth(), style = ButtonStyle.Alt, tiny = true)
                }
            }
            is UiRequest.Confirm -> {
                Text(r.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                if (r.message.isNotBlank()) Subtle(r.message, Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.q_no), { onAnswer(UiResponse.Confirmed(false)) }, style = ButtonStyle.Alt, tiny = true)
                    PButton(stringResource(R.string.q_yes), { onAnswer(UiResponse.Confirmed(true)) }, tiny = true)
                }
            }
            is UiRequest.Input -> TextAnswer(r.title, r.placeholder, null, onAnswer)
            is UiRequest.Editor -> TextAnswer(r.title, null, r.prefill, onAnswer)
            is UiRequest.Ask -> AskAnswerForm(r, onAnswer)
        }
        Gap(6.dp)
        PButton(stringResource(R.string.q_skip), { onAnswer(UiResponse.Cancelled()) }, style = ButtonStyle.Ghost, tiny = true)
    }
}

@Composable
private fun TextAnswer(title: String, placeholder: String?, prefill: String?, onAnswer: (UiResponse) -> Unit) {
    var text by remember(title) { mutableStateOf(prefill.orEmpty()) }
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
    PField(text, { text = it }, Modifier.padding(top = 8.dp), placeholder = placeholder, minLines = if (prefill != null) 3 else 1)
    Gap(8.dp)
    PButton(stringResource(R.string.q_send), { onAnswer(UiResponse.Value(text)) }, tiny = true)
}

@Composable
private fun AskAnswerForm(request: UiRequest.Ask, onAnswer: (UiResponse) -> Unit) {
    val picked = remember(request.id) { mutableStateOf(request.questions.associate { it.id to emptySet<String>() }) }
    val custom = remember(request.id) { mutableStateOf(request.questions.associate { it.id to "" }) }
    request.questions.forEach { q ->
        q.header?.let { Kicker(it) }
        Text(q.question, Modifier.padding(top = 2.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
        q.options.forEach { option ->
            val on = option.label in picked.value[q.id].orEmpty()
            Row(
                Modifier.fillMaxWidth().clickable {
                    val current = picked.value[q.id].orEmpty()
                    val next = if (q.multi) (if (on) current - option.label else current + option.label) else setOf(option.label)
                    picked.value = picked.value + (q.id to next)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = on, onClick = null)
                Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                    Text(option.label + if (q.recommended == q.options.indexOf(option)) "  " + stringResource(R.string.q_suggested) else "", fontSize = 14.sp, color = P.ink)
                    option.description?.let { Subtle(it) }
                }
            }
        }
        PField(custom.value[q.id].orEmpty(), { custom.value = custom.value + (q.id to it) }, Modifier.padding(top = 4.dp), placeholder = stringResource(R.string.q_own_answer), singleLine = true)
        Gap(8.dp)
    }
    PButton(stringResource(R.string.q_send_answers), {
        onAnswer(UiResponse.Answers(request.questions.map { q -> AskAnswer(q.id, picked.value[q.id].orEmpty().toList(), custom.value[q.id]?.takeIf { it.isNotBlank() }) }))
    }, tiny = true)
}
