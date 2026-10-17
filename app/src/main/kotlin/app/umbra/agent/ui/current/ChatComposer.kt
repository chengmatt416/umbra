package app.umbra.agent.ui.current

import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.porcelain.pcHue
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.core.orchestrator.ConversationState
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.OmpSlashCommand
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How a message sent while the agent works is delivered. */
enum class WhileWorking(val label: String) { Steer("Steer"), Queue("Queue"), Interrupt("Interrupt") }

/** What the composer asks the screen to do. */
interface ComposerActions {
    fun send(text: String, images: List<ContentBlock.Image>, mode: WhileWorking)
    fun slash(text: String)
    fun bang(command: String)
    fun stop()
    fun removeQueued(text: String, queue: String)
    fun promoteQueued(text: String)
}

/**
 * The session composer: `/` opens omp's command palette (with subcommands and hints), `!` runs a shell command in the
 * session folder, pictures can be attached, and while the agent works a message steers it, waits in the queue, or
 * interrupts the turn. Queued messages can be taken back or sent now.
 */
@Composable
fun ChatComposer(
    conversation: ConversationState,
    text: String,
    onText: (String) -> Unit,
    busy: Boolean,
    paused: Boolean,
    actions: ComposerActions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val images = remember { mutableStateListOf<ContentBlock.Image>() }
    var mode by remember { mutableStateOf(WhileWorking.Steer) }
    var stopMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        scope.launch { uris.forEach { uri -> encodePicture(context, uri)?.let(images::add) } }
    }
    val working = conversation.working || busy
    var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }
    // Text set from outside (palette, branch, omp's set_editor_text) arrives with the cursor at its end.
    if (field.text != text) field = androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))
    val takesImages = conversation.session?.model?.takesImages ?: true

    Column(Modifier.fillMaxWidth().background(pc(0xF0E9EDE5)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Extension widgets above the editor, then the queue.
        conversation.widgets.values.filter { it.aboveEditor }.forEach { w ->
            Text(w.lines.joinToString("\n"), Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(pc(0x99E3EDE0)).padding(8.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        }
        if (conversation.queuedSteering.isNotEmpty() || conversation.queuedFollowUp.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                conversation.queuedSteering.forEach { q -> QueueChip(stringResource(R.string.comp_steer), q, onRemove = { actions.removeQueued(q, "steering") }) }
                conversation.queuedFollowUp.forEach { q -> QueueChip(stringResource(R.string.comp_queued), q, onRemove = { actions.removeQueued(q, "followUp") }, onPromote = { actions.promoteQueued(q) }) }
            }
        }

        val palette = paletteFor(text, conversation.commands)
        if (palette.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(14.dp)).background(pc(0xFFF7FAF4)).border(1.dp, pc(0x6EBFD1BB), RoundedCornerShape(14.dp))) {
                items(palette, key = { it.first }) { (insert, label, hint) ->
                    Column(Modifier.fillMaxWidth().clickable { onText(insert) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(label, fontSize = 13.sp, fontFamily = P.mono, fontWeight = FontWeight.SemiBold, color = P.ink)
                        if (!hint.isNullOrBlank()) Text(hint, fontSize = 11.sp, color = P.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        if (images.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                images.forEachIndexed { i, img ->
                    Box {
                        Base64Picture(img.base64, 64)
                        Box(Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(8.dp)).background(pcHue(0xCC26372F)).clickable { images.removeAt(i) }.padding(3.dp)) {
                            PIcon(PIcons.close, size = 12.dp, tint = Color.White)
                        }
                    }
                }
            }
            if (!takesImages) Subtle(stringResource(R.string.comp_no_images), Modifier.padding(bottom = 6.dp))
        }

        val shape = RoundedCornerShape(20.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(pc(0xFFF7FAF4)).border(1.dp, pc(0xB5FFFFFF), shape).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
                if (text.isEmpty()) Text(
                    stringResource(when {
                        working -> R.string.comp_hint_working
                        paused -> R.string.comp_hint_paused
                        else -> R.string.comp_hint
                    }),
                    fontSize = 15.sp, color = pc(0xFF9AAA98),
                )
                val messageLabel = stringResource(R.string.cap_message)
                BasicTextField(field, { v -> field = v; if (v.text != text) onText(v.text) }, Modifier.fillMaxWidth().semantics { contentDescription = messageLabel }, textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink, fontFamily = if (text.startsWith("!")) P.mono else null), cursorBrush = SolidColor(P.accent2), maxLines = 8)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PButton("", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.plus, contentDescription = stringResource(R.string.comp_attach))
                // While the agent works, a message reaches it now (steer) or after this turn (queue); the choice only
                // shows once there is something to send.
                if (working && text.isNotBlank()) {
                    Box(Modifier.padding(start = 6.dp)) { Segmented(listOf(WhileWorking.Steer to stringResource(R.string.comp_now), WhileWorking.Queue to stringResource(R.string.comp_later)), mode, { mode = it }) }
                }
                Box(Modifier.weight(1f))
                if (working) {
                    Box {
                        PButton(stringResource(R.string.comp_pause), { stopMenu = true }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.pause)
                        DropdownMenu(stopMenu, { stopMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.comp_pause_task)) }, onClick = { stopMenu = false; actions.stop() })
                            if (text.isNotBlank()) DropdownMenuItem(text = { Text(stringResource(R.string.comp_interrupt)) }, onClick = {
                                stopMenu = false
                                actions.send(text.trim(), images.toList(), WhileWorking.Interrupt)
                                onText(""); images.clear()
                            })
                        }
                    }
                    Box(Modifier.padding(start = 6.dp))
                }
                PButton("", {
                    val t = text.trim()
                    when {
                        t.startsWith("!") && t.length > 1 -> actions.bang(t.removePrefix("!").removePrefix("!").trim())
                        t.startsWith("/") && isCommand(t, conversation.commands) -> actions.slash(t)
                        t.isNotEmpty() || images.isNotEmpty() -> actions.send(t, images.toList(), mode)
                    }
                    onText(""); images.clear()
                }, tiny = true, icon = PIcons.send, enabled = text.isNotBlank() || images.isNotEmpty(), contentDescription = stringResource(R.string.cap_send))
            }
        }
        if (conversation.statuses.isNotEmpty()) {
            Text(conversation.statuses.values.joinToString("  ·  "), Modifier.padding(top = 5.dp, start = 6.dp), fontSize = 10.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        conversation.widgets.values.filter { !it.aboveEditor }.forEach { w ->
            Text(w.lines.joinToString("\n"), Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 11.sp, fontFamily = P.mono, color = P.sub)
        }
    }
}

