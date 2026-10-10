package app.umbra.agent.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.ui.porcelain.P
import app.umbra.core.control.Principal
import app.umbra.core.orchestrator.terminal.InputResult
import app.umbra.core.orchestrator.terminal.TerminalService
import app.umbra.runtime.linux.tmux.CellStyle
import app.umbra.runtime.linux.tmux.ScreenLine
import app.umbra.runtime.linux.tmux.TermColor
import app.umbra.runtime.linux.tmux.TerminalScreen
import kotlinx.coroutines.launch

/** The terminal's colours: the standard 16 tuned to the porcelain night glaze, the 256-colour cube, true colour. */
object TermPalette {
    val background = Color(0xFF1F2B24)
    val foreground = Color(0xFFD4E8D3)
    private val base = listOf(
        0xFF2A3A30, 0xFFE0857A, 0xFF9BD3A2, 0xFFF0CF95, 0xFF8FB3D9, 0xFFC9A0D6, 0xFF8FD0C8, 0xFFD4E8D3,
        0xFF5E7464, 0xFFF0A59C, 0xFFB8E6BE, 0xFFF7DDB0, 0xFFAECBEA, 0xFFDDBDE6, 0xFFAEE2DB, 0xFFF4FBF2,
    ).map { Color(it) }

    fun color(c: TermColor, isFg: Boolean): Color = when (c) {
        TermColor.Default -> if (isFg) foreground else background
        is TermColor.Rgb -> Color(c.r, c.g, c.b)
        is TermColor.Palette -> when (val i = c.index) {
            in 0..15 -> base[i]
            in 16..231 -> {
                val n = i - 16
                fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
                Color(level(n / 36), level(n / 6 % 6), level(n % 6))
            }
            else -> (8 + (i - 232) * 10).coerceIn(0, 255).let { Color(it, it, it) }
        }
    }
}

private fun style(s: CellStyle): SpanStyle {
    var fg = TermPalette.color(s.fg, true)
    var bg = TermPalette.color(s.bg, false)
    if (s.inverse) { val t = fg; fg = bg; bg = t }
    if (s.dim) fg = fg.copy(alpha = 0.6f)
    return SpanStyle(
        color = fg,
        background = if (bg == TermPalette.background) Color.Unspecified else bg,
        fontWeight = if (s.bold) FontWeight.Bold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        textDecoration = if (s.underline) TextDecoration.Underline else null,
    )
}

fun ScreenLine.annotated(): AnnotatedString = buildAnnotatedString {
    runs.forEach { r -> pushStyle(style(r.style)); append(r.text); pop() }
}

/** Keys a phone keyboard lacks. */
private val EXTRA_KEYS = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "Alt" to "", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
    "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "|" to "|", "/" to "/", "-" to "-", "~" to "~",
)

/**
 * One terminal: what tmux shows (§6.1: tmux is the emulator), the keyboard when the user holds input, and the size
 * reported to the service (which only lets the input owner's view resize the pane).
 */
