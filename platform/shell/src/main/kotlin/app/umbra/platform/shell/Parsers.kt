package app.umbra.platform.shell

import app.umbra.core.model.observation.DeviceState
import app.umbra.core.model.observation.DisplayInfo
import app.umbra.core.model.observation.ForegroundInfo
import app.umbra.core.model.observation.ProcessInfo
import app.umbra.platform.api.FileEntry
import app.umbra.platform.api.LaunchOutcome
import app.umbra.platform.api.PackageDetails
import app.umbra.platform.api.PackageSummary

/*
 * Parsers for the text Android's own tools print (pm, am, dumpsys, cmd display, ps, stat). The formats
 * are not an API, so every parser here is written against output captured from a real Android 16
 * device and kept next to its tests (src/test/resources/golden), and each is also exercised live
 * against a device in the integration tests.
 */

object PmParsers {
    private val tail = Regex("""^(.*?)(?: versionCode:(\d+))?(?: uid:(\d+))?$""")

    /**
     * `pm list packages -f --show-versioncode -U` lines:
     * `package:/data/app/~~x==/com.x-y==/base.apk=com.x versionCode:12 uid:10221`.
     * The path can contain `=`, so the package name is what follows the last one.
     */
    fun parseList(text: String, system: Boolean = false, disabled: Set<String> = emptySet()): List<PackageSummary> =
        text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (!line.startsWith("package:")) return@mapNotNull null
            val m = tail.matchEntire(line.removePrefix("package:")) ?: return@mapNotNull null
            val body = m.groupValues[1]
            val path = body.substringBeforeLast('=', "").ifEmpty { null }
            val pkg = body.substringAfterLast('=')
            if (pkg.isBlank()) return@mapNotNull null
            PackageSummary(
                packageName = pkg,
                enabled = pkg !in disabled,
                system = system,
                versionCode = m.groupValues[2].toLongOrNull(),
                apkPath = path,
                uid = m.groupValues[3].toIntOrNull(),
            )
        }.toList()

    /** The package names from `pm list packages` output, for set arithmetic (disabled, third-party). */
    fun names(text: String): Set<String> = parseList(text).map { it.packageName }.toSet()

    /** Installer/uninstaller replies: `Success`, or `Failure [REASON]` possibly followed by detail. */
    fun resultOf(output: String): Result<String> {
        val text = output.trim()
        return if (text.lineSequence().any { it.trim().startsWith("Success") }) Result.success(text.ifEmpty { "Success" })
        else Result.failure(IllegalStateException(text.ifEmpty { "no output" }))
    }

    /**
     * Fields of `dumpsys package <name>` (the full dump of one package, ~200 KB for a system app):
     *
     * ```
     *     versionCode=36 minSdk=36 targetSdk=36
     *     versionName=16
     *     flags=[ SYSTEM HAS_CODE ... ]
     *     firstInstallTime=2026-10-05 10:04:00
     *     requested permissions:
     *       android.permission.INTERNET
     *     install permissions:
     *       android.permission.INTERNET: granted=true
     *     User 0: ...
     *       runtime permissions:
     *         android.permission.CAMERA: granted=false, flags=[ ... ]
     * ```
     */
    fun parseDetails(packageName: String, dump: String): PackageDetails? {
        if (!dump.contains("Package [$packageName]") && !dump.contains("versionName=")) return null
        var versionName: String? = null
        var versionCode: Long? = null
        var flags: String? = null
        var uid: Int? = null
        var installer: String? = null
        var firstInstall: String? = null
        var lastUpdate: String? = null
        val requested = LinkedHashSet<String>()
        val granted = LinkedHashSet<String>()
        var section: String? = null
        var sectionIndent = 0
        for (raw in dump.lineSequence()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            val indent = line.length - line.trimStart().length
            if (section != null && trimmed.isNotEmpty() && indent <= sectionIndent) section = null
            when {
                trimmed == "requested permissions:" -> { section = "requested"; sectionIndent = indent }
                trimmed == "install permissions:" -> { section = "granted"; sectionIndent = indent }
                trimmed == "runtime permissions:" -> { section = "granted"; sectionIndent = indent }
                section == "requested" -> trimmed.substringBefore(':').takeIf { PERMISSION_NAME.matches(it) }?.let { requested += it }
                section == "granted" -> PERMISSION_GRANT.find(trimmed)?.let { if (it.groupValues[2] == "true") granted += it.groupValues[1] }
                versionName == null && trimmed.startsWith("versionName=") -> versionName = trimmed.removePrefix("versionName=").trim()
                versionCode == null && trimmed.startsWith("versionCode=") -> versionCode = trimmed.removePrefix("versionCode=").substringBefore(' ').toLongOrNull()
                flags == null && trimmed.startsWith("flags=[") -> flags = trimmed.removePrefix("flags=[").substringBefore(']')
                uid == null && (trimmed.startsWith("appId=") || trimmed.startsWith("userId=")) ->
                    uid = trimmed.substringAfter('=').trim().toIntOrNull()
                installer == null && trimmed.startsWith("installerPackageName=") -> installer = trimmed.removePrefix("installerPackageName=").trim().takeIf { it != "null" }
                firstInstall == null && trimmed.startsWith("firstInstallTime=") -> firstInstall = trimmed.removePrefix("firstInstallTime=").trim()
                lastUpdate == null && trimmed.startsWith("lastUpdateTime=") -> lastUpdate = trimmed.removePrefix("lastUpdateTime=").trim()
            }
        }
        val flagSet = flags?.trim()?.split(Regex("\\s+"))?.toSet().orEmpty()
        return PackageDetails(
            summary = PackageSummary(
                packageName = packageName,
                system = "SYSTEM" in flagSet,
                versionName = versionName,
                versionCode = versionCode,
                installer = installer,
                debuggable = "DEBUGGABLE" in flagSet,
                uid = uid,
            ),
            requestedPermissions = requested.toList(),
            grantedPermissions = granted.toList(),
            firstInstallTimeMs = firstInstall?.let(::parseDeviceTime),
            lastUpdateTimeMs = lastUpdate?.let(::parseDeviceTime),
            uid = uid,
        )
    }

    private val PERMISSION_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")
    private val PERMISSION_GRANT = Regex("""^([A-Za-z0-9_.]+): granted=(true|false)""")

    /** `yyyy-MM-dd HH:mm:ss` in the device's local time; the host's zone is the best guess available. */
    private fun parseDeviceTime(text: String): Long? = try {
        java.time.LocalDateTime.parse(text.replace(' ', 'T')).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: java.time.DateTimeException) {
        null
    }
}

