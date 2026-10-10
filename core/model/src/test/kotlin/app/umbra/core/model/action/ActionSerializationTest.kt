package app.umbra.core.model.action

import app.umbra.core.model.UmbraJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class ActionSerializationTest {
    @Test
    fun operationsSerializeCompactlyWithTypeDiscriminator() {
        assertEquals("""{"type":"tap"}""", UmbraJson.encodeToString<Operation>(Operation.Tap))
        assertEquals(
            """{"type":"shell","command":"ls -la"}""",
            UmbraJson.encodeToString<Operation>(Operation.Shell(command = "ls -la")),
        )
    }

    @Test
    fun operationRoundTripsThroughPolymorphicSerializer() {
        val op: Operation = Operation.Scroll(
            direction = Direction.UP,
            untilVisible = ElementQuery(textContains = "Settings"),
            maxScrolls = 3,
        )
        val decoded = UmbraJson.decodeFromString<Operation>(UmbraJson.encodeToString(op))
        assertEquals(op, decoded)
    }

    @Test
    fun actionRequestRoundTripsWithNestedExpectations() {
        val request = ActionRequest(
            id = "toolu_1",
            operation = Operation.TypeText(text = "hunter2", secret = true, submit = true),
            target = Target(
                environment = Environment.ANDROID,
                display = 4,
                app = "com.example.app",
                element = ElementRef(query = ElementQuery(resourceId = "password", editable = true)),
            ),
            requires = Privilege.ACCESSIBILITY,
            verify = Verification(
                expect = Expectation.AllOf(
                    listOf(
                        Expectation.TextGone("Sign in"),
                        Expectation.Not(Expectation.NoCrash("com.example.app")),
                    ),
                ),
                timeoutMs = 4_000,
            ),
            failure = FailurePolicy(retries = 2, fallbacks = listOf(Fallback.VISUAL, Fallback.PRIVILEGED)),
        )
        val json = UmbraJson.encodeToString(request)
        assertEquals(request, UmbraJson.decodeFromString<ActionRequest>(json))
    }

    @Test
    fun decodingIsLenientAboutUnknownKeysAndBadEnumValues() {
        // LLM-produced arguments: extra field, an enum value we do not know.
        val json = """{"type":"scroll","direction":"sideways","extra":"ignored"}"""
        val op = UmbraJson.decodeFromString<Operation>(json)
        assertIs<Operation.Scroll>(op)
        assertEquals(Direction.DOWN, op.direction, "unknown enum value falls back to the default")
    }

    @Test
    fun failureResultCarriesStructuredError() {
        val result = ActionResult.failure(
            id = "a1",
            code = ErrorCode.NOT_FOUND,
            message = "No element matched text=\"Login\"",
            retryable = true,
        )
        assertEquals(ActionStatus.FAILED, result.status)
        assertEquals(false, result.isOk)
        val error = assertNotNull(result.error)
        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertTrue(error.retryable)
        assertNull(result.verification)
    }

    @Test
    fun emptyQueryIsDetected() {
        assertTrue(ElementQuery().isEmpty)
        assertTrue(ElementQuery(index = 2).isEmpty, "an index alone does not make a query")
        assertFalse(ElementQuery(text = "x").isEmpty)
    }
}
