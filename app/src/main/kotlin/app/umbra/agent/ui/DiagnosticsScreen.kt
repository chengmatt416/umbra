package app.umbra.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.SelfTest
import app.umbra.agent.data.StepStatus
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs each capability for real (UX report S-05): it says plainly that some checks take over the phone, shows which
 * check of how many is running, and can be cancelled at any time.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val test = remember { SelfTest(context.container) }
    val results by test.results.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val running = results.any { it.status == StepStatus.RUNNING }
    val doneCount = results.count { it.status != StepStatus.PENDING && it.status != StepStatus.RUNNING }
    PorcelainPage(stringResource(R.string.dest_diagnostics), onBack) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 6.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Eyebrow(stringResource(R.string.diag_eyebrow))
                SerifTitle(stringResource(R.string.diag_title), Modifier.padding(top = 8.dp), size = 26)
                Subtle(stringResource(R.string.diag_body), Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) PButton(stringResource(R.string.diag_cancel), { job?.cancel() }, style = ButtonStyle.Warn, icon = PIcons.stop)
                    else PButton(stringResource(R.string.diag_run), { job = scope.launch { test.run() } }, icon = PIcons.play)
                    if (results.isNotEmpty() && !running) PButton(stringResource(R.string.diag_copy), {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umbra diagnostics", test.report()))
                    }, style = ButtonStyle.Alt, icon = PIcons.copy)
                }
                if (results.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        PBar(doneCount / results.size.toFloat())
                        Text(
                            if (running) stringResource(R.string.diag_progress, (doneCount + 1).coerceAtMost(results.size), results.size)
                            else stringResource(R.string.diag_finished, results.count { it.status == StepStatus.PASSED }, results.count { it.status == StepStatus.FAILED }, results.count { it.status == StepStatus.SKIPPED }),
                            Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P.sub, style = TextStyle(fontFeatureSettings = P.tabular),
                        )
                    }
                }
            }
            items(results, key = { it.id }) { step ->
                PCard(style = CardStyle.Soft, padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                            when (step.status) {
                                StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P.accent2)
                                StepStatus.PASSED -> PIcon(PIcons.check, size = 16.dp, tint = P.good)
                                StepStatus.FAILED -> PIcon(PIcons.close, size = 16.dp, tint = P.danger)
                                StepStatus.SKIPPED -> PIcon(PIcons.more, size = 16.dp, tint = P.muted)
                                StepStatus.PENDING -> PIcon(PIcons.clock, size = 16.dp, tint = P.muted)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(step.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                            if (step.detail.isNotBlank()) Text(step.detail, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = if (step.status == StepStatus.FAILED) P.danger else P.sub)
                        }
                        if (step.durationMs > 0) Text(formatMs(step.durationMs), fontSize = 11.sp, color = P.muted, style = TextStyle(fontFeatureSettings = P.tabular))
                    }
                }
            }
        }
    }
}
