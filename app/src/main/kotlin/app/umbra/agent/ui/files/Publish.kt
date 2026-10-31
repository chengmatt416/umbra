@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.umbra.agent.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.agent.publish.Progress
import app.umbra.agent.publish.Publication
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.NavRow
import app.umbra.agent.ui.shell.PageColumn
import app.umbra.agent.ui.shell.PorcelainPage
import app.umbra.agent.ui.shell.RowGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * One-tap publishing of HTML artifacts (2026-10-28). The preview's Publish chip sends the page to the user's own
 * Cloudflare account (Pages, served on pages.dev) and opens its link to share. The first time, a sheet connects the
 * account; after that a tap publishes, and a page that changed since shows "Publish update". Work runs in the app's
 * scope, so leaving the preview does not cancel an upload; any preview of the same file shows where it is.
 */

/** Where one artifact's publish stands, for every preview of it. */
private sealed interface Run {
    data class Working(val progress: Progress) : Run
    data class Failed(val message: String) : Run
    data class Done(val publication: Publication, val seen: Boolean = false) : Run
}

private object PublishRuns {
    val state = MutableStateFlow<Map<String, Run>>(emptyMap())

    fun start(container: AppContainer, artifact: File) {
        val key = artifact.canonicalPath
        if (state.value[key] is Run.Working) return
        state.update { it + (key to Run.Working(Progress(Progress.Stage.SCANNING))) }
        container.scope.launch {
            val run = try {
                Run.Done(container.pagesPublisher.publish(artifact) { p -> state.update { it + (key to Run.Working(p)) } })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Run.Failed(e.message ?: e.javaClass.simpleName) }
            state.update { it + (key to run) }
        }
    }

    fun clear(artifact: File) = state.update { it - artifact.canonicalPath }
    fun seen(artifact: File) = state.update { all -> all[artifact.canonicalPath].let { if (it is Run.Done) all + (artifact.canonicalPath to it.copy(seen = true)) else all } }
}

private fun stageFraction(p: Progress): Float = when (p.stage) {
    Progress.Stage.SCANNING -> .04f
    Progress.Stage.HASHING -> .04f + .24f * (if (p.total == 0) 1f else p.completed / p.total.toFloat())
    Progress.Stage.PROJECT -> .32f
    Progress.Stage.UPLOADING -> .36f + .5f * (if (p.total == 0) 1f else p.completed / p.total.toFloat())
    Progress.Stage.DEPLOYING -> .9f
    Progress.Stage.COMPLETE -> 1f
}

@Composable
private fun stageText(p: Progress): String = when (p.stage) {
    Progress.Stage.SCANNING -> stringResource(R.string.pub_stage_scanning)
    Progress.Stage.HASHING -> stringResource(R.string.pub_stage_hashing, p.completed, p.total)
    Progress.Stage.PROJECT -> stringResource(R.string.pub_stage_project)
    Progress.Stage.UPLOADING -> if (p.total == 0) stringResource(R.string.pub_stage_nothing_new) else stringResource(R.string.pub_stage_uploading, p.completed, p.total)
    Progress.Stage.DEPLOYING -> stringResource(R.string.pub_stage_deploying)
    Progress.Stage.COMPLETE -> stringResource(R.string.pub_stage_done)
}

/**
 * The preview's Publish chips: "Publish" (one tap; the first time it asks to connect Cloudflare), while working its
 * progress, once live a "Live" chip that opens the link, and "Publish update" when the page changed since.
 */
@Composable
fun PublishChips(artifact: File) {
    val container = LocalContext.current.container
    val publisher = container.pagesPublisher
    val revision by publisher.revision.collectAsStateWithLifecycle()
    val runs by PublishRuns.state.collectAsStateWithLifecycle()
    val run = runs[artifact.canonicalPath]
    val published by produceState<Pair<Publication, Boolean>?>(null, artifact, revision) {
        value = withContext(Dispatchers.IO) {
            publisher.publicationFor(artifact)?.takeIf { it.publishedAt != null }?.let { it to publisher.changedSince(artifact, it) }
        }
    }
    var connect by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Publication?>(null) }
    // A publish that just finished opens its link once, wherever the preview is.
    LaunchedEffect(run) {
        if (run is Run.Done && !run.seen) { PublishRuns.seen(artifact); sheet = run.publication }
    }
    fun publish() { if (publisher.connected()) PublishRuns.start(container, artifact) else connect = true }

    when {
        run is Run.Working -> PButton(stringResource(R.string.pub_working, (stageFraction(run.progress) * 100).toInt()), {}, tiny = true, style = ButtonStyle.Alt, enabled = false, icon = PIcons.globe)
        published != null -> {
            val (publication, changed) = published!!
            PButton(stringResource(R.string.pub_live), { sheet = publication }, tiny = true, style = ButtonStyle.Alt, icon = PIcons.globe)
            if (changed) PButton(stringResource(R.string.pub_update), ::publish, tiny = true, icon = PIcons.arrowUp)
        }
        else -> PButton(stringResource(R.string.pub_publish), ::publish, tiny = true, icon = PIcons.globe)
    }
    if (connect) ConnectCloudflareSheet(publishAfter = true, onDismiss = { connect = false }, onConnected = { connect = false; PublishRuns.start(container, artifact) })
    sheet?.let { p ->
        PublishedSheet(p, changed = published?.takeIf { it.first.projectName == p.projectName }?.second == true,
            onUpdate = { sheet = null; publish() }, onDismiss = { sheet = null })
    }
}

