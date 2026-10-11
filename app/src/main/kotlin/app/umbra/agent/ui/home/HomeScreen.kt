package app.umbra.agent.ui.home

import app.umbra.agent.ui.sessionsFlow
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.ui.CapabilityProbe
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Callout
import app.umbra.agent.ui.porcelain.CardStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.FoldedOrb
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.P
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.PCard
import app.umbra.agent.ui.porcelain.PIcons
import app.umbra.agent.ui.porcelain.PObject
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle
import app.umbra.agent.ui.porcelain.Tag
import app.umbra.agent.ui.porcelain.Tone
import app.umbra.agent.ui.sessions.sessionTone
import app.umbra.agent.ui.sessions.stateText
import app.umbra.agent.ui.softly
import app.umbra.core.session.SessionState
import kotlinx.coroutines.launch

private val EXAMPLES = listOf(
    "Turn on Do Not Disturb and tell me what changed",
    "Find out which apps use the most storage",
    "Create a small Python script that renames my photos by date, and test it",
    "Check my battery health and summarise what is draining it",
)

/**
 * Home: the Folded Orb, the question "What shall we make possible?", the Smart Composer that starts new work, and
 * the tasks that are under way. Nothing here pretends a task runs: the cards show the session store's states.
 */
@Composable
fun HomeScreen(onOpenSession: (String) -> Unit, onSetup: () -> Unit, onInbox: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val projects by container.projects.all.collectAsStateWithLifecycle()
    val homeProject by container.ui.homeProject.collectAsStateWithLifecycle()
    val guestState by container.guest.state.collectAsStateWithLifecycle()
    val probe = remember { CapabilityProbe(container) }
    val settings by container.settings.flow.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(guestState, settings) {
        softly("the set-up check") { missing = probe.probe().filter { it.essential && it.status != app.umbra.agent.ui.CapabilityStatus.OK }.map { it.title } }
    }

    val working = sessions.any { it.state == SessionState.RUNNING }
    val active = sessions.filter { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL || it.state == SessionState.PAUSED }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 18.dp)) {
        Eyebrow("Your AI companion", Modifier.padding(top = 6.dp))
        Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.BottomCenter) {
            FoldedOrb(Modifier.fillMaxSize(), working = working)
            Text(if (working) "Working in the background" else "At your pace", fontSize = 11.sp, letterSpacing = 1.sp, color = Color(0xFF73886F))
        }
        Gap(10.dp)
        SerifTitle("What shall we\nmake possible?", size = 30)
        Subtle("Ask something, or continue a long-running session. Umbra works in the background and asks before anything risky.", Modifier.padding(top = 8.dp))
        Gap(14.dp)

        if (missing.isNotEmpty()) {
            Callout("Finish setting up so Umbra can work on this phone. Missing: ${missing.joinToString()}.", Tone.Warn)
            Gap(8.dp)
            PButton("Set up", onSetup, style = ButtonStyle.Alt, tiny = true)
            Gap(14.dp)
        }

        SmartComposer(
            projectName = homeProject?.let { projects[it]?.name },
            projects = projects.values.sortedBy { it.createdAtMs }.map { it.id to it.name },
            onProject = { container.ui.setHomeProject(it) },
            sending = sending,
            onSnapshot = {
                // Circle something in the app behind Umbra: step aside, select, and come back to the composer there.
                if (!android.provider.Settings.canDrawOverlays(context) && !app.umbra.platform.android.UmbraAccessibilityService.connected.value) {
                    error = "Selecting from the screen needs the draw-over-apps permission or Umbra's accessibility service."
                } else {
                    (context as? Activity)?.moveTaskToBack(true)
                    container.capsule.startSelection(delayMs = 450)
                }
            },
            onSend = { text ->
                sending = true
                scope.launch {
                    try {
                        val id = container.capsule.screen.send(text, sessionId = null, projectId = homeProject?.takeIf { projects.containsKey(it) }, fromCapsule = false)
                        error = null
                        onOpenSession(id)
                    } catch (e: Exception) {
                        error = e.message ?: e::class.simpleName
                    } finally {
                        sending = false
                    }
                }
            },
        )
        error?.let { Gap(10.dp); Callout(it, Tone.Danger) }
        if (sessions.isEmpty()) {
            Gap(12.dp)
            EXAMPLES.forEach { example ->
                Gap(6.dp)
                PObject(example, null, PIcons.spark, onClick = { container.ui.saveDraft(example) })
            }
        }

        if (approvals.isNotEmpty() || questions.isNotEmpty()) {
            Gap(14.dp)
            val n = approvals.size + questions.size
            PCard(style = CardStyle.Soft, onClick = onInbox) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$n thing${if (n > 1) "s" else ""} waiting for you", Modifier.weight(1f), fontSize = 14.sp, color = P.ink)
                    Tag("Inbox", Tone.Warn)
                }
            }
        }

        active.forEach { s ->
            Gap(14.dp)
            PCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Active task", Modifier.weight(1f), fontSize = 12.sp, color = P.sub)
                    Tag(stateText(s), sessionTone(s))
                }
                Text(s.title, Modifier.padding(top = 8.dp), fontSize = 16.sp, color = P.ink)
                val step = s.plan.firstOrNull { it.status == "active" } ?: s.plan.lastOrNull { it.status == "done" }
                (step?.text ?: s.lastError ?: s.request.takeIf { it != s.title })?.let { Subtle(it, Modifier.padding(top = 4.dp), maxLines = 2) }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PButton("View session", { onOpenSession(s.id) }, style = ButtonStyle.Alt, tiny = true)
                    if (approvals.any { it.sessionId == s.id } || questions.any { it.sessionId == s.id }) PButton("Review approval", onInbox, style = ButtonStyle.Alt, tiny = true)
                }
            }
        }

        val recent = sessions.filter { it.state.isTerminal || it.state == SessionState.CREATED }.take(3)
        if (recent.isNotEmpty()) {
            Gap(20.dp)
            Eyebrow("Recent")
            recent.forEach { s ->
                Gap(8.dp)
                PObject(s.title, stateText(s), PIcons.bot, onClick = { onOpenSession(s.id) })
            }
        }
        Gap(28.dp)
    }
}

