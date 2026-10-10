package app.umbra.tools

import app.umbra.platform.api.ShellBackend
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.exec
import kotlinx.coroutines.delay

/**
 * Keeps the agent from operating a phone whose screen is off or locked. A sleeping screen is woken and
 * a swipe-only lock screen dismissed, as a person would; a PIN, pattern or password is the user's and is
 * reported rather than tried (spec §28: the agent never handles the device's unlock secret).
 */
internal class ScreenGuard(private val env: PipelineEnv) {
    private var okUntilMs = 0L

    /** What the user has to do before the agent can use the screen, or null when it is usable. */
    suspend fun ensureUsable(): String? {
        val now = env.clock.nowMs()
        if (now < okUntilMs) return null
        val shell = env.backends.shell(ShellIdentity.SHELL) ?: env.backends.shell(ShellIdentity.APP) ?: return null
        // Cannot tell (no dumpsys access): do not block the agent on a guess.
        var state: Screen = state(shell) ?: return null
        if (!state.awake) {
            shell.exec("input keyevent KEYCODE_WAKEUP", timeoutMs = 5_000)
            delay(500)
            state = state(shell) ?: return null
        }
        if (state.locked) {
            // A swipe-only lock screen goes away when asked, but a slow phone takes a while to do it: ask, then look again.
            for (attempt in 1..DISMISS_ATTEMPTS) {
                if (!state.locked || state.secure) break
                shell.exec("wm dismiss-keyguard", timeoutMs = 5_000)
                delay(DISMISS_WAIT_MS)
                state = state(shell) ?: return null
            }
        }
        if (!state.awake) return "The phone's screen is off and could not be turned on. Ask the user to wake it."
        if (state.locked && state.secure) return "The phone is locked with a PIN, pattern or password. Ask the user to unlock it, then continue."
        if (state.locked) return "The lock screen did not go away. Ask the user to swipe it away, then continue."
        okUntilMs = env.clock.nowMs() + CACHE_MS
        return null
    }

    private class Screen(val awake: Boolean, val locked: Boolean, val secure: Boolean)

    private suspend fun state(shell: ShellBackend): Screen? {
        val power = shell.exec("dumpsys power | grep -m1 -E 'mWakefulness='", timeoutMs = 8_000)
        if (!power.ok || power.stdout.isBlank()) return null
        val awake = power.stdout.contains("Awake")
        // mDreamingLockscreen is true while the lock screen is showing.
        val window = shell.exec("dumpsys window | grep -m1 -E 'mDreamingLockscreen='", timeoutMs = 8_000)
        val locked = window.ok && window.stdout.contains("mDreamingLockscreen=true")
        // Only a PIN, pattern or password is the user's to enter; the keyguard service says whether the lock is one.
        val secure = locked && shell.exec("dumpsys window policy | grep -m1 -E '^ *secure='", timeoutMs = 8_000).stdout.contains("secure=true")
        return Screen(awake, locked, secure)
    }

    private companion object {
        const val CACHE_MS = 5_000L
        const val DISMISS_ATTEMPTS = 6
        const val DISMISS_WAIT_MS = 700L
    }
}