/** Under the preview's chips: the publish's progress as a thin line and words, or why it failed with Try again. */
@Composable
fun PublishStatus(artifact: File) {
    val container = LocalContext.current.container
    val runs by PublishRuns.state.collectAsStateWithLifecycle()
    when (val run = runs[artifact.canonicalPath]) {
        is Run.Working -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val fraction by androidx.compose.animation.core.animateFloatAsState(stageFraction(run.progress), label = "publish")
            Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(P.line2)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(P.accent2))
            }
            Text(stageText(run.progress), fontSize = 12.sp, color = P.sub)
        }
        is Run.Failed -> Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Callout(stringResource(R.string.pub_failed, run.message), Tone.Danger)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.pub_retry), { PublishRuns.start(container, artifact) }, tiny = true, icon = PIcons.refresh)
                PButton(stringResource(R.string.action_cancel), { PublishRuns.clear(artifact) }, tiny = true, style = ButtonStyle.Ghost)
            }
        }
        else -> Unit
    }
}

/** A site that is live: its link, big, to share, copy or open; "Publish update" when the page changed; Take down. */
@Composable
fun PublishedSheet(publication: Publication, changed: Boolean, onUpdate: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val url = publication.publicUrl
    val host = Uri.parse(url).host ?: url
    val copied = stringResource(R.string.pub_copied)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(P.good.copy(alpha = .13f)).border(1.dp, P.line2, CircleShape), contentAlignment = Alignment.Center) {
                    PIcon(PIcons.globe, size = 19.dp, tint = P.good)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pub_live_eyebrow).uppercase(), color = P.good, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp)
                    Text(publishedLine(context, publication), fontSize = 12.5.sp, color = P.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SelectionContainer {
                Text(host, Modifier.semantics { heading() }, fontFamily = P.serif, fontSize = 26.sp, lineHeight = 31.sp, color = P.ink)
            }
            Text(stringResource(R.string.pub_anyone), fontSize = 13.5.sp, lineHeight = 19.sp, color = P.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.pub_share), {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }, Modifier.weight(1f), icon = PIcons.share)
                PButton(stringResource(R.string.pub_copy), {
                    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("link", url))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                }, style = ButtonStyle.Alt, icon = PIcons.copy)
                PButton(stringResource(R.string.pub_open), {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, style = ButtonStyle.Ghost, icon = PIcons.external)
            }
            if (changed && onUpdate != null) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.base2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.pub_changed), fontSize = 13.5.sp, lineHeight = 19.sp, color = P.ink)
                    PButton(stringResource(R.string.pub_update), onUpdate, tiny = true, icon = PIcons.arrowUp)
                }
            }
            error?.let { Callout(it, Tone.Danger) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.line2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pub_takedown_hint), Modifier.weight(1f), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted)
                PButton(stringResource(R.string.pub_takedown), { confirm = true }, tiny = true, style = ButtonStyle.Ghost, icon = PIcons.trash, enabled = !busy)
            }
        }
    }
    if (confirm) PConfirm(
        stringResource(R.string.pub_takedown_title), stringResource(R.string.pub_takedown_body, host), stringResource(R.string.pub_takedown),
        onConfirm = {
            confirm = false; busy = true; error = null
            scope.launch {
                try { container.pagesPublisher.takeDown(publication); onDismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                finally { busy = false }
            }
        },
        onDismiss = { confirm = false },
    )
}

private fun publishedLine(context: Context, p: Publication): String {
    val at = p.publishedAt?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
    return listOfNotNull(at, context.resources.getQuantityString(R.plurals.pub_files, p.fileCount, p.fileCount), Formatter.formatShortFileSize(context, p.bytes)).joinToString(" · ")
}

