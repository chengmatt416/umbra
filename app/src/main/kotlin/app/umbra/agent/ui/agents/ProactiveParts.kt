package app.umbra.agent.ui.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.porcelain.*

/** What each autonomy level means, in words; the selected one is described under the picker. */
private val LEVEL_NAMES = listOf(R.string.proactive_level0, R.string.proactive_level1, R.string.proactive_level2, R.string.proactive_level3, R.string.proactive_level4)
private val LEVEL_BODIES = listOf(R.string.proactive_level0_body, R.string.proactive_level1_body, R.string.proactive_level2_body, R.string.proactive_level3_body, R.string.proactive_level4_body)

@Composable
internal fun ProactiveLevelDescription(level: Int, modifier: Modifier = Modifier) {
    AnimatedContent(level.coerceIn(0, 4), modifier, transitionSpec = {
        (fadeIn(tween(180)) + slideInVertically(Motion.enter()) { it / 6 }) togetherWith fadeOut(tween(90))
    }, label = "level-description") { l ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(LEVEL_NAMES[l]), color = P.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(LEVEL_BODIES[l]), color = P.sub, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}

/** The proactive sections: compact pills with live counts; the selected pill scrolls itself into view. */
@Composable
internal fun ProactiveTabs(labels: List<Pair<String, Int?>>, selected: Int, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(selected) { state.animateScrollToItem((selected - 1).coerceAtLeast(0)) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(end = 24.dp)) {
        itemsIndexed(labels) { i, (label, count) ->
            val on = i == selected
            val weight by animateFloatAsState(if (on) 1f else 0f, Motion.settle(), label = "tab")
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.inkPress(interaction).clip(CircleShape)
                    .background(lerpColor(P.highlight, P.ink, weight))
                    .then(if (on) Modifier else Modifier.border(1.dp, P.line2, CircleShape))
                    .clickable(interaction, null, role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on; contentDescription = if (count != null) "$label, $count" else label }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, color = lerpColor(P.ink, inkOnPrimary, weight), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (count != null && count > 0) Text(count.toString(), Modifier.clip(CircleShape).background(lerpColor(P.line2, inkOnPrimary.copy(alpha = .22f), weight)).padding(horizontal = 7.dp, vertical = 1.dp),
                    color = lerpColor(P.sub, inkOnPrimary, weight), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

/** A thin bar that grows to [done]/[total] with the layout spring. */
@Composable
internal fun ProgressLine(done: Int, total: Int, modifier: Modifier = Modifier, color: Color = P.ink) {
    val target = if (total <= 0) 0f else done.toFloat() / total
    val fraction by animateFloatAsState(target, Motion.settle(), label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(P.line2)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(CircleShape).background(color))
    }
}

/** One milestone: a filled dot when done, a ring with a centre while active, a hollow ring while waiting. */
@Composable
internal fun MilestoneLine(status: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val done = status == "done"
        val failed = status == "failed"
        Box(Modifier.size(14.dp).clip(CircleShape).then(
            when {
                done -> Modifier.background(P.ink)
                failed -> Modifier.background(P.danger)
                else -> Modifier.border(1.5.dp, if (status == "active" || status == "verifying") P.ink else P.sub.copy(alpha = .55f), CircleShape)
            }), contentAlignment = Alignment.Center) {
            if (status == "active" || status == "verifying") Box(Modifier.size(6.dp).clip(CircleShape).background(P.ink))
            if (done) PIcon(PIcons.check, size = 10.dp, tint = inkOnPrimary, strokeWidth = 3f)
        }
        Text(title, color = if (done) P.sub else P.ink, fontSize = 14.sp)
    }
}

/** A small label pill; [tone] colours only things that mean something (attention, done, failure). */
@Composable
internal fun StatusChip(text: String, tone: Color = P.sub) {
    Text(text, Modifier.clip(RoundedCornerShape(8.dp)).background(tone.copy(alpha = .1f)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = tone, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .3.sp)
}

@Composable
internal fun SectionLabel(text: String, color: Color = P.sub) {
    Text(text.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

/** An empty section says what will appear and where it comes from, without a wall of text. */
@Composable
internal fun ProactiveEmpty(icon: IconSpec, title: String, body: String) {
    Column(Modifier.fillMaxWidth().inkEnter().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(P.highlight), contentAlignment = Alignment.Center) { PIcon(icon, size = 22.dp, tint = P.ink) }
        Text(title, color = P.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = P.sub, fontSize = 13.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
    }
}
