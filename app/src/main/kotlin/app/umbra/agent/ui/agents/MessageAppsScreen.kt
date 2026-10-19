package app.umbra.agent.ui.agents

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.umbra.agent.R
import app.umbra.agent.agents.Channels
import app.umbra.agent.connectors.Method
import app.umbra.agent.container
import app.umbra.agent.notifications.NotificationPrivacy
import app.umbra.agent.notifications.NotificationRecord
import app.umbra.agent.ui.porcelain.*
import app.umbra.agent.ui.shell.*
import app.umbra.platform.android.UmbraNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun MessageAppsScreen(onBack: () -> Unit, onGo: (String) -> Unit) {
    val container = LocalContext.current.container
    val configs by container.connectors.settings.all.collectAsStateWithLifecycle()
    PorcelainPage(stringResource(R.string.ag_message_apps), onBack) {
        PageColumn {
            RowGroup {
                Channels.apps.forEach { app ->
                    val methods = configs.firstOrNull { it.appId == app.id }?.methods ?: container.connectors.settings.of(app.id).methods
                    Row(Modifier.fillMaxWidth().clickable { onGo("messages/${app.id}") }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(P.accent2), contentAlignment = Alignment.Center) {
                            Text(app.label.take(1), color = P.base, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(channelName(app.id), color = P.ink, fontSize = 15.sp)
                            Text(methods.mapNotNull(Method::of).joinToString(" · ") { container.connectors.methodName(it) }.ifEmpty { stringResource(R.string.cn_off) }, fontSize = 11.sp, color = P.sub)
                        }
                        PIcon(PIcons.chevronRight, size = 16.dp, tint = P.muted)
                    }
                }
            }
            NavRow(PIcons.bell, stringResource(R.string.ag_notification_reader)) { onGo("notifications") }
        }
    }
}

