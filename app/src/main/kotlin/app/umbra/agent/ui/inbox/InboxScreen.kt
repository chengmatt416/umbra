package app.umbra.agent.ui.inbox

import app.umbra.agent.ui.sessionsFlow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.ButtonStyle
import app.umbra.agent.ui.porcelain.Eyebrow
import app.umbra.agent.ui.porcelain.Gap
import app.umbra.agent.ui.porcelain.PButton
import app.umbra.agent.ui.porcelain.SerifTitle
import app.umbra.agent.ui.porcelain.Subtle

/**
 * The Attention Inbox: every approval and question waiting for the user, across sessions. Answering here does not
 * switch the workspace (§3.1 Attention Focus); "Open session" does, when the user wants the context.
 */
@Composable
fun InboxScreen(onOpenSession: (String) -> Unit, onHistory: () -> Unit) {
    val container = LocalContext.current.container
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val questions by container.questions.pending.collectAsStateWithLifecycle()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val titles = sessions.associate { it.id to it.title }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 4.dp, 18.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Eyebrow("Attention inbox")
            SerifTitle("Approvals", Modifier.padding(top = 8.dp), size = 28)
            if (approvals.isEmpty() && questions.isEmpty()) {
                Subtle("Nothing is waiting for you. Approvals and questions from every session appear here.", Modifier.padding(top = 10.dp))
            }
        }
        items(approvals, key = { it.id }) { request ->
            ApprovalPanel(request, titles[request.sessionId], onDecision = { container.approvals.resolve(request.id, it) })
            Gap(4.dp)
            PButton("Open session", { onOpenSession(request.sessionId) }, style = ButtonStyle.Ghost, tiny = true)
        }
        items(questions, key = { it.request.id }) { pending ->
            QuestionPanel(pending, titles[pending.sessionId], onAnswer = { container.questions.answer(pending.request.id, it) })
            Gap(4.dp)
            PButton("Open session", { onOpenSession(pending.sessionId) }, style = ButtonStyle.Ghost, tiny = true)
        }
        item { PButton("Approval history", onHistory, style = ButtonStyle.Alt, tiny = true) }
    }
}
