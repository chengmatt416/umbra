package app.umbra.agent.notifications

/** Applied before content reaches disk or an agent. */
object NotificationPrivacy {
    enum class Policy { USE, CONTEXT, IGNORE }

    private val sensitiveApp = Regex("bank|wallet|authenticator|authy|password|bitwarden|1password|lastpass|finance|payment", RegexOption.IGNORE_CASE)
    private val codeContext = Regex("one.?time|verification|passcode|\\bcode\\b|\\bpin\\b|\\botp\\b|驗證碼|認證碼|验证码|認証コード|登入碼|登录码|動態碼|动态码", RegexOption.IGNORE_CASE)
    private val digits = Regex("(?<![\\p{L}\\d])[\\d][\\d -]{2,10}\\d(?![\\p{L}\\d])")
    private val mixed = Regex("(?<![\\p{L}\\d])[A-Za-z0-9]{4,10}(?![\\p{L}\\d])")

    fun defaultPolicy(packageName: String, messaging: Boolean): Policy = when {
        sensitiveApp.containsMatchIn(packageName) -> Policy.IGNORE
        messaging -> Policy.USE
        else -> Policy.CONTEXT
    }

    fun containsCode(text: String): Boolean = codeContext.containsMatchIn(text) &&
        (digits.containsMatchIn(text) || mixed.findAll(text).any { it.value.any(Char::isDigit) })

    fun redact(text: String, force: Boolean = false): String {
        if (!force && !codeContext.containsMatchIn(text)) return text
        return mixed.replace(digits.replace(text, "[code hidden]")) { match ->
            if (match.value.any(Char::isDigit)) "[code hidden]" else match.value
        }
    }
}
