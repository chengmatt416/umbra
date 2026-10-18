package app.umbra.agent.ui.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.data.AppPrefs
import app.umbra.agent.ui.porcelain.ModalBottomSheet
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.pc
import app.umbra.core.session.Effort

/** An omp thinking level in the user's words ("high" → "High"); an unknown one as omp spells it. */
@Composable
fun effortName(level: String): String = when (level) {
    "off" -> stringResource(R.string.r_effort_off)
    "minimal" -> stringResource(R.string.r_effort_minimal)
    "low" -> stringResource(R.string.r_effort_low)
    "medium" -> stringResource(R.string.r_effort_medium)
    "high" -> stringResource(R.string.r_effort_high)
    "xhigh" -> stringResource(R.string.r_effort_xhigh)
    "max" -> stringResource(R.string.r_effort_max)
    Effort.OMP_AUTO -> stringResource(R.string.r_model_auto)
    else -> level
}

/** What the effort chip beside the model says for new tasks: "Auto" or the level the user set. */
@Composable
fun effortLabel(prefs: AppPrefs): String =
    if (prefs.autoEffort || prefs.thinkingLevel == null) stringResource(R.string.r_model_auto) else effortName(prefs.thinkingLevel)

/** A session's effort as its header shows it: "Auto → High" when Auto picked the level, else the level. */
@Composable
fun sessionEffortLabel(effort: Effort): String =
    if (effort.auto && effort.level != Effort.OMP_AUTO) stringResource(R.string.r_effort_auto_to, effortName(effort.level)) else effortName(effort.level)

/**
 * How hard the model thinks: "Auto" (Laya reads the task; omp's own judge without Laya), then omp's levels, lightest
 * first. A level the model does not offer is greyed out; [supported] empty means the model is not known yet (Auto
 * picks it, or the list does not describe it), and every level stays open: omp then takes the nearest one it has.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun EffortPicker(current: Effort?, supported: List<String>, modelName: String?, modelIsAuto: Boolean, onChoose: (Effort) -> Unit, onDismiss: () -> Unit) {
    val choose = { e: Effort -> onChoose(e); onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.r_effort_title), Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
                if (modelName != null) Text(modelName, fontSize = 12.sp, color = P.muted)
            }
            if (modelIsAuto) Text(stringResource(R.string.r_effort_by_model), Modifier.padding(start = 4.dp, bottom = 6.dp), fontSize = 12.sp, color = P.sub)
            val auto = current == null || current.auto
            val autoSub = stringResource(R.string.r_effort_auto_body) +
                if (current != null && current.auto && current.level != Effort.OMP_AUTO) " · " + effortName(current.level) else ""
            Option(stringResource(R.string.r_model_auto), autoSub, auto, enabled = true, mono = false) { choose(Effort(Effort.OMP_AUTO, auto = true)) }
            Effort.LEVELS.forEach { level ->
                val offered = supported.isEmpty() || level in supported
                val sub = when {
                    !offered -> stringResource(R.string.r_effort_unsupported)
                    level == "low" -> stringResource(R.string.r_effort_low_body)
                    level == "xhigh" -> stringResource(R.string.r_effort_xhigh_body)
                    else -> null
                }
                Option(effortName(level), sub, !auto && current.level == level, enabled = offered, mono = false) { if (offered) choose(Effort(level)) }
            }
        }
    }
}
