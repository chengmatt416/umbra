package app.umbra.core.safety

import app.umbra.core.model.action.Operation

/** Persistent grants cover a literal command family, never shell syntax or supplied programs. */
object CommandPrefixes {
    private val literal = Regex("[A-Za-z0-9_./:@%+=,-]+")
    private val subcommands = setOf("git", "npm", "pnpm", "yarn", "docker", "podman", "adb", "systemctl", "service", "apt", "apt-get", "pip", "pip3", "cargo", "go", "gradle", "tmux", "gh", "swift", "kubectl")
    private val interpreters = setOf("sh", "bash", "zsh", "dash", "fish", "su", "sudo", "doas", "env", "eval", "exec", "xargs", "python", "python3", "node", "bun", "perl", "ruby", "php", "lua", "busybox", "toybox")

    private fun words(command: String): List<String>? {
        if (command.any { it == '\n' || it == '\r' }) return null
        val words = command.trim().split(Regex("[ \\t]+"))
        if (words.isEmpty() || words.any { !literal.matches(it) }) return null
        if (words.first().substringAfterLast('/') in interpreters || '=' in words.first()) return null
        return words
    }

    fun offered(operation: Operation): String? {
        val shell = operation as? Operation.Shell ?: return null
        if (shell.asRoot || shell.stdin != null || shell.env.isNotEmpty()) return null
        val words = words(shell.command) ?: return null
        val second = words.getOrNull(1)
        val hasSubcommand = words.first().substringAfterLast('/') in subcommands && second?.matches(Regex("[A-Za-z][A-Za-z0-9_-]*")) == true
        val hasFlag = second?.matches(Regex("--?[A-Za-z][A-Za-z0-9-]*")) == true
        return words.take(if (hasSubcommand || hasFlag) 2 else 1).joinToString(" ")
    }

    fun matches(command: String, prefix: String): Boolean {
        val actual = words(command) ?: return false
        val expected = words(prefix) ?: return false
        return actual.size >= expected.size && actual.take(expected.size) == expected
    }
}