@Composable
private fun QueueChip(kind: String, text: String, onRemove: () -> Unit, onPromote: (() -> Unit)? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(pc(0xFFF1E9DE)).border(1.dp, pc(0xFFE1CBAC), RoundedCornerShape(50)).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$kind: ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = pc(0xFF956F44))
        Text(text.take(40), fontSize = 11.sp, color = pc(0xFF956F44), maxLines = 1)
        if (onPromote != null) Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(R.string.comp_send_now), onClick = onPromote).padding(4.dp)) { PIcon(PIcons.arrowRight, size = 12.dp, tint = pc(0xFF956F44)) }
        Box(Modifier.clip(RoundedCornerShape(50)).clickable(onClickLabel = stringResource(R.string.comp_take_back), onClick = onRemove).padding(4.dp)) { PIcon(PIcons.close, size = 12.dp, tint = pc(0xFF956F44)) }
    }
}

/** Palette rows for what is typed: (text to insert, label, hint). */
private fun paletteFor(text: String, commands: List<OmpSlashCommand>): List<Triple<String, String, String?>> {
    if (!text.startsWith("/") || text.contains('\n')) return emptyList()
    val body = text.removePrefix("/")
    val name = body.substringBefore(' ')
    if (!body.contains(' ')) {
        return commands.filter { c -> c.name.startsWith(name, true) || c.aliases.any { it.startsWith(name, true) } }
            .take(40).map { Triple("/${it.name} ", "/${it.name}" + (it.inputHint?.let { h -> " $h" } ?: ""), it.description) }
    }
    val command = commands.firstOrNull { it.name.equals(name, true) || it.aliases.any { a -> a.equals(name, true) } } ?: return emptyList()
    val rest = body.substringAfter(' ')
    if (rest.contains(' ')) return emptyList()
    return command.subcommands.filter { it.name.startsWith(rest, true) && !it.name.equals(rest, true) }
        .map { Triple("/${command.name} ${it.name} ", "/${command.name} ${it.name}" + (it.usage?.let { u -> " $u" } ?: ""), it.description) }
}

private fun isCommand(text: String, commands: List<OmpSlashCommand>): Boolean {
    val name = text.removePrefix("/").substringBefore(' ')
    return commands.any { it.name.equals(name, true) || it.aliases.any { a -> a.equals(name, true) } }
}

/** A picked picture, scaled to at most 1600 px and JPEG-encoded, as a prompt image. */
private suspend fun encodePicture(context: Context, uri: Uri): ContentBlock.Image? = withContext(Dispatchers.IO) {
    try {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
        val scale = 1600f / maxOf(bitmap.width, bitmap.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, 85, out)
        ContentBlock.Image(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), "image/jpeg")
    } catch (_: Exception) {
        null
    }
}
