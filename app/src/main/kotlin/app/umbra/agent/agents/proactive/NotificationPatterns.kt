package app.umbra.agent.agents.proactive

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Persisted literal watchers keep their meaning; newly reviewed regexes use a non-backtracking engine. */
object NotificationPatterns {
    fun matches(spec: ProbeSpec.NotificationPattern, pattern: Pattern, body: String): Boolean =
        if (spec.literal) body.take(8000).contains(spec.regex, ignoreCase = true) else pattern.matcher(body.take(8000)).find()

    fun compile(spec: ProbeSpec.NotificationPattern): Pattern {
        require(spec.regex.length <= 300) { "Notification patterns are limited to 300 characters" }
        val pattern = try { Pattern.compile(if (spec.literal) Pattern.quote(spec.regex) else spec.regex, Pattern.CASE_INSENSITIVE) }
        catch (e: PatternSyntaxException) { throw IllegalArgumentException("Use a valid RE2 notification pattern; lookarounds and backreferences are not supported", e) }
        require(pattern.programSize() <= 2048) { "This notification pattern is too complex" }
        return pattern
    }
}
