package app.umbra.tools

import app.umbra.core.model.Clock
import app.umbra.core.model.action.CheckResult
import app.umbra.core.model.action.ElementQuery
import app.umbra.core.model.action.Environment
import app.umbra.core.model.action.Expectation
import app.umbra.core.model.action.LogFilter
import app.umbra.core.model.action.Verification
import app.umbra.core.model.action.VerificationResult
import app.umbra.core.model.observation.ForegroundInfo
import app.umbra.core.model.observation.NodeFlag
import app.umbra.core.model.observation.UiSnapshot
import app.umbra.platform.api.BackendRegistry
import app.umbra.platform.api.ShellIdentity
import app.umbra.platform.api.SnapshotOptions
import app.umbra.platform.api.exec
import app.umbra.platform.logs.LogMonitor
import app.umbra.platform.vision.ImageCodec
import app.umbra.platform.vision.ImageOps
import app.umbra.platform.vision.PixelImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What the screen looked like before an action ran, so "did anything change" has something to compare to. */
class Baseline(
    val uiSignature: Int?,
    val nodeCount: Int,
    val thumbnail: PixelImage? = null,
)

class VerifyContext(
    val environment: Environment,
    val displayId: Int?,
    val baseline: Baseline?,
    /** Start of the action: crashes and file changes before this are not its doing. */
    val startedAtMs: Long,
)

/** Hashes what a user could see of a display: changes when anything visible changes. */
internal fun uiSignature(snapshot: UiSnapshot, displayId: Int?): Pair<Int, Int> {
    var hash = 17
    var count = 0
    for (w in snapshot.windows) {
        if (displayId != null && w.displayId != displayId) continue
        hash = 31 * hash + w.windowId
        for (n in w.nodes) {
            if (!n.isVisible) continue
            count++
            hash = 31 * hash + (n.className?.hashCode() ?: 0)
            hash = 31 * hash + (n.resourceId?.hashCode() ?: 0)
            hash = 31 * hash + (n.text?.hashCode() ?: 0)
            hash = 31 * hash + (n.contentDescription?.hashCode() ?: 0)
            hash = 31 * hash + n.bounds.hashCode()
            hash = 31 * hash + n.flags.hashCode()
        }
    }
    return hash to count
}

/**
 * Decides whether an [Expectation] holds (spec §17). Checks poll until they pass or time out, because
 * most UI changes land a few hundred milliseconds after the action that caused them. Every verdict
 * carries the evidence that produced it, so a failure tells the agent what was actually seen.
 */
