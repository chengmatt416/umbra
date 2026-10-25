package app.umbra.agent.ui.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.data.ProactiveDefaults
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.*

internal fun minuteText(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

/**
 * How proactive an agent is and when its briefs arrive: the same controls in setup, in Settings → Proactive agents
 * (the defaults for new agents) and, through [ProactiveScreen], for one agent.
 */
@Composable
internal fun ProactiveDefaultsEditor(defaults: ProactiveDefaults, onChange: (ProactiveDefaults) -> Unit) {
    val context = LocalContext.current
    RowGroup {
        Segmented((0..4).map { it to "L$it" }, defaults.level, { onChange(defaults.copy(level = it)) }, fill = true)
        ProactiveLevelDescription(defaults.level, Modifier.padding(16.dp))
    }
    RowGroup {
        for (morning in listOf(true, false)) {
            val minute = if (morning) defaults.morningMinute else defaults.eveningMinute
            val label = stringResource(if (morning) R.string.proactive_morning else R.string.proactive_evening)
            fun save(value: Int?) = onChange(if (morning) defaults.copy(morningMinute = value) else defaults.copy(eveningMinute = value))
            SwitchRow(PIcons.clock, label, minute?.let(::minuteText) ?: stringResource(R.string.proactive_brief_off), minute != null) { on -> save(if (on) (if (morning) 480 else 1110) else null) }
            if (minute != null) NavRow(PIcons.clock, stringResource(R.string.proactive_brief_time, label), value = minuteText(minute)) {
                android.app.TimePickerDialog(context, { _, h, m -> save(h * 60 + m) }, minute / 60, minute % 60, android.text.format.DateFormat.is24HourFormat(context)).show()
            }
        }
    }
}

/**
 * Settings → Proactive agents: the defaults a new agent starts with, then every agent with its level and brief times,
 * each leading to its own proactive settings or its Briefing.
 */
@Composable
fun ProactiveAgentsScreen(onBack: () -> Unit, onAgentSettings: (String) -> Unit, onBriefing: (String) -> Unit) {
    val container = LocalContext.current.container
    val prefs by container.settings.flow.collectAsStateWithLifecycle()
    val profiles by container.agents.store.profiles.collectAsStateWithLifecycle()
    PorcelainPage(stringResource(R.string.proactive_agents_title), onBack) {
        PageColumn {
            Text(stringResource(R.string.proactive_agents_body), color = P.sub, fontSize = 13.sp, lineHeight = 19.sp)
            SectionLabel(stringResource(R.string.proactive_defaults))
            ProactiveDefaultsEditor(prefs.proactiveDefaults) { next -> container.settings.update { it.copy(proactiveDefaults = next) } }
            if (profiles.isNotEmpty()) SectionLabel(stringResource(R.string.proactive_agents_list))
            profiles.forEach { p ->
                val shape = RoundedCornerShape(20.dp)
                Row(Modifier.fillMaxWidth().inkSurface(shape, CardStyle.Ceramic).clip(shape).clickable(role = Role.Button) { onAgentSettings(p.id) }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentAvatar(p.avatar, p.name, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = P.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(listOfNotNull("L${p.proactivityLevel}", p.morningBriefMinute?.let { stringResource(R.string.proactive_morning) + " " + minuteText(it) },
                            p.eveningBriefMinute?.let { stringResource(R.string.proactive_evening) + " " + minuteText(it) }).joinToString(" · "), color = P.sub, fontSize = 13.sp)
                    }
                    PButton(stringResource(R.string.briefing_title), { onBriefing(p.id) }, tiny = true, style = ButtonStyle.Ghost)
                }
            }
        }
    }
}
