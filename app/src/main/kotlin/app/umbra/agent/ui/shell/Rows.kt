package app.umbra.agent.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.porcelain.IconSpec
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PIcon
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.pc

/** A group of rows on one rounded surface, with an optional small heading above it. */
@Composable
fun RowGroup(heading: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        if (heading != null) Text(heading, Modifier.padding(start = 6.dp, bottom = 6.dp), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = P.sub)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(pc(0xFFF6F8F2)), content = content)
    }
}

/** A row that opens something: icon, title, an optional second line, an optional value, a chevron. */
@Composable
fun NavRow(icon: IconSpec?, title: String, sub: String? = null, value: String? = null, tint: androidx.compose.ui.graphics.Color = P.ink, chevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) PIcon(icon, size = 22.dp, tint = if (tint == P.ink) P.sub else tint)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, Modifier.padding(top = 1.dp), fontSize = 12.5.sp, color = P.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, fontSize = 14.sp, color = P.sub, maxLines = 1)
        if (chevron) PIcon(PIcons.chevronRight, size = 18.dp, tint = P.muted)
    }
}

/** A row with a switch; the whole row toggles. */
@Composable
fun SwitchRow(icon: IconSpec?, title: String, sub: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) PIcon(icon, size = 22.dp, tint = P.sub)
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = P.ink)
            if (!sub.isNullOrBlank()) Text(sub, Modifier.padding(top = 1.dp), fontSize = 12.5.sp, color = P.sub)
        }
        Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = P.accent2, checkedThumbColor = P.base, uncheckedTrackColor = P.base2, uncheckedBorderColor = P.line))
    }
}

/** A page body that scrolls: the groups with even spacing and room at the end. */
@Composable
fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
