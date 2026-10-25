package app.umbra.core.safety

import app.umbra.core.safety.RiskCategory.ACCOUNT_SECURITY_CHANGE
import app.umbra.core.safety.RiskCategory.CREDENTIAL_SUBMISSION
import app.umbra.core.safety.RiskCategory.DEVICE_SECURITY_COMPROMISE
import app.umbra.core.safety.RiskCategory.FACTORY_RESET
import app.umbra.core.safety.RiskCategory.FINANCIAL_TRANSACTION
import app.umbra.core.safety.RiskCategory.IMPORTANT_DATA_DELETION
import app.umbra.core.safety.RiskCategory.INSTALLS_UNTRUSTED_APP
import app.umbra.core.safety.RiskCategory.PUBLISHES_CONTENT
import app.umbra.core.safety.RiskCategory.PURCHASE
import app.umbra.core.safety.RiskCategory.SENDS_MESSAGE
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskCategory.SYSTEM_CONFIGURATION
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.LOW
import app.umbra.core.safety.RiskLevel.MEDIUM

/**
 * Reads the intent of a tap or a typed value from the target's label, the visible screen text and
 * the foreground app (spec §28). Keyword tables cover English, Traditional and Simplified Chinese.
 * This is deliberately conservative and cheap; a Laya `noul` refinement can sit on top of it.
 */
class UiRiskAnalyzer {
    fun analyzeTap(ctx: ActionContext): List<RiskFinding> {
        val node = ctx.targetNode
        val label = node?.label?.lowercase()?.trim().orEmpty()
        val out = mutableListOf<RiskFinding>()
        val pkg = ctx.foregroundPackage.orEmpty()

        if (label.isNotEmpty()) {
            if (matches(label, FACTORY_RESET_WORDS)) out += finding(FACTORY_RESET, CRITICAL, label, "resets the device")
            if (matches(label, DELETE_ACCOUNT_WORDS)) out += finding(IMPORTANT_DATA_DELETION, CRITICAL, label, "deletes an account")
            if (matches(label, ACCOUNT_SECURITY_WORDS)) out += finding(ACCOUNT_SECURITY_CHANGE, HIGH, label, "changes account security")
            if (matches(label, FINANCIAL_WORDS)) out += finding(FINANCIAL_TRANSACTION, CRITICAL, label, "moves money")
            if (matches(label, PURCHASE_WORDS)) {
                out += finding(PURCHASE, CRITICAL, label, "completes a purchase or subscription")
                out += finding(FINANCIAL_TRANSACTION, CRITICAL, label, "submits a binding checkout")
            }
            if (matches(label, DEVICE_SECURITY_WORDS)) out += finding(DEVICE_SECURITY_COMPROMISE, HIGH, label, "weakens device security")
            if (matches(label, DELETE_ALL_WORDS)) out += finding(IMPORTANT_DATA_DELETION, HIGH, label, "deletes data")
            else if (matches(label, DELETE_WORDS)) out += finding(IMPORTANT_DATA_DELETION, MEDIUM, label, "deletes data")

            if (matches(label, PUBLISH_WORDS)) {
                out += finding(PUBLISHES_CONTENT, if (pkg in SOCIAL_PACKAGES) HIGH else MEDIUM, label, "publishes content")
            }
            if (matches(label, SEND_WORDS)) {
                out += finding(SENDS_MESSAGE, if (pkg in MESSAGING_PACKAGES) HIGH else MEDIUM, label, "sends a message")
            }
            if (matches(label, CREDENTIAL_WORDS) && ctx.passwordFieldVisible) {
                out += finding(CREDENTIAL_SUBMISSION, HIGH, label, "submits credentials")
            }
            if (pkg in INSTALLER_PACKAGES && matches(label, INSTALL_WORDS)) {
                out += finding(INSTALLS_UNTRUSTED_APP, HIGH, label, "installs an app from the package installer")
            }
            if (pkg in PERMISSION_PACKAGES && matches(label, ALLOW_WORDS)) {
                out += finding(SYSTEM_CONFIGURATION, MEDIUM, label, "grants a permission")
            }
            // A generic confirm button on a checkout screen is the purchase button.
            if (matches(label, CONFIRM_WORDS) && looksLikeCheckout(ctx.screenTexts)) {
                out += finding(PURCHASE, CRITICAL, label, "confirms a payment screen showing a price")
                out += finding(FINANCIAL_TRANSACTION, CRITICAL, label, "submits a binding checkout")
            }
        }
        if (isSensitiveApp(pkg)) {
            out += RiskFinding(SENSITIVE_DATA_EXPOSURE, HIGH, "interacts with a banking, wallet or password-manager app ($pkg)")
        }
        return out
    }

