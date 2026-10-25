package app.umbra.agent.ui.current

import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.R
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.describeTool
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.ConsolePane
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.core.orchestrator.Block
import app.umbra.core.orchestrator.ChatItem
import app.umbra.core.orchestrator.ToolRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the user can do with a message (long-press). */
data class MessageActions(
    val copy: (String) -> Unit,
    /** Edit and resend from this user message (omp `branch`). */
    val editFrom: ((ChatItem.User) -> Unit)? = null,
    /** Continue from this user message in a new conversation (omp `fork`). */
    val forkFrom: ((ChatItem.User) -> Unit)? = null,
)

/** A user message (`Porcelain Messages`: the user's words on the plain glaze). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessage(item: ChatItem.User, actions: MessageActions) {
    // A message another session sent arrives as a user turn: shown as theirs.
    if (SessionMessageCard(item)) return
    var menu by remember(item.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.padding(start = 40.dp)) {
            Column(
                Modifier
                    .clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                    .background(if (app.umbra.agent.ui.porcelain.Theme.mono) P.accent2 else pc(0xFFDCE8D8))
                    .border(1.dp, if (app.umbra.agent.ui.porcelain.Theme.mono) P.accent2 else pc(0x6EBFD1BB), RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                    .combinedClickable(onClick = {}, onLongClick = { menu = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (item.steering) Text(stringResource(R.string.chat_steered), fontSize = 10.sp, color = P.amber, fontWeight = FontWeight.SemiBold)
                if (item.text.isNotBlank()) Text(item.text, fontSize = 15.sp, lineHeight = 22.sp, color = if (app.umbra.agent.ui.porcelain.Theme.mono) app.umbra.agent.ui.porcelain.inkOnPrimary else P.ink)
                if (item.images.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.images.forEach { Base64Picture(it.base64, 96) }
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = { menu = false; actions.copy(item.text) })
                actions.editFrom?.let { f -> DropdownMenuItem(text = { Text(stringResource(R.string.chat_edit_from)) }, onClick = { menu = false; f(item) }) }
                actions.forkFrom?.let { f -> DropdownMenuItem(text = { Text(stringResource(R.string.chat_fork_from)) }, onClick = { menu = false; f(item) }) }
            }
        }
    }
}

/** An assistant message: thinking (folded), text (markdown), tool calls as cards, and what it cost. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AssistantMessage(item: ChatItem.Assistant, tools: Map<String, ToolRun>, resultImages: Map<String, List<app.umbra.core.orchestrator.Image>>, actions: MessageActions) {
    var menu by remember(item.key) { mutableStateOf(false) }
    Box {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = { menu = true }), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item.blocks.forEach { block ->
                when (block) {
                    is Block.Thinking -> Thinking(block, item.streaming && block == item.blocks.last())
                    is Block.Text -> if (block.text.isNotBlank()) Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 3.dp, end = 10.dp)) { PIcon(PIcons.spark, size = 16.dp, tint = P.accent) }
                        MarkdownText(block.text, Modifier.weight(1f), color = P.ink)
                    }
                    is Block.ToolCall -> ToolCard(block, tools[block.id], resultImages[block.id].orEmpty())
                    is Block.Picture -> Base64Picture(block.image.base64, 180)
                }
            }
            item.error?.takeIf { item.stopReason == "error" || item.stopReason == "aborted" }?.let { Callout(it, if (item.stopReason == "aborted") Tone.Warn else Tone.Danger) }
            if (!item.streaming && (item.outputTokens > 0 || item.cost > 0)) {
                Text(
                    listOfNotNull(item.model, "${item.inputTokens}↑ ${item.outputTokens}↓", item.cost.takeIf { it > 0 }?.let { "$" + "%.4f".format(it) }).joinToString(" · "),
                    Modifier.padding(start = 26.dp), fontSize = 10.sp, color = P.muted,
                )
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_copy)) }, onClick = { menu = false; actions.copy(item.text) })
        }
    }
}

@Composable
private fun Thinking(block: Block.Thinking, live: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pc(0x80E8EFE4)).clickable { open = !open }.animateContentSize().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) app.umbra.agent.ui.porcelain.ThinkingOrb(stringResource(R.string.orb_thinking), state = app.umbra.agent.ui.porcelain.ThinkingOrbState.SOLVING)
            Text(if (block.redacted) stringResource(R.string.chat_thinking_hidden) else if (live) "  " + stringResource(R.string.chat_thinking) else stringResource(R.string.chat_thought), fontSize = 12.sp, fontStyle = FontStyle.Italic, color = P.muted)
        }
        if (open || live) Text(block.text.takeLast(if (open) 20_000 else 400), Modifier.padding(top = 4.dp), fontSize = 12.sp, lineHeight = 17.sp, color = P.sub, fontStyle = FontStyle.Italic)
    }
}

/** Simple mode: tool calls are one line each (what it does), opened by a tap; no output until then. */
val LocalCompactTools = androidx.compose.runtime.staticCompositionLocalOf { false }

