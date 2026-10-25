package app.umbra.platform.android

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.umbra.core.model.action.ErrorCode
import app.umbra.platform.api.BackendException
import app.umbra.platform.api.NotificationBackend
import app.umbra.platform.api.NotificationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Bound by the system once the user grants notification access to the app. */
class UmbraNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        activeNotifications.orEmpty().forEach { posted.tryEmit(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { posted.tryEmit(sbn) }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    companion object {
        private val posted = MutableSharedFlow<StatusBarNotification>(extraBufferCapacity = 256)
        val events = posted.asSharedFlow()
        @Volatile
        var instance: UmbraNotificationListener? = null
            private set

        /** Component name for `cmd notification allow_listener`. */
        fun componentName(packageName: String) = "$packageName/${UmbraNotificationListener::class.java.name}"
    }
}

/** Reads and acts on the status-bar notifications through [UmbraNotificationListener] (spec §14). */
class AndroidNotificationBackend(private val context: Context) : NotificationBackend {
    override val name = "notification_listener"

    override suspend fun isAvailable() = UmbraNotificationListener.instance != null

    private fun listener() = UmbraNotificationListener.instance
        ?: throw BackendException(ErrorCode.BACKEND_UNAVAILABLE, "Notification access is not granted to Umbra. Grant it in Settings > Notifications > Notification access.")

    private fun find(key: String): StatusBarNotification? = listener().activeNotifications?.firstOrNull { it.key == key }

    override suspend fun list(): List<NotificationInfo> = withContext(Dispatchers.Default) {
        listener().activeNotifications.orEmpty().map { sbn ->
            val n = sbn.notification
            val extras = n.extras
            NotificationInfo(
                key = sbn.key,
                packageName = sbn.packageName,
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString(),
                postedAtMs = sbn.postTime,
                actions = n.actions.orEmpty().map { it.title?.toString().orEmpty() },
                ongoing = sbn.isOngoing,
                clearable = sbn.isClearable,
                canReply = n.actions.orEmpty().any { a -> a.remoteInputs?.isNotEmpty() == true },
            )
        }.sortedByDescending { it.postedAtMs }
    }

    /** Sending another app's PendingIntent from the background needs an explicit opt-in on Android 14+. */
    private fun send(intent: PendingIntent, fillIn: Intent? = null): Boolean = try {
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        intent.send(context, 0, fillIn, null, null, null, options.toBundle())
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }

    override suspend fun open(key: String): Boolean = withContext(Dispatchers.Default) {
        val sbn = find(key) ?: return@withContext false
        val content = sbn.notification.contentIntent ?: return@withContext false
        app.umbra.platform.api.DispatchFence.checkCurrent()
        val sent = send(content)
        if (sent && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) {
            app.umbra.platform.api.DispatchFence.checkCurrent()
            listener().cancelNotification(key)
        }
        sent
    }

    override suspend fun dismiss(key: String): Boolean = withContext(Dispatchers.Default) {
        val sbn = find(key) ?: return@withContext false
        if (!sbn.isClearable) return@withContext false
        app.umbra.platform.api.DispatchFence.checkCurrent()
        listener().cancelNotification(key)
        true
    }

    override suspend fun pressAction(key: String, index: Int): Boolean = withContext(Dispatchers.Default) {
        val action = find(key)?.notification?.actions?.getOrNull(index) ?: return@withContext false
        app.umbra.platform.api.DispatchFence.checkCurrent()
        action.actionIntent?.let(::send) ?: false
    }

    override suspend fun reply(key: String, text: String): Boolean = withContext(Dispatchers.Default) {
        val action = find(key)?.notification?.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return@withContext false
        val inputs = action.remoteInputs
        val results = Bundle().also { b -> inputs.forEach { b.putCharSequence(it.resultKey, text) } }
        val fillIn = Intent()
        RemoteInput.addResultsToIntent(inputs, fillIn, results)
        app.umbra.platform.api.DispatchFence.checkCurrent()
        action.actionIntent?.let { send(it, fillIn) } ?: false
    }
}
