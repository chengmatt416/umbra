package app.umbra.agent.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.*
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.math.max

/*
 * The card design system (2026-10-28). One card, many purposes: a head (mark, kind, title, subtitle), an optional hero
 * figure, a stack of blocks, actions. Ink rules hold: paper and ink, data in ink weights, colour only where it means
 * something (good, needs care, failed). Every block keeps the same rhythm (14 dp between blocks, 12–15 sp text, tabular
 * figures for numbers) so a hundred templates read as one family. Motion is small and once: bars and arcs grow in when
 * the card first appears, nothing loops except a countdown's seconds.
 */

/** What the user did on a card; the host turns it into a message to the agent, a link or the clipboard. */
sealed interface CardEvent {
    data class Send(val blockId: String?, val text: String) : CardEvent
    data class Open(val url: String) : CardEvent
    data class Copy(val text: String) : CardEvent
    data class State(val state: CardState) : CardEvent
}

/** What this phone remembers about one card: ticked checklist items, and the answers already sent (which lock). */
@Serializable
data class CardState(val checks: Map<String, Boolean> = emptyMap(), val sent: Map<String, String> = emptyMap())

private val ROW = 14.dp

@Composable
internal fun toneColor(tone: CardTone): Color = when (tone) {
    CardTone.GOOD -> P.good
    CardTone.WARN -> P.amber
    CardTone.DANGER -> P.danger
    CardTone.NEUTRAL, CardTone.INFO, CardTone.ACCENT -> P.ink
}

/** Ink weights for series and slices: one hue, several strengths, so a chart never needs a legend of colours. */
@Composable
private fun inkSeries(i: Int): Color = P.ink.copy(alpha = listOf(1f, .62f, .4f, .24f, .14f)[i % 5])

/** The icons a template or an agent may name (PIcons' own names); any other name draws no icon. */
private val ICONS: Map<String, IconSpec> by lazy { mapOf(
    "menu" to PIcons.menu,
    "home" to PIcons.home,
    "spark" to PIcons.spark,
    "grid" to PIcons.grid,
    "code" to PIcons.code,
    "terminal" to PIcons.terminal,
    "workflow" to PIcons.workflow,
    "sliders" to PIcons.sliders,
    "server" to PIcons.server,
    "cloud" to PIcons.cloud,
    "git" to PIcons.git,
    "key" to PIcons.key,
    "shield" to PIcons.shield,
    "alert" to PIcons.alert,
    "book" to PIcons.book,
    "monitor" to PIcons.monitor,
    "phone" to PIcons.phone,
    "play" to PIcons.play,
    "pause" to PIcons.pause,
    "stop" to PIcons.stop,
    "check" to PIcons.check,
    "clock" to PIcons.clock,
    "user" to PIcons.user,
    "bot" to PIcons.bot,
    "arrowRight" to PIcons.arrowRight,
    "arrowUp" to PIcons.arrowUp,
    "checks" to PIcons.checks,
    "external" to PIcons.external,
    "chevronDown" to PIcons.chevronDown,
    "chevronLeft" to PIcons.chevronLeft,
    "chevronRight" to PIcons.chevronRight,
    "plus" to PIcons.plus,
    "close" to PIcons.close,
    "search" to PIcons.search,
    "layers" to PIcons.layers,
    "hand" to PIcons.hand,
    "lock" to PIcons.lock,
    "unlock" to PIcons.unlock,
    "download" to PIcons.download,
    "copy" to PIcons.copy,
    "eye" to PIcons.eye,
    "bell" to PIcons.bell,
    "heart" to PIcons.heart,
    "history" to PIcons.history,
    "pin" to PIcons.pin,
    "battery" to PIcons.battery,
    "moon" to PIcons.moon,
    "users" to PIcons.users,
    "message" to PIcons.message,
    "zap" to PIcons.zap,
    "qr" to PIcons.qr,
    "globe" to PIcons.globe,
    "clip" to PIcons.clip,
    "camera" to PIcons.camera,
    "swap" to PIcons.swap,
    "fork" to PIcons.fork,
    "gauge" to PIcons.gauge,
    "activity" to PIcons.activity,
    "file" to PIcons.file,
    "box" to PIcons.box,
    "refresh" to PIcons.refresh,
    "link" to PIcons.link,
    "wifi" to PIcons.wifi,
    "mute" to PIcons.mute,
    "target" to PIcons.target,
    "send" to PIcons.send,
    "record" to PIcons.record,
    "mic" to PIcons.mic,
    "info" to PIcons.info,
    "fullscreen" to PIcons.fullscreen,
    "scissors" to PIcons.scissors,
    "recycle" to PIcons.recycle,
    "filter" to PIcons.filter,
    "settings" to PIcons.settings,
    "more" to PIcons.more,
    "folder" to PIcons.folder,
    "drop" to PIcons.drop,
    "trash" to PIcons.trash,
) }

internal fun iconNamed(name: String?): IconSpec? = name?.let { ICONS[it] ?: ICONS[it.replaceFirstChar { c -> c.lowercase() }] }

/** The card as a whole. [interactive] false draws answers as they were (for history) and never emits events. */
@Composable
fun CardView(spec: CardSpec, state: CardState, onEvent: (CardEvent) -> Unit, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val shape = RoundedCornerShape(22.dp)
    val tone = toneColor(spec.tone)
    val attention = spec.tone == CardTone.WARN || spec.tone == CardTone.DANGER
    Column(
        modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic)
            .then(if (attention) Modifier.border(1.dp, tone.copy(alpha = .4f), shape) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ROW),
    ) {
        if (spec.title != null || spec.eyebrow != null || spec.icon != null) CardHead(spec, tone)
        spec.hero?.let { Hero(it) }
        // A divider separates blocks; one at either end of a card would be a stray line.
        spec.blocks.dropWhile { it == CardBlock.Divider }.dropLastWhile { it == CardBlock.Divider }
            .forEach { block -> BlockView(block, state, onEvent, interactive, spec.title) }
        if (spec.actions.isNotEmpty()) Actions(spec.actions, onEvent, interactive, spec.title)
        spec.footnote?.let { Text(it, color = P.muted, fontSize = 11.sp, lineHeight = 15.sp) }
    }
}