object AmParsers {
    /**
     * `am start` output:
     * `Starting: Intent { cmp=com.android.settings/.Settings }`, optionally
     * `Warning: Activity not started, intent has been delivered to currently running top-most instance.`,
     * or `Error type 3` / `Error: Activity class {pkg/pkg.Main} does not exist.`
     */
    fun parseStart(output: String): LaunchOutcome {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val errors = lines.filter { it.startsWith("Error") || it.startsWith("java.lang.") || it.contains("SecurityException") }
        if (errors.isNotEmpty()) return LaunchOutcome(false, message = errors.joinToString(" ").take(300))
        val component = lines.firstNotNullOfOrNull { Regex("""cmp=([\w.$]+/[\w.$]+)""").find(it)?.groupValues?.get(1) }
        val warning = lines.firstOrNull { it.startsWith("Warning:") }
        if (component == null && lines.none { it.startsWith("Starting:") }) {
            return LaunchOutcome(false, message = lines.joinToString(" ").ifEmpty { "no output from am start" }.take(300))
        }
        return LaunchOutcome(true, component = component, message = warning.orEmpty())
    }

    /** `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>`: the last line is `pkg/.Activity`. */
    fun parseResolvedComponent(output: String): String? =
        output.lines().map { it.trim() }.lastOrNull { Regex("""^[\w.]+/[\w.$]+$""").matches(it) }
}

object ActivityParsers {
    private val record = Regex("""ActivityRecord\{[0-9a-f]+ u\d+ ([\w.$]+)/([\w.$]+) t\d+""")

    /** Resumed activity per display from `dumpsys activity activities` (`Display #N (activities from top to bottom):` blocks). */
    fun foreground(dump: String): List<ForegroundInfo> {
        val result = LinkedHashMap<Int, ForegroundInfo>()
        var display: Int? = null
        for (line in dump.lineSequence()) {
            val header = DISPLAY_HEADER.find(line)
            if (header != null) {
                display = header.groupValues[1].toInt()
                continue
            }
            val d = display ?: continue
            // A line that starts in column 0 ends the display's block.
            if (line.isNotEmpty() && !line.startsWith(" ")) {
                display = null
                continue
            }
            if (d in result) continue
            val trimmed = line.trim()
            if (trimmed.startsWith("topResumedActivity=") || trimmed.startsWith("mResumedActivity=")) {
                record.find(trimmed)?.let { m ->
                    val pkg = m.groupValues[1]
                    val cls = m.groupValues[2].let { if (it.startsWith(".")) pkg + it else it }
                    result[d] = ForegroundInfo(d, pkg, "$pkg/$cls")
                }
            }
        }
        return result.values.toList()
    }

    private val DISPLAY_HEADER = Regex("""^Display #(\d+) """)
}

object DisplayParsers {
    private val header = Regex("""^Display id (\d+): DisplayInfo\{"(.*?)", displayId \d+""")