@Composable
fun TerminalPane(service: TerminalService, id: String, ownsInput: Boolean, modifier: Modifier = Modifier, onFenced: (String) -> Unit = {}) {
    val screen by remember(id) { service.screen(id) }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var fontSize by remember { mutableStateOf(12) }
    val textStyle = TextStyle(fontFamily = P.mono, fontSize = fontSize.sp, lineHeight = (fontSize * 1.25f).sp, color = TermPalette.foreground)
    val cell = remember(fontSize) { measurer.measure("M", textStyle) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    fun send(text: String) {
        var out = text
        if (ctrl && out.length == 1 && out[0].isLetterOrDigit()) { out = (out[0].uppercaseChar().code and 0x1f).toChar().toString(); ctrl = false }
        if (alt) { out = "\u001b" + out; alt = false }
        scope.launch {
            val r = service.sendText(id, out, Principal.User)
            if (r is InputResult.Fenced) onFenced(r.reason)
        }
    }

    Column(modifier) {
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TermPalette.background)
                .clickable(enabled = ownsInput) { runCatching { focus.requestFocus() } }
                .padding(6.dp),
        ) {
            val cellW = with(density) { cell.size.width.toDp() }
            val cellH = with(density) { (fontSize * 1.25f).sp.toDp() }
            val cols = (maxWidth / cellW).toInt().coerceAtLeast(20)
            val rows = (maxHeight / cellH).toInt().coerceAtLeast(5)
            LaunchedEffect(cols, rows, ownsInput) {
                if (ownsInput) service.viewSize(id, cols, rows, Principal.User)
            }
            val s = screen
            if (s == null) {
                Text("Connecting to the terminal…", style = textStyle.copy(color = TermPalette.foreground.copy(alpha = 0.6f)))
            } else {
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                    Column {
                        s.lines.forEach { line -> Text(line.annotated(), style = textStyle, maxLines = 1, softWrap = false) }
                    }
                    if (s.cursorVisible && !s.dead && ownsInput) {
                        Box(
                            Modifier.offset(x = cellW * s.cursorX, y = cellH * s.cursorY).size(cellW, cellH)
                                .background(TermPalette.foreground.copy(alpha = 0.55f)),
                        )
                    }
                }
                if (s.dead) Text(
                    "Process ended" + (s.deadStatus?.let { " (status $it)" } ?: ""),
                    Modifier.padding(4.dp).background(Color(0xCC26372F)).padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color(0xFFF0CF95), fontSize = 12.sp,
                )
            }
            // The keyboard: a hidden field that turns typed text, Enter and Backspace into terminal bytes.
            BasicTextField(
                field,
                { v ->
                    // The keyboard keeps its own copy of the field: send only what changed against what the field
                    // held, and let the field grow (resetting it on every key makes keyboards resend their buffer).
                    val old = field.text
                    val new = v.text
                    val common = old.commonPrefixWith(new).length
                    repeat(old.length - common) { send("\u007f") }
                    val inserted = new.substring(common)
                    if (inserted.isNotEmpty()) send(inserted.replace('\n', '\r'))
                    field = if (inserted.contains('\n') || new.length > 512 || new.length < SENTINEL.length) {
                        TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
                    } else {
                        v
                    }
                },
                Modifier.size(1.dp).alpha(0f).focusRequester(focus).semantics { contentDescription = "Terminal input" }
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val seq = when (e.key) {
                            Key.Enter, Key.NumPadEnter -> "\r"
                            Key.Backspace -> "\u007f"
                            Key.Tab -> "\t"
                            Key.Escape -> "\u001b"
                            Key.DirectionUp -> "\u001b[A"
                            Key.DirectionDown -> "\u001b[B"
                            Key.DirectionRight -> "\u001b[C"
                            Key.DirectionLeft -> "\u001b[D"
                            else -> if (e.isCtrlPressed && e.utf16CodePoint in 'a'.code..'z'.code) ((e.utf16CodePoint and 0x1f).toChar()).toString() else null
                        }
                        if (seq != null) { send(seq); true } else false
                    },
                enabled = ownsInput,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                textStyle = textStyle,
            )
        }
        if (ownsInput) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                EXTRA_KEYS.forEach { (label, seq) ->
                    val on = (label == "Ctrl" && ctrl) || (label == "Alt" && alt)
                    Text(
                        label,
                        Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) P.accent2 else Color(0xFFDCE7D9))
                            .border(1.dp, Color(0xA1FFFFFF), RoundedCornerShape(9.dp))
                            .clickable {
                                when (label) {
                                    "Ctrl" -> ctrl = !ctrl
                                    "Alt" -> alt = !alt
                                    else -> send(seq)
                                }
                            }
                            .padding(horizontal = 11.dp, vertical = 8.dp),
                        fontSize = 13.sp, fontFamily = P.mono, color = if (on) Color.White else Color(0xFF355740),
                    )
                }
                Text("A−", Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFFDCE7D9)).clickable { fontSize = (fontSize - 1).coerceAtLeast(8) }.padding(horizontal = 11.dp, vertical = 8.dp), fontSize = 13.sp, color = Color(0xFF355740))
                Text("A+", Modifier.clip(RoundedCornerShape(9.dp)).background(Color(0xFFDCE7D9)).clickable { fontSize = (fontSize + 1).coerceAtMost(22) }.padding(horizontal = 11.dp, vertical = 8.dp), fontSize = 13.sp, color = Color(0xFF355740))
            }
        }
    }
}

private const val SENTINEL = "  "

/** Older lines of a terminal, from tmux's bounded scrollback. */
@Composable
fun ScrollbackView(lines: List<ScreenLine>, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(14.dp)).background(TermPalette.background).padding(8.dp)) {
        items(lines.size) { i -> Text(lines[i].annotated(), fontFamily = P.mono, fontSize = 11.sp, color = TermPalette.foreground, softWrap = false, maxLines = 1) }
    }
}
