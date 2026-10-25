package app.umbra.agent.ui.porcelain

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * Dialogs and sheets as glass. A system dialog is a separate window with nothing behind it to refract, so under
 * Liquid and Apple the app's dialogs and sheets are drawn inside the app's own window instead: the dialog registers
 * with a host at the root of the app, the host dims the app (inside the live layer, so the glass refracts the dimmed
 * app) and draws the dialog as glass over it, reading its tint from what is behind. Porcelain keeps Material's
 * window dialogs exactly as before, and a dialog opened from another window (the Capsule) does too.
 */

internal enum class OverlayKind { Alert, Sheet }

@Stable
internal class OverlayEntry(val kind: OverlayKind) {
    var content by mutableStateOf<@Composable ColumnScope.() -> Unit>({})
    var onDismiss by mutableStateOf({})
    var leaving by mutableStateOf(false)
}

/** The dialogs and sheets open in the app's window, oldest first, and the window that hosts them. */
object GlassOverlays {
    internal val shown = mutableStateListOf<OverlayEntry>()
    internal var hostView: android.view.View? by mutableStateOf(null)
}

/** True when dialogs here are drawn in-window as glass (a non-Porcelain theme, in the window that hosts them). */
@Composable
fun inWindowOverlays(): Boolean = !Theme.current.plain && GlassOverlays.hostView === LocalView.current

/** Registers a dialog or sheet with the host for as long as the caller is composed; back dismisses it. */
@Composable
internal fun Overlay(kind: OverlayKind, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val entry = remember { OverlayEntry(kind) }
    SideEffect {
        entry.content = content
        entry.onDismiss = onDismiss
    }
    DisposableEffect(entry) {
        entry.content = content
        entry.onDismiss = onDismiss
        GlassOverlays.shown.add(entry)
        // when the caller closes it, it leaves on its own spring (the host removes it after)
        onDispose { entry.leaving = true }
    }
    BackHandler { onDismiss() }
}

/**
 * The dimming under in-window dialogs. It sits inside the live layer of the app, so the glass dialog over it
 * refracts the dimmed app, as iOS's alerts do.
 */
@Composable
fun BoxScope.OverlayScrim() {
    val any = GlassOverlays.shown.any { !it.leaving }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(any) { alpha.animateTo(if (any) 1f else 0f, tween(if (any) 220 else 260)) }
    if (alpha.value > 0.001f || any) {
        val dim = if (Theme.dark) 0.48f else 0.26f
        Box(
            Modifier
                .matchParentSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = dim * alpha.value)) }
                .then(
                    if (any) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        GlassOverlays.shown.lastOrNull { !it.leaving }?.onDismiss?.invoke()
                    } else Modifier,
                ),
        )
    }
}

/** Draws the in-window dialogs and sheets as glass over the app's live [layer] (place it outside that layer). */
@Composable
fun BoxScope.OverlayHost(layer: LayerBackdrop, layerOrigin: () -> Offset) {
    val view = LocalView.current
    DisposableEffect(view) {
        GlassOverlays.hostView = view
        onDispose { if (GlassOverlays.hostView === view) GlassOverlays.hostView = null }
    }
    GlassOverlays.shown.toList().forEach { entry ->
        androidx.compose.runtime.key(entry) {
            when (entry.kind) {
                OverlayKind.Alert -> GlassAlert(entry, layer, layerOrigin)
                OverlayKind.Sheet -> GlassSheet(entry, layer, layerOrigin)
            }
        }
    }
}

/** The glass material of a dialog: refraction, blur and vibrancy of the app under it, the rim light, a deep shadow. */
private fun Modifier.overlayGlass(layer: LayerBackdrop, shape: Shape, tone: Float, glass: Boolean): Modifier = drawBackdrop(
    backdrop = layer,
    shape = { shape },
    effects = {
        vibrancy()
        blur(if (glass) 14.dp.toPx() else 30.dp.toPx())
        if (glass) lens(22.dp.toPx(), 44.dp.toPx())
    },
    highlight = if (glass) ({ appleRim(0.9f, 1.dp) }) else null,
    shadow = { appleShadow(30.dp, androidx.compose.ui.util.lerp(0.18f, 0.5f, tone)) },
    onDrawSurface = {
        val light = Color.White.copy(alpha = if (glass) 0.62f else 0.86f)
        val dark = Color(0xFF1C1C1E).copy(alpha = if (glass) 0.58f else 0.86f)
        drawRect(lerp(light, dark, tone))
    },
)

@Composable
private fun overlayGlassOn(): Boolean = Theme.liquid || appleGlassActive()