@Composable
private fun CardHead(spec: CardSpec, tone: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        iconNamed(spec.icon)?.let { icon ->
            Box(Modifier.size(36.dp).clip(CircleShape).background(if (spec.tone == CardTone.NEUTRAL) P.highlight else tone.copy(alpha = .13f))
                .border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
                PIcon(icon, size = 17.dp, tint = tone)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            spec.eyebrow?.let {
                Text(it.uppercase(), color = if (spec.tone == CardTone.NEUTRAL) P.sub else tone, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = .8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            spec.title?.let { Text(it, Modifier.semantics { heading() }, color = P.ink, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium) }
            spec.subtitle?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
        }
    }
}

@Composable
private fun Hero(hero: CardHero) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(hero.value, color = if (hero.tone == CardTone.NEUTRAL) P.ink else toneColor(hero.tone), fontFamily = P.serif, fontSize = 44.sp, lineHeight = 48.sp,
                style = TextStyle(fontFeatureSettings = P.tabular))
            hero.unit?.let { Text(it, Modifier.padding(bottom = 7.dp), color = P.sub, fontSize = 17.sp) }
        }
        hero.caption?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
    }
}

/** One block on its own, for surfaces that are not a whole card (a dashboard tile). */
@Composable
internal fun BlockView(block: CardBlock, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    when (block) {
        is CardBlock.Text -> TextBlock(block)
        is CardBlock.Stats -> StatsBlock(block)
        is CardBlock.KeyValues -> KeyValuesBlock(block)
        is CardBlock.Items -> ItemsBlock(block)
        is CardBlock.Table -> TableBlock(block)
        is CardBlock.Bars -> BarsBlock(block)
        is CardBlock.Columns -> ColumnsBlock(block)
        is CardBlock.Line -> LineBlock(block)
        is CardBlock.Donut -> DonutBlock(block)
        is CardBlock.Progress -> ProgressBlock(block)
        is CardBlock.Gauge -> GaugeBlock(block)
        is CardBlock.Steps -> StepsBlock(block)
        is CardBlock.Timeline -> TimelineBlock(block)
        is CardBlock.Checklist -> ChecklistBlock(block, state, onEvent, interactive, cardTitle)
        is CardBlock.Choice -> ChoiceBlock(block, state, onEvent, interactive, cardTitle)
        is CardBlock.Form -> FormBlock(block, state, onEvent, interactive, cardTitle)
        is CardBlock.Rating -> RatingBlock(block, state, onEvent, interactive, cardTitle)
        is CardBlock.Slider -> SliderBlock(block, state, onEvent, interactive, cardTitle)
        is CardBlock.Quote -> QuoteBlock(block)
        is CardBlock.Code -> CodeBlock(block, onEvent)
        is CardBlock.Image -> ImageBlock(block)
        is CardBlock.Chips -> ChipsBlock(block)
        is CardBlock.Callout -> CalloutBlock(block)
        is CardBlock.Compare -> CompareBlock(block)
        is CardBlock.Person -> PersonBlock(block)
        is CardBlock.Link -> LinkBlock(block, onEvent, interactive)
        is CardBlock.Countdown -> CountdownBlock(block)
        is CardBlock.Heatmap -> HeatmapBlock(block)
        CardBlock.Divider -> Box(Modifier.fillMaxWidth().height(1.dp).background(P.line2))
    }
}

// ---- reading blocks ----

@Composable
private fun TextBlock(b: CardBlock.Text) = when (b.style) {
    CardBlock.Text.TextStyle.LEAD -> Text(b.text, color = P.ink, fontSize = 16.sp, lineHeight = 23.sp)
    CardBlock.Text.TextStyle.MUTED -> Text(b.text, color = P.sub, fontSize = 13.sp, lineHeight = 19.sp)
    CardBlock.Text.TextStyle.SERIF -> Text(b.text, color = P.ink, fontFamily = P.serif, fontSize = 20.sp, lineHeight = 27.sp)
    CardBlock.Text.TextStyle.BODY -> Text(b.text, color = P.ink, fontSize = 14.sp, lineHeight = 21.sp)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsBlock(b: CardBlock.Stats) {
    // Two to a row (three when they are short, one alone when it is the only one), each a label over a figure, with its
    // change in a small chip. A figure that does not fit shrinks a little rather than losing digits.
    val perRow = when {
        b.items.size == 1 -> 1
        b.items.size == 3 && b.items.all { it.value.length <= 6 } -> 3
        else -> 2
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        b.items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { stat ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(P.highlight).border(1.dp, P.line2, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(stat.label, color = P.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            androidx.compose.foundation.text.BasicText(stat.value, Modifier.weight(1f, fill = false),
                                style = TextStyle(color = P.ink, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = P.tabular),
                                maxLines = 1, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 22.sp, stepSize = 1.sp))
                            stat.unit?.let { Text(it, Modifier.padding(bottom = 3.dp), color = P.sub, fontSize = 12.sp, maxLines = 1) }
                        }
                        stat.delta?.let { delta ->
                            val c = if (stat.deltaTone == CardTone.NEUTRAL) P.sub else toneColor(stat.deltaTone)
                            Text(delta, Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = .1f)).padding(horizontal = 6.dp, vertical = 1.dp),
                                color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = P.tabular))
                        }
                        stat.caption?.let { Text(it, color = P.muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2) }
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun KeyValuesBlock(b: CardBlock.KeyValues) {
    Column {
        b.rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(P.line2))
            Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.label, Modifier.weight(.42f), color = P.sub, fontSize = 13.sp, lineHeight = 18.sp)
                Text(row.value, Modifier.weight(.58f), color = if (row.tone == CardTone.NEUTRAL) P.ink else toneColor(row.tone),
                    fontSize = if (row.emphasis) 16.sp else 14.sp, lineHeight = 20.sp, fontWeight = if (row.emphasis) FontWeight.SemiBold else FontWeight.Medium,
                    textAlign = TextAlign.End, style = TextStyle(fontFeatureSettings = P.tabular))
            }
        }
    }
}

