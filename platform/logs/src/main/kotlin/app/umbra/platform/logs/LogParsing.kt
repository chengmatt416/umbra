package app.umbra.platform.logs

import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.observation.LogEvent
import app.umbra.core.model.observation.LogKind
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

object LogLevels {
    private const val ORDER = "VDIWEFS"

    /** Rank of a level letter; unknown letters rank as verbose so nothing is hidden by a typo. */
    fun rank(level: String): Int = ORDER.indexOf(level.firstOrNull()?.uppercaseChar() ?: 'V').coerceAtLeast(0)

    fun atLeast(level: String, minimum: String): Boolean = rank(level) >= rank(minimum)
}

/**
 * Parses `logcat -v threadtime` lines, optionally with the `year` or `epoch` modifiers:
 *
 * ```
 * 10-05 12:34:56.789  4321  4350 E AndroidRuntime: FATAL EXCEPTION: main
 * 2026-10-05 12:34:56.789  4321  4350 E AndroidRuntime: ...        (-v year)
 * 1791203696.789  4321  4350 E AndroidRuntime: ...                 (-v epoch)
 * ```
 */
object LogcatParser {
    // Optional columns: a year before the date (`-v year`), a uid before the pid (`-v uid`). Real logcat
    // right-aligns the epoch seconds, so lines can start with spaces.
    private val threadtime = Regex("""^\s*(?:(\d{4})-)?(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})\s+(?:(\S+)\s+)?(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*:\s?(.*)$""")
    private val epoch = Regex("""^\s*(\d{9,11})\.(\d{3})\s+(?:(\S+)\s+)?(\d+)\s+(\d+)\s+([VDIWEFS])\s+(.*?)\s*:\s?(.*)$""")