/**
 * Connecting the user's own Cloudflare account, once: what it is for, the three steps to make a token, two fields.
 * The token goes to the encrypted secret store and only ever to api.cloudflare.com.
 */
@Composable
fun ConnectCloudflareSheet(publishAfter: Boolean, onDismiss: () -> Unit, onConnected: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, containerColor = pc(0xFFFBFDF7)) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).imePadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.pub_connect_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, color = P.ink)
            Text(stringResource(R.string.pub_connect_body), fontSize = 13.5.sp, lineHeight = 20.sp, color = P.sub)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.base2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Step(1, stringResource(R.string.pub_connect_step1))
                PButton(stringResource(R.string.pub_connect_open_tokens), {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dash.cloudflare.com/profile/api-tokens")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, Modifier.padding(start = 32.dp), tiny = true, style = ButtonStyle.Ghost, icon = PIcons.external)
                Step(2, stringResource(R.string.pub_connect_step2))
                Step(3, stringResource(R.string.pub_connect_step3))
            }
            PField(account, { account = it.trim() }, label = stringResource(R.string.pub_account_id), singleLine = true, mono = true, enabled = !busy)
            PField(token, { token = it.trim() }, label = stringResource(R.string.pub_api_token), singleLine = true, mono = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            error?.let { Callout(it, Tone.Danger) }
            PButton(
                stringResource(if (busy) R.string.pub_connecting else if (publishAfter) R.string.pub_connect_and_publish else R.string.pub_connect),
                {
                    busy = true; error = null
                    scope.launch {
                        container.pagesPublisher.connect(account, token)
                            .onSuccess { name ->
                                context.getSharedPreferences("publish", Context.MODE_PRIVATE).edit().putString("account_name", name).apply()
                                onConnected()
                            }
                            .onFailure { error = it.message ?: it.javaClass.simpleName }
                        busy = false
                    }
                },
                Modifier.fillMaxWidth(), enabled = !busy && account.isNotBlank() && token.isNotBlank(), icon = PIcons.globe,
            )
            Text(stringResource(R.string.pub_connect_privacy), fontSize = 12.sp, lineHeight = 17.sp, color = P.muted)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).border(1.dp, P.line, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = P.sub, style = TextStyle(fontFeatureSettings = P.tabular))
        }
        Text(text, Modifier.weight(1f), fontSize = 13.5.sp, lineHeight = 19.sp, color = P.ink)
    }
}

/** Settings → Publishing: the connected account and every site published from this phone. */
@Composable
fun PublishingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val publisher = container.pagesPublisher
    val revision by publisher.revision.collectAsStateWithLifecycle()
    val connected = remember(revision) { publisher.connected() }
    val accountName = remember(revision) { context.getSharedPreferences("publish", Context.MODE_PRIVATE).getString("account_name", null) }
    val sites by produceState(emptyList<Publication>(), revision) { value = withContext(Dispatchers.IO) { runCatching { publisher.publications() }.getOrDefault(emptyList()) } }
    var connect by remember { mutableStateOf(false) }
    var disconnect by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Publication?>(null) }
    PorcelainPage(stringResource(R.string.pub_settings_title), onBack) {
        PageColumn {
            Text(stringResource(R.string.pub_settings_body), Modifier.padding(horizontal = 6.dp), fontSize = 13.sp, lineHeight = 19.sp, color = P.sub)
            RowGroup(stringResource(R.string.pub_cloudflare)) {
                if (connected) NavRow(PIcons.globe, accountName ?: stringResource(R.string.pub_connected), stringResource(R.string.pub_connected_sub),
                    value = stringResource(R.string.pub_disconnect), chevron = false) { disconnect = true }
                else NavRow(PIcons.globe, stringResource(R.string.pub_connect), stringResource(R.string.pub_connect_sub)) { connect = true }
            }
            val live = sites.filter { it.publishedAt != null }
            if (live.isNotEmpty()) RowGroup(stringResource(R.string.pub_sites, live.size)) {
                live.forEach { p -> NavRow(PIcons.link, Uri.parse(p.publicUrl).host ?: p.publicUrl, publishedLine(context, p)) { open = p } }
            }
        }
    }
    if (connect) ConnectCloudflareSheet(publishAfter = false, onDismiss = { connect = false }, onConnected = { connect = false })
    if (disconnect) PConfirm(
        stringResource(R.string.pub_disconnect_title), stringResource(R.string.pub_disconnect_body), stringResource(R.string.pub_disconnect),
        onConfirm = { disconnect = false; publisher.disconnect() }, onDismiss = { disconnect = false },
    )
    open?.let { p -> PublishedSheet(p, changed = false, onUpdate = null, onDismiss = { open = null }) }
}
