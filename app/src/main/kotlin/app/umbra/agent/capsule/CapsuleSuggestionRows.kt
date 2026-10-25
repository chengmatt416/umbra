package app.umbra.agent.capsule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R

@Composable internal fun CapsuleSuggestionRows(capsule: Capsule, snapshot: CapsuleSuggestionSnapshot, compact: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.cap_suggestions_label, snapshot.count, snapshot.count), color = Section, fontSize = 13.sp)
        snapshot.items.take(if (compact) 1 else 3).forEach { item ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.cap_suggestion_review)) {
                capsule.open("agent/${item.agentId}/briefing?focus=suggestions", null)
            }.onGloballyPositioned { coordinates ->
                val at = coordinates.positionOnScreen()
                if (at.x.isFinite() && at.y.isFinite()) capsule.noteDebugTarget(item.id, app.umbra.core.model.Point(
                    (at.x + coordinates.size.width / 2f).toInt(), (at.y + coordinates.size.height / 2f).toInt()))
            }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${item.agentName} · ${item.title}", color = Label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!compact) Text(item.summary, color = Small, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.cap_suggestion_review), color = Sage, fontSize = 12.sp)
            }
        }
        if (!compact && snapshot.count > 3) Text(stringResource(R.string.cap_suggestions_more), color = Small, fontSize = 12.sp)
    }
}