/** One tool call: what it does, whether it runs, its output; specialised for the common tools. */
@Composable
fun ToolCard(call: Block.ToolCall, run: ToolRun?, images: List<app.umbra.core.orchestrator.Image>) {
    // Writing to another session or starting a subagent reads as the message it sent.
    if (OutgoingSessionCard(call, run)) return
    var open by remember(call.id) { mutableStateOf(false) }
    val running = run?.running ?: false
    val failed = run?.isError == true
    val tone = when {
        running -> pc(0xFF6E8F74)
        failed -> P.danger
        run?.result != null -> P.good
        else -> P.muted
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pc(0xCCF2F6EF)).border(1.dp, pc(0x6EBFD1BB), RoundedCornerShape(14.dp))
            .clickable { open = !open }.animateContentSize().padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) app.umbra.agent.ui.porcelain.ThinkingOrb(describeTool(call.name, call.arguments), state = app.umbra.agent.ui.porcelain.thinkingOrbStateForTool(call.name))
            else Box(Modifier.size(9.dp).clip(CircleShape).background(tone))
            Text(call.name, Modifier.padding(start = 9.dp), fontSize = 12.sp, fontFamily = P.mono, fontWeight = FontWeight.Bold, color = P.ink)
            Text(describeTool(call.name, call.arguments), Modifier.padding(start = 8.dp).weight(1f), fontSize = 12.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        call.intent?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P.sub) }
        val output = run?.result ?: run?.partial
        if (call.name == "edit" && open) EditDiff(call.arguments)
        if (open && call.name != "edit" && call.name != "bash" && !LocalCompactTools.current) {
            Text(call.arguments.toString().take(4_000), Modifier.padding(top = 6.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        }
        if (!output.isNullOrBlank() && (open || ((failed || running || call.name == "bash") && !LocalCompactTools.current))) {
            val shown = if (open) output.takeLast(30_000) else output.lines().takeLast(if (running) 6 else 4).joinToString("\n")
            Text(
                shown,
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (call.name == "bash") P.code else pc(0x80E3EBE0))
                    .horizontalScroll(rememberScrollState()).padding(9.dp),
                fontSize = 11.sp, lineHeight = 16.sp, fontFamily = P.mono, color = if (call.name == "bash") P.codeText else if (failed) P.danger else P.sub,
            )
        }
        if (images.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            images.forEach { Base64Picture(it.base64, 200) }
        }
    }
}

