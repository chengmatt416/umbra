package app.umbra.agent.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.AppleAccent
import app.umbra.agent.data.ThemeAppearance
import app.umbra.agent.data.ThemePrefs
import app.umbra.agent.data.ThemeStyle
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.LiquidBackdrop
import app.umbra.agent.ui.porcelain.LiquidSwatch
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PToggle
import app.umbra.agent.ui.porcelain.SectionHead
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Theme
import app.umbra.agent.ui.porcelain.ThemeReveal
import app.umbra.agent.ui.porcelain.ThemeSpec
import app.umbra.agent.ui.porcelain.Tokens
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.porcelain.hsl
import app.umbra.agent.ui.porcelain.jelly
import app.umbra.agent.ui.porcelain.rememberPress
import app.umbra.agent.ui.shell.PorcelainPage
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** A named drop of colour in the Liquid palette. */
private class Preset(val hue: Float, @param:StringRes val name: Int)

private val presets = listOf(
    Preset(188f, R.string.hue_lagoon), Preset(212f, R.string.hue_ocean), Preset(250f, R.string.hue_iris), Preset(286f, R.string.hue_orchid),
    Preset(338f, R.string.hue_rose), Preset(14f, R.string.hue_ember), Preset(40f, R.string.hue_amber), Preset(152f, R.string.hue_jade),
)