    /**
     * Returns null for lines that are not log entries (`--------- beginning of main`, blank lines,
     * continuation junk). [nowMs] and [zone] resolve the missing year and the local time zone.
     */
    fun parse(line: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LogEvent? {
        epoch.matchEntire(line)?.let { m ->
            val g = m.groupValues
            return event(g[1].toLong() * 1000 + g[2].toLong(), g[6], g[7], g[4].toInt(), Uids.parse(g[3]), g[8])
        }
        val m = threadtime.matchEntire(line) ?: return null
        val g = m.groupValues
        val timestamp = toEpochMs(g[1].toIntOrNull(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt(), g[7].toInt(), nowMs, zone)
            ?: return null
        return event(timestamp, g[11], g[12], g[9].toInt(), Uids.parse(g[8]), g[13])
    }

    private fun event(timestampMs: Long, level: String, tag: String, pid: Int, uid: Int?, message: String) = LogEvent(
        timestampMs = timestampMs,
        level = level,
        tag = tag.trim(),
        pid = pid,
        uid = uid,
        message = message,
        kind = classify(level, tag.trim(), message),
    )

    /** Crash, ANR and error kinds by level and well-known signatures. */
    fun classify(level: String, tag: String, message: String): LogKind = when {
        tag == "AndroidRuntime" && message.startsWith("FATAL EXCEPTION") -> LogKind.CRASH
        tag == "ActivityManager" && message.startsWith("ANR in ") -> LogKind.ANR
        (tag == "libc" && message.startsWith("Fatal signal")) || (tag == "DEBUG" && message.startsWith("*** *** ***")) -> LogKind.NATIVE_CRASH
        level == "E" || level == "F" -> LogKind.ERROR
        level == "W" -> LogKind.WARNING
        else -> LogKind.NORMAL
    }

    private fun toEpochMs(
        year: Int?, month: Int, day: Int, hour: Int, minute: Int, second: Int, milli: Int,
        nowMs: Long, zone: ZoneId,
    ): Long? = try {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        fun at(y: Int) = LocalDateTime.of(y, month, day, hour, minute, second, milli * 1_000_000).atZone(zone).toInstant().toEpochMilli()
        if (year != null) {
            at(year)
        } else {
            // logcat omits the year: pick the current one, or the previous one around New Year.
            val candidate = at(now.year)
            if (candidate > nowMs + 24 * 3_600_000L) at(now.year - 1) else candidate
        }
    } catch (_: java.time.DateTimeException) {
        null
    }

    /** UTC epoch milliseconds, for tests that build lines by hand. */
    fun formatEpoch(timestampMs: Long, pid: Int, tid: Int, level: String, tag: String, message: String): String =
        "%d.%03d %5d %5d %s %s: %s".format(timestampMs / 1000, timestampMs % 1000, pid, tid, level, tag, message)

    @Suppress("unused")
    private val utc = ZoneOffset.UTC
}

/** Matches events against a [LogFilter], resolving packages through [packageOf] when the event has none. */
class LogFilterMatcher(private val filter: LogFilter, private val packageOf: (Int) -> String? = { null }) {
    private val regex: Regex? = filter.regex?.let { Regex(it) }

    fun matches(event: LogEvent): Boolean {
        val minimum = filter.tagLevels[event.tag] ?: filter.minLevel
        if (!LogLevels.atLeast(event.level, minimum)) {
            return false
        }
        if (filter.tags.isNotEmpty() && event.tag !in filter.tags && event.tag !in filter.tagLevels) return false
        filter.pid?.let { if (event.pid != it) return false }
        filter.uid?.let { if (event.uid != it) return false }
        filter.packageName?.let { wanted ->
            val actual = event.packageName ?: packageOf(event.pid)
            if (actual != wanted) return false
        }
        regex?.let { if (!it.containsMatchIn(event.message)) return false }
        return true
    }
}

/**
 * The uid column of `logcat -v uid`: a number, or the name logcat resolved it to (`radio`, `u0_a221`).
 * Names follow Android's id scheme: app uids are `10000 + n` shown as `uX_aN`, isolated ones `uX_iN`,
 * and the well-known system accounts have fixed numbers.
 */
object Uids {
    private val APP = Regex("""u(\d+)_a(\d+)""")
    private val ISOLATED = Regex("""u(\d+)_i(\d+)""")

    private val SYSTEM = mapOf(
        "root" to 0, "daemon" to 1, "system" to 1000, "radio" to 1001, "bluetooth" to 1002, "graphics" to 1003,
        "input" to 1004, "audio" to 1005, "camera" to 1006, "log" to 1007, "compass" to 1008, "mount" to 1009,
        "wifi" to 1010, "adb" to 1011, "install" to 1012, "media" to 1013, "dhcp" to 1014, "sdcard_rw" to 1015,
        "vpn" to 1016, "keystore" to 1017, "usb" to 1018, "drm" to 1019, "mdnsr" to 1020, "gps" to 1021,
        "media_rw" to 1023, "mtp" to 1024, "drmrpc" to 1026, "nfc" to 1027, "sdcard_r" to 1028, "clat" to 1029,
        "loop_radio" to 1030, "media_drm" to 1031, "package_info" to 1032, "sdcard_pics" to 1033,
        "sdcard_av" to 1034, "sdcard_all" to 1035, "logd" to 1036, "shared_relro" to 1037, "dbus" to 1038,
        "tlsdate" to 1039, "media_ex" to 1040, "audioserver" to 1041, "metrics_coll" to 1042, "metricsd" to 1043,
        "webserv" to 1044, "debuggerd" to 1045, "media_codec" to 1046, "cameraserver" to 1047, "firewall" to 1048,
        "trunks" to 1049, "nvram" to 1050, "dns" to 1051, "dns_tether" to 1052, "webview_zygote" to 1053,
        "vehicle_network" to 1054, "media_audio" to 1055, "media_video" to 1056, "media_image" to 1057,
        "tombstoned" to 1058, "media_obb" to 1059, "ese" to 1060, "ota_update" to 1061, "automotive_evs" to 1062,
        "lowpan" to 1063, "hsm" to 1064, "reserved_disk" to 1065, "statsd" to 1066, "incidentd" to 1067,
        "secure_element" to 1068, "lmkd" to 1069, "llkd" to 1070, "iorapd" to 1071, "gpu_service" to 1072,
        "network_stack" to 1073, "gsid" to 1074, "fsverity_cert" to 1075, "credstore" to 1076, "external_storage" to 1077,
        "ext_data_rw" to 1078, "ext_obb_rw" to 1079, "context_hub" to 1080, "virtualizationservice" to 1081,
        "artd" to 1082, "uwb" to 1083, "thread_network" to 1084, "diag" to 2002, "shell" to 2000, "cache" to 2001,
        "net_bt_admin" to 3001, "net_bt" to 3002, "inet" to 3003, "net_raw" to 3004, "net_admin" to 3005,
        "net_bw_stats" to 3006, "net_bw_acct" to 3007, "readproc" to 3009, "wakelock" to 3010, "uhid" to 3011,
        "readtracefs" to 3012, "nobody" to 9999,
    )

    fun parse(token: String?): Int? {
        if (token.isNullOrEmpty()) return null
        token.toIntOrNull()?.let { return it }
        APP.matchEntire(token)?.let { return it.groupValues[1].toInt() * 100_000 + 10_000 + it.groupValues[2].toInt() }
        ISOLATED.matchEntire(token)?.let { return it.groupValues[1].toInt() * 100_000 + 99_000 + it.groupValues[2].toInt() }
        return SYSTEM[token]
    }
}
