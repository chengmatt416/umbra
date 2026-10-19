package app.umbra.agent.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPrivacyTest {
    @Test fun `codes are removed before storage including localized and spaced codes`() {
        assertEquals("驗證碼：[code hidden]，請勿分享", NotificationPrivacy.redact("驗證碼：123456，請勿分享"))
        assertEquals("Your verification code is [code hidden].", NotificationPrivacy.redact("Your verification code is 123 456."))
        assertTrue(NotificationPrivacy.containsCode("Login code: 873201"))
        assertTrue(NotificationPrivacy.containsCode("Your code is A1B2C3"))
        assertEquals("Your code is [code hidden]", NotificationPrivacy.redact("Your code is A1B2C3"))
        assertEquals("Copy [code hidden]", NotificationPrivacy.redact("Copy 123456", force = true))
        assertFalse(NotificationPrivacy.containsCode("Dinner at 18:30"))
        assertEquals("Order 123456 has shipped", NotificationPrivacy.redact("Order 123456 has shipped"))
    }

    @Test fun `financial apps are ignored even if recognized as messaging`() {
        assertEquals(NotificationPrivacy.Policy.IGNORE, NotificationPrivacy.defaultPolicy("com.bank.app", true))
        assertEquals(NotificationPrivacy.Policy.USE, NotificationPrivacy.defaultPolicy("com.whatsapp", true))
        assertEquals(NotificationPrivacy.Policy.CONTEXT, NotificationPrivacy.defaultPolicy("com.calendar", false))
    }
}