    /** `cmd display get-displays` (Android 16): one `Display id N: DisplayInfo{...}` line per display. */
    fun parse(text: String): List<DisplayInfo> = text.lineSequence().mapNotNull { line ->
        val h = header.find(line) ?: return@mapNotNull null
        val id = h.groupValues[1].toInt()
        val real = Regex("""real (\d+) x (\d+)""").find(line)
        val density = Regex("""density (\d+) \(""").find(line)?.groupValues?.get(1)?.toInt() ?: 0
        val type = Regex("""type (\w+)""").find(line)?.groupValues?.get(1)
        val owner = Regex("""ownerPackage (\S+?)[,}]""").find(line)?.groupValues?.get(1)
        DisplayInfo(
            id = id,
            name = h.groupValues[2],
            width = real?.groupValues?.get(1)?.toInt() ?: 0,
            height = real?.groupValues?.get(2)?.toInt() ?: 0,
            densityDpi = density,
            isDefault = id == 0,
            isVirtual = type != null && type != "INTERNAL" && type != "EXTERNAL",
            ownerPackage = owner,
            state = Regex("""state (\w+)""").find(line)?.groupValues?.get(1),
            uniqueId = Regex("""uniqueId "([^"]*)"""").find(line)?.groupValues?.get(1),
        )
    }.toList()
}

object SystemParsers {
    /** `/proc/meminfo`: values are in kB. */
    fun memInfo(text: String): Pair<Long?, Long?> {
        fun mb(key: String) = Regex("""^$key:\s+(\d+) kB""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toLong()?.div(1024)
        return mb("MemTotal") to mb("MemAvailable")
    }

    /** `df -k <path>`: free megabytes of the first data row. */
    fun dfFreeMb(text: String): Long? =
        text.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim()?.split(Regex("\\s+"))?.getOrNull(3)?.toLongOrNull()?.div(1024)

    /** `dumpsys battery`: level and whether anything is supplying power. */
    fun battery(text: String): Pair<Int?, Boolean?> {
        val level = Regex("""^\s*level: (\d+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toInt()
        val powered = listOf("AC", "USB", "Wireless", "Dock").map { Regex("""^\s*$it powered: (true|false)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) }
        return level to (if (powered.all { it == null }) null else powered.any { it == "true" })
    }

    /** `dumpsys thermalservice`: `Thermal Status: 0..6`. */
    fun thermal(text: String): String? = Regex("""Thermal Status: (\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
        listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown").getOrElse(it) { "status $it" }
    }

    /** `dumpsys connectivity`: the active default network as `wifi`, `mobile`, ... and whether it is validated. */
    fun network(text: String): Pair<String?, Boolean?> {
        val activeId = Regex("""Active default network: (\d+)""").find(text)?.groupValues?.get(1) ?: return null to false
        val line = text.lineSequence().firstOrNull { it.contains("NetworkAgentInfo{network{$activeId}") } ?: return null to null
        val type = Regex("""ni\{(\w+) """).find(line)?.groupValues?.get(1)?.lowercase()
        return type to line.contains("IS_VALIDATED")
    }

    /**
     * `ps -A -o PID,USER,RSS,ARGS`: a header row, then `pid user rss args...`. App processes put their
     * process name in ARGS, kernel threads are `[bracketed]`.
     */
    fun ps(text: String): List<ProcessInfo> = text.lineSequence().drop(1).mapNotNull { line ->
        val cols = line.trim().split(Regex("\\s+"), limit = 4)
        if (cols.size < 4) return@mapNotNull null
        val pid = cols[0].toIntOrNull() ?: return@mapNotNull null
        ProcessInfo(pid = pid, name = cols[3].substringBefore(' '), user = cols[1], memMb = cols[2].toLongOrNull()?.div(1024), command = cols[3])
    }.toList()

    /** `stat -c '%F|%s|%Y|%a|%n'` rows. */
    fun stat(text: String): List<FileEntry> = text.lineSequence().mapNotNull { line ->
        val f = line.split('|', limit = 5)
        if (f.size < 5) return@mapNotNull null
        val path = f[4]
        FileEntry(
            name = path.trimEnd('/').substringAfterLast('/'), path = path, isDirectory = f[0].startsWith("directory"),
            sizeBytes = f[1].toLongOrNull() ?: 0, modifiedMs = (f[2].toLongOrNull() ?: 0) * 1000, mode = f[3],
        )
    }.toList()
}

/** Everything a [DeviceState] needs, collected by one shell round trip each. */
internal fun deviceState(
    battery: String?, mem: String?, df: String?, thermal: String?, network: String?,
): DeviceState {
    val (level, powered) = battery?.let(SystemParsers::battery) ?: (null to null)
    val (total, avail) = mem?.let(SystemParsers::memInfo) ?: (null to null)
    val (type, online) = network?.let(SystemParsers::network) ?: (null to null)
    return DeviceState(
        batteryPct = level, charging = powered, thermalStatus = thermal?.let(SystemParsers::thermal),
        memAvailMb = avail, memTotalMb = total, storageFreeMb = df?.let(SystemParsers::dfFreeMb),
        networkType = type, networkOnline = online,
    )
}