/**
 * Appearance: Porcelain (the original glaze) or Liquid, and Liquid's colour, light or dark, and motion. Every change
 * pours over the whole app from the control that made it; the custom hue follows the finger live and is saved when
 * the finger lifts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val theme = prefs.theme
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val liquid = theme.style == ThemeStyle.LIQUID
    val apple = theme.style == ThemeStyle.APPLE

    fun save(origin: Offset?, change: (ThemePrefs) -> ThemePrefs) {
        val next = change(container.settings.value.theme)
        if (next == container.settings.value.theme) return
        ThemeReveal.switch(origin) {
            container.settings.update { it.copy(theme = next) }
            Theme.current = ThemeSpec.of(next, systemDark)
        }
    }

    PorcelainPage(stringResource(R.string.dest_appearance), onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp)) {
            Headline(stringResource(R.string.app_headline_sans), stringResource(R.string.app_headline_serif), Modifier.padding(top = 6.dp))
            Subtle(stringResource(R.string.app_intro), Modifier.padding(top = 6.dp))
            Gap(16.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeCard(
                    stringResource(R.string.theme_porcelain), stringResource(R.string.theme_porcelain_body), selected = theme.style == ThemeStyle.PORCELAIN,
                    onPick = { at -> save(at) { it.copy(style = ThemeStyle.PORCELAIN) } }, modifier = Modifier.weight(1f),
                ) { PorcelainPreview() }
                ThemeCard(
                    stringResource(R.string.theme_liquid), stringResource(R.string.theme_liquid_body), selected = liquid,
                    onPick = { at -> save(at) { it.copy(style = ThemeStyle.LIQUID) } }, modifier = Modifier.weight(1f),
                ) { LiquidPreview(ThemeSpec.of(theme.copy(style = ThemeStyle.LIQUID), systemDark)) }
                ThemeCard(
                    stringResource(R.string.theme_apple), stringResource(R.string.theme_apple_body), selected = apple,
                    onPick = { at -> save(at) { it.copy(style = ThemeStyle.APPLE) } }, modifier = Modifier.weight(1f),
                ) { ApplePreview(ThemeSpec.of(theme.copy(style = ThemeStyle.APPLE), systemDark)) }
            }

            AnimatedVisibility(liquid, enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)), exit = fadeOut(tween(160)) + shrinkVertically(tween(240))) {
                Column {
                    SectionHead(stringResource(R.string.app_color), Modifier.padding(top = 20.dp), body = stringResource(R.string.app_color_body))
                    val current = presets.firstOrNull { abs(it.hue - theme.hue) < 0.5f }
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4) {
                        presets.forEachIndexed { i, preset ->
                            Swatch(preset, selected = preset === current, seed = i * 1.7f) { at -> save(at) { it.copy(hue = preset.hue) } }
                        }
                    }
                    Text(
                        stringResource(R.string.app_hue_value, current?.let { stringResource(it.name) } ?: stringResource(R.string.app_custom), Theme.current.hue.roundToInt()),
                        Modifier.padding(top = 10.dp, bottom = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.sub,
                    )
                    Kicker(stringResource(R.string.app_custom_hue), Modifier.padding(top = 10.dp), color = P.muted)
                    HueSlider(
                        hue = Theme.current.hue,
                        onSlide = { h -> Theme.current = ThemeSpec.of(container.settings.value.theme.copy(hue = h), systemDark) },
                        onDone = { h -> container.settings.update { it.copy(theme = it.theme.copy(hue = h)) } },
                    )
                }
            }

            AnimatedVisibility(apple, enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 1f, stiffness = 320f)), exit = fadeOut(tween(160)) + shrinkVertically(tween(240))) {
                Column {
                    SectionHead(stringResource(R.string.app_tint), Modifier.padding(top = 20.dp), body = stringResource(R.string.app_tint_body))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppleAccent.entries.forEach { a ->
                            AccentDot(a, selected = theme.appleAccent == a) { at -> save(at) { it.copy(appleAccent = a) } }
                        }
                    }
                    Gap(14.dp)
                    PCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                        PToggle(stringResource(R.string.app_glass), stringResource(R.string.app_glass_body), theme.appleGlass, { on -> save(null) { it.copy(appleGlass = on) } }, divider = false)
                    }
                    if (theme.appleGlass && reduceTransparency()) Box(Modifier.padding(top = 10.dp)) { Callout(stringResource(R.string.app_glass_reduced), Tone.Warn) }
                }
            }

            AnimatedVisibility(liquid || apple, enter = fadeIn(tween(260)) + expandVertically(spring(dampingRatio = 1f, stiffness = 320f)), exit = fadeOut(tween(160)) + shrinkVertically(tween(240))) {
                Column {
                    SectionHead(stringResource(R.string.app_mode), Modifier.padding(top = 18.dp))
                    var modeCenter by remember { mutableStateOf<Offset?>(null) }
                    Box(Modifier.onGloballyPositioned { modeCenter = it.boundsInRoot().center }) {
                        Segmented(
                            listOf(
                                ThemeAppearance.LIGHT to stringResource(R.string.app_mode_light),
                                ThemeAppearance.DARK to stringResource(R.string.app_mode_dark),
                                ThemeAppearance.SYSTEM to stringResource(R.string.app_mode_system),
                            ),
                            theme.appearance, { mode -> save(modeCenter) { it.copy(appearance = mode) } }, fill = true,
                        )
                    }
                    Gap(8.dp)
                    PToggle(stringResource(if (apple) R.string.app_motion_apple else R.string.app_motion), stringResource(if (apple) R.string.app_motion_body_apple else R.string.app_motion_body), theme.motion, { on -> save(null) { it.copy(motion = on) } }, divider = false)

                    Gap(10.dp)
                    TryItCard()
                }
            }
            if (!liquid && !apple) Box(Modifier.padding(top = 18.dp)) { Callout(stringResource(R.string.app_porcelain_note), Tone.Neutral) }
            Gap(28.dp)
        }
    }
}

@Composable
private fun ThemeCard(title: String, body: String, selected: Boolean, onPick: (Offset?) -> Unit, modifier: Modifier = Modifier, preview: @Composable BoxScope.() -> Unit) {
    var center by remember { mutableStateOf<Offset?>(null) }
    val press = rememberPress()
    val ring by androidx.compose.animation.animateColorAsState(if (selected) P.accent else Color.Transparent, tween(260), label = "ring")
    val stateText = stringResource(if (selected) R.string.pchoice_selected else R.string.pchoice_not_selected)
    Column(
        modifier
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .jelly(press, 0.5f, glow = RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .border(2.dp, ring, RoundedCornerShape(22.dp))
            .clickable(interactionSource = press, indication = androidx.compose.foundation.LocalIndication.current, role = Role.RadioButton) { onPick(center) }
            .semantics { contentDescription = title; stateDescription = stateText }
            .padding(5.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(16.dp))) {
            preview()
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape).background(P.accent),
                    contentAlignment = Alignment.Center,
                ) { PIcon(PIcons.check, size = 14.dp, tint = Color.White) }
            }
        }
        Text(title, Modifier.padding(start = 4.dp, top = 9.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = P.ink, maxLines = 1)
        Text(body, Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 6.dp), fontSize = 11.sp, lineHeight = 15.sp, color = P.muted)
    }
}

/** A tiny page in the original glaze, whatever the current theme. */
@Composable
private fun PorcelainPreview() {
    val t = Tokens.Porcelain
    Box(Modifier.fillMaxSize().background(t.page).padding(12.dp)) {
        MiniPage(card = t.ceramic, cardRim = Color(0xCCFFFFFF), bar = t.ink.copy(alpha = 0.22f), button = t.primaryButton)
    }
}