@Composable
fun MessageAppScreen(appId: String, onBack: () -> Unit, onGo: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val settings = container.connectors.settings
    val configs by settings.all.collectAsStateWithLifecycle()
    val config = configs.firstOrNull { it.appId == appId } ?: settings.of(appId)
    val change by container.notificationReader.changes.collectAsStateWithLifecycle()
    var chats by remember { mutableStateOf(emptyList<app.umbra.agent.connectors.ChatMessage>()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { container.connectors.messages.start() }
    LaunchedEffect(appId, change, config) {
        try { chats = container.connectors.messages.read(appId, null).distinctBy { it.chat }; loadError = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: e::class.simpleName }
    }
    PorcelainPage(channelName(appId), onBack) {
        PageColumn {
            RowGroup(stringResource(R.string.msg_methods)) {
                SwitchRow(PIcons.bell, stringResource(R.string.cn_m_notifications), stringResource(R.string.msg_notification_method), Method.NOTIFICATIONS.id in config.methods) { on ->
                    settings.update(appId) { it.copy(methods = if (on) (it.methods + Method.NOTIFICATIONS.id).distinct() else it.methods - Method.NOTIFICATIONS.id) }
                }
                if (appId == "sms") {
                    SwitchRow(PIcons.message, stringResource(R.string.cn_m_system), stringResource(R.string.msg_sms_method), Method.SYSTEM.id in config.methods) { on ->
                        settings.update(appId) { it.copy(methods = if (on) (it.methods + Method.SYSTEM.id).distinct() else it.methods - Method.SYSTEM.id) }
                        if (on) permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
                    }
                    NavRow(PIcons.key, stringResource(R.string.msg_sms_permission)) { permissions.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)) }
                }
                NavRow(PIcons.bell, stringResource(R.string.msg_notification_access)) { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
            if (appId == "imessage" || appId == "x") ApiSettings(appId)
            Text(stringResource(R.string.msg_other_methods_pending), fontSize = 12.sp, color = P.muted, lineHeight = 18.sp)
            RowGroup(stringResource(R.string.msg_auto_replies)) {
                loadError?.let { Text(it, Modifier.padding(16.dp), fontSize = 13.sp, color = P.danger) }
                if (chats.isEmpty()) Text(stringResource(R.string.msg_no_chats), Modifier.padding(16.dp), fontSize = 13.sp, color = P.sub)
                chats.forEach { chat ->
                    SwitchRow(null, chat.chat, stringResource(if (chat.group) R.string.msg_group_asks else R.string.msg_ask_first), chat.chat in config.autoChats && !chat.group) { on ->
                        if (!chat.group) settings.update(appId) { it.copy(autoChats = if (on) (it.autoChats + chat.chat).distinct() else it.autoChats - chat.chat) }
                    }
                }
            }
            NavRow(PIcons.bell, stringResource(R.string.ag_notification_reader)) { onGo("notifications") }
        }
    }
}

@Composable
private fun ApiSettings(appId: String) {
    val container = LocalContext.current.container
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(container.connectors.settings.of(appId).api["url"].orEmpty()) }
    var credential by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    RowGroup(stringResource(R.string.cn_m_api)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (appId == "imessage") PField(url, { url = it }, label = stringResource(R.string.msg_server_url), singleLine = true, mono = true)
            Text(stringResource(if (appId == "imessage") R.string.msg_blue_setup else R.string.msg_x_setup), fontSize = 12.sp, color = P.sub, lineHeight = 18.sp)
            PField(credential, { credential = it }, label = stringResource(if (appId == "imessage") R.string.msg_password else R.string.msg_user_token), singleLine = true, visualTransformation = PasswordVisualTransformation())
            PButton(stringResource(if (busy) R.string.msg_connecting else R.string.msg_check_save), {
                busy = true; result = null
                scope.launch {
                    try {
                        container.connectors.apis.check(appId, url.trim().trimEnd('/'), credential.trim())
                        container.secrets.put(app.umbra.agent.connectors.MessageApis.secret(appId), credential.trim())
                        container.connectors.settings.update(appId) { it.copy(api = if (appId == "imessage") mapOf("url" to url.trim().trimEnd('/')) else emptyMap(), methods = (listOf(Method.API.id) + it.methods).distinct()) }
                        credential = ""; error = false; result = container.context.getString(R.string.msg_connected)
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { error = true; result = e.message ?: e::class.simpleName }
                    finally { busy = false }
                }
            }, enabled = !busy && credential.isNotBlank(), tiny = true)
            if (container.connectors.apis.configured(appId)) PButton(stringResource(R.string.msg_disconnect_api), {
                container.secrets.remove(app.umbra.agent.connectors.MessageApis.secret(appId))
                container.connectors.settings.update(appId) { it.copy(methods = it.methods - Method.API.id) }
                result = null
            }, style = ButtonStyle.Ghost, tiny = true)
            result?.let { Text(it, color = if (error) P.danger else P.sub, fontSize = 12.sp) }
        }
    }
}

@Composable
fun NotificationReaderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val reader = context.container.notificationReader
    val version by reader.changes.collectAsStateWithLifecycle()
    var records by remember { mutableStateOf(emptyList<NotificationRecord>()) }
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            records = reader.records()
            apps = context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                .filter { it.packageName != context.packageName && (context.packageManager.getLaunchIntentForPackage(it.packageName) != null || records.any { r -> r.packageName == it.packageName }) }
                .map { it.packageName to context.packageManager.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
        }
    }
    PorcelainPage(stringResource(R.string.ag_ch_notifications), onBack) {
        PageColumn {
            RowGroup {
                NavRow(PIcons.bell, stringResource(R.string.msg_notification_access), stringResource(if (UmbraNotificationListener.instance != null) R.string.ag_allowed else R.string.ag_not_allowed)) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
            RowGroup(stringResource(R.string.msg_apps)) {
                apps.forEach { (pkg, label) -> NavRow(null, label, value = policyLabel(reader.policy(pkg))) { selected = pkg } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PButton(stringResource(R.string.msg_view_log), { log = !log }, tiny = true, style = ButtonStyle.Alt)
                PButton(stringResource(R.string.msg_forget_all), { forget = true }, tiny = true, style = ButtonStyle.Ghost)
            }
            Text(stringResource(R.string.msg_privacy, reader.retentionDays), fontSize = 12.sp, lineHeight = 18.sp, color = P.muted)
            RowGroup(stringResource(R.string.msg_retention)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 30).forEach { days -> Pill(stringResource(R.string.msg_days, days), reader.retentionDays == days) { reader.retentionDays = days } }
                }
            }
            if (log) RowGroup {
                if (records.isEmpty()) Text(stringResource(R.string.msg_empty_log), Modifier.padding(16.dp), fontSize = 13.sp, color = P.sub)
                records.take(100).forEach { r ->
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${r.app} · ${r.title}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = P.ink)
                        Text(r.text, fontSize = 13.sp, color = P.sub)
                    }
                }
            }
        }
    }
    selected?.let { pkg ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text(apps.firstOrNull { it.first == pkg }?.second ?: pkg) },
            text = { Column { NotificationPrivacy.Policy.entries.forEach { policy ->
                NavRow(null, policyLabel(policy), value = if (reader.policy(pkg) == policy) "✓" else null) { reader.setPolicy(pkg, policy); selected = null }
            } } },
            confirmButton = { PButton(stringResource(R.string.action_cancel), { selected = null }, style = ButtonStyle.Ghost) },
        )
    }
    if (forget) PConfirm(stringResource(R.string.msg_forget_all), stringResource(R.string.msg_forget_body), stringResource(R.string.msg_forget_all), { reader.forgetAll(); forget = false }, { forget = false })
}

@Composable
private fun policyLabel(policy: NotificationPrivacy.Policy): String = stringResource(when (policy) {
    NotificationPrivacy.Policy.USE -> R.string.msg_use
    NotificationPrivacy.Policy.CONTEXT -> R.string.msg_context
    NotificationPrivacy.Policy.IGNORE -> R.string.msg_ignore
})
