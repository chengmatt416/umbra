package app.umbra.agent.ui.porcelain

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one confirmation every irreversible or wide action goes through: what will happen in plain words, what stays,
 * and a button that names the action. [danger] colours the confirm button clay red.
 */
@Composable
fun PConfirm(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
    dismissLabel: String = androidx.compose.ui.res.stringResource(app.umbra.agent.R.string.action_cancel),
    confirmEnabled: Boolean = true,
    /** What the dismiss button does when it is more than closing (for example "Remove"); outside taps only close. */
    onDismissButton: () -> Unit = onDismiss,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFFF2F6EF),
        shape = RoundedCornerShape(P.radius),
        title = { Text(title, fontFamily = P.serif, fontSize = 22.sp, lineHeight = 26.sp, color = P.ink) },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (body.isNotBlank()) Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = P.sub)
                extra?.invoke(this)
            }
        },
        confirmButton = {
            PButton(confirmLabel, onConfirm, style = if (danger) ButtonStyle.Danger else ButtonStyle.Primary, enabled = confirmEnabled)
        },
        dismissButton = { PButton(dismissLabel, onDismissButton, style = ButtonStyle.Ghost) },
    )
}

/** An empty page: centred in the space it has, a sentence on what will appear, and the next step if there is one. */
@Composable
fun PEmpty(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: IconSpec? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) BadgeIcon(icon, size = 48.dp)
        Text(title, Modifier.padding(top = 16.dp), fontFamily = P.serif, fontSize = 24.sp, lineHeight = 28.sp, color = P.ink, textAlign = TextAlign.Center)
        Text(body, Modifier.padding(top = 8.dp).widthIn(max = 320.dp), fontSize = 14.sp, lineHeight = 21.sp, color = P.muted, textAlign = TextAlign.Center)
        if (action != null) Box(Modifier.padding(top = 18.dp)) { action() }
    }
}

/**
 * A horizontally scrolling row that fades out at the edge that still has more, so it is plain that the row
 * continues. Without more to scroll it looks like an ordinary row.
 */
@Composable
fun FadingRow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    spacing: Int = 6,
    content: @Composable RowScope.() -> Unit,
) {
    val end by animateFloatAsState(if (state.canScrollForward) 1f else 0f, tween(180), label = "fadeEnd")
    val start by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, tween(180), label = "fadeStart")
    Row(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val edge = 28.dp.toPx() / size.width
                if (end > 0f) drawRect(
                    Brush.horizontalGradient(0f to Color.Black, (1f - edge) to Color.Black, 1f to Color.Black.copy(alpha = 1f - end)),
                    blendMode = BlendMode.DstIn,
                )
                if (start > 0f) drawRect(
                    Brush.horizontalGradient(0f to Color.Black.copy(alpha = 1f - start), edge to Color.Black, 1f to Color.Black),
                    blendMode = BlendMode.DstIn,
                )
            }
            .horizontalScroll(state),
        horizontalArrangement = Arrangement.spacedBy(spacing.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Progressive disclosure: a quiet "Details" line that opens the technical part. What the user needs to decide stays
 * above it; the vocabulary of the architecture lives inside.
 */
@Composable
fun Disclosure(label: String, modifier: Modifier = Modifier, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, tween(220, easing = P.ease), label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "open" else "closed" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P.accent)
            PIcon(PIcons.chevronDown, Modifier.rotate(turn), size = 14.dp, tint = P.accent)
        }
        AnimatedVisibility(open, enter = fadeIn(tween(200)) + expandVertically(tween(240, easing = P.ease)), exit = fadeOut(tween(150)) + shrinkVertically(tween(200))) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), content = content)
        }
    }
}

/** The reason a control next to it is unavailable, and what to do first. */
@Composable
fun DisabledReason(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PIcon(PIcons.info, Modifier.padding(top = 2.dp), size = 13.dp, tint = P.amber)
        Text(text, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF856B49))
    }
}

/** A text field in the Porcelain surface: soft fill, a 14 dp radius, the accent on focus. */
@Composable
fun PField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    mono: Boolean = false,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(),
        enabled = enabled,
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it, color = P.muted) } },
        singleLine = singleLine, minLines = minLines, maxLines = maxLines,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = if (mono) P.mono else P.sans, fontSize = 14.sp, color = P.ink),
        shape = RoundedCornerShape(14.dp),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color(0xFFF4F8F1),
            unfocusedContainerColor = Color(0xCCF2F6EF),
            disabledContainerColor = Color(0x80F2F6EF),
            focusedBorderColor = P.accent,
            unfocusedBorderColor = Color(0x8CBFD1BB),
            focusedLabelColor = P.accent2,
            cursorColor = P.accent2,
        ),
    )
}

/** A section heading inside a page: a serif title and an optional one-line explanation under it. */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, body: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(title, fontFamily = P.serif, fontSize = 20.sp, lineHeight = 24.sp, color = P.ink)
        if (body != null) Text(body, Modifier.padding(top = 4.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P.muted)
    }
}

/** A row that opens another page: a label, a one-line summary and a chevron (`.setting` with navigation). */
@Composable
fun PLink(label: String, summary: String? = null, modifier: Modifier = Modifier, icon: IconSpec? = null, divider: Boolean = true, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) PIcon(icon, size = 18.dp, tint = P.accent)
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                if (!summary.isNullOrBlank()) Text(summary, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            PIcon(PIcons.chevronRight, size = 16.dp, tint = P.muted)
        }
        if (divider) Box(Modifier.fillMaxWidth().padding(start = if (icon != null) 30.dp else 0.dp).height(1.dp).background(Color(0x3391AA90)))
    }
}

/** A switch row (`.setting` with a toggle). The whole row toggles. */
@Composable
fun PToggle(label: String, description: String? = null, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, divider: Boolean = true, badge: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                    badge?.invoke()
                }
                if (!description.isNullOrBlank()) Text(description, Modifier.padding(top = 3.dp), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted)
            }
            androidx.compose.material3.Switch(checked, onChange)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x3391AA90)))
    }
}

/** A choice among a few options, each with a one-line consequence (radio list in Porcelain). */
@Composable
fun <T> PChoice(options: List<Triple<T, String, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, title, body) ->
            val on = value == selected
            val stateText = androidx.compose.ui.res.stringResource(if (on) app.umbra.agent.R.string.pchoice_selected else app.umbra.agent.R.string.pchoice_not_selected)
            val shape = RoundedCornerShape(16.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) Color(0xFFE6EFE1) else Color(0x99F2F6EF), shape)
                    .border(1.dp, if (on) Color(0xFF82A185) else Color(0x5EBFD1BB), shape)
                    .clickable(role = Role.RadioButton) { onSelect(value) }
                    .semantics { contentDescription = title; stateDescription = stateText }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.padding(top = 2.dp).border(1.5.dp, if (on) P.accent2 else Color(0xFFA9BAA6), RoundedCornerShape(50)).padding(4.dp),
                ) { Box(Modifier.background(if (on) P.accent2 else Color.Transparent, RoundedCornerShape(50)).padding(4.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                    Text(body, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted)
                }
            }
        }
    }
}

/** Remembers whether an expanded thing is open across recompositions only (for list rows). */
@Composable
fun rememberOpen(key: Any?): androidx.compose.runtime.MutableState<Boolean> = remember(key) { mutableStateOf(false) }
