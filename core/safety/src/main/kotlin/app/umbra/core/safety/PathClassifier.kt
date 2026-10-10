package app.umbra.core.safety

internal enum class PathClass {
    /** Operating-system paths: deleting or overwriting them can brick the device or the guest. */
    SYSTEM,

    /** Raw block/memory devices. */
    DEVICE_NODE,

    /** Credentials, keys and other secrets. */
    SENSITIVE_FILE,

    /** Another app's private data. */
    APP_DATA,

    /** The user's own files: shared storage, photos, downloads. */
    USER_DATA,
    WORKSPACE_ROOT,
    WORKSPACE,

    /** Where the app stages APKs it built itself. */
    STAGING,
    TEMP,

    /** The agent's own app, approvals and ledger. */
    SELF_PROTECTED,
    UNKNOWN,
}

/** Classifies a path string from a command line, lexically (no filesystem access). */
internal class PathClassifier(private val ctx: ActionContext) {
    private val workspaceRoots = ctx.workspaceRoots.map { normalizeAbsolute(it) }
    private val stagingRoots = (ctx.stagingRoots).map { normalizeAbsolute(it) }
    private val tempRoots = ctx.tempRoots.map { normalizeAbsolute(it) }
    private val protectedPaths = ctx.protectedPaths.map { normalizeAbsolute(it) }

    fun isGlob(raw: String): Boolean = raw.any { it == '*' || it == '?' || it == '[' }

    fun classify(raw: String): PathClass {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PathClass.UNKNOWN
        if (trimmed == "~" || trimmed == "\$HOME" || trimmed == "\${HOME}") return PathClass.WORKSPACE_ROOT
        val expanded = when {
            trimmed.startsWith("~/") -> "/__home__/" + trimmed.removePrefix("~/")
            trimmed.startsWith("\$HOME/") -> "/__home__/" + trimmed.removePrefix("\$HOME/")
            trimmed.startsWith("\${HOME}/") -> "/__home__/" + trimmed.removePrefix("\${HOME}/")
            else -> trimmed
        }
        if (expanded.startsWith("/__home__/")) {
            return if (isSensitive(expanded)) PathClass.SENSITIVE_FILE else PathClass.WORKSPACE
        }
        if (expanded.contains('$') || expanded.contains('`')) return PathClass.UNKNOWN

        val absolute = if (expanded.startsWith("/")) {
            normalizeAbsolute(expanded)
        } else {
            val cwd = ctx.cwd
            if (cwd == null) return if (expanded.split('/').count { it == ".." } > 0) PathClass.UNKNOWN else PathClass.WORKSPACE
            normalizeAbsolute("$cwd/$expanded")
        }
        return classifyAbsolute(absolute)
    }

    private fun classifyAbsolute(p: String): PathClass {
        // Order matters: the workspace lives inside the agent's own app-data directory, so explicit
        // protected paths win first, then workspace/staging/temp, and only then the blanket
        // "this is the agent's own package data" rule.
        if (protectedPaths.any { p == it || p.startsWith("$it/") }) return PathClass.SELF_PROTECTED
        for (root in workspaceRoots) {
            if (p == root) return PathClass.WORKSPACE_ROOT
            if (p.startsWith("$root/")) return PathClass.WORKSPACE
        }
        if (stagingRoots.any { p == it || p.startsWith("$it/") }) return PathClass.STAGING
        if (tempRoots.any { p == it || p.startsWith("$it/") }) return PathClass.TEMP
        if (isOwnPackagePath(p)) return PathClass.SELF_PROTECTED

        if (isDeviceNode(p)) return PathClass.DEVICE_NODE
        if (isSensitive(p)) return PathClass.SENSITIVE_FILE
        if (isSystem(p)) return PathClass.SYSTEM

        val appDataPackage = appDataPackage(p)
        if (appDataPackage != null) {
            return if (appDataPackage in ctx.trustedPackages) PathClass.WORKSPACE else PathClass.APP_DATA
        }
        if (isUserData(p)) return PathClass.USER_DATA
        return PathClass.UNKNOWN
    }

    private fun isOwnPackagePath(p: String): Boolean {
        val pkg = appDataPackage(p) ?: externalAppDirPackage(p)
        return pkg != null && pkg in ctx.protectedPackages
    }

