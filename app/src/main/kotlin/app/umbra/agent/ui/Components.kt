package app.umbra.agent.ui

import androidx.compose.ui.res.pluralStringResource
import app.umbra.agent.R
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.core.orchestrator.QuestionBroker
import app.umbra.core.safety.ApprovalDecision
import app.umbra.core.safety.ApprovalRequest
import app.umbra.core.safety.RememberScope
import app.umbra.core.safety.RiskLevel
import app.umbra.core.session.PlanStep
import app.umbra.core.session.SessionState
import app.umbra.runtime.omp.AskAnswer
import app.umbra.runtime.omp.UiRequest
import app.umbra.runtime.omp.UiResponse
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun stateLabel(state: SessionState) = app.umbra.agent.ui.sessions.stateText(state)

fun stateColor(state: SessionState): Color = when (state) {
    SessionState.RUNNING -> Semantic.info
    SessionState.WAITING_APPROVAL -> Semantic.warn
    SessionState.COMPLETED -> Semantic.ok
    SessionState.FAILED -> Semantic.danger
    SessionState.PAUSED, SessionState.STOPPED, SessionState.CREATED -> Semantic.idle
}

@Composable
fun StateChip(state: SessionState, modifier: Modifier = Modifier) {
    val color = stateColor(state)
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == SessionState.RUNNING) CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = color)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(stateLabel(state), style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
    }
}

fun riskColor(level: RiskLevel): Color = when (level) {
    RiskLevel.NONE, RiskLevel.LOW -> Semantic.ok
    RiskLevel.MEDIUM -> Semantic.warn
    RiskLevel.HIGH, RiskLevel.CRITICAL -> Semantic.danger
}

// ---- timeline pieces ----

@Composable
fun UserBubble(text: String, imageCount: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.padding(start = 48.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                if (imageCount > 0) Text(pluralStringResource(R.plurals.tl_images_attached, imageCount, imageCount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun AgentBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(end = 24.dp),
        ) {
            MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

fun toolStatusColor(status: String?): Color = when (status) {
    null -> Semantic.info
    "ok" -> Semantic.ok
    "needs_user", "timeout", "cancelled", "unsupported" -> Semantic.warn
    else -> Semantic.danger
}

@Composable
fun ToolRow(item: TimelineItem.Tool) {
    var expanded by remember(item.callId) { mutableStateOf(false) }
    val color = toolStatusColor(item.status)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
            else Box(Modifier.size(9.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(item.tool, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                describeTool(item.tool, item.args),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.durationMs > 0) Text("${formatMs(item.durationMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded || item.status != null && item.status != "ok") {
            item.intent?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.status == "ok" || item.status == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
                    maxLines = if (expanded) 40 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded) item.backend?.let { Text("via $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (item.screenshots.isNotEmpty()) {
            Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.screenshots.forEach { Screenshot(it) }
            }
        }
    }
}

@Composable
fun Screenshot(path: String, height: Int = 160) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val sample = generateSequence(1) { it * 2 }.takeWhile { bounds.outHeight / it > 600 }.lastOrNull()?.times(2) ?: 1
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }
    bitmap?.let {
        Image(
            it, contentDescription = stringResource(R.string.tl_screenshot),
            modifier = Modifier.height(height.dp).clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.FillHeight,
        )
    }
}

fun formatMs(ms: Long): String = when {
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${ms % 60_000 / 1000}s"
}

@Composable
fun NoticeRow(level: String, title: String, text: String) {
    val color = when (level) {
        "error" -> Semantic.danger
        "warning" -> Semantic.warn
        else -> Semantic.idle
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column {
            Text(title, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun StateDivider(state: SessionState, reason: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
        Text(
            "  ${stateLabel(state)}${reason?.takeIf { it.isNotBlank() && state != SessionState.RUNNING }?.let { " · $it" } ?: ""}  ",
            style = MaterialTheme.typography.labelSmall,
            color = stateColor(state),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        androidx.compose.material3.HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
fun ApprovalLogRow(item: TimelineItem.Approval) {
    val (label, color) = when (item.kind) {
        "approved" -> stringResource(R.string.tl_you_approved) to Semantic.ok
        "auto_allowed" -> stringResource(R.string.tl_auto_allowed) to Semantic.ok
        "denied" -> stringResource(R.string.tl_you_denied) to Semantic.danger
        "auto_denied" -> stringResource(R.string.tl_auto_denied) to Semantic.danger
        "timed_out" -> stringResource(R.string.tl_timed_out) to Semantic.warn
        else -> stringResource(R.string.tl_approval_other, item.kind.replace('_', ' ')) to Semantic.idle
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text("$label: ${item.summary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ---- plan ----

@Composable
fun PlanCard(steps: List<PlanStep>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val done = steps.count { it.status == "done" }
    val current = steps.firstOrNull { it.status == "active" } ?: steps.firstOrNull { it.status == "pending" }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.clickable { expanded = !expanded }.animateContentSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tl_plan), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text("$done/${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                if (!expanded) Text(current?.text.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (expanded) steps.forEach { step ->
                val (mark, color) = when (step.status) {
                    "done" -> "✓" to Semantic.ok
                    "active" -> "▸" to Semantic.info
                    "failed", "blocked" -> "✕" to Semantic.danger
                    "skipped" -> "–" to Semantic.idle
                    else -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.padding(top = 6.dp)) {
                    Text(mark, Modifier.width(20.dp), color = color, fontWeight = FontWeight.Bold)
                    Text(step.text, style = MaterialTheme.typography.bodySmall, color = if (step.status == "done" || step.status == "skipped") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** What the task currently holds: helpers, processes, apps and displays (spec §24, §25). Hidden when there is nothing to show. */
@Composable
fun ActivityCard(session: app.umbra.core.session.Session, modifier: Modifier = Modifier) {
    val subagents = session.subagents.filter { it.state == "running" }
    if (subagents.isEmpty() && session.processes.isEmpty() && session.activeApps.isEmpty() && session.displays.isEmpty()) return
    var expanded by remember(session.id) { mutableStateOf(false) }
    val parts = buildList {
        if (subagents.isNotEmpty()) add(pluralStringResource(R.plurals.tl_helpers, subagents.size, subagents.size))
        if (session.processes.isNotEmpty()) add(pluralStringResource(R.plurals.tl_processes, session.processes.size, session.processes.size))
        if (session.activeApps.isNotEmpty()) add(pluralStringResource(R.plurals.tl_apps, session.activeApps.size, session.activeApps.size))
        if (session.displays.isNotEmpty()) add(pluralStringResource(R.plurals.tl_displays, session.displays.size, session.displays.size))
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.clickable { expanded = !expanded }.animateContentSize().padding(12.dp)) {
            Text(stringResource(R.string.tl_running, parts.joinToString(" · ")), style = MaterialTheme.typography.labelLarge)
            if (expanded) {
                subagents.forEach { Text(stringResource(R.string.tl_helper, it.id) + (it.role?.let { r -> " ($r)" } ?: ""), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall) }
                session.processes.take(12).forEach { Text("${it.name}  pid ${it.pid}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                session.activeApps.forEach { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                session.displays.forEach { Text(stringResource(R.string.tl_display, it), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