class Verifier(
    private val backends: BackendRegistry,
    private val logs: LogMonitor?,
    private val clock: Clock = Clock.System,
    private val codec: ImageCodec? = null,
) {
    suspend fun verify(spec: Verification, ctx: VerifyContext): VerificationResult {
        if (spec.expect is Expectation.None) return VerificationResult(true, "no verification requested")
        val start = clock.nowMs()
        if (spec.settleMs > 0) delay(spec.settleMs)
        val deadline = clock.nowMs() + spec.timeoutMs
        var last: Eval? = null
        while (true) {
            // Reading the screen can take seconds on a busy device. A round that is under way when the
            // deadline passes may finish (the grace), but a hung one cannot hold the call forever.
            val round = withTimeoutOrNull(maxOf(deadline - clock.nowMs(), 0) + ROUND_GRACE_MS) { evaluate(spec.expect, Round(ctx)) }
            if (round != null) {
                last = round
                if (round.passed) break
            }
            if (clock.nowMs() >= deadline) break
            delay(spec.pollMs)
        }
        val took = clock.nowMs() - start
        val result = last ?: Eval(false, listOf(CheckResult(spec.expect.describe(), false, "the screen could not be read in time")))
        val summary = if (result.passed) "verified: ${result.checks.joinToString("; ") { it.evidence }}"
        else "not verified after ${took}ms: ${result.checks.filter { !it.passed }.joinToString("; ") { it.evidence }}"
        return VerificationResult(result.passed, summary, result.checks, took)
    }

    /** One evaluation without polling. */
    suspend fun check(expect: Expectation, ctx: VerifyContext): VerificationResult {
        val e = evaluate(expect, Round(ctx))
        return VerificationResult(e.passed, e.checks.joinToString("; ") { it.evidence }, e.checks)
    }

    /** Takes the "before" picture for [expect]; null parts are simply not available. */
    suspend fun baseline(expect: Expectation, environment: Environment, displayId: Int?): Baseline {
        val snapshot = snapshot(environment, displayId)
        val sig = snapshot?.let { uiSignature(it, displayId) }
        val thumb = if (containsScreenChange(expect)) thumbnail(environment, displayId) else null
        return Baseline(sig?.first, sig?.second ?: 0, thumb)
    }

    private fun containsScreenChange(e: Expectation): Boolean = when (e) {
        is Expectation.ScreenChanged -> true
        is Expectation.AllOf -> e.items.any(::containsScreenChange)
        is Expectation.AnyOf -> e.items.any(::containsScreenChange)
        is Expectation.Not -> containsScreenChange(e.item)
        else -> false
    }

    // ---- evaluation ----

    private class Eval(val passed: Boolean, val checks: List<CheckResult>)

    private fun leaf(e: Expectation, passed: Boolean, evidence: String) = Eval(passed, listOf(CheckResult(e.describe(), passed, evidence)))

    /** Lazily fetched observations, shared by the leaves of one evaluation round. */
    private inner class Round(val ctx: VerifyContext) {
        private var snap: UiSnapshot? = null
        private var snapLoaded = false
        private var fg: List<ForegroundInfo>? = null

        suspend fun snapshot(): UiSnapshot? {
            if (!snapLoaded) {
                snap = this@Verifier.snapshot(ctx.environment, ctx.displayId)
                snapLoaded = true
            }
            return snap
        }

        suspend fun foreground(): List<ForegroundInfo> {
            fg?.let { return it }
            val fromApps = try {
                backends.apps?.takeIf { it.isAvailable() }?.foreground(ctx.displayId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val result = fromApps ?: snapshot()?.windows
                ?.filter { it.isActive && (ctx.displayId == null || it.displayId == ctx.displayId) }
                ?.map { ForegroundInfo(it.displayId, it.packageName, null, it.title) }
                .orEmpty()
            fg = result
            return result
        }
    }

    private suspend fun evaluate(e: Expectation, round: Round): Eval {
        val ctx = round.ctx
        return when (e) {
            Expectation.None -> leaf(e, true, "nothing to verify")
            Expectation.UiChanged -> uiChanged(e, round)
            is Expectation.ScreenChanged -> screenChanged(e, round)
            is Expectation.TextVisible -> {
                val snap = round.snapshot() ?: return noUi(e)
                val found = snap.containsText(e.text, e.exact, e.ignoreCase, ctx.displayId)
                leaf(e, found, if (found) "\"${e.text}\" is on screen" else "\"${e.text}\" is not on screen")
            }
            is Expectation.TextGone -> {
                val snap = round.snapshot() ?: return noUi(e)
                val found = snap.containsText(e.text, e.exact, e.ignoreCase, ctx.displayId)
                leaf(e, !found, if (found) "\"${e.text}\" is still on screen" else "\"${e.text}\" is gone")
            }
            is Expectation.ElementVisible -> {
                val snap = round.snapshot() ?: return noUi(e)
                val n = matches(snap, e.query, ctx.displayId).size
                leaf(e, n > 0, if (n > 0) "$n matching element(s) visible" else "no element matches ${ElementTargeting(null).describeQuery(e.query)}")
            }
            is Expectation.ElementGone -> {
                val snap = round.snapshot() ?: return noUi(e)
                val n = matches(snap, e.query, ctx.displayId).size
                leaf(e, n == 0, if (n == 0) "element is gone" else "$n matching element(s) still visible")
            }
            is Expectation.ElementState -> elementState(e, round)
            is Expectation.PackageForeground -> {
                val fg = round.foreground()
                val ok = fg.any { it.packageName == e.packageName }
                leaf(e, ok, if (ok) "${e.packageName} is in the foreground" else "foreground is ${fg.mapNotNull { it.packageName }.distinct().joinToString().ifEmpty { "unknown" }}")
            }
            is Expectation.ActivityIs -> {
                val fg = round.foreground()
                val want = normalizeComponent(e.component)
                val ok = fg.any { f -> f.activity?.let(::normalizeComponent)?.let { if (e.prefix) it.startsWith(want) else it == want } == true }
                leaf(e, ok, if (ok) "activity is ${e.component}" else "activity is ${fg.mapNotNull { it.activity }.joinToString().ifEmpty { "unknown" }}")
            }
            is Expectation.ProcessRunning -> {
                val running = isRunning(e.name)
                leaf(e, running == true, when (running) { true -> "${e.name} is running"; false -> "${e.name} is not running"; null -> "cannot list processes" })
            }
            is Expectation.ProcessGone -> {
                val running = isRunning(e.name)
                leaf(e, running == false, when (running) { true -> "${e.name} is still running"; false -> "${e.name} has exited"; null -> "cannot list processes" })
            }
            is Expectation.FileExists -> {
                val stat = backends.files?.let { f -> tryOrNull { f.stat(e.path, e.privileged) } }
                leaf(e, stat != null, if (stat != null) "${e.path} exists (${stat.sizeBytes} bytes)" else "${e.path} does not exist")
            }
            is Expectation.FileChanged -> {
                val stat = backends.files?.let { f -> tryOrNull { f.stat(e.path, e.privileged) } }
                val since = e.sinceMs ?: ctx.startedAtMs
                val ok = stat != null && stat.modifiedMs >= since
                leaf(e, ok, if (stat == null) "${e.path} does not exist" else if (ok) "${e.path} was modified" else "${e.path} was not modified since the action started")
            }
            is Expectation.LogMatches -> logMatches(e, ctx)
            is Expectation.NoCrash -> {
                val monitor = logs
                if (monitor == null) {
                    leaf(e, false, "no log monitor is running, cannot confirm ${e.packageName} did not crash")
                } else {
                    val crashes = monitor.crashes(ctx.startedAtMs, e.packageName)
                    leaf(e, crashes.isEmpty(), if (crashes.isEmpty()) "no crash or ANR in ${e.packageName}" else crashes.first().summary())
                }
            }
            is Expectation.ShellOutput -> shellOutput(e)
            is Expectation.AllOf -> {
                val parts = e.items.map { evaluate(it, round) }
                Eval(parts.all { it.passed }, parts.flatMap { it.checks })
            }
            is Expectation.AnyOf -> {
                val parts = e.items.map { evaluate(it, round) }
                Eval(parts.any { it.passed }, parts.flatMap { it.checks })
            }
            is Expectation.Not -> {
                val inner = evaluate(e.item, round)
                Eval(!inner.passed, inner.checks.map { it.copy(expectation = "not ${it.expectation}", passed = !it.passed) })
            }
        }
    }

    private fun noUi(e: Expectation) = leaf(e, false, "no UI backend is available to check this")

    private fun matches(snap: UiSnapshot, q: ElementQuery, displayId: Int?) =
        snap.windows.filter { displayId == null || it.displayId == displayId }.flatMap { it.find(q) }.filter { it.isVisible }

    private suspend fun uiChanged(e: Expectation, round: Round): Eval {
        val base = round.ctx.baseline ?: return leaf(e, false, "no earlier observation to compare with")
        val snap = round.snapshot() ?: return noUi(e)
        val (sig, count) = uiSignature(snap, round.ctx.displayId)
        val changed = base.uiSignature != sig
        return leaf(e, changed, if (changed) "the screen changed (${base.nodeCount} -> $count visible elements)" else "the screen did not change ($count visible elements)")
    }

    private suspend fun screenChanged(e: Expectation.ScreenChanged, round: Round): Eval {
        val before = round.ctx.baseline?.thumbnail ?: return leaf(e, false, "no earlier screenshot to compare with")
        val now = thumbnail(round.ctx.environment, round.ctx.displayId) ?: return leaf(e, false, "cannot take a screenshot")
        val ratio = ImageOps.diffRatio(before, now)
        return leaf(e, ratio >= e.minDiffRatio, "${"%.1f".format(ratio * 100)}% of the screen changed (needs ${"%.1f".format(e.minDiffRatio * 100)}%)")
    }

    private suspend fun elementState(e: Expectation.ElementState, round: Round): Eval {
        val snap = round.snapshot() ?: return noUi(e)
        val nodes = matches(snap, e.query, round.ctx.displayId)
        val node = nodes.firstOrNull() ?: return leaf(e, false, "no element matches ${ElementTargeting(null).describeQuery(e.query)}")
        val problems = buildList {
            e.checked?.let { if (node.has(NodeFlag.CHECKED) != it) add("checked is ${node.has(NodeFlag.CHECKED)}, expected $it") }
            e.enabled?.let { if (node.isEnabled != it) add("enabled is ${node.isEnabled}, expected $it") }
            e.text?.let { if (node.text?.trim() != it.trim()) add("text is \"${if (node.isPassword) "[hidden]" else node.text.orEmpty()}\", expected \"$it\"") }
        }
        return leaf(e, problems.isEmpty(), if (problems.isEmpty()) "element has the expected state" else problems.joinToString(", "))
    }

    private suspend fun isRunning(name: String): Boolean? {
        backends.processes?.let { p ->
            try {
                if (p.isAvailable()) return p.isRunning(name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        val shell = backends.shell(ShellIdentity.SHELL) ?: backends.shell(ShellIdentity.APP) ?: return null
        return try {
            shell.exec("pidof ${shellQuote(name)}").ok
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun logMatches(e: Expectation.LogMatches, ctx: VerifyContext): Eval {
        val filter = LogFilter(tags = listOfNotNull(e.tag), packageName = e.packageName, regex = e.regex)
        val since = clock.nowMs() - e.withinMs
        val monitor = logs
        val hits = when {
            monitor != null -> monitor.query(filter, 1, since)
            else -> backends.logs?.let { l -> tryOrNull { l.read(filter, 1_000) } }?.filter { it.timestampMs >= since }
        } ?: return leaf(e, false, "no log source is available")
        return leaf(e, hits.isNotEmpty(), if (hits.isNotEmpty()) "log line matched: ${hits.last().message.take(120)}" else "no log line matched /${e.regex}/ in the last ${e.withinMs}ms")
    }

    private suspend fun shellOutput(e: Expectation.ShellOutput): Eval {
        val shell = (if (e.asRoot) backends.shell(ShellIdentity.ROOT) else backends.shell(ShellIdentity.SHELL) ?: backends.shell(ShellIdentity.APP))
            ?: return leaf(e, false, "no shell is available")
        val r = try {
            shell.exec(e.command)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            return leaf(e, false, "command failed to run: ${ex.message}")
        }
        val codeOk = e.exitCode == null || r.exitCode == e.exitCode
        val regexOk = e.regex == null || Regex(e.regex!!).containsMatchIn(r.stdout)
        val ok = codeOk && regexOk && !r.timedOut
        return leaf(e, ok, if (ok) "'${e.command.take(60)}' succeeded" else "'${e.command.take(60)}' exited ${r.exitCode}${if (!regexOk) " and its output did not match /${e.regex}/" else ""}")
    }

    // ---- helpers ----

    private suspend fun snapshot(environment: Environment, displayId: Int?): UiSnapshot? {
        for (ui in backends.ui(environment)) {
            try {
                if (ui.isAvailable()) return ui.snapshot(SnapshotOptions(displayId = displayId))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun thumbnail(environment: Environment, displayId: Int?): PixelImage? {
        val c = codec ?: return null
        for (s in backends.screenshots(environment)) {
            try {
                if (!s.isAvailable()) continue
                return ImageOps.downscale(c.decode(s.capture(displayId).bytes), 270)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    internal companion object {
        /** How long a check that is already reading the screen may overrun the deadline. */
        const val ROUND_GRACE_MS = 10_000L

        /** `pkg/.Main` and `pkg/pkg.Main` name the same activity. */
        fun normalizeComponent(component: String): String {
            val pkg = component.substringBefore('/')
            if ('/' !in component) return pkg
            val cls = component.substringAfter('/')
            return "$pkg/" + if (cls.startsWith('.')) pkg + cls else cls
        }
    }
}

internal fun shellQuote(s: String): String =
    if (s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "._-/:@+=,%" }) s else "'" + s.replace("'", "'\\''") + "'"

internal fun Expectation.describe(): String = when (this) {
    Expectation.None -> "none"
    Expectation.UiChanged -> "ui_changed"
    is Expectation.ScreenChanged -> "screen_changed"
    is Expectation.TextVisible -> "text_visible(\"$text\")"
    is Expectation.TextGone -> "text_gone(\"$text\")"
    is Expectation.ElementVisible -> "element_visible"
    is Expectation.ElementGone -> "element_gone"
    is Expectation.ElementState -> "element_state"
    is Expectation.PackageForeground -> "package_foreground($packageName)"
    is Expectation.ActivityIs -> "activity($component)"
    is Expectation.ProcessRunning -> "process_running($name)"
    is Expectation.ProcessGone -> "process_gone($name)"
    is Expectation.FileExists -> "file_exists($path)"
    is Expectation.FileChanged -> "file_changed($path)"
    is Expectation.LogMatches -> "log_matches(/$regex/)"
    is Expectation.NoCrash -> "no_crash($packageName)"
    is Expectation.ShellOutput -> "shell_output(${command.take(40)})"
    is Expectation.AllOf -> "all_of"
    is Expectation.AnyOf -> "any_of"
    is Expectation.Not -> "not"
}
