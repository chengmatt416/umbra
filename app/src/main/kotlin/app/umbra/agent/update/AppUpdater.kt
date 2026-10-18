package app.umbra.agent.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.umbra.agent.MainActivity
import app.umbra.agent.R
import app.umbra.agent.data.AppSettings
import app.umbra.platform.api.ShellBackend
import app.umbra.platform.shell.ShellPackages
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Updates without a store. The site publishes update.json for the current release (website/build.mjs) and serves the
 * APK from Cloudflare R2 (/download). Umbra checks at start and twice a day, downloads a newer APK (resuming where it
 * stopped; on Wi-Fi only unless the user says otherwise), and checks size, SHA-256, package name, version and that the
 * APK is signed with this app's own key. It installs when no task runs: silently through the helper or root
 * (`pm install`), with Umbra out of sight; otherwise the system's installer asks the user, from a notification.
 */
class AppUpdater(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    /** The helper or root, which install without asking. Never the app's own shell: it cannot install. */
    private val privileged: ShellBackend,
    /** Something runs that the restart an update brings would interrupt. */
    private val busy: suspend () -> Boolean,
    private val appVisible: StateFlow<Boolean>,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAtMs: Long) : State

        /** Newer than this version and not downloaded yet: waiting for Wi-Fi, or automatic updates are off. */
        data class Available(val update: UpdateManifest) : State
        data class Downloading(val update: UpdateManifest, val bytes: Long) : State

        /** Downloaded and verified. */
        data class Ready(val update: UpdateManifest) : State
        data class Installing(val update: UpdateManifest) : State

        /** The system's installer waits for the user. */
        data class Confirm(val update: UpdateManifest) : State
        data class Failed(val message: String, val update: UpdateManifest? = null) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    private val dir = File(context.filesDir, "updates")
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val work = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Volatile
    private var confirmIntent: Intent? = null

    private fun packageInfo(flags: Long = 0): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags))

    val installedVersionCode: Long get() = packageInfo().longVersionCode
    val installedVersionName: String get() = packageInfo().versionName.orEmpty()

    fun start() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel_updates), NotificationManager.IMPORTANCE_DEFAULT))
        restore()
        UpdateCheckJob.schedule(context)
        scope.launch {
            delay(FIRST_CHECK_DELAY_MS)
            while (true) {
                if (settings.value.autoUpdate) check(userAsked = false)
                // A failed or waiting download is tried again soon; otherwise the next look is in half a day.
                delay(if (state.value is State.Failed || state.value is State.Available) RETRY_MS else CHECK_EVERY_MS)
            }
        }
        scope.launch {
            while (true) {
                delay(QUIET_POLL_MS)
                runCatching { installWhenQuiet() }.onFailure { Log.w(TAG, "install when quiet", it) }
            }
        }
    }

    /** Looks for a newer release and downloads it. [userAsked]: on any network, even with automatic updates off. */
    suspend fun check(userAsked: Boolean) {
        if (!work.tryLock()) return
        try {
            val now = state.value
            if (now is State.Installing || now is State.Confirm) return
            withContext(Dispatchers.IO) { checkBlocking(userAsked) }
        } finally {
            work.unlock()
        }
        installWhenQuiet()
    }

    fun checkNow() {
        scope.launch { check(userAsked = true) }
    }

    /**
     * What the launch-time question offers: a newer release than this one, read from update.json without downloading
     * anything; null when there is none or the site cannot be reached.
     */
    suspend fun offered(): UpdateManifest? = withContext(Dispatchers.IO) {
        runCatching { fetch(manifestUrl()) }.getOrNull()?.takeIf { UpdatePolicy.isNewer(it, installedVersionCode) && UpdatePolicy.problem(it) == null }
    }

    /**
     * The person said "Update": download now on any network, then install as soon as no task runs — silently through
     * the helper or root, and Umbra opens again by itself ([onPackageReplaced]); without them the system asks once.
     */
    fun updateNow() {
        prefs.edit().putBoolean(KEY_REOPEN, true).apply()
        scope.launch {
            check(userAsked = true)
            while (true) {
                when (val s = state.value) {
                    is State.Ready -> if (!busy()) { install(s.update); return@launch }
                    is State.Checking, is State.Downloading, is State.Available -> Unit
                    else -> return@launch
                }
                delay(5_000)
            }
        }
    }

    /** Installs the downloaded update now, as the user asked: Umbra closes and the new version starts. */
    fun installNow() {
        scope.launch {
            when (val s = state.value) {
                is State.Ready -> install(s.update)
                is State.Confirm -> showConfirm()
                else -> Unit
            }
        }
    }

    private fun checkBlocking(userAsked: Boolean) {
        val previous = state.value
        mutableState.value = State.Checking
        val manifestUrl = manifestUrl()
        val update = try {
            fetch(manifestUrl)
        } catch (e: Exception) {
            Log.w(TAG, "check $manifestUrl", e)
            // A downloaded update stays ready when the site cannot be reached.
            if (previous is State.Ready) {
                mutableState.value = previous
                return
            }
            // The details are in the log; the person reads why in plain words (a parser's message means nothing to them).
            val unusable = e is IllegalArgumentException || e.message?.startsWith("update.json:") == true
            val reason = context.getString(if (unusable) R.string.upd_err_no_manifest else R.string.upd_err_offline)
            mutableState.value = State.Failed(reason, (previous as? State.Available)?.update)
            return
        }
        if (!UpdatePolicy.isNewer(update, installedVersionCode)) {
            mutableState.value = State.UpToDate(System.currentTimeMillis())
            return
        }
        val apk = apkFile(update)
        if (apk.isFile && verifiedBefore(apk, update)) {
            mutableState.value = State.Ready(update)
            return
        }
        val prefs = settings.value
        if (!userAsked && (!prefs.autoUpdate || prefs.updateOnWifiOnly && metered())) {
            mutableState.value = State.Available(update)
            return
        }
        try {
            download(update, manifestUrl, apk)
        } catch (e: Exception) {
            Log.w(TAG, "download ${update.file}", e)
            mutableState.value = State.Failed(e.message ?: e.javaClass.simpleName, update)
        }
    }

    private fun fetch(url: String): UpdateManifest =
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("update.json: HTTP ${r.code}")
            val m = UpdatePolicy.parse(r.body.string())
            UpdatePolicy.problem(m)?.let { throw IOException("update.json: $it") }
            m
        }

    private fun download(update: UpdateManifest, manifestUrl: String, apk: File) {
        dir.mkdirs()
        val part = File(dir, apk.name + ".part")
        // Only one update is kept: a file of another version is of no use any more.
        dir.listFiles().orEmpty().filter { it != part && it != apk }.forEach { it.delete() }
        var have = if (part.isFile) part.length() else 0L
        if (have > update.bytes) {
            part.delete()
            have = 0
        }
        val needed = update.bytes - have + SPACE_MARGIN
        if (dir.usableSpace < needed) throw IOException(context.getString(R.string.upd_no_space, mib(needed)))
        mutableState.value = State.Downloading(update, have)
        if (have < update.bytes) {
            val request = Request.Builder().url(UpdatePolicy.apkUrl(manifestUrl, update.url)).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(request).execute().use { r ->
                when {
                    r.code == 206 && have > 0 -> Unit
                    r.code == 200 -> have = 0
                    else -> throw IOException("${update.file}: HTTP ${r.code}")
                }
                FileOutputStream(part, have > 0).use { out ->
                    val input = r.body.byteStream()
                    val buffer = ByteArray(256 * 1024)
                    var shown = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        have += n
                        if (have > update.bytes) throw IOException("${update.file}: more than ${update.bytes} bytes")
                        val t = SystemClock.elapsedRealtime()
                        if (t - shown > 500) {
                            mutableState.value = State.Downloading(update, have)
                            shown = t
                        }
                    }
                }
            }
        }
        if (part.length() != update.bytes) throw IOException("${update.file}: ${part.length()} of ${update.bytes} bytes")
        val digest = sha256(part)
        if (digest != update.sha256) {
            part.delete()
            throw IOException("${update.file}: SHA-256 $digest ≠ ${update.sha256}")
        }
        verifyArchive(part, update)?.let {
            part.delete()
            throw IOException("${update.file}: $it")
        }
        if (!part.renameTo(apk)) throw IOException("rename ${part.name}")
        this.prefs.edit()
            .putString(KEY_READY, UpdatePolicy.encode(update))
            .putString(KEY_VERIFIED, marker(apk, update))
            .apply()
        mutableState.value = State.Ready(update)
    }

    /** Why the downloaded file must not be installed, or null: it is this app, of that version, signed with our key. */
    private fun verifyArchive(file: File, update: UpdateManifest): String? {
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: return "not an APK"
        if (archive.packageName != context.packageName) return "package ${archive.packageName}"
        if (archive.longVersionCode != update.versionCode) return "versionCode ${archive.longVersionCode} ≠ ${update.versionCode}"
        val theirs = signers(archive)
        if (theirs.isEmpty() || theirs != signers(packageInfo(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))) return "signed with another key"
        return null
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()

    /** A downloaded update installs once nothing runs and Umbra is out of sight; without the helper or root, the user is asked. */
    private suspend fun installWhenQuiet() {
        val ready = state.value as? State.Ready ?: return
        if (!settings.value.autoUpdate || busy()) return
        if (canInstallSilently()) {
            if (!appVisible.value) install(ready.update)
        } else if (prefs.getLong(KEY_NOTIFIED, 0) != ready.update.versionCode) {
            prefs.edit().putLong(KEY_NOTIFIED, ready.update.versionCode).apply()
            notify(context.getString(R.string.upd_notif_ready_title, ready.update.version), context.getString(R.string.upd_notif_ready_body), install = true)
        }
    }

    private suspend fun canInstallSilently(): Boolean = runCatching { privileged.isAvailable() }.getOrDefault(false)

    private suspend fun install(update: UpdateManifest) {
        if (!work.tryLock()) return
        try {
            val apk = apkFile(update)
            if (!apk.isFile) {
                mutableState.value = State.Idle
                return
            }
            mutableState.value = State.Installing(update)
            prefs.edit().putLong(KEY_INSTALLING, update.versionCode).commit()
            if (canInstallSilently()) {
                // When it works, the system ends this process during the call and the new version starts in its place.
                val silent = runCatching { withContext(Dispatchers.IO) { ShellPackages(privileged).install(apk.path, replace = true, grantAll = false, userId = android.os.Process.myUid() / 100_000) } }
                if (silent.isSuccess) return
                Log.w(TAG, "pm install", silent.exceptionOrNull())
            }
            withContext(Dispatchers.IO) { installWithSystem(update, apk) }
        } finally {
            work.unlock()
        }
    }

    /** Hands the APK to the system's installer, which asks the user (its answer comes to [UpdateStatusReceiver]). */
    private fun installWithSystem(update: UpdateManifest, apk: File) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 1 shl 20) }
                    session.fsync(out)
                }
                val status = Intent(context, UpdateStatusReceiver::class.java).setPackage(context.packageName)
                session.commit(PendingIntent.getBroadcast(context, id, status, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE).intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "system install", e)
            mutableState.value = State.Failed(e.message ?: e.javaClass.simpleName, update)
        }
    }

    /** The system installer's answer. */
    fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val update = when (val s = state.value) {
            is State.Installing -> s.update
            is State.Confirm -> s.update
            is State.Ready -> s.update
            else -> null
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmIntent = confirm
                if (update != null) mutableState.value = State.Confirm(update)
                if (appVisible.value) showConfirm()
                else if (update != null) notify(context.getString(R.string.upd_notif_ready_title, update.version), context.getString(R.string.upd_notif_ready_body), install = true)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The user said no: it stays ready, and is not offered again by itself until the next version.
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableState.value = update?.let { State.Ready(it) } ?: State.Idle
            else -> mutableState.value = State.Failed(message ?: "status $status", update)
        }
    }

    private fun showConfirm() {
        val intent = confirmIntent ?: return
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Log.w(TAG, "confirm", it) }
    }

    /** After an update (MY_PACKAGE_REPLACED): says so if it was ours, and clears the downloaded file. */
    fun onPackageReplaced() {
        val expected = prefs.getLong(KEY_INSTALLING, 0)
        prefs.edit().remove(KEY_INSTALLING).remove(KEY_NOTIFIED).apply()
        restore()
        notifications.cancel(NOTIFICATION_ID)
        if (expected != 0L && expected == installedVersionCode) {
            notify(context.getString(R.string.upd_notif_done_title, installedVersionName), context.getString(R.string.upd_notif_done_body), install = false)
            // Asked for from the app: it opens again by itself.
            if (prefs.getBoolean(KEY_REOPEN, false)) {
                prefs.edit().remove(KEY_REOPEN).apply()
                runCatching {
                    context.startActivity(Intent(context, app.umbra.agent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                }.onFailure { Log.w(TAG, "reopen", it) }
            }
        }
    }

    /** Drops downloads this version made useless; a verified newer one is ready again after a restart. */
    private fun restore() {
        val installed = installedVersionCode
        dir.listFiles().orEmpty().forEach { f ->
            val code = UpdatePolicy.versionCodeOf(f.name)
            if (code == null || code <= installed) f.delete()
        }
        val ready = prefs.getString(KEY_READY, null)?.let { runCatching { UpdatePolicy.parse(it) }.getOrNull() }
        if (ready != null && ready.versionCode > installed && apkFile(ready).isFile && verifiedBefore(apkFile(ready), ready)) {
            mutableState.value = State.Ready(ready)
        } else {
            prefs.edit().remove(KEY_READY).remove(KEY_VERIFIED).apply()
        }
    }

    private fun notify(title: String, body: String, install: Boolean) {
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_PAGE, "settings")
            .putExtra(MainActivity.EXTRA_INSTALL_UPDATE, install)
        val notification = android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_umbra)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    /** Where to look: the site, or for a test the address in `debug.umbra.update_url` (only adb or root can set it). */
    private fun manifestUrl(): String = runCatching {
        ProcessBuilder("getprop", "debug.umbra.update_url").start().inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrNull()?.takeIf { it.startsWith("https://") } ?: UpdatePolicy.DEFAULT_URL

    private fun metered(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun apkFile(update: UpdateManifest) = File(dir, "umbra-${update.versionCode}.apk")

    private fun marker(apk: File, update: UpdateManifest) = "${apk.name}:${apk.length()}:${apk.lastModified()}:${update.sha256}"

    private fun verifiedBefore(apk: File, update: UpdateManifest) = prefs.getString(KEY_VERIFIED, null) == marker(apk, update)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun mib(bytes: Long) = "${bytes / (1024 * 1024)} MiB"

    companion object {
        private const val TAG = "AppUpdater"
        const val CHANNEL = "updates"
        private const val NOTIFICATION_ID = 7_300
        private const val KEY_READY = "ready"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_REOPEN = "reopen_after_update"
        const val KEY_INSTALLING = "installing"
        private const val KEY_NOTIFIED = "notified"
        private const val FIRST_CHECK_DELAY_MS = 30_000L
        private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val RETRY_MS = 20 * 60 * 1000L
        private const val QUIET_POLL_MS = 60_000L
        private const val SPACE_MARGIN = 200L * 1024 * 1024
    }
}