@Composable
private fun GlassAlert(entry: OverlayEntry, layer: LayerBackdrop, layerOrigin: () -> Offset) {
    val glass = overlayGlassOn()
    val shape = RoundedCornerShape(if (Theme.apple) 30.dp else 26.dp)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(entry.leaving) {
        if (entry.leaving) {
            appear.animateTo(0f, tween(180))
            GlassOverlays.shown.remove(entry)
        } else appear.animateTo(1f, appleSpring(1f, 0.38f))
    }
    val tone = rememberBackdropTone(layer.graphicsLayer, layerOrigin, Theme.dark, Theme.current.tokens.base)
    val onDark = tone.value > 0.5f
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .then(tone.modifier)
                // materialize: grows from 88 %, sharpens and becomes opaque together; leaves the same way, faster
                .graphicsLayer {
                    val v = appear.value
                    alpha = v.coerceIn(0f, 1f)
                    val s = 0.88f + 0.12f * v
                    scaleX = s
                    scaleY = s
                    val b = (1f - v) * 14.dp.toPx()
                    renderEffect = if (b > 0.5f) androidx.compose.ui.graphics.BlurEffect(b, b, androidx.compose.ui.graphics.TileMode.Decal) else null
                }
                .overlayGlass(layer, shape, tone.value, glass)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val ink = if (onDark) Color.White else if (Theme.liquid && !Theme.dark) Theme.current.tokens.ink else Color.Black
            val surface = if (onDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
            CompositionLocalProvider(LocalContentColor provides ink, LocalGlassSurface provides surface) {
                entry.content(this)
            }
        }
    }
}

@Composable
private fun GlassSheet(entry: OverlayEntry, layer: LayerBackdrop, layerOrigin: () -> Offset) {
    val glass = overlayGlassOn()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(2000f) }
    var height by remember { mutableStateOf(0f) }
    LaunchedEffect(entry.leaving, height) {
        if (height <= 0f) return@LaunchedEffect
        if (entry.leaving) {
            offset.animateTo(height + 40f, appleSpring(1f, 0.3f))
            GlassOverlays.shown.remove(entry)
        } else if (offset.value > height) {
            offset.snapTo(height)
            offset.animateTo(0f, appleSpring(1f, 0.38f))
        }
    }
    val tone = rememberBackdropTone(layer.graphicsLayer, layerOrigin, Theme.dark, Theme.current.tokens.base)
    val onDark = tone.value > 0.5f
    val shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
    // the grabber drags the sheet: down follows the finger, up resists (rubber band); on release, where the motion was
    // heading decides — far or fast enough down closes, else it settles back (velocity handed to the spring)
    val drag = rememberDraggableState { d ->
        scope.launch {
            val next = offset.value + d
            offset.snapTo(if (next < 0f) -rubberBand(-next, height, 0.55f) else next)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 40.dp)
                .onGloballyPositioned { if (height == 0f) height = it.size.height.toFloat() }
                .then(tone.modifier)
                .graphicsLayer { translationY = offset.value }
                .overlayGlass(layer, shape, tone.value, glass)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .draggable(
                        drag, Orientation.Vertical,
                        onDragStopped = { v ->
                            val projected = offset.value + projectMomentum(v)
                            if (projected > height * 0.45f && v >= -50f) entry.onDismiss()
                            else offset.animateTo(0f, appleSpring(0.8f, 0.3f), initialVelocity = v)
                        },
                    )
                    .padding(top = 8.dp, bottom = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(RoundedCornerShape(50)).background(if (onDark) Color.White.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.2f)))
            }
            val ink = if (onDark) Color.White else if (Theme.liquid && !Theme.dark) Theme.current.tokens.ink else Color.Black
            val surface = if (onDark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
            CompositionLocalProvider(LocalContentColor provides ink, LocalGlassSurface provides surface) {
                entry.content(this)
            }
        }
    }
}

// ---- drop-in replacements ----

/**
 * Material's AlertDialog, with the same parameters. Porcelain (or another window) gets Material's dialog unchanged;
 * Liquid and Apple get an in-window glass alert: icon, title, text, and the buttons side by side at the end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
) {
    if (!inWindowOverlays()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier, dismissButton = dismissButton,
            icon = icon, title = title, text = text, shape = shape, containerColor = containerColor, tonalElevation = tonalElevation, properties = properties,
        )
        return
    }
    val apple = Theme.apple
    Overlay(OverlayKind.Alert, onDismissRequest) {
        if (icon != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { icon() }
        if (title != null) {
            ProvideTextStyle(
                if (apple) TextStyle(fontFamily = P.sans, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = LocalContentColor.current)
                else TextStyle(fontFamily = P.serif, fontSize = 22.sp, lineHeight = 26.sp, color = LocalContentColor.current),
            ) { title() }
        }
        if (text != null) {
            ProvideTextStyle(TextStyle(fontFamily = P.sans, fontSize = 14.sp, lineHeight = 20.sp, color = LocalContentColor.current.copy(alpha = 0.78f))) {
                Column(Modifier.fillMaxWidth()) { text() }
            }
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            dismissButton?.invoke()
            confirmButton()
        }
    }
}

/**
 * Material's ModalBottomSheet, with the same parameters the app uses. Porcelain gets Material's sheet unchanged;
 * Liquid and Apple get an in-window glass sheet that rises on a spring and can be dragged down by its grabber.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = androidx.compose.material3.BottomSheetDefaults.ContainerColor,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!inWindowOverlays()) {
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState, containerColor = containerColor, content = content)
        return
    }
    Overlay(OverlayKind.Sheet, onDismissRequest, content)
}