@Composable
private fun EditDiff(arguments: kotlinx.serialization.json.JsonObject) {
    fun s(k: String) = (arguments[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val old = s("oldText") ?: s("old_string") ?: s("old_text")
    val new = s("newText") ?: s("new_string") ?: s("new_text")
    if (old == null && new == null) {
        Text(arguments.toString().take(4_000), Modifier.padding(top = 6.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        return
    }
    Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pc(0xFFF6F8F1)).padding(8.dp)) {
        old?.lines()?.forEach { Text("- $it", Modifier.fillMaxWidth().background(pc(0xFFEDDAD6)), fontSize = 11.sp, fontFamily = P.mono, color = pc(0xFF9E635E)) }
        new?.lines()?.forEach { Text("+ $it", Modifier.fillMaxWidth().background(pc(0xFFDAE9D8)), fontSize = 11.sp, fontFamily = P.mono, color = pc(0xFF4B7350)) }
    }
}

/** A `!command` the user ran in the session folder. */
@Composable
fun BashRun(item: ChatItem.Bash) {
    ConsolePane("! ${item.command}", status = when {
        item.cancelled -> stringResource(R.string.chat_cmd_cancelled)
        item.exitCode == null -> stringResource(R.string.chat_cmd_running)
        else -> stringResource(R.string.chat_cmd_exit, item.exitCode.toString())
    }) {
        Text(item.output.takeLast(20_000).ifBlank { stringResource(R.string.chat_cmd_no_output) }, Modifier.fillMaxWidth().heightIn(max = 320.dp).horizontalScroll(rememberScrollState()).padding(12.dp), fontSize = 11.sp, lineHeight = 17.sp, fontFamily = P.mono, color = P.codeText)
    }
}

@Composable
fun SummaryMarker(item: ChatItem.Summary) {
    var open by remember(item.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(P.line))
            Text(
                "  " + (if (item.compaction) stringResource(R.string.chat_compacted) else stringResource(R.string.chat_branch_summary)) + (item.tokensBefore?.let { " · " + stringResource(R.string.chat_tokens_before, (it / 1000).toInt()) } ?: "") + "  ",
                fontSize = 11.sp, color = P.muted,
            )
            Box(Modifier.weight(1f).height(1.dp).background(P.line))
        }
        if (open) MarkdownText(item.summary, Modifier.padding(top = 6.dp), color = P.sub)
    }
}

@Composable
fun CommandOutputCard(item: ChatItem.CommandOutput) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pc(0xCCF2F6EF)).border(1.dp, pc(0x6EBFD1BB), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(item.text.take(40_000), fontSize = 12.sp, lineHeight = 18.sp, fontFamily = P.mono, color = P.ink)
    }
}

@Composable
fun CustomMessage(item: ChatItem.Custom) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pc(0x99E3EDE0)).padding(12.dp)) {
        if (item.type.isNotBlank()) Tag(item.type, Tone.Neutral)
        MarkdownText(item.text, Modifier.padding(top = 6.dp), color = P.ink)
    }
}

@Composable
fun FilesMention(item: ChatItem.Files) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.paths.forEach { Tag(it.substringAfterLast('/'), Tone.Neutral, icon = PIcons.file) }
    }
}

@Composable
fun NoticeLine(item: ChatItem.Notice) {
    Callout(item.text, when (item.level) { "error" -> Tone.Danger; "warning" -> Tone.Warn; else -> Tone.Neutral })
}

/** A picture carried in the conversation (base64), decoded off the main thread. Empty data = left out of the cache. */
@Composable
fun Base64Picture(data: String, height: Int) {
    if (data.isEmpty()) {
        Box(Modifier.size(height.dp * 0.75f, height.dp).clip(RoundedCornerShape(10.dp)).background(pc(0xFFE3EBE0)), contentAlignment = Alignment.Center) {
            Subtle(stringResource(R.string.chat_picture), Modifier.padding(6.dp))
        }
        return
    }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, data) {
        value = withContext(Dispatchers.Default) {
            try {
                val bytes = Base64.decode(data, Base64.DEFAULT)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outHeight / (sample * 2) > 700) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    bitmap?.let { Image(it, stringResource(R.string.chat_picture), Modifier.height(height.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.FillHeight) }
        ?: Box(Modifier.width(height.dp * 0.6f).height(height.dp).clip(RoundedCornerShape(10.dp)).background(pc(0xFFE3EBE0)))
}
