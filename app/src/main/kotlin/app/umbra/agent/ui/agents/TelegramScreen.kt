package app.umbra.agent.ui.agents

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.telegram.TelegramBot
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PField
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.pc
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import app.umbra.agent.ui.shell.SwitchRow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A QR code of [text], drawn module by module. */
@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier, ink: Color = P.ink) {
    val matrix = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)) }.getOrNull()
    } ?: return
    Canvas(modifier.size(size).background(Color.White)) {
        val cell = this.size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/**
 * The Telegram bot (Figma 14.3 set-up, 14.4 linked): make a bot with @BotFather, paste its token, open the one-time
 * link from your Telegram. Linked, it lists what the bot may do, how it stays connected, and the unlink button.
 */
@Composable
fun TelegramScreen(agentId: String, onBack: () -> Unit) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bot = container.telegram
    val state by bot.state.collectAsStateWithLifecycle()
    val health by bot.health.collectAsStateWithLifecycle()
    // The link's state changes with time (the code expires) as well as with the bot's state.
    val link by produceState(bot.link, state) { while (true) { value = bot.link; delay(2_000) } }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val agentName = container.agents.store.get(agentId)?.name ?: stringResource(R.string.ag_default_name)

    PorcelainPage(stringResource(R.string.ag_telegram), onBack) {
        PageColumn {
            if (link == TelegramBot.Link.LINKED) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    PIcon(PIcons.arrowUp, size = 22.dp, tint = P.sub)
                    Column(Modifier.weight(1f)) {
                        Text("@" + state.botUsername, fontSize = 16.sp, fontFamily = P.mono, color = P.ink)
                        Text(
                            stringResource(R.string.tg_only_you, state.userName ?: "", (state.userId ?: 0).toString().take(4) + "…") + " · " +
                                stringResource(if (health == null) R.string.tg_connected else R.string.tg_reconnecting),
                            fontSize = 12.sp, color = P.sub,
                        )
                    }
                }
                RowGroup(stringResource(R.string.tg_can)) {
                    SwitchRow(PIcons.message, stringResource(R.string.tg_messages, agentName), stringResource(R.string.tg_messages_sub), state.messages) { on -> bot.setOptions { it.copy(messages = on) } }
                    SwitchRow(PIcons.mic, stringResource(R.string.tg_voice), stringResource(R.string.tg_voice_sub, agentName), state.voice) { on -> bot.setOptions { it.copy(voice = on) } }
                    SwitchRow(PIcons.check, stringResource(R.string.tg_approvals), stringResource(R.string.tg_approvals_sub), state.approvals) { on -> bot.setOptions { it.copy(approvals = on) } }
                    SwitchRow(PIcons.users, stringResource(R.string.tg_switch), stringResource(R.string.tg_switch_sub), state.switching) { on -> bot.setOptions { it.copy(switching = on) } }
                }
                RowGroup(stringResource(R.string.tg_connection)) {
                    NavRow(PIcons.wifi, stringResource(R.string.tg_polling), stringResource(R.string.tg_polling_sub), value = stringResource(if (health == null) R.string.tg_ok else R.string.tg_problem), chevron = false) {}
                }
                health?.let { Callout(it, Tone.Warn) }
                PButton(stringResource(R.string.tg_unlink), { bot.unlink() }, Modifier.fillMaxWidth(), style = ButtonStyle.Danger)
                return@PageColumn
            }

            // Step 1: a bot from @BotFather.
            Step(1, done = link != TelegramBot.Link.NONE, title = stringResource(R.string.tg_step1)) {
                PButton(stringResource(R.string.tg_open_botfather), {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, tiny = true, style = ButtonStyle.Alt, icon = PIcons.external)
            }
            // Step 2: its token.
            Step(2, done = link != TelegramBot.Link.NONE, title = stringResource(R.string.tg_step2)) {
                if (link == TelegramBot.Link.NONE) {
                    PField(token, { token = it.trim() }, placeholder = "7731…:AAH-…", singleLine = true, mono = true, visualTransformation = PasswordVisualTransformation())
                    PButton(stringResource(R.string.action_save), {
                        busy = true; error = null
                        scope.launch {
                            try { bot.setToken(token); token = ""; bot.newLink() } catch (e: Exception) { error = e.message ?: e::class.simpleName } finally { busy = false }
                        }
                    }, tiny = true, enabled = token.isNotBlank() && !busy)
                } else {
                    Text("@${state.botUsername} · " + stringResource(R.string.tg_token_kept), fontSize = 12.sp, color = P.sub)
                    PButton(stringResource(R.string.tg_forget), { bot.forget() }, tiny = true, style = ButtonStyle.Ghost)
                }
            }
            // Step 3: the one-time link from the user's Telegram.
            Step(3, done = false, title = stringResource(R.string.tg_step3)) {
                if (link == TelegramBot.Link.NONE) {
                    Text(stringResource(R.string.tg_step3_wait), fontSize = 12.sp, color = P.muted)
                } else {
                    val url = bot.linkUrl().takeIf { link == TelegramBot.Link.LINKING }
                    if (url == null) {
                        PButton(stringResource(R.string.tg_new_link), { bot.newLink() }, tiny = true)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, P.line, RoundedCornerShape(12.dp)).padding(6.dp)) { QrCode(url, 104.dp) }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(url.removePrefix("https://"), fontSize = 12.sp, fontFamily = P.mono, color = P.ink)
                                PButton(stringResource(R.string.tg_copy), {
                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Telegram", url))
                                }, tiny = true, style = ButtonStyle.Alt, icon = PIcons.copy)
                                PButton(stringResource(R.string.tg_open_here), {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }, tiny = true, style = ButtonStyle.Ghost)
                                Text(stringResource(R.string.tg_link_wait), fontSize = 11.5.sp, color = P.muted)
                            }
                        }
                    }
                }
            }
            error?.let { Callout(it, Tone.Danger) }
            Text(stringResource(R.string.tg_only_owner), fontSize = 12.sp, lineHeight = 18.sp, color = P.muted, modifier = Modifier.padding(horizontal = 6.dp))
        }
    }
}

@Composable
private fun Step(n: Int, done: Boolean, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(if (done) P.accent2 else P.base2), contentAlignment = Alignment.Center) {
                if (done) PIcon(PIcons.check, size = 14.dp, tint = P.base) else Text("$n", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = P.sub)
            }
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = P.ink)
        }
        content()
    }
}
