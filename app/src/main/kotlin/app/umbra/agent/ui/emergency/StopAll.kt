package app.umbra.agent.ui.emergency

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import app.umbra.agent.ui.porcelain.P
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.ui.porcelain.PConfirm
import app.umbra.agent.ui.sessionsFlow
import app.umbra.core.session.SessionState
import kotlinx.coroutines.launch

/**
 * The one "stop everything" there is (UX report C-04, X-05): Settings, Emergency Control and the notification all
 * end here, all call the same [app.umbra.core.orchestrator.AgentSupervisor.stopAll], and the confirmation counts what
 * it ends: working and paused tasks stop, queued ones leave the queue.
 */
@Composable
fun StopAllConfirm(onDismiss: () -> Unit, onDone: (String) -> Unit = {}) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val container = context.container
    val scope = rememberCoroutineScope()
    val sessions by remember { container.sessionStore.sessionsFlow() }.collectAsStateWithLifecycle(emptyList())
    val working = sessions.count { it.state == SessionState.RUNNING || it.state == SessionState.WAITING_APPROVAL }
    val paused = sessions.count { it.state == SessionState.PAUSED }
    val queued = sessions.count { it.state == SessionState.CREATED && it.queuedAtMs != null }
    val nothing = working + paused + queued == 0
    PConfirm(
        title = stringResource(R.string.stop_all_title),
        body = stringResource(if (nothing) R.string.stop_all_nothing else R.string.stop_all_body),
        confirmLabel = stringResource(R.string.stop_all_confirm),
        confirmEnabled = !nothing,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            scope.launch {
                container.supervisor.stopAll()
                onDone(resources.getString(R.string.stop_all_done))
            }
        },
        extra = if (nothing) null else {
            {
                // Only what it actually ends, one line each.
                listOf(R.plurals.stop_all_working to working, R.plurals.stop_all_paused to paused, R.plurals.stop_all_queued to queued)
                    .filter { it.second > 0 }
                    .forEach { (res, n) -> Text("• " + pluralStringResource(res, n, n), fontSize = 14.sp, lineHeight = 20.sp, color = P.ink) }
            }
        },
    )
}
