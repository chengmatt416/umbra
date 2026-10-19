package app.umbra.agent.notifications

import android.app.Notification
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.service.notification.StatusBarNotification
import app.umbra.agent.AppContainer
import app.umbra.agent.agents.Channels
import app.umbra.agent.agents.Incoming
import app.umbra.agent.agents.IncomingSource
import app.umbra.core.model.UmbraJson
import app.umbra.platform.android.UmbraNotificationListener
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class NotificationRecord(
    val id: String,
    val key: String,
    val packageName: String,
    val app: String,
    val title: String,
    val text: String,
    val atMs: Long,
    val chat: String,
    val group: Boolean,
    val canReply: Boolean,
    val actions: List<String>,
)

/** SQLite contains only AES-GCM ciphertext and expiry times; the key stays in the Android Keystore. */
class NotificationReader(private val container: AppContainer) : IncomingSource {
    private val context = container.context
    private val prefs = context.getSharedPreferences("notification-policy", 0)
    private val db by lazy {
        context.openOrCreateDatabase("notifications.db", 0, null).apply {
            execSQL("CREATE TABLE IF NOT EXISTS entries (id TEXT PRIMARY KEY, at_ms INTEGER NOT NULL, body TEXT NOT NULL)")
            execSQL("CREATE INDEX IF NOT EXISTS entries_time ON entries(at_ms)")
        }
    }
    private val changed = MutableStateFlow(0L)
    val changes = changed.asStateFlow()
    private var started = false
    var retentionDays: Int
        get() = prefs.getInt("retention", 7).coerceIn(1, 30)
        set(value) { prefs.edit().putInt("retention", value.coerceIn(1, 30)).apply(); prune(); changed.value++ }

    fun policy(pkg: String): NotificationPrivacy.Policy = prefs.getString("policy:$pkg", null)?.let {
        runCatching { NotificationPrivacy.Policy.valueOf(it) }.getOrNull()
    } ?: NotificationPrivacy.defaultPolicy(pkg, Channels.ofPackage(pkg) != null)

    @Synchronized
    fun setPolicy(pkg: String, policy: NotificationPrivacy.Policy) {
        prefs.edit().putString("policy:$pkg", policy.name).apply()
        // Remove the old content too: a newly ignored app must disappear from future digests.
        if (policy == NotificationPrivacy.Policy.IGNORE) {
            val ids = db.query("entries", arrayOf("id", "body"), null, null, null, null, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val record = UmbraJson.decodeFromString(NotificationRecord.serializer(), container.secrets.decrypt(cursor.getString(1)))
                        if (record.packageName == pkg) add(cursor.getString(0))
                    }
                }
            }
            ids.forEach { db.delete("entries", "id = ?", arrayOf(it)) }
        }
        changed.value++
    }

    fun start() {
        if (started) return
        started = true
        container.agents.addSource(this)
        container.scope.launch(Dispatchers.IO) {
            UmbraNotificationListener.events.collect { sbn -> runCatching { capture(sbn) } }
        }
        container.scope.launch(Dispatchers.IO) {
            UmbraNotificationListener.instance?.activeNotifications.orEmpty().forEach { runCatching { capture(it) } }
        }
    }

    private suspend fun capture(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == context.packageName || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val policy = policy(sbn.packageName)
        if (policy == NotificationPrivacy.Policy.IGNORE) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (text.isBlank()) return
        val hasCode = NotificationPrivacy.containsCode("$title $text")
        if (prefs.getString("policy:${sbn.packageName}", null) == null && hasCode) return
        val cleanTitle = NotificationPrivacy.redact(title, hasCode).take(512)
        val cleanText = NotificationPrivacy.redact(text, hasCode).take(16_384)
        val channel = Channels.ofPackage(sbn.packageName)
        val chat = n.shortcutId?.let { NotificationPrivacy.redact(it, hasCode) } ?: cleanTitle
        val id = MessageDigest.getInstance("SHA-256").digest("${sbn.packageName}\u0000$chat\u0000${sbn.postTime}\u0000$cleanText".toByteArray()).joinToString("") { "%02x".format(it) }
        val record = NotificationRecord(id, sbn.key, sbn.packageName, channel?.id ?: sbn.packageName, cleanTitle, cleanText,
            sbn.postTime, chat, extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true),
            n.actions.orEmpty().any { a -> a.remoteInputs.orEmpty().any { it.allowFreeFormInput } },
            n.actions.orEmpty().map { NotificationPrivacy.redact(it.title.toString(), hasCode) })
        val inserted = synchronized(this) {
            prune()
            db.insertWithOnConflict("entries", null, ContentValues().apply {
                put("id", id); put("at_ms", record.atMs)
                put("body", container.secrets.encrypt(UmbraJson.encodeToString(NotificationRecord.serializer(), record)))
            }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
        if (!inserted) return
        changed.value++
        // Code-bearing notifications can be kept redacted when explicitly enabled, but never wake a model.
        if (policy == NotificationPrivacy.Policy.USE && !hasCode) {
            val incoming = incoming(record)
            if (incoming != null) container.agents.onIncoming(incoming)
        }
    }

    private fun incoming(r: NotificationRecord): Incoming? {
        if (policy(r.packageName) != NotificationPrivacy.Policy.USE) return null
        val channel = Channels.ofPackage(r.packageName)
        if (channel != null && app.umbra.agent.connectors.Method.NOTIFICATIONS !in container.connectors.settings.methods(channel.id)) return null
        return Incoming(r.id, if (channel == null) Incoming.Kind.NOTIFICATION else Incoming.Kind.MESSAGE,
            r.app, channel?.label ?: r.packageName, r.chat, r.title, r.text, r.atMs, r.group)
    }

    override suspend fun since(afterMs: Long): List<Incoming> = records(afterMs).mapNotNull(::incoming)

    @Synchronized
    fun records(afterMs: Long = 0, limit: Int = 500): List<NotificationRecord> {
        prune()
        return db.query("entries", arrayOf("body"), "at_ms > ?", arrayOf(afterMs.toString()), null, null, "at_ms DESC", limit.coerceIn(1, 2000).toString()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val record = UmbraJson.decodeFromString(NotificationRecord.serializer(), container.secrets.decrypt(c.getString(0)))
                    if (policy(record.packageName) != NotificationPrivacy.Policy.IGNORE) add(record)
                }
            }
        }
    }

    @Synchronized
    fun prune() { db.delete("entries", "at_ms < ?", arrayOf((System.currentTimeMillis() - retentionDays * 86_400_000L).toString())) }

    @Synchronized
    fun forgetAll() { db.delete("entries", null, null); changed.value++ }
}