    fun analyzeType(ctx: ActionContext, secret: Boolean, submit: Boolean): List<RiskFinding> {
        val node = ctx.targetNode
        val out = mutableListOf<RiskFinding>()
        val pkg = ctx.foregroundPackage.orEmpty()
        if (CheckoutGuard.isPaymentField(ctx)) out += RiskFinding(FINANCIAL_TRANSACTION, CRITICAL, "Payment-card entry requires native user takeover")
        if (node?.isPassword == true || secret) {
            out += RiskFinding(CREDENTIAL_SUBMISSION, HIGH, "types into a password field or a value marked secret")
        } else if (submit && ctx.passwordFieldVisible) {
            out += RiskFinding(CREDENTIAL_SUBMISSION, HIGH, "submits a form that contains a password field")
        }
        if (isSensitiveApp(pkg)) {
            out += RiskFinding(SENSITIVE_DATA_EXPOSURE, HIGH, "types into a banking, wallet or password-manager app ($pkg)")
        }
        if (submit && pkg in MESSAGING_PACKAGES) {
            out += RiskFinding(SENDS_MESSAGE, HIGH, "typing with submit in a messaging app sends the message")
        }
        return out
    }

    fun analyzeObservation(ctx: ActionContext): List<RiskFinding> =
        if (isSensitiveApp(ctx.foregroundPackage.orEmpty())) {
            listOf(RiskFinding(SENSITIVE_DATA_EXPOSURE, MEDIUM, "screen content of ${ctx.foregroundPackage} may be sent to a cloud model"))
        } else {
            emptyList()
        }

    fun isSensitiveApp(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        val lower = pkg.lowercase()
        return SENSITIVE_PACKAGE_FRAGMENTS.any { lower.contains(it) }
    }

    private fun looksLikeCheckout(texts: List<String>): Boolean {
        val joined = texts.joinToString(" ").lowercase()
        return PRICE.containsMatchIn(joined) && CHECKOUT_WORDS.any { joined.contains(it) }
    }

    private fun finding(category: RiskCategory, level: RiskLevel, label: String, what: String) =
        RiskFinding(category, level, "tapping \"$label\" $what")

    private fun matches(label: String, words: List<String>): Boolean = words.any { phrase(label, it) }

    /**
     * Whole-word match for ASCII phrases, substring match for CJK. A single-word keyword only counts
     * on short labels (a button), so "Pay attention to the road" is not a payment button.
     */
    private fun phrase(label: String, word: String): Boolean {
        if (word.any { it.code > 0x2E80 }) return label.contains(word)
        if (label == word) return true
        val words = label.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        val maxWords = if (word.contains(' ')) MAX_PHRASE_LABEL_WORDS else MAX_WORD_LABEL_WORDS
        if (words.size > maxWords) return false
        return Regex("(^|[^\\p{L}\\p{N}])${Regex.escape(word)}($|[^\\p{L}\\p{N}])").containsMatchIn(label)
    }

