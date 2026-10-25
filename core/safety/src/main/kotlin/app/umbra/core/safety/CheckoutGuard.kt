package app.umbra.core.safety

/** Checkout information is evidence for human review, never a model's claim that the merchant or total is verified. */
object CheckoutGuard {
    private val paymentField = Regex("(?i)card number|credit card|debit card|cvv|cvc|security code|卡號|卡号|安全碼|安全码")
    private val personalPayment = Regex("(?i)3[- ]?d secure|3ds|google pay|apple pay|one[- ]time (?:code|password)|verification code|驗證碼|验证码|動態密碼|动态密码")
    fun isPaymentField(context: ActionContext) = paymentField.containsMatchIn(context.targetNode?.label.orEmpty())
    fun requiresUser(context: ActionContext) = context.passwordFieldVisible || isPaymentField(context) || personalPayment.containsMatchIn(context.screenTexts.joinToString("\n"))
    fun details(context: ActionContext): Map<String, String> {
        val lines = context.screenTexts.map { OutboundDlpGuard().redact(it).take(500) }.filter { it.isNotBlank() }
        val total = lines.indexOfFirst { Regex("(?i)^(?:total|amount due|grand total|合計|合计|總計|总计|應付|应付)\\b|^(?:合計|合计|總計|总计|應付|应付)").containsMatchIn(it.trim()) }
        return mapOf(
            "checkout_service" to (context.recipientOrDomain ?: context.foregroundPackage ?: "Service not verified"),
            "checkout_total" to (if (total >= 0) lines.drop(total).take(3).joinToString(" · ") else "Total and currency not verified. Check the actual page."),
            "checkout_items" to lines.take(20).joinToString("\n"),
            "checkout_policy" to lines.filter { Regex("(?i)refund|cancel|return|delivery|shipping|fee|退|取消|運費|运费|費用|费用").containsMatchIn(it) }.joinToString("\n").ifBlank { "Refund/cancellation policy, fees and prerequisites have not been verified." },
        )
    }
}
