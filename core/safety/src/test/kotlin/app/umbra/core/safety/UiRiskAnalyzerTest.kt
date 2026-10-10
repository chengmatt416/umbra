package app.umbra.core.safety

import app.umbra.core.safety.RiskCategory.CREDENTIAL_SUBMISSION
import app.umbra.core.safety.RiskCategory.FACTORY_RESET
import app.umbra.core.safety.RiskCategory.FINANCIAL_TRANSACTION
import app.umbra.core.safety.RiskCategory.IMPORTANT_DATA_DELETION
import app.umbra.core.safety.RiskCategory.INSTALLS_UNTRUSTED_APP
import app.umbra.core.safety.RiskCategory.PUBLISHES_CONTENT
import app.umbra.core.safety.RiskCategory.PURCHASE
import app.umbra.core.safety.RiskCategory.SENDS_MESSAGE
import app.umbra.core.safety.RiskCategory.SENSITIVE_DATA_EXPOSURE
import app.umbra.core.safety.RiskLevel.CRITICAL
import app.umbra.core.safety.RiskLevel.HIGH
import app.umbra.core.safety.RiskLevel.MEDIUM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiRiskAnalyzerTest {
    private val analyzer = UiRiskAnalyzer()

    private fun tap(label: String, pkg: String? = "com.example.app", password: Boolean = false, screen: List<String> = emptyList()) =
        analyzer.analyzeTap(ActionContext(foregroundPackage = pkg, targetNode = button(label), passwordFieldVisible = password, screenTexts = screen))

    private fun List<RiskFinding>.has(category: RiskCategory, level: RiskLevel) = any { it.category == category && it.level >= level }

    @Test
    fun purchaseButtonsAreHighRisk() {
        for (label in listOf("Buy now", "Pay", "Place order", "Subscribe", "Start free trial", "Confirm payment", "立即購買", "支付", "結帳", "订阅")) {
            assertTrue(tap(label).has(PURCHASE, HIGH), "$label -> ${tap(label)}")
        }
    }

    @Test
    fun longDescriptiveLabelsAreNotPaymentButtons() {
        assertTrue(tap("Pay attention to the road ahead please").none { it.category == PURCHASE })
        assertTrue(tap("Add to cart").none { it.category == PURCHASE })
    }

    @Test
    fun financialTransfersAreHighRisk() {
        for (label in listOf("Send money", "Transfer", "Withdraw", "轉帳", "提现")) {
            assertTrue(tap(label).has(FINANCIAL_TRANSACTION, HIGH), label)
        }
    }

    @Test
    fun sendIsMediumEverywhereAndHighInMessagingApps() {
        assertTrue(tap("Send").has(SENDS_MESSAGE, MEDIUM) && !tap("Send").has(SENDS_MESSAGE, HIGH))
        assertTrue(tap("Send", pkg = "com.whatsapp").has(SENDS_MESSAGE, HIGH))
        assertTrue(tap("傳送", pkg = "jp.naver.line.android").has(SENDS_MESSAGE, HIGH))
    }

    @Test
    fun postIsHighInSocialApps() {
        assertTrue(tap("Post", pkg = "com.twitter.android").has(PUBLISHES_CONTENT, HIGH))
        assertTrue(tap("Publish").has(PUBLISHES_CONTENT, MEDIUM))
    }

    @Test
    fun loginIsCredentialSubmissionOnlyWhenAPasswordFieldIsVisible() {
        assertTrue(tap("Log in", password = true).has(CREDENTIAL_SUBMISSION, HIGH))
        assertTrue(tap("Sign in", password = true).has(CREDENTIAL_SUBMISSION, HIGH))
        assertTrue(tap("Log in", password = false).none { it.category == CREDENTIAL_SUBMISSION })
    }

    @Test
    fun genericConfirmOnACheckoutScreenIsThePurchaseButton() {
        val checkout = listOf("Order summary", "Total", "$19.99", "Payment method")
        assertTrue(tap("Confirm", screen = checkout).has(PURCHASE, HIGH))
        assertTrue(tap("Confirm", screen = listOf("Are you sure?")).none { it.category == PURCHASE })
    }

    @Test
    fun resetsAndAccountDeletionAreCritical() {
        assertTrue(tap("Factory reset").has(FACTORY_RESET, CRITICAL))
        assertTrue(tap("恢复出厂设置").has(FACTORY_RESET, CRITICAL))
        assertTrue(tap("Delete account").has(IMPORTANT_DATA_DELETION, CRITICAL))
    }

    @Test
    fun deleteIsMediumAndDeleteAllIsHigh() {
        assertTrue(tap("Delete").has(IMPORTANT_DATA_DELETION, MEDIUM))
        assertEquals(MEDIUM, tap("Delete").maxOf { it.level })
        assertTrue(tap("Delete all").has(IMPORTANT_DATA_DELETION, HIGH))
        assertTrue(tap("Clear data").has(IMPORTANT_DATA_DELETION, HIGH))
    }

    @Test
    fun installButtonInThePackageInstallerIsHighRisk() {
        assertTrue(tap("Install", pkg = "com.google.android.packageinstaller").has(INSTALLS_UNTRUSTED_APP, HIGH))
        assertTrue(tap("Install", pkg = "com.example.app").none { it.category == INSTALLS_UNTRUSTED_APP })
    }

    @Test
    fun harmlessTapsHaveNoFindings() {
        for (label in listOf("OK", "Cancel", "Back", "Search", "Settings", "Menu", "Open", "Next")) {
            assertEquals(emptyList(), tap(label), label)
        }
    }

    @Test
    fun typingIntoPasswordFieldsOrSecretsIsCredentialSubmission() {
        val ctx = ActionContext(targetNode = passwordField())
        assertTrue(analyzer.analyzeType(ctx, secret = false, submit = false).has(CREDENTIAL_SUBMISSION, HIGH))
        assertTrue(analyzer.analyzeType(ActionContext(), secret = true, submit = false).has(CREDENTIAL_SUBMISSION, HIGH))
        assertTrue(analyzer.analyzeType(ActionContext(passwordFieldVisible = true), secret = false, submit = true).has(CREDENTIAL_SUBMISSION, HIGH))
        assertTrue(analyzer.analyzeType(ActionContext(), secret = false, submit = false).isEmpty())
    }

    @Test
    fun submittingTextInAMessagingAppSendsIt() {
        val ctx = ActionContext(foregroundPackage = "org.telegram.messenger")
        assertTrue(analyzer.analyzeType(ctx, secret = false, submit = true).has(SENDS_MESSAGE, HIGH))
        assertTrue(analyzer.analyzeType(ctx, secret = false, submit = false).none { it.category == SENDS_MESSAGE })
    }

    @Test
    fun sensitiveAppsAreFlaggedForInputAndObservation() {
        for (pkg in listOf("com.bitwarden.app", "com.chase.sig.android.bank", "com.google.android.apps.authenticator2", "com.coinbase.android")) {
            assertTrue(analyzer.isSensitiveApp(pkg), pkg)
            assertTrue(tap("Open", pkg = pkg).has(SENSITIVE_DATA_EXPOSURE, HIGH), pkg)
            assertTrue(analyzer.analyzeObservation(ActionContext(foregroundPackage = pkg)).has(SENSITIVE_DATA_EXPOSURE, MEDIUM), pkg)
        }
        assertEquals(false, analyzer.isSensitiveApp("com.example.notes"))
        assertTrue(analyzer.analyzeObservation(ActionContext(foregroundPackage = "com.example.notes")).isEmpty())
    }
}