/**
 * The Smart Composer (`.smart-composer`): one draft and one set of context chips shared with the Capsule, the project
 * new work goes to, Snapshot selection, the microphone (speech recognition when the phone has it) and Send. The
 * keyboard opens only when the field is tapped.
 */
@Composable
fun SmartComposer(
    projectName: String?,
    projects: List<Pair<String, String>>,
    onProject: (String?) -> Unit,
    sending: Boolean,
    onSnapshot: () -> Unit,
    onSend: (String) -> Unit,
) {
    val container = LocalContext.current.container
    val context = LocalContext.current
    val draft by container.ui.draft.collectAsStateWithLifecycle()
    val chips by container.ui.draftContext.collectAsStateWithLifecycle()
    var projectMenu by remember { mutableStateOf(false) }
    val canListen = remember { SpeechRecognizer.isRecognitionAvailable(context) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (heard.isNotBlank()) container.ui.saveDraft(listOf(draft, heard).filter { it.isNotBlank() }.joinToString(" "))
        }
    }
    val shape = RoundedCornerShape(21.dp)
    Column(
        Modifier.fillMaxWidth().shadow(8.dp, shape, ambientColor = Color(0x22455F49), spotColor = Color(0x33455F49)).clip(shape)
            .background(Color(0xFFF7FAF4)).border(1.dp, Color(0xB5FFFFFF), shape).padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // The same context the Capsule's composers hold (§3.1): a screen selection made over another app shows here too.
        chips.forEach { chip -> app.umbra.agent.capsule.ChipRow(chip) { container.ui.removeContext(chip.id) } }
        if (chips.isNotEmpty()) Gap(8.dp)
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            if (draft.isEmpty()) Text("Ask Umbra to do anything…", fontSize = 15.sp, color = Color(0xFF9AAA98))
            BasicTextField(
                draft,
                { container.ui.saveDraft(it) },
                Modifier.fillMaxWidth().semantics { contentDescription = "Message" },
                textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P.ink),
                cursorBrush = SolidColor(P.accent2),
                maxLines = 8,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                PButton(projectName ?: "Independent", { projectMenu = true }, style = ButtonStyle.Ghost, tiny = true, icon = PIcons.layers)
                DropdownMenu(projectMenu, { projectMenu = false }) {
                    DropdownMenuItem(text = { Text("Independent session") }, onClick = { projectMenu = false; onProject(null) })
                    projects.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { projectMenu = false; onProject(id) }) }
                }
            }
            Box(Modifier.weight(1f))
            PButton("", onSnapshot, style = ButtonStyle.Alt, tiny = true, icon = PIcons.target, contentDescription = "Select from the screen")
            Box(Modifier.padding(start = 6.dp))
            if (canListen) {
                PButton("", {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
                }, style = ButtonStyle.Alt, tiny = true, icon = PIcons.mic, contentDescription = "Speak")
                Box(Modifier.padding(start = 6.dp))
            }
            PButton("", { if (draft.isNotBlank()) onSend(draft.trim()) }, tiny = true, icon = PIcons.send, enabled = draft.isNotBlank() && !sending, contentDescription = "Send")
        }
    }
}