/** A tiny iOS page: grouped grey, a white inset list, a filled button and, with glass, a floating tab bar. */
@Composable
private fun ApplePreview(spec: ThemeSpec) {
    val dark = spec.dark
    val card = app.umbra.agent.ui.porcelain.AppleColors.card(dark)
    val label = app.umbra.agent.ui.porcelain.AppleColors.label(dark).copy(alpha = 0.22f)
    val sep = app.umbra.agent.ui.porcelain.AppleColors.separator(dark)
    Box(Modifier.fillMaxSize().background(app.umbra.agent.ui.porcelain.AppleColors.groupedBackground(dark)).padding(10.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(9.dp).clip(CircleShape).background(app.umbra.agent.ui.porcelain.AppleColors.label(dark).copy(alpha = 0.8f)))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(card)) {
                repeat(2) { i ->
                    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(spec.accentColor))
                        Box(Modifier.padding(start = 6.dp).fillMaxWidth(0.6f).height(5.dp).clip(CircleShape).background(label))
                    }
                    if (i == 0) Box(Modifier.padding(start = 26.dp).fillMaxWidth().height(1.dp).background(sep))
                }
            }
            Box(Modifier.fillMaxWidth(0.55f).height(16.dp).clip(CircleShape).background(spec.accentColor))
        }
        if (spec.appleGlass) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.86f).height(18.dp).clip(CircleShape)
                    .background(if (dark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.7f))
                    .border(1.dp, Color.White.copy(alpha = if (dark) 0.25f else 0.9f), CircleShape),
            ) { Box(Modifier.padding(3.dp).fillMaxWidth(0.3f).height(12.dp).clip(CircleShape).background(spec.accentColor.copy(alpha = 0.25f))) }
        }
    }
}