@Composable
private fun ItemsBlock(b: CardBlock.Items) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        b.items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                val tone = toneColor(item.tone)
                when {
                    b.numbered -> NumberRing(i + 1)
                    iconNamed(item.icon) != null -> Box(Modifier.size(28.dp).clip(CircleShape).background(P.highlight).border(1.dp, P.line2, CircleShape),
                        contentAlignment = Alignment.Center) { PIcon(iconNamed(item.icon)!!, size = 14.dp, tint = tone) }
                    else -> Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(if (item.tone == CardTone.NEUTRAL) P.ink.copy(alpha = .5f) else tone))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title, color = P.ink, fontSize = 15.sp, lineHeight = 21.sp)
                    item.detail?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
                    item.meta?.let { Text(it, color = P.muted, fontSize = 11.sp, lineHeight = 15.sp) }
                }
                item.trailing?.let {
                    Text(it, color = if (item.tone == CardTone.NEUTRAL) P.ink else tone, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        style = TextStyle(fontFeatureSettings = P.tabular), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun NumberRing(n: Int) {
    Box(Modifier.size(24.dp).clip(CircleShape).border(1.25.dp, P.ink.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
        Text("$n", color = P.ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = P.tabular))
    }
}

@Composable
private fun TableBlock(b: CardBlock.Table) {
    // Wide tables scroll sideways inside the card instead of squeezing every column.
    val wide = b.columns.size > 3
    val cellWidth = if (wide) 112.dp else Dp.Unspecified
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, P.line2, RoundedCornerShape(14.dp))
        .then(if (wide) Modifier.horizontalScroll(rememberScrollState()) else Modifier)) {
        Row(Modifier.background(P.highlight).padding(horizontal = 12.dp, vertical = 8.dp)) {
            b.columns.forEachIndexed { c, title ->
                Text(title.uppercase(), Modifier.then(if (wide) Modifier.width(cellWidth) else Modifier.weight(1f)), color = P.sub, fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp, textAlign = if (c in b.numericColumns) TextAlign.End else TextAlign.Start, maxLines = 1)
            }
        }
        b.rows.forEachIndexed { r, row ->
            Box(Modifier.then(if (wide) Modifier else Modifier.fillMaxWidth()).height(1.dp).background(P.line2))
            Row(Modifier.then(if (r == b.highlightRow) Modifier.background(P.ink.copy(alpha = .05f)) else Modifier).padding(horizontal = 12.dp, vertical = 9.dp)) {
                b.columns.indices.forEach { c ->
                    Text(row.getOrElse(c) { "" }, Modifier.then(if (wide) Modifier.width(cellWidth) else Modifier.weight(1f)), color = P.ink,
                        fontSize = 13.sp, lineHeight = 18.sp, fontWeight = if (r == b.highlightRow) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = if (c in b.numericColumns) TextAlign.End else TextAlign.Start, style = TextStyle(fontFeatureSettings = P.tabular),
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Grows from 0 to 1 once, the first time the card is drawn (or at once when motion is off). */
@Composable
private fun rememberGrowth(): Float {
    val motion = Theme.current.motion
    val p = remember { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (motion) p.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    return p.value
}

@Composable
private fun BarsBlock(b: CardBlock.Bars) {
    val grow = rememberGrowth()
    val top = b.max ?: b.items.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        b.items.forEach { bar ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row {
                    Text(bar.label, Modifier.weight(1f), color = P.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(bar.display ?: (formatNumber(bar.value) + (b.unit?.let { " $it" } ?: "")), color = P.sub, fontSize = 13.sp,
                        style = TextStyle(fontFeatureSettings = P.tabular))
                }
                val fill = (bar.value / top).coerceIn(0.0, 1.0).toFloat() * grow
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(P.ink.copy(alpha = .07f))) {
                    Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                        .background(if (bar.tone == CardTone.NEUTRAL) P.ink else toneColor(bar.tone)))
                }
            }
        }
    }
}

@Composable
private fun ColumnsBlock(b: CardBlock.Columns) {
    val grow = rememberGrowth()
    val top = b.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    // Every bar on one scale; the room for the highlighted value's label is kept above all of them, so the label
    // never shortens the bar it names. The unit rides with that label.
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(138.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            b.points.forEachIndexed { i, point ->
                val last = b.highlightLast && i == b.points.lastIndex
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (last) Text(formatNumber(point.value) + (b.unit?.takeIf { it.length <= 4 }?.let { " $it" } ?: ""), color = P.ink, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false, style = TextStyle(fontFeatureSettings = P.tabular))
                    Box(Modifier.fillMaxWidth().height(120.dp * ((point.value / top).toFloat() * grow).coerceIn(.02f, 1f))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                        .background(if (last) P.ink else P.ink.copy(alpha = .18f)))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            b.points.forEach { Text(it.label, Modifier.weight(1f), color = P.muted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1) }
        }
        b.unit?.takeIf { !b.highlightLast || it.length > 4 }?.let { Text(it, color = P.muted, fontSize = 11.sp) }
    }
}

@Composable
private fun LineBlock(b: CardBlock.Line) {
    val grow = rememberGrowth()
    val all = b.series.flatMap { it.points }
    if (all.isEmpty()) return
    val lo = all.minOrNull()!!
    val hi = all.maxOrNull()!!.let { if (it == lo) lo + 1 else it }
    val colors = b.series.mapIndexed { i, s -> if (s.tone == CardTone.ACCENT || s.tone == CardTone.NEUTRAL || s.tone == CardTone.INFO) inkSeries(i) else toneColor(s.tone) }
    val spark = b.labels.isEmpty() && b.series.size == 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(if (spark) 56.dp else 132.dp)) {
            val pad = 4.dp.toPx()
            val w = size.width - pad * 2
            val h = size.height - pad * 2
            // Guides: three hairlines, so a reader can place a value without numbers in the way.
            if (!spark) for (k in 0..2) drawLine(colors.first().copy(alpha = .08f), Offset(pad, pad + h * k / 2f), Offset(pad + w, pad + h * k / 2f), 1f)
            b.series.forEachIndexed { si, s ->
                if (s.points.size < 2) return@forEachIndexed
                val pts = s.points.mapIndexed { i, v -> Offset(pad + w * i / (s.points.size - 1), pad + h * (1f - ((v - lo) / (hi - lo)).toFloat())) }
                val shown = (pts.size * grow).toInt().coerceIn(2, pts.size)
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until shown) {
                        val p0 = pts[i - 1]; val p1 = pts[i]; val mx = (p0.x + p1.x) / 2f
                        cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                    }
                }
                if (si == 0) {
                    val area = Path().apply { addPath(path); lineTo(pts[shown - 1].x, pad + h); lineTo(pts[0].x, pad + h); close() }
                    drawPath(area, Brush.verticalGradient(listOf(colors[si].copy(alpha = .14f), colors[si].copy(alpha = 0f)), startY = pad, endY = pad + h))
                }
                drawPath(path, colors[si], style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                val end = pts[shown - 1]
                drawCircle(colors[si], 4.dp.toPx(), end)
                drawCircle(Color.White.copy(alpha = .9f), 1.8.dp.toPx(), end)
            }
        }
        if (b.labels.isNotEmpty()) Row {
            Text(b.labels.first(), color = P.muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            if (b.labels.size > 2) Text(b.labels[b.labels.size / 2], color = P.muted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            Text(b.labels.last(), color = P.muted, fontSize = 10.sp)
        }
        if (b.series.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            b.series.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(width = 14.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(colors[i]))
                    Text(s.name, color = P.sub, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DonutBlock(b: CardBlock.Donut) {
    val grow = rememberGrowth()
    val total = b.slices.sumOf { max(it.value, 0.0) }.takeIf { it > 0 } ?: 1.0
    val colors = b.slices.indices.map { inkSeries(it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(116.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val gap = if (b.slices.size > 1) 3f else 0f
                var start = -90f
                b.slices.forEachIndexed { i, s ->
                    val sweep = (360f * (max(s.value, 0.0) / total).toFloat()) * grow
                    if (sweep > gap) drawArc(colors[i], start + gap / 2, sweep - gap, false, topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                b.center?.let { Text(it, color = P.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = P.tabular)) }
                b.centerCaption?.let { Text(it, color = P.muted, fontSize = 10.sp) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.slices.forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(colors[i]))
                    Text(s.label, Modifier.weight(1f), color = P.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.display ?: "${(100 * s.value / total).toInt()}%", color = P.sub, fontSize = 13.sp, style = TextStyle(fontFeatureSettings = P.tabular))
                }
            }
        }
    }
}

@Composable
private fun ProgressBlock(b: CardBlock.Progress) {
    val grow = rememberGrowth()
    val tone = if (b.tone == CardTone.ACCENT || b.tone == CardTone.NEUTRAL) P.ink else toneColor(b.tone)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(b.label ?: "", Modifier.weight(1f), color = P.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("${(b.value.coerceIn(0.0, 1.0) * 100).toInt()}%", color = P.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = P.tabular))
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(P.ink.copy(alpha = .07f))) {
            Box(Modifier.fillMaxWidth(b.value.coerceIn(0.0, 1.0).toFloat() * grow).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(tone))
        }
        b.caption?.let { Text(it, color = P.sub, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@Composable
private fun GaugeBlock(b: CardBlock.Gauge) {
    val grow = rememberGrowth()
    val range = (b.max - b.min).takeIf { it != 0.0 } ?: 1.0
    val f = ((b.value - b.min) / range).coerceIn(0.0, 1.0).toFloat()
    val tone = if (b.tone == CardTone.ACCENT || b.tone == CardTone.NEUTRAL) P.ink else toneColor(b.tone)
    val track = P.ink.copy(alpha = .08f)
    Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(width = 180.dp, height = 100.dp)) {
            val stroke = 12.dp.toPx()
            val box = Size(size.width - stroke, (size.height - stroke / 2) * 2 - stroke)
            drawArc(track, 180f, 180f, false, Offset(stroke / 2, stroke / 2), box, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(tone, 180f, 180f * f * grow, false, Offset(stroke / 2, stroke / 2), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(Modifier.padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(b.display ?: formatNumber(b.value), color = P.ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = P.tabular))
            b.label?.let { Text(it, color = P.sub, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun StepsBlock(b: CardBlock.Steps) {
    Column {
        b.steps.forEachIndexed { i, step ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    val (fill, ring, mark) = when (step.state) {
                        CardBlock.Steps.State.DONE -> Triple(P.ink, P.ink, PIcons.check)
                        CardBlock.Steps.State.FAILED -> Triple(P.danger, P.danger, PIcons.close)
                        CardBlock.Steps.State.CURRENT -> Triple(Color.Transparent, P.ink, null)
                        else -> Triple(Color.Transparent, P.ink.copy(alpha = .25f), null)
                    }
                    Box(Modifier.size(22.dp).clip(CircleShape).background(fill).border(1.5.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
                        when {
                            mark != null -> PIcon(mark, size = 12.dp, tint = inkOnPrimary.takeIf { step.state == CardBlock.Steps.State.DONE } ?: Color.White)
                            step.state == CardBlock.Steps.State.CURRENT -> Box(Modifier.size(8.dp).clip(CircleShape).background(P.ink))
                        }
                    }
                    if (i < b.steps.lastIndex) Box(Modifier.width(1.5.dp).weight(1f).background(if (step.state == CardBlock.Steps.State.DONE) P.ink.copy(alpha = .5f) else P.line))
                }
                Column(Modifier.weight(1f).padding(bottom = if (i < b.steps.lastIndex) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(step.title, color = if (step.state == CardBlock.Steps.State.TODO || step.state == CardBlock.Steps.State.SKIPPED) P.sub else P.ink,
                        fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (step.state == CardBlock.Steps.State.CURRENT) FontWeight.SemiBold else FontWeight.Normal)
                    step.detail?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun TimelineBlock(b: CardBlock.Timeline) {
    Column {
        b.events.forEachIndexed { i, e ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(e.time, Modifier.width(52.dp).padding(top = 1.dp), color = P.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = P.mono, maxLines = 2)
                Column(Modifier.width(10.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(if (e.tone == CardTone.NEUTRAL) P.ink else toneColor(e.tone)))
                    if (i < b.events.lastIndex) Box(Modifier.width(1.5.dp).weight(1f).background(P.line))
                }
                Column(Modifier.weight(1f).padding(bottom = if (i < b.events.lastIndex) 14.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(e.title, color = P.ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    e.detail?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun QuoteBlock(b: CardBlock.Quote) {
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(P.ink))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.text, color = P.ink, fontFamily = P.serif, fontSize = 18.sp, lineHeight = 26.sp)
            b.author?.let { Text("— $it", color = P.sub, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun CodeBlock(b: CardBlock.Code, onEvent: (CardEvent) -> Unit) {
    val copy = stringResource(R.string.card_copy)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.code)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(b.language.orEmpty().uppercase(), Modifier.weight(1f), color = P.codeText.copy(alpha = .55f), fontSize = 10.sp, letterSpacing = .8.sp)
            Text(copy, Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onEvent(CardEvent.Copy(b.code)) }.padding(horizontal = 8.dp, vertical = 6.dp),
                color = P.codeText.copy(alpha = .8f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
        Text(b.code, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), color = P.codeText,
            fontFamily = P.mono, fontSize = 12.sp, lineHeight = 17.sp, softWrap = false)
    }
}

@Composable
private fun ImageBlock(b: CardBlock.Image) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, b.source) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CardImages.load(context, b.source) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(b.aspect ?: (bitmap?.let { it.width.toFloat() / it.height } ?: 1.6f)).clip(RoundedCornerShape(14.dp))
            .background(P.highlight), contentAlignment = Alignment.Center) {
            bitmap?.let { androidx.compose.foundation.Image(it, b.caption, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: PIcon(PIcons.camera, size = 20.dp, tint = P.muted)
        }
        b.caption?.let { Text(it, color = P.sub, fontSize = 12.sp, lineHeight = 17.sp) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipsBlock(b: CardBlock.Chips) {
    val tone = if (b.tone == CardTone.NEUTRAL) P.sub else toneColor(b.tone)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.items.forEach { Text(it, Modifier.clip(CircleShape).border(1.dp, if (b.tone == CardTone.NEUTRAL) P.line else tone.copy(alpha = .35f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp), color = if (b.tone == CardTone.NEUTRAL) P.ink else tone, fontSize = 12.sp, maxLines = 1) }
    }
}

@Composable
private fun CalloutBlock(b: CardBlock.Callout) {
    val tone = if (b.tone == CardTone.INFO || b.tone == CardTone.NEUTRAL || b.tone == CardTone.ACCENT) P.ink else toneColor(b.tone)
    val icon = when (b.tone) { CardTone.GOOD -> PIcons.check; CardTone.WARN, CardTone.DANGER -> PIcons.alert; else -> PIcons.info }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = if (tone == P.ink) .05f else .1f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PIcon(icon, Modifier.padding(top = 1.dp), size = 16.dp, tint = tone)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.title?.let { Text(it, color = tone, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Text(b.text, color = P.ink, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun CompareBlock(b: CardBlock.Compare) {
    // Each option is a column; the recommended one is outlined in ink and carries its badge. Two share the width
    // (and the height); three or four keep a readable width and scroll sideways.
    val two = b.columns.size <= 2
    Row(Modifier.fillMaxWidth().then(if (two) Modifier.height(IntrinsicSize.Min) else Modifier.horizontalScroll(rememberScrollState())),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        b.columns.forEach { col ->
            val shape = RoundedCornerShape(16.dp)
            Column((if (two) Modifier.weight(1f).fillMaxHeight() else Modifier.width(132.dp)).clip(shape).background(if (col.highlight) P.highlight else Color.Transparent)
                .border(if (col.highlight) 1.5.dp else 1.dp, if (col.highlight) P.ink else P.line2, shape).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                col.badge?.let {
                    Text(it.uppercase(), Modifier.clip(RoundedCornerShape(6.dp)).background(P.ink).padding(horizontal = 6.dp, vertical = 2.dp),
                        color = inkOnPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp)
                }
                Text(col.title, color = P.ink, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
                b.aspects.forEachIndexed { i, aspect ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(aspect, color = P.muted, fontSize = 11.sp)
                        Text(col.values.getOrElse(i) { "—" }, color = P.ink, fontSize = 13.sp, lineHeight = 18.sp, style = TextStyle(fontFeatureSettings = P.tabular))
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonBlock(b: CardBlock.Person) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val initials = b.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }
        Box(Modifier.size(44.dp).clip(CircleShape).background(P.ink), contentAlignment = Alignment.Center) {
            Text(initials, color = inkOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.name, color = P.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            b.role?.let { Text(it, color = P.sub, fontSize = 13.sp) }
            b.detail?.let { Text(it, color = P.muted, fontSize = 12.sp, lineHeight = 16.sp) }
        }
    }
}

@Composable
private fun LinkBlock(b: CardBlock.Link, onEvent: (CardEvent) -> Unit, interactive: Boolean) {
    val domain = runCatching { java.net.URI(b.url).host?.removePrefix("www.") }.getOrNull().orEmpty()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, P.line2, RoundedCornerShape(14.dp))
        .then(if (interactive) Modifier.clickable(role = Role.Button) { onEvent(CardEvent.Open(b.url)) } else Modifier).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(P.highlight).border(1.dp, P.line2, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) { PIcon(PIcons.globe, size = 16.dp, tint = P.ink) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.title, color = P.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            b.description?.let { Text(it, color = P.sub, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(domain, color = P.muted, fontSize = 11.sp, maxLines = 1)
        }
        PIcon(PIcons.external, size = 14.dp, tint = P.sub)
    }
}

@Composable
private fun CountdownBlock(b: CardBlock.Countdown) {
    val target = remember(b.at) { CardTime.parse(b.at) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(target) { while (target != null && now < target) { delay(1_000); now = System.currentTimeMillis() } }
    val left = ((target ?: now) - now).coerceAtLeast(0) / 1000
    val parts = listOf(left / 86_400 to stringResource(R.string.card_days), left % 86_400 / 3_600 to stringResource(R.string.card_hours),
        left % 3_600 / 60 to stringResource(R.string.card_minutes), left % 60 to stringResource(R.string.card_seconds)).dropWhile { it.first == 0L && left >= 60 }.take(3)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.label?.let { Text(it, color = P.sub, fontSize = 13.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEach { (value, unit) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(P.highlight).border(1.dp, P.line2, RoundedCornerShape(14.dp)).padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d".format(value), color = P.ink, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = P.tabular))
                    Text(unit, color = P.muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun HeatmapBlock(b: CardBlock.Heatmap) {
    val columns = b.columns.coerceIn(1, 14)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        b.values.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { v ->
                    // Ink weights, never full black: a run of "done" days reads as a soft dark band, not a barcode.
                    Box(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(6.dp))
                        .background(if (v <= 0.0) P.ink.copy(alpha = .06f) else P.ink.copy(alpha = (.16f + .6f * v.coerceIn(0.0, 1.0).toFloat()))))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (b.labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            b.labels.take(columns).forEach { Text(it, Modifier.weight(1f), color = P.muted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1) }
        }
        b.caption?.let { Text(it, color = P.sub, fontSize = 12.sp) }
    }
}

// ---- interactive blocks ----

/** An answer already sent: the block shows it, locked, with a check. */
@Composable
private fun SentLine(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.good.copy(alpha = .1f)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PIcon(PIcons.check, size = 14.dp, tint = P.good)
        Text(stringResource(R.string.card_sent, text), color = P.ink, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

private fun prefix(cardTitle: String?) = cardTitle?.let { "[Card · $it] " } ?: "[Card] "

@Composable
private fun ChecklistBlock(b: CardBlock.Checklist, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    val sent = state.sent[b.id]
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        b.items.forEach { item ->
            val key = "${b.id}/${item.id}"
            val done = state.checks[key] ?: item.done
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .then(if (interactive && sent == null) Modifier.clickable(role = Role.Checkbox) {
                    onEvent(CardEvent.State(state.copy(checks = state.checks + (key to !done))))
                } else Modifier).padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(if (done) P.ink else Color.Transparent)
                    .border(1.5.dp, if (done) P.ink else P.ink.copy(alpha = .35f), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                    if (done) PIcon(PIcons.check, size = 13.dp, tint = inkOnPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.text, color = if (done) P.sub else P.ink, fontSize = 15.sp, lineHeight = 21.sp,
                        style = if (done) TextStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough) else TextStyle.Default)
                    item.detail?.let { Text(it, color = P.muted, fontSize = 12.sp, lineHeight = 16.sp) }
                }
            }
        }
        val total = b.items.size
        val doneCount = b.items.count { state.checks["${b.id}/${it.id}"] ?: it.done }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.card_done_of, doneCount, total), Modifier.weight(1f), color = P.sub, fontSize = 12.sp)
            if (sent == null && interactive && b.sendLabel != null) {
                val doneWord = stringResource(R.string.card_checklist_done)
                val leftWord = stringResource(R.string.card_checklist_left)
                PButton(b.sendLabel, {
                    val done = b.items.filter { state.checks["${b.id}/${it.id}"] ?: it.done }.joinToString(", ") { it.text }
                    val left = b.items.filterNot { state.checks["${b.id}/${it.id}"] ?: it.done }.joinToString(", ") { it.text }
                    onEvent(CardEvent.Send(b.id, prefix(cardTitle) + "$doneWord: ${done.ifEmpty { "—" }}. $leftWord: ${left.ifEmpty { "—" }}."))
                }, tiny = true, style = ButtonStyle.Alt)
            }
        }
        sent?.let { SentLine(it) }
    }
}

@Composable
private fun ChoiceBlock(b: CardBlock.Choice, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    val sent = state.sent[b.id]
    var picked by remember(b.id) { mutableStateOf(emptySet<String>()) }
    val single = !b.multi && b.submitLabel == null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        b.question?.let { Text(it, color = P.ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium) }
        b.options.forEach { option ->
            val on = option.id in picked || sent != null && sent.contains(option.label)
            val shape = RoundedCornerShape(14.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(if (on) P.ink.copy(alpha = .06f) else Color.Transparent)
                .border(if (on) 1.5.dp else 1.dp, if (on) P.ink else P.line, shape)
                .then(if (interactive && sent == null) Modifier.clickable(role = if (b.multi) Role.Checkbox else Role.RadioButton) {
                    if (single) onEvent(CardEvent.Send(b.id, prefix(cardTitle) + option.label))
                    else picked = if (b.multi) (if (option.id in picked) picked - option.id else picked + option.id) else setOf(option.id)
                } else Modifier).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(20.dp).clip(if (b.multi) RoundedCornerShape(6.dp) else CircleShape)
                    .border(1.5.dp, if (on) P.ink else P.ink.copy(alpha = .35f), if (b.multi) RoundedCornerShape(6.dp) else CircleShape), contentAlignment = Alignment.Center) {
                    if (on) Box(Modifier.size(10.dp).clip(if (b.multi) RoundedCornerShape(3.dp) else CircleShape).background(P.ink))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(option.label, color = P.ink, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                    option.detail?.let { Text(it, color = P.sub, fontSize = 13.sp, lineHeight = 18.sp) }
                }
                option.badge?.let {
                    Text(it, Modifier.clip(RoundedCornerShape(6.dp)).background(P.ink).padding(horizontal = 6.dp, vertical = 2.dp), color = inkOnPrimary,
                        fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (sent != null) SentLine(sent)
        else if (interactive && !single) PButton(b.submitLabel ?: stringResource(R.string.card_send), {
            val labels = b.options.filter { it.id in picked }.joinToString(", ") { it.label }
            onEvent(CardEvent.Send(b.id, prefix(cardTitle) + labels))
        }, Modifier.fillMaxWidth(), enabled = picked.isNotEmpty(), icon = PIcons.send)
    }
}

@Composable
private fun FormBlock(b: CardBlock.Form, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    val sent = state.sent[b.id]
    val values = remember(b.id) { mutableStateMapOf<String, String>().apply { b.fields.forEach { f -> put(f.id, f.value.orEmpty()) } } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        b.fields.forEach { field ->
            val enabled = interactive && sent == null
            when (field.kind) {
                CardBlock.Form.Kind.TOGGLE -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .then(if (enabled) Modifier.clickable(role = Role.Switch) { values[field.id] = if (values[field.id] == "true") "false" else "true" } else Modifier)
                    .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, Modifier.weight(1f), color = P.ink, fontSize = 15.sp)
                    val on = values[field.id] == "true"
                    Box(Modifier.size(width = 44.dp, height = 26.dp).clip(CircleShape).background(if (on) P.ink else P.ink.copy(alpha = .14f)).padding(3.dp),
                        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (on) inkOnPrimary else Color.White))
                    }
                }
                CardBlock.Form.Kind.SELECT -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(field.label, color = P.sub, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        field.options.forEach { option ->
                            val on = values[field.id] == option
                            Text(option, Modifier.clip(CircleShape).background(if (on) P.ink else Color.Transparent).border(1.dp, if (on) P.ink else P.line, CircleShape)
                                .then(if (enabled) Modifier.clickable(role = Role.RadioButton) { values[field.id] = option } else Modifier)
                                .padding(horizontal = 12.dp, vertical = 7.dp), color = if (on) inkOnPrimary else P.ink, fontSize = 13.sp)
                        }
                    }
                }
                else -> PField(values[field.id].orEmpty(), { if (enabled) values[field.id] = it }, Modifier.fillMaxWidth()
                    .then(if (field.kind == CardBlock.Form.Kind.TEXTAREA) Modifier.heightIn(min = 88.dp) else Modifier),
                    label = field.label + if (field.required) " *" else "", placeholder = field.placeholder, singleLine = field.kind != CardBlock.Form.Kind.TEXTAREA)
            }
        }
        val missing = b.fields.any { it.required && values[it.id].isNullOrBlank() }
        if (sent != null) SentLine(sent)
        else if (interactive) PButton(b.submitLabel, {
            val text = b.fields.joinToString("; ") { f ->
                val v = values[f.id].orEmpty().let { if (f.kind == CardBlock.Form.Kind.TOGGLE) (if (it == "true") "✓" else "✗") else it }
                "${f.label}: ${v.ifBlank { "—" }}"
            }
            onEvent(CardEvent.Send(b.id, prefix(cardTitle) + text))
        }, Modifier.fillMaxWidth(), enabled = !missing, icon = PIcons.send)
    }
}

@Composable
private fun RatingBlock(b: CardBlock.Rating, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    val sent = state.sent[b.id]
    var value by remember(b.id) { mutableIntStateOf(b.value ?: 0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        b.label?.let { Text(it, color = P.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..b.max.coerceIn(2, 10)).forEach { n ->
                val on = n <= value
                Box(Modifier.size(38.dp).clip(CircleShape).background(if (on) P.ink else Color.Transparent).border(1.dp, if (on) P.ink else P.line, CircleShape)
                    .then(if (interactive && sent == null) Modifier.clickable(role = Role.Button) {
                        value = n
                        if (b.submitLabel == null) onEvent(CardEvent.Send(b.id, prefix(cardTitle) + "$n/${b.max}"))
                    } else Modifier).semantics { contentDescription = "$n/${b.max}" }, contentAlignment = Alignment.Center) {
                    Text("$n", color = if (on) inkOnPrimary else P.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (sent != null) SentLine(sent)
        else if (interactive && b.submitLabel != null) PButton(b.submitLabel, { onEvent(CardEvent.Send(b.id, prefix(cardTitle) + "$value/${b.max}")) },
            enabled = value > 0, tiny = true)
    }
}

@Composable
private fun SliderBlock(b: CardBlock.Slider, state: CardState, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    val sent = state.sent[b.id]
    var value by remember(b.id) { mutableFloatStateOf(b.value.toFloat()) }
    val steps = if (b.step > 0) (((b.max - b.min) / b.step).toInt() - 1).coerceIn(0, 200) else 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(b.label, Modifier.weight(1f), color = P.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(formatNumber(value.toDouble()) + (b.unit?.let { " $it" } ?: ""), color = P.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = P.tabular))
        }
        androidx.compose.material3.Slider(value, { if (interactive && sent == null) value = it }, valueRange = b.min.toFloat()..b.max.toFloat(), steps = steps,
            enabled = interactive && sent == null, colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = P.ink, activeTrackColor = P.ink,
                inactiveTrackColor = P.ink.copy(alpha = .12f), activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent))
        if (sent != null) SentLine(sent)
        else if (interactive) PButton(b.submitLabel ?: stringResource(R.string.card_send), {
            onEvent(CardEvent.Send(b.id, prefix(cardTitle) + "${b.label}: ${formatNumber(value.toDouble())}${b.unit?.let { " $it" } ?: ""}"))
        }, Modifier.fillMaxWidth(), style = ButtonStyle.Alt, icon = PIcons.send)
    }
}

@Composable
private fun Actions(actions: List<CardAction>, onEvent: (CardEvent) -> Unit, interactive: Boolean, cardTitle: String?) {
    if (!interactive) return
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.take(4).forEach { a ->
            val style = when (a.style) {
                CardAction.ActionStyle.PRIMARY -> ButtonStyle.Primary
                CardAction.ActionStyle.DANGER -> ButtonStyle.Danger
                CardAction.ActionStyle.GHOST -> ButtonStyle.Ghost
                CardAction.ActionStyle.SECONDARY -> ButtonStyle.Alt
            }
            val icon = when { a.url != null -> PIcons.external; a.copy != null -> PIcons.copy; a.style == CardAction.ActionStyle.PRIMARY -> PIcons.arrowRight; else -> null }
            PButton(a.label, {
                when {
                    a.send != null -> onEvent(CardEvent.Send(null, prefix(cardTitle) + a.send))
                    a.url != null -> onEvent(CardEvent.Open(a.url))
                    a.copy != null -> onEvent(CardEvent.Copy(a.copy))
                }
            }, style = style, tiny = true, icon = icon)
        }
    }
}

/** 1234567 → "1,234,567"; 0.5 → "0.5"; at most two decimals, locale grouping. */
internal fun formatNumber(v: Double): String =
    if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) "%,d".format(v.toLong()) else "%,.2f".format(v).trimEnd('0').trimEnd('.', ',')

/** Times written by agents: ISO date-time (with or without zone), a date, or epoch milliseconds. */
internal object CardTime {
    fun parse(text: String): Long? {
        val t = text.trim()
        t.toLongOrNull()?.let { return it }
        runCatching { return java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }
        runCatching { return java.time.LocalDateTime.parse(t).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
        runCatching { return java.time.LocalDate.parse(t).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }
        return null
    }
}

/**
 * Images on cards: https only, redirects included (a redirect may move to another https address, never to plain http),
 * at most 4 MB, decoded at a modest size.
 */
internal object CardImages {
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().followRedirects(true).followSslRedirects(false).callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()
    }
    private val cache = android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24)

    fun load(context: android.content.Context, source: String): androidx.compose.ui.graphics.ImageBitmap? {
        cache.get(source)?.let { return it }
        // Fetching an image sends its address out: an address carrying a key or a stored credential is not fetched, and
        // neither is any address while the stored credentials cannot be read.
        val decoded = runCatching { java.net.URLDecoder.decode(source, Charsets.UTF_8) }.getOrDefault(source)
        val leaks = runCatching {
            val guard = app.umbra.core.safety.OutboundDlpGuard(4) { context.container.protectedValues() }
            guard.matches(source).isNotEmpty() || guard.matches(decoded).isNotEmpty()
        }.getOrDefault(true)
        if (leaks) return null
        val bytes = runCatching {
            when {
                source.startsWith("https://") -> http.newCall(okhttp3.Request.Builder().url(source).build()).execute().use { r ->
                    if (!r.isSuccessful || !r.request.url.isHttps) null else r.body.byteStream().use { it.readNBytes(4_000_001) }.takeIf { it.size <= 4_000_000 }
                }
                else -> null
            }
        }.getOrNull() ?: return null
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        var sample = 1
        while (max(options.outWidth, options.outHeight) / sample > 1600) sample *= 2
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return bitmap.asImageBitmap().also { cache.put(source, it) }
    }
}
