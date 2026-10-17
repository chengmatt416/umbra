package app.umbra.agent.ui.reference

import app.umbra.agent.ui.porcelain.pc
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.umbra.agent.R
import app.umbra.agent.ui.MarkdownText
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Fact
import app.umbra.agent.ui.porcelain.GraphFlow
import app.umbra.agent.ui.porcelain.GraphNode
import app.umbra.agent.ui.porcelain.Headline
import app.umbra.agent.ui.porcelain.Kicker
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PBar
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.PToggle
import app.umbra.agent.ui.porcelain.Segmented
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.StepItem
import app.umbra.agent.ui.porcelain.StepState
import app.umbra.agent.ui.porcelain.Steps
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.shell.PorcelainPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class RefTab { Spec, Design }

/**
 * Reference (UX report F-05): the prototype's two pages that had no place in the app. The Architecture Spec v0.1
 * ships with the app, as written, read by section; the design system is the real Porcelain components, live.
 */
@Composable
fun ReferenceScreen(onBack: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(RefTab.Spec) }
    PorcelainPage(stringResource(R.string.dest_reference), onBack) {
        Column(Modifier.fillMaxSize()) {
            Segmented(
                listOf(RefTab.Spec to stringResource(R.string.ref_spec), RefTab.Design to stringResource(R.string.ref_design)), tab, { tab = it },
                Modifier.padding(horizontal = 18.dp, vertical = 6.dp), fill = true,
            )
            when (tab) {
                RefTab.Spec -> SpecReader()
                RefTab.Design -> DesignSystem()
            }
        }
    }
}

private data class Section(val title: String, val body: String)

@Composable
private fun SpecReader() {
    val context = LocalContext.current
    val sections by produceState<List<Section>?>(null) {
        value = withContext(Dispatchers.IO) {
            val text = context.assets.open("reference/architecture-spec-v0.1.md").bufferedReader().use { it.readText() }
            // The preamble (title and status) is section 0; every "## " heading starts the next.
            text.split(Regex("(?m)^(?=## )")).map { part ->
                val first = part.lineSequence().firstOrNull().orEmpty()
                Section(first.removePrefix("## ").removePrefix("# ").trim(), part.lines().drop(1).joinToString("\n").trim())
            }
        }
    }
    var open by rememberSaveable { mutableStateOf<Int?>(null) }
    val list = sections ?: return
    val selected = open
    if (selected != null) {
        androidx.activity.compose.BackHandler { open = null }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp)) {
            item {
                PButton(stringResource(R.string.ref_back), { open = null }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.chevronLeft)
                SerifTitle(list[selected].title, Modifier.padding(top = 12.dp, bottom = 10.dp), size = 24)
                MarkdownText(list[selected].body, color = P.ink)
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Eyebrow(stringResource(R.string.ref_eyebrow))
            SerifTitle(stringResource(R.string.ref_spec_title), Modifier.padding(top = 8.dp), size = 26)
            Subtle(stringResource(R.string.ref_spec_note), Modifier.padding(top = 8.dp, bottom = 6.dp))
            Kicker(stringResource(R.string.ref_sections), Modifier.padding(top = 6.dp))
        }
        itemsIndexed(list) { i, section -> PObject(section.title, null, PIcons.book, onClick = { open = i }) }
    }
}

