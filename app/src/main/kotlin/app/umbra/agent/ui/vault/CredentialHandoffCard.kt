package app.umbra.agent.ui.vault

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.credentials.CredentialHandoffActivity
import app.umbra.agent.ui.porcelain.*

@Composable
internal fun CredentialHandoffCard(sessionId: String? = null) {
    val context = LocalContext.current
    val request by context.container.credentialHandoff.pending.collectAsStateWithLifecycle()
    context.container.credentialHandoff.problem?.let { Text(it, color = P.danger) }
    request?.takeIf { sessionId == null || it.sessionId == sessionId }?.let { pending -> PCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Kicker(stringResource(R.string.credential_title))
            Text("${pending.service} · ${pending.target.packageName}\n${pending.reason}", color = P.ink)
            Text(stringResource(R.string.credential_input_hint), color = P.sub)
            PButton(stringResource(R.string.credential_open), {
                context.startActivity(Intent(context, CredentialHandoffActivity::class.java).putExtra("request", pending.id))
            })
        }
    } }
}
