package app.umbra.agent.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.rememberLabel
import app.umbra.core.orchestrator.QuestionBroker
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.RiskLevel
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse

fun riskTone(level: RiskLevel) = when (level) {
    RiskLevel.NONE, RiskLevel.LOW -> Tone.Good
    RiskLevel.MEDIUM -> Tone.Warn
    RiskLevel.HIGH, RiskLevel.CRITICAL -> Tone.Danger
}

/**
 * One approval: exactly what will happen, why it is risky, and an answer that covers this one action only unless
 * the user picks a remembered scope (the reference's "Approve once").
 */
@Composable
fun ApprovalPanel(request: ApprovalRequest, sessionTitle: String?, onDecision: (ApprovalDecision) -> Unit, modifier: Modifier = Modifier) {
    val level = request.assessment.level
    var more by remember(request.id) { mutableStateOf(false) }
    PCard(modifier, style = CardStyle.Soft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Kicker(sessionTitle ?: "Approval")
                Text(request.description, Modifier.padding(top = 6.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink,
                    fontFamily = if (request.toolName == "shell" || request.toolName == "bash") P.mono else null)
            }
            Tag(level.name.lowercase().replaceFirstChar { it.uppercase() } + " risk", riskTone(level))
        }
        request.assessment.findings.forEach { Subtle("• ${it.category.title}: ${it.reason}", Modifier.padding(top = 4.dp)) }
        request.details["why"]?.let { Subtle("Why: $it", Modifier.padding(top = 4.dp)) }
        request.foregroundPackage?.let { Subtle("In: $it", Modifier.padding(top = 2.dp)) }
        Subtle("This answers this one action. It does not grant lasting control.", Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PButton("Deny", { onDecision(ApprovalDecision.Denied("denied by the user")) }, style = ButtonStyle.Alt, tiny = true)
            PButton("Approve once", { onDecision(ApprovalDecision.Approved()) }, tiny = true, style = if (level >= RiskLevel.HIGH) ButtonStyle.Warn else ButtonStyle.Primary)
            if (request.rememberOptions.isNotEmpty() && level < RiskLevel.CRITICAL) {
                Box {
                    PButton("More", { more = true }, style = ButtonStyle.Ghost, tiny = true)
                    DropdownMenu(more, { more = false }) {
                        request.rememberOptions.forEach { scope ->
                            DropdownMenuItem(text = { Text(rememberLabel(scope)) }, onClick = { more = false; onDecision(ApprovalDecision.Approved(scope)) })
                        }
                    }
                }
            }
        }
    }
}

/** A question omp (or one of its extensions) asks: select, confirm, text, editor or a structured ask. */
@Composable
fun QuestionPanel(pending: QuestionBroker.Pending, sessionTitle: String?, onAnswer: (UiResponse) -> Unit, modifier: Modifier = Modifier) {
    PCard(modifier, style = CardStyle.Soft) {
        Kicker(sessionTitle ?: "Question")
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
                    PButton("No", { onAnswer(UiResponse.Confirmed(false)) }, style = ButtonStyle.Alt, tiny = true)
                    PButton("Yes", { onAnswer(UiResponse.Confirmed(true)) }, tiny = true)
                }
            }
            is UiRequest.Input -> TextAnswer(r.title, r.placeholder, null, onAnswer)
            is UiRequest.Editor -> TextAnswer(r.title, null, r.prefill, onAnswer)
            is UiRequest.Ask -> AskAnswerForm(r, onAnswer)
        }
        Gap(6.dp)
        PButton("Skip", { onAnswer(UiResponse.Cancelled()) }, style = ButtonStyle.Ghost, tiny = true)
    }
}

@Composable
private fun TextAnswer(title: String, placeholder: String?, prefill: String?, onAnswer: (UiResponse) -> Unit) {
    var text by remember(title) { mutableStateOf(prefill.orEmpty()) }
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = placeholder?.let { { Text(it) } }, minLines = if (prefill != null) 3 else 1)
    Gap(8.dp)
    PButton("Send", { onAnswer(UiResponse.Value(text)) }, tiny = true)
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
                    Text(option.label + if (q.recommended == q.options.indexOf(option)) "  (suggested)" else "", fontSize = 14.sp, color = P.ink)
                    option.description?.let { Subtle(it) }
                }
            }
        }
        OutlinedTextField(custom.value[q.id].orEmpty(), { custom.value = custom.value + (q.id to it) }, Modifier.fillMaxWidth().padding(top = 4.dp), placeholder = { Text("Or write your own answer") }, singleLine = true)
        Gap(8.dp)
    }
    PButton("Send answers", {
        onAnswer(UiResponse.Answers(request.questions.map { q -> AskAnswer(q.id, picked.value[q.id].orEmpty().toList(), custom.value[q.id]?.takeIf { it.isNotBlank() }) }))
    }, tiny = true)
}
