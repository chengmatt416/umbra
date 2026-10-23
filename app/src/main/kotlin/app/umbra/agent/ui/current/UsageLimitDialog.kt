package app.umbra.agent.ui.current

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.PChoice
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PToggle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.session.AiUsageLimit
import app.umbra.core.session.AiUsageUnit
import app.umbra.core.session.UsageReset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun UsageLimitDialog(current: AiUsageLimit?, sessionId: String?, agent: Boolean = false, onSave: suspend (AiUsageLimit?) -> Unit, onDismiss: () -> Unit) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(current != null) }
    var unit by remember { mutableStateOf(current?.unit ?: AiUsageUnit.API_USD) }
    var maximum by remember { mutableStateOf(current?.maximum?.toString() ?: "1") }
    var reset by remember { mutableStateOf(current?.reset ?: UsageReset.NEVER) }
    var saving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val amount = maximum.toDoubleOrNull()
    val valid = !enabled || (amount != null && amount.isFinite() && amount > 0 && (unit == AiUsageUnit.API_USD || amount <= 100))
    val spent by produceState<Double?>(null, sessionId, current) {
        val session = sessionId?.let { container.sessionStore.get(it) }
        if (session != null) value = runCatching {
            container.usageBudgets.spent(session).entries.firstOrNull { it.key.startsWith(if (agent) "agent:" else "task:") }?.value
        }.getOrNull()
    }
    PConfirm(
        title = stringResource(R.string.ai_usage_limit), body = "", danger = false,
        confirmLabel = stringResource(R.string.action_save), confirmEnabled = valid && !saving,
        onDismiss = { if (!saving) onDismiss() },
        onConfirm = {
            scope.launch {
                saving = true
                try {
                    val limit = if (!enabled) null else AiUsageLimit(unit, amount!!, reset,
                        current?.takeIf { it.unit == unit && it.reset == reset }?.startedAtMs ?: System.currentTimeMillis())
                    onSave(limit)
                    onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { problem = e.message ?: "Could not save the limit." }
                finally { saving = false }
            }
        },
        extra = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                PToggle(stringResource(R.string.ai_limit_enabled), stringResource(if (agent) R.string.ai_agent_limit_body else R.string.ai_task_limit_body), enabled, { enabled = it })
                if (enabled) {
                    PChoice(listOf(
                        Triple(AiUsageUnit.API_USD, stringResource(R.string.ai_unit_usd), stringResource(R.string.ai_unit_usd_body)),
                        Triple(AiUsageUnit.FIVE_HOUR_PERCENT, stringResource(R.string.ai_unit_5h), stringResource(R.string.ai_unit_percent_body)),
                        Triple(AiUsageUnit.WEEKLY_PERCENT, stringResource(R.string.ai_unit_week), stringResource(R.string.ai_unit_percent_body)),
                    ), unit, { unit = it })
                    PField(maximum, { maximum = it }, placeholder = stringResource(R.string.ai_limit_amount))
                    Gap(8.dp)
                    PChoice(listOf(
                        Triple(UsageReset.NEVER, stringResource(R.string.ai_reset_never), ""),
                        Triple(UsageReset.TEN_MINUTES, stringResource(R.string.ai_reset_10m), ""),
                        Triple(UsageReset.HOURLY, stringResource(R.string.ai_reset_hour), ""),
                        Triple(UsageReset.DAILY, stringResource(R.string.ai_reset_day), ""),
                    ), reset, { reset = it })
                    spent?.let { Subtle(stringResource(R.string.ai_spent, it)) }
                    Subtle(stringResource(R.string.ai_limit_note))
                }
                problem?.let { Callout(it, Tone.Danger) }
            }
        },
    )
}