/** The design reference, made of the components themselves (nothing here is a picture of them). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesignSystem() {
    var segment by rememberSaveable { mutableIntStateOf(0) }
    var toggle by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 8.dp, 18.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Eyebrow(stringResource(R.string.ref_eyebrow))
            Headline(stringResource(R.string.ref_ds_title), null, Modifier.padding(top = 8.dp), size = 26)
            Subtle(stringResource(R.string.ref_ds_body), Modifier.padding(top = 8.dp))
        }
        item {
            PCard {
                Kicker(stringResource(R.string.ref_ds_colors))
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "base" to P.base, "ink" to P.ink, "sub" to P.sub, "muted" to P.muted, "accent" to P.accent, "accent2" to P.accent2,
                        "accent3" to P.accent3, "good" to P.good, "amber" to P.amber, "danger" to P.danger, "night" to P.night, "highlight" to P.highlight,
                    ).forEach { (name, color) -> Swatch(name, color) }
                }
            }
        }
        item {
            PCard {
                Kicker(stringResource(R.string.ref_ds_type))
                SerifTitle(stringResource(R.string.ref_ds_type_display), Modifier.padding(top = 10.dp), size = 26)
                Headline("Workspaces,", "without boundaries.", Modifier.padding(top = 8.dp), size = 22)
                Text(stringResource(R.string.ref_ds_type_body), Modifier.padding(top = 8.dp), fontSize = 14.sp, color = P.ink, style = TextStyle(fontFeatureSettings = P.tabular))
                Subtle("bodySmall · 12/18", Modifier.padding(top = 4.dp))
                Eyebrow("Eyebrow · 10 sp · tracked", Modifier.padding(top = 8.dp))
            }
        }
        item {
            PCard {
                Kicker(stringResource(R.string.ref_ds_buttons))
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton(stringResource(R.string.ref_ds_primary), {}, icon = PIcons.check)
                    PButton(stringResource(R.string.ref_ds_alt), {}, style = ButtonStyle.Alt)
                    PButton(stringResource(R.string.ref_ds_ghost), {}, style = ButtonStyle.Ghost)
                    PButton(stringResource(R.string.ref_ds_warn), {}, style = ButtonStyle.Warn, tiny = true)
                    PButton(stringResource(R.string.ref_ds_danger), {}, style = ButtonStyle.Danger, tiny = true)
                    PButton(stringResource(R.string.ref_ds_primary), {}, tiny = true, enabled = false)
                }
            }
        }
        item {
            PCard {
                Kicker(stringResource(R.string.ref_ds_states))
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(stringResource(R.string.ref_ds_good), Tone.Good)
                    Tag(stringResource(R.string.ref_ds_neutral), Tone.Neutral)
                    Tag(stringResource(R.string.ref_ds_warn), Tone.Warn)
                    Tag(stringResource(R.string.ref_ds_danger), Tone.Danger)
                }
                Box(Modifier.padding(top = 10.dp)) { Callout(stringResource(R.string.ref_ds_callout), Tone.Neutral) }
                Box(Modifier.padding(top = 8.dp)) { Callout(stringResource(R.string.ref_ds_callout), Tone.Warn) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Fact("Input Lockdown", "≠ stopping running programs", Modifier.weight(1f))
                    Fact("Lifting", "≠ resuming any session", Modifier.weight(1f))
                }
            }
        }
        item {
            PCard {
                Kicker(stringResource(R.string.ref_ds_controls))
                Segmented(listOf(0 to "Chat", 1 to "Terminal", 2 to "Activity"), segment, { segment = it }, Modifier.padding(top = 10.dp), fill = true)
                PToggle(stringResource(R.string.ref_ds_toggle), stringResource(R.string.ref_ds_toggle_body), toggle, { toggle = it }, divider = false)
                PBar(0.62f)
                Box(Modifier.padding(top = 10.dp)) { PObject("Prepare the 0.1.0 release", "4 min ago", PIcons.bot, trailing = { Tag("Working", Tone.Good) }) }
            }
        }
        item {
            PCard(style = CardStyle.Soft) {
                Kicker(stringResource(R.string.ref_ds_flow))
                Steps(
                    listOf(
                        StepItem("Validate", "Candidate, remote ref, evidence", StepState.Done),
                        StepItem("Prepare · fence", "Durable intent", StepState.Active),
                        StepItem("Commit", "Conditional write", StepState.Wait),
                    ),
                )
                GraphFlow(listOf(GraphNode("Plan", "Completed", Tone.Good), GraphNode("Build", "Working", Tone.Good), GraphNode("Ship", "Queued")))
            }
        }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).background(color, RoundedCornerShape(12.dp)).border(1.dp, pc(0x3391AA90), RoundedCornerShape(12.dp)))
        Text(name, Modifier.padding(top = 4.dp), fontSize = 10.sp, color = P.sub)
    }
}
