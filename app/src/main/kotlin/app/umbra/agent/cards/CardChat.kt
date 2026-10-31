package app.umbra.agent.cards

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import app.umbra.core.orchestrator.Block
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Who a card's answers go to. The chat that shows a card provides it: the agent's conversation sends as the user
 * (AgentRuntime.talk), a coding session's chat as a user message to that session. Without one (history, previews),
 * cards are drawn as they were and do nothing.
 */
class CardHost(val sessionId: String, val send: suspend (String) -> Unit)

val LocalCardHost = staticCompositionLocalOf<CardHost?> { null }

/** The tool whose calls are drawn as cards instead of tool rows. */
object CardCalls {
    const val SHOW = "show_card"
}

/**
 * One `show_card` call, drawn as its card. The template turns the agent's data into the card each time it is drawn
 * (the same data always gives the same card); what the user ticked or answered is kept per card on this phone.
 */
@Composable
fun ToolCallCard(call: Block.ToolCall, modifier: Modifier = Modifier, pending: Boolean = false) {
    val host = LocalCardHost.current
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val built = remember(call.id, call.arguments) { CardTemplates.fromArguments(call.arguments) }
    val key = (host?.sessionId ?: "none") + "/" + call.id
    var state by remember(key) { mutableStateOf(container.cardStates.get(key)) }
    var link by remember { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.card_copied)
    val spec = built.getOrNull()
    if (spec == null) {
        // Still arriving: the card's shape, quietly, until its data is complete.
        if (pending) Box(modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(22.dp)).background(P.highlight.copy(alpha = .6f)))
        else Text(stringResource(R.string.card_unknown, built.exceptionOrNull()?.message?.take(80) ?: "?"), modifier, color = P.muted, fontSize = 12.sp)
        return
    }
    CardView(spec, state, onEvent = { event ->
        when (event) {
            is CardEvent.State -> { state = event.state; container.cardStates.put(key, event.state) }
            is CardEvent.Send -> host?.let { h ->
                scope.launch {
                    try {
                        h.send(event.text)
                        // An answer locks its block, so a second tap cannot send it twice.
                        event.blockId?.let { id ->
                            state = state.copy(sent = state.sent + (id to event.text.substringAfter("] ")))
                            container.cardStates.put(key, state)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Toast.makeText(context, e.message ?: "", Toast.LENGTH_LONG).show() }
                }
            }
            is CardEvent.Copy -> {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("card", event.text))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            }
            is CardEvent.Open -> link = event.url
        }
    }, modifier.fillMaxWidth(), interactive = host != null)
    // A link an agent put on a card opens only after the user has seen where it goes.
    link?.let { url ->
        val domain = runCatching { Uri.parse(url).host }.getOrNull() ?: url
        AlertDialog(
            onDismissRequest = { link = null },
            title = { Text(stringResource(R.string.card_open_link_title)) },
            text = { Column { Text(stringResource(R.string.card_open_link_body, domain), color = P.sub, fontSize = 14.sp) } },
            confirmButton = {
                PButton(stringResource(R.string.card_open), {
                    link = null
                    if (url.startsWith("https://") || url.startsWith("http://")) runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                })
            },
            dismissButton = { PButton(stringResource(R.string.action_cancel), { link = null }, style = ButtonStyle.Ghost) },
        )
    }
}