/** One iOS tint: a filled circle, ringed when chosen; it settles on a critically damped spring. */
@Composable
private fun AccentDot(accent: AppleAccent, selected: Boolean, onPick: (Offset?) -> Unit) {
    var center by remember { mutableStateOf<Offset?>(null) }
    val color = app.umbra.agent.ui.porcelain.AppleColors.accent(accent, Theme.dark)
    val ring by androidx.compose.animation.core.animateFloatAsState(if (selected) 1f else 0f, app.umbra.agent.ui.porcelain.appleSpring(1f, 0.3f), label = "ring")
    val name = stringResource(accentName(accent))
    val stateText = stringResource(if (selected) R.string.pchoice_selected else R.string.pchoice_not_selected)
    Box(
        Modifier
            .size(36.dp)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .clip(CircleShape)
            .clickable(role = Role.RadioButton) { onPick(center) }
            .semantics { contentDescription = name; stateDescription = stateText }
            .drawBehind {
                val r = size.minDimension / 2f
                if (ring > 0.01f) drawCircle(color, radius = r, alpha = ring, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                drawCircle(color, radius = r - (2.dp.toPx() + 3.dp.toPx()) * ring - 2.dp.toPx() * (1f - ring))
            },
    )
}

private fun accentName(a: AppleAccent) = when (a) {
    AppleAccent.BLUE -> R.string.tint_blue
    AppleAccent.INDIGO -> R.string.tint_indigo
    AppleAccent.PURPLE -> R.string.tint_purple
    AppleAccent.PINK -> R.string.tint_pink
    AppleAccent.RED -> R.string.tint_red
    AppleAccent.ORANGE -> R.string.tint_orange
    AppleAccent.GREEN -> R.string.tint_green
    AppleAccent.TEAL -> R.string.tint_teal
    AppleAccent.GRAPHITE -> R.string.tint_graphite
}

@Composable
private fun reduceTransparency() = app.umbra.agent.ui.porcelain.rememberReduceTransparency()

/** A tiny page in Liquid, in the chosen colour, flowing. */
@Composable
private fun LiquidPreview(spec: ThemeSpec) {
    Box(Modifier.fillMaxSize()) {
        LiquidBackdrop(Modifier.fillMaxSize(), spec, seed = 9f)
        Box(Modifier.fillMaxSize().padding(12.dp)) {
            MiniPage(
                card = spec.tokens.ceramic,
                cardRim = Color.White.copy(alpha = if (spec.dark) 0.3f else 0.9f),
                bar = spec.tokens.ink.copy(alpha = 0.25f),
                button = spec.tokens.primaryButton,
            )
        }
    }
}

@Composable
private fun BoxScope.MiniPage(card: Brush, cardRim: Color, bar: Color, button: Brush) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card).border(1.dp, cardRim, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.7f).height(7.dp).clip(CircleShape).background(bar))
            Box(Modifier.fillMaxWidth(0.45f).height(5.dp).clip(CircleShape).background(bar.copy(alpha = bar.alpha * 0.6f)))
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(card).border(1.dp, cardRim, RoundedCornerShape(12.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.fillMaxWidth(0.55f).height(5.dp).clip(CircleShape).background(bar.copy(alpha = bar.alpha * 0.6f)))
            Box(Modifier.fillMaxWidth(0.5f).height(16.dp).clip(CircleShape).background(button))
        }
    }
}

@Composable
private fun Swatch(preset: Preset, selected: Boolean, seed: Float, onPick: (Offset?) -> Unit) {
    var center by remember { mutableStateOf<Offset?>(null) }
    val press = rememberPress()
    val name = stringResource(preset.name)
    val stateText = stringResource(if (selected) R.string.pchoice_selected else R.string.pchoice_not_selected)
    val dark = Theme.dark
    LiquidSwatch(
        hsl(preset.hue, 0.78f, if (dark) 0.58f else 0.55f), selected,
        Modifier
            .size(62.dp)
            .onGloballyPositioned { center = it.boundsInRoot().center }
            .jelly(press, 1.2f)
            .clip(CircleShape)
            .clickable(interactionSource = press, indication = null, role = Role.RadioButton) { onPick(center) }
            .semantics { contentDescription = name; stateDescription = stateText },
        seed = seed,
    )
}

/**
 * The custom hue: a rainbow track and a glass drop that follows the finger. The drop trails the finger on a spring
 * and stretches while it catches up, like the other drops in Liquid.
 */