    /** `/data/data/<pkg>`, `/data/user/<n>/<pkg>`, `/data/user_de/<n>/<pkg>` -> pkg. */
    private fun appDataPackage(p: String): String? {
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3 || parts[0] != "data") return null
        return when (parts[1]) {
            "data" -> parts[2]
            "user", "user_de" -> parts.getOrNull(3)
            else -> null
        }
    }

    /** `/sdcard/Android/data|obb|media/<pkg>` and `/storage/emulated/<n>/Android/...` -> pkg. */
    private fun externalAppDirPackage(p: String): String? {
        val parts = p.split('/').filter { it.isNotEmpty() }
        val androidIdx = parts.indexOf("Android")
        if (androidIdx < 0 || androidIdx + 2 >= parts.size) return null
        if (parts[androidIdx + 1] !in setOf("data", "obb", "media")) return null
        val head = parts.take(androidIdx)
        val looksExternal = head.firstOrNull() in setOf("sdcard", "storage", "mnt") || head.firstOrNull() == "data" && head.getOrNull(1) == "media"
        return if (looksExternal) parts[androidIdx + 2] else null
    }

    private fun isDeviceNode(p: String): Boolean {
        if (!p.startsWith("/dev/")) return false
        val rest = p.removePrefix("/dev/")
        return DEVICE_PREFIXES.any { rest == it || rest.startsWith("$it/") || (it.endsWith("*") && false) } ||
            DEVICE_NAME_PREFIXES.any { rest.startsWith(it) }
    }

    private fun isSensitive(p: String): Boolean {
        val lower = p.lowercase()
        return SENSITIVE_FRAGMENTS.any { lower.contains(it) } ||
            SENSITIVE_SUFFIXES.any { lower.endsWith(it) } ||
            lower.substringAfterLast('/') in SENSITIVE_BASENAMES
    }

    private fun isSystem(p: String): Boolean {
        if (p == "/") return true
        val parts = p.split('/').filter { it.isNotEmpty() }
        val top = parts.first()
        // A glob in the first component (`/*`) or directly under /data (`/data/*`) reaches everything.
        if (top.any { it == '*' || it == '?' }) return true
        if (top == "data" && parts.getOrNull(1)?.any { it == '*' || it == '?' } == true) return true
        if (top in SYSTEM_TOP_ANY_DEPTH) return true
        if (top == "dev") return parts.size == 1 || p.startsWith("/dev/block")
        if (top == "data") {
            if (parts.size == 1) return true
            if (parts[1] in DATA_SYSTEM_DIRS) return true
            if (parts[1] == "local" && parts.getOrNull(2) != "tmp") return true
        }
        if (top in LINUX_TOP_EXACT && parts.size == 1) return true
        return false
    }

    private fun isUserData(p: String): Boolean {
        val parts = p.split('/').filter { it.isNotEmpty() }
        return when (parts.firstOrNull()) {
            "sdcard", "storage" -> true
            "mnt" -> parts.getOrNull(1) in setOf("sdcard", "media_rw", "user", "runtime", "pass_through")
            "data" -> parts.getOrNull(1) == "media"
            else -> false
        }
    }

    companion object {
        private val SYSTEM_TOP_ANY_DEPTH = setOf(
            "system", "system_ext", "vendor", "product", "odm", "apex", "boot", "efs", "persist",
            "metadata", "cache", "oem", "proc", "sys", "init", "linkerconfig", "config",
        )
        private val DATA_SYSTEM_DIRS = setOf(
            "system", "system_ce", "system_de", "misc", "misc_ce", "misc_de", "vendor", "vendor_ce",
            "vendor_de", "adb", "anr", "tombstones", "dalvik-cache", "app", "app-private", "apex",
            "property", "resource-cache", "ota", "ota_package", "bootchart", "backup", "mediadrm", "nfc",
        )
        private val LINUX_TOP_EXACT = setOf("bin", "sbin", "lib", "lib32", "lib64", "usr", "etc", "var", "opt", "srv", "home", "root", "mnt")
        private val DEVICE_PREFIXES = setOf("block", "mapper", "disk", "bootdevice", "mem", "kmem", "port")
        private val DEVICE_NAME_PREFIXES = setOf("mmcblk", "sd", "nvme", "loop", "sg", "mtd", "ubi", "ram", "dm-", "sda", "vd", "zram")
        private val SENSITIVE_FRAGMENTS = listOf(
            "/data/misc/keystore", "/data/misc/keychain", "/data/misc/wifi/", "/data/misc/user/",
            "/data/system/locksettings", "/data/system/gatekeeper", "/data/system/gesture.key",
            "/data/system/password.key", "/data/system/users/", "/.ssh/id_", "/.ssh/authorized_keys",
            "/.gnupg/", "/.aws/credentials", "/.config/gcloud", "/.omp/agent/agent.db", "/.omp/agent/auth",
            "/.docker/config.json", "/.kube/config",
        )
        private val SENSITIVE_SUFFIXES = listOf(".pem", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", "id_rsa", "id_ed25519")
        private val SENSITIVE_BASENAMES = setOf(".env", ".netrc", ".git-credentials", ".npmrc", ".pypirc", "wallet.dat", "credentials.json")

        /** Lexically resolves `.`, `..` and duplicate slashes in an absolute path. */
        fun normalizeAbsolute(path: String): String {
            val out = ArrayDeque<String>()
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isNotEmpty()) out.removeLast()
                    else -> out.addLast(part)
                }
            }
            return "/" + out.joinToString("/")
        }
    }
}
