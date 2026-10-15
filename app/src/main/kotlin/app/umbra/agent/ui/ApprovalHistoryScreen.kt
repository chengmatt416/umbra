package app.umbra.agent.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.porcelain.PEmpty
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.core.safety.ApprovalLedgerEntry
import app.umbra.core.safety.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything Umbra asked and how it ended (spec §32 "review approvals"). The ledger is append-only: "Hide from list"
 * only moves the list's starting point, and "Show all" brings every entry back.
 */
@Composable
fun ApprovalHistoryScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<ApprovalLedgerEntry>?>(null) }
    var hidden by remember { mutableIntStateOf(0) }
    var confirmHide by remember { mutableStateOf(false) }
    LaunchedEffect(prefs.approvalHistoryHiddenBeforeMs) {
        withContext(Dispatchers.IO) {
            entries = container.ledger.read(sinceMs = prefs.approvalHistoryHiddenBeforeMs)
            hidden = if (prefs.approvalHistoryHiddenBeforeMs == 0L) 0 else container.ledger.countUntil(prefs.approvalHistoryHiddenBeforeMs)
        }
    }
    val list = entries

    PorcelainPage(
        stringResource(R.string.dest_approvals), onBack,
        actions = { if (!list.isNullOrEmpty()) PButton(stringResource(R.string.ah_hide), { confirmHide = true }, style = ButtonStyle.Ghost, tiny = true) },
    ) {
        when {
            list == null -> Unit
            list.isEmpty() && hidden == 0 -> PEmpty(stringResource(R.string.ah_empty_title), stringResource(R.string.ah_empty_body), icon = PIcons.check)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Eyebrow(stringResource(R.string.ah_eyebrow)) }
                if (hidden > 0) item {
                    PCard(style = CardStyle.Soft) {
                        Subtle(pluralStringResource(R.plurals.ah_hidden_note, hidden, hidden))
                        Box(Modifier.padding(top = 8.dp)) {
                            PButton(stringResource(R.string.ah_show_all), { container.settings.update { it.copy(approvalHistoryHiddenBeforeMs = 0) } }, style = ButtonStyle.Alt, tiny = true)
                        }
                    }
                }
                items(list, key = { "${it.requestId}-${it.kind}-${it.timestampMs}" }) { e -> LedgerRow(e) }
            }
        }
    }

    if (confirmHide && list != null) {
        PConfirm(
            title = stringResource(R.string.ah_hide_title),
            body = pluralStringResource(R.plurals.ah_hide_body, list.size, list.size),
            confirmLabel = stringResource(R.string.ah_hide),
            danger = false,
            onDismiss = { confirmHide = false },
            onConfirm = {
                confirmHide = false
                val newest = list.maxOfOrNull { it.timestampMs } ?: System.currentTimeMillis()
                container.settings.update { it.copy(approvalHistoryHiddenBeforeMs = newest) }
            },
        )
    }
}

@Composable
private fun LedgerRow(e: ApprovalLedgerEntry) {
    val (label, tone) = ledgerKind(e.kind)
    val resources = androidx.compose.ui.platform.LocalResources.current
    PCard(style = CardStyle.Soft, padding = PaddingValues(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(stringResource(label), tone)
            Text(
                stringResource(riskRes(e.level)) + " · " + DateUtils.getRelativeTimeSpanString(e.timestampMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                Modifier.weight(1f), fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(e.summary, Modifier.padding(top = 8.dp), fontFamily = P.mono, fontSize = 12.sp, lineHeight = 18.sp, color = P.ink, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (e.categories.isNotEmpty()) Subtle(e.categories.joinToString { resources.getString(riskCategoryRes(it)) }, Modifier.padding(top = 6.dp))
    }
}

private fun ledgerKind(kind: String): Pair<Int, Tone> = when (kind) {
    "approved" -> R.string.ah_kind_approved to Tone.Good
    "auto_allowed" -> R.string.ah_kind_auto_allowed to Tone.Good
    "denied" -> R.string.ah_kind_denied to Tone.Danger
    "auto_denied" -> R.string.ah_kind_auto_denied to Tone.Danger
    "timed_out" -> R.string.ah_kind_timed_out to Tone.Warn
    "cancelled" -> R.string.ah_kind_cancelled to Tone.Neutral
    else -> R.string.ah_kind_requested to Tone.Neutral
}

@StringRes
fun riskRes(level: RiskLevel) = when (level) {
    RiskLevel.NONE -> R.string.risk_none
    RiskLevel.LOW -> R.string.risk_low
    RiskLevel.MEDIUM -> R.string.risk_medium
    RiskLevel.HIGH -> R.string.risk_high
    RiskLevel.CRITICAL -> R.string.risk_critical
}