@Composable
private fun HueSlider(hue: Float, onSlide: (Float) -> Unit, onDone: (Float) -> Unit) {
    val slide by rememberUpdatedState(onSlide)
    val done by rememberUpdatedState(onDone)
    val dark = Theme.dark
    val shown = remember { Animatable(hue) }
    val grab = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var last by remember { mutableFloatStateOf(hue) }
    LaunchedEffect(hue) { shown.animateTo(hue, spring(dampingRatio = 0.75f, stiffness = 700f)) }
    val track = remember(dark) { (0..12).map { hsl(it * 30f, 0.78f, if (dark) 0.55f else 0.60f) } }
    val desc = stringResource(R.string.app_custom_hue)
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics { contentDescription = desc }
            // a tap or a sideways drag picks a hue; a vertical swipe is left to the page's scroll
            .pointerInput(Unit) {
                val pad = 20.dp.toPx()
                fun hueAt(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * 359.9f
                detectTapGestures(
                    onPress = { scope.launch { grab.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }; tryAwaitRelease(); scope.launch { grab.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 400f)) } },
                    onTap = { at -> last = hueAt(at.x); slide(last); done(last) },
                )
            }
            .pointerInput(Unit) {
                val pad = 20.dp.toPx()
                fun hueAt(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f) * 359.9f
                detectHorizontalDragGestures(
                    onDragStart = { at ->
                        scope.launch { grab.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
                        last = hueAt(at.x)
                        slide(last)
                    },
                    onDragEnd = {
                        scope.launch { grab.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 400f)) }
                        done(last)
                    },
                    onDragCancel = {
                        scope.launch { grab.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 400f)) }
                        done(last)
                    },
                ) { change, _ ->
                    change.consume()
                    last = hueAt(change.position.x)
                    slide(last)
                }
            }
            .drawBehind {
                val pad = 20.dp.toPx()
                val trackH = 14.dp.toPx()
                val top = (size.height - trackH) / 2f
                drawRoundRect(Brush.horizontalGradient(track, startX = pad, endX = size.width - pad), topLeft = Offset(0f, top), size = Size(size.width, trackH), cornerRadius = CornerRadius(trackH / 2f))
                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), startY = top, endY = top + trackH), topLeft = Offset(0f, top), size = Size(size.width, trackH), cornerRadius = CornerRadius(trackH / 2f))
                val span = size.width - 2 * pad
                val tx = pad + hue / 360f * span
                val sx = pad + shown.value / 360f * span
                val g = grab.value
                val r = 13.dp.toPx() * (1f + 0.3f * g)
                val stretch = abs(tx - sx).coerceAtMost(36.dp.toPx())
                val w = 2 * r + stretch
                val h = 2 * r - stretch * 0.18f
                val cx = (tx + sx) / 2f
                val cy = size.height / 2f
                drawRoundRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(cx - w / 2, cy - h / 2 + 2.dp.toPx()), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                drawRoundRect(Color.White, topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h), cornerRadius = CornerRadius(h / 2))
                val inset = 4.dp.toPx()
                drawRoundRect(
                    hsl(hue, 0.78f, if (dark) 0.58f else 0.55f), topLeft = Offset(cx - w / 2 + inset, cy - h / 2 + inset), size = Size(w - 2 * inset, h - 2 * inset),
                    cornerRadius = CornerRadius((h - 2 * inset) / 2),
                )
            },
    )
}

/** A playground: the controls of the app, to feel the theme before leaving the page. */
@Composable
private fun TryItCard() {
    var on by remember { mutableStateOf(true) }
    var pick by remember { mutableStateOf(1) }
    PCard {
        Kicker(stringResource(R.string.app_try), color = P.accent)
        Subtle(stringResource(R.string.app_try_body), Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PButton(stringResource(R.string.app_try_press), {}, icon = PIcons.drop)
            PButton(stringResource(R.string.app_try_glass), {}, style = ButtonStyle.Alt)
            PButton(stringResource(R.string.app_try_soft), {}, style = ButtonStyle.Ghost)
        }
        Gap(12.dp)
        Segmented(
            listOf(0 to stringResource(R.string.app_try_one), 1 to stringResource(R.string.app_try_two), 2 to stringResource(R.string.app_try_three)),
            pick, { pick = it }, fill = true,
        )
        PToggle(stringResource(R.string.app_try_toggle), null, on, { on = it }, divider = false)
    }
}
