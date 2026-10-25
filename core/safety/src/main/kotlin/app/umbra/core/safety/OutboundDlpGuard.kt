package app.umbra.core.safety

import app.umbra.core.model.action.Operation

/** Local deterministic checks. Findings contain labels only, never the detected secret. */
class OutboundDlpGuard(private val minimumSecretLength: Int = 8, private val knownSecrets: () -> Collection<String> = { emptyList() }) {
    init { require(minimumSecretLength in 4..128) }
    data class Match(val label: String, val range: IntRange)
    fun matches(text: String): List<Match> {
        val found = mutableListOf<Match>()
        patterns.forEach { (label, regex) -> regex.findAll(text).forEach { found += Match(label, it.range) } }
        Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)").findAll(text).forEach {
            val digits = it.value.filter(Char::isDigit)
            if (digits.length in 13..19 && digits.first() in '2'..'6' && digits.toSet().size > 1 && luhn(digits)) found += Match("payment card", it.range)
        }
        knownSecrets().filter { it.length >= minimumSecretLength }.forEach { secret ->
            var at = text.indexOf(secret)
            while (at >= 0) { found += Match("stored credential", at until at + secret.length); at = text.indexOf(secret, at + secret.length) }
        }
        return found
    }
    fun requireSafe(text: String) { require(matches(text).isEmpty()) { "Sensitive credentials or identity/payment data cannot be sent by an agent. Use the secure credential handoff or complete this step yourself." } }
    /** Bounded text inspection for an outgoing file, including known values crossing read boundaries. */
    fun requireSafe(reader: java.io.Reader, maximumChars: Long) {
        require(maximumChars in 1..500_000_000L)
        val secrets = knownSecrets().filter { it.length >= minimumSecretLength }.toList()
        val longest = secrets.maxOfOrNull { it.length } ?: 0
        require(longest <= 65_536) { "A protected value exceeds the file inspection limit" }
        val guard = OutboundDlpGuard(minimumSecretLength) { secrets }
        val overlap = maxOf(512, longest)
        val buffer = CharArray(16_384)
        var carry = ""
        var total = 0L
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) return
            check(n > 0) { "File inspection stopped making progress" }
            total += n
            require(total <= maximumChars) { "The outgoing file exceeds the inspection limit" }
            val text = carry + String(buffer, 0, n)
            guard.requireSafe(text)
            carry = text.takeLast(overlap)
        }
    }
    fun redact(text: String): String {
        val ranges = matches(text).sortedBy { it.range.first }
        if (ranges.isEmpty()) return text
        return buildString {
            var position = 0
            for (match in ranges) {
                if (match.range.first < position) continue
                append(text.substring(position, match.range.first)); append("[REDACTED ${match.label}]")
                position = match.range.last + 1
            }
            append(text.substring(position))
        }
    }
    fun assess(op: Operation, context: ActionContext): RiskAssessment {
        val payload = payload(op) ?: context.outboundText ?: return RiskAssessment.NONE
        val findings = matches(payload)
        return if (findings.isEmpty()) RiskAssessment.NONE else RiskAssessment.of(RiskFinding(RiskCategory.SECRET_EXFILTRATION, RiskLevel.CRITICAL,
            "Outbound content contains ${findings.map { it.label }.distinct().joinToString()}. Use native secure handoff; do not put secrets into model/tool content."))
    }
    companion object {
        private val patterns = listOf(
            "API token" to Regex("\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\\b"),
            "private key" to Regex("-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----[\\s\\S]*?(?:-----END (?:[A-Z ]+ )?PRIVATE KEY-----|$)"),
            "password" to Regex("(?i)\\b(?:password|passwd|client_secret|api_key|access_token)[\"']?\\s*[:=]\\s*[\"']?[^\\s\"']{8,}"),
            "one-time code" to Regex("(?i)(?:verification code|one[- ]time (?:code|password)|2fa code|驗證碼|验证码|動態密碼|动态密码)\\s*[:：-]?\\s*\\d{4,8}\\b"),
            "government ID" to Regex("(?i)(?:passport|social security|身分證|身份證|身份证|護照|护照)\\s*(?:number|號碼|号码)?\\s*[:：#-]?\\s*(?=[A-Z0-9-]*\\d)[A-Z0-9-]{6,15}\\b"),
            "bank account" to Regex("(?i)(?:bank account|銀行帳號|银行账号)\\s*[:：#-]?\\s*(?=[A-Z0-9 -]{0,33}\\d)[A-Z0-9 -]{8,34}\\b"),
        )
        fun luhn(digits: String): Boolean {
            if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
            return digits.reversed().mapIndexed { i, c -> val n = c.digitToInt() * if (i % 2 == 1) 2 else 1; if (n > 9) n - 9 else n }.sum() % 10 == 0
        }
        fun payload(op: Operation): String? = when (op) {
            is Operation.TypeText -> op.text
            is Operation.Clipboard -> op.text
            is Operation.Shell -> if (Regex("(?i)\\b(curl|wget|scp|sftp|ssh|rclone|sendmail|fetch|requests|https?)\\b").containsMatchIn(op.command)) op.command + "\n" + op.stdin.orEmpty() else null
            else -> null
        }
    }
}