    private companion object {
        const val MAX_WORD_LABEL_WORDS = 3
        const val MAX_PHRASE_LABEL_WORDS = 8

        val PRICE = Regex("""[$€£¥₩₹]\s?\d|\d+([.,]\d{2})?\s?(usd|eur|gbp|twd|nt\$|jpy|cny|rmb|元|塊)""")
        val CHECKOUT_WORDS = listOf("total", "order summary", "payment method", "card number", "billing", "subtotal", "checkout", "訂單", "订单", "付款方式", "合計", "合计")

        val PURCHASE_WORDS = listOf(
            "buy now", "buy", "purchase", "place order", "place your order", "pay now", "pay", "checkout", "check out",
            "subscribe", "start subscription", "start free trial", "confirm purchase", "confirm order", "complete purchase",
            "order now", "get premium", "upgrade now", "confirm payment",
            "submit binding reservation", "authorize payment", "授權扣款", "授权扣款",
            "支付", "付款", "購買", "购买", "立即購買", "立即购买", "結帳", "结账", "下單", "下单", "訂閱", "订阅", "確認付款", "确认付款",
        )
        val FINANCIAL_WORDS = listOf(
            "send money", "transfer", "transfer funds", "withdraw", "deposit", "wire", "send payment", "swap", "trade", "convert",
            "轉帳", "转账", "提款", "提現", "提现", "匯款", "汇款", "提領",
        )
        val SEND_WORDS = listOf("send", "send message", "send email", "reply", "reply all", "傳送", "发送", "發送", "回覆", "回复")
        val PUBLISH_WORDS = listOf("publish", "post", "tweet", "share publicly", "go live", "post to", "發布", "发布", "貼文", "发帖")
        val CREDENTIAL_WORDS = listOf("sign in", "log in", "login", "sign up", "register", "authenticate", "登入", "登录", "註冊", "注册")
        val ACCOUNT_SECURITY_WORDS = listOf(
            "change password", "reset password", "set new password", "two-factor", "2fa", "recovery email", "recovery phone",
            "remove phone number", "sign out of all", "log out of all", "turn off two", "disable two", "security key", "add passkey",
            "更改密碼", "更改密码", "重設密碼", "重置密码", "雙重驗證", "双重验证",
        )
        val DELETE_ACCOUNT_WORDS = listOf("delete account", "delete my account", "deactivate account", "close account", "刪除帳號", "删除账号", "刪除帳戶", "停用帳號")
        val FACTORY_RESET_WORDS = listOf(
            "factory reset", "erase all data", "reset phone", "reset device", "wipe data", "erase everything", "reset all settings",
            "恢復原廠", "恢复出厂", "清除所有資料", "清除所有数据", "抹掉所有",
        )
        val DELETE_ALL_WORDS = listOf("delete all", "clear all", "empty trash", "clear data", "clear storage", "uninstall", "erase", "清除資料", "清除数据", "卸載", "卸载")
        val DELETE_WORDS = listOf("delete", "remove", "刪除", "删除")
        val DEVICE_SECURITY_WORDS = listOf(
            "turn off play protect", "install unknown apps", "allow from this source", "unknown sources", "remove screen lock",
            "disable screen lock", "usb debugging", "activate device admin", "make this device admin", "display over other apps",
            "all files access", "allow all the time", "允許安裝未知", "允许安装未知", "無障礙", "无障碍",
        )
        val INSTALL_WORDS = listOf("install", "update", "install anyway", "安裝", "安装", "更新")
        val ALLOW_WORDS = listOf("allow", "while using the app", "only this time", "allow all the time", "允許", "允许")
        val CONFIRM_WORDS = listOf("confirm", "continue", "ok", "next", "yes", "complete", "submit", "done", "place", "確認", "确认", "繼續", "继续")

        val MESSAGING_PACKAGES = setOf(
            "com.google.android.gm", "com.google.android.apps.messaging", "com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.microsoft.office.outlook", "com.discord", "com.slack", "jp.naver.line.android", "com.tencent.mm", "com.facebook.orca",
            "com.instagram.android", "com.twitter.android", "com.android.mms", "com.samsung.android.messaging", "com.skype.raider",
        )
        val SOCIAL_PACKAGES = setOf(
            "com.twitter.android", "com.instagram.android", "com.facebook.katana", "com.linkedin.android", "com.reddit.frontpage",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.pinterest", "com.tumblr", "app.bsky", "org.joinmastodon.android",
        )
        val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
        )
        val PERMISSION_PACKAGES = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.android.packageinstaller")
        val SENSITIVE_PACKAGE_FRAGMENTS = listOf(
            "bank", "wallet", "bitwarden", "lastpass", "1password", "onepassword", "keepass", "authenticator", "authy", "binance",
            "coinbase", "metamask", "robinhood", "venmo", "cash.app", "paypal", "revolut", "wise", "alipay", "twallet", "lineplay",
        )
    }
}
