package io.rownd.android

import io.rownd.android.util.signInCompletedEventData
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventEmitter
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SignInCompletedEventDeduper
import io.rownd.android.util.signInCompletedDedupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RowndEventTest {

    @Test
    fun `sign in completed event data includes app variant user type fallback`() {
        val data = signInCompletedEventData(
            method = RowndSignInType.SignInLink,
            userType = RowndSignInUserType.ExistingUser,
        )

        assertEquals(RowndSignInType.SignInLink.value, data["method"])
        assertEquals(RowndSignInUserType.ExistingUser.value, data["user_type"])
        assertEquals(RowndSignInUserType.ExistingUser.value, data["app_variant_user_type"])
    }

    @Test
    fun `sign in completed event data omits user type fields when absent`() {
        val data = signInCompletedEventData(method = RowndSignInType.Google)

        assertEquals(RowndSignInType.Google.value, data["method"])
        assertFalse(data.containsKey("user_type"))
        assertFalse(data.containsKey("app_variant_user_type"))
    }

    @Test
    fun `sign in completed deduper suppresses hub event after native authentication`() {
        val deduper = SignInCompletedEventDeduper()

        assertTrue(deduper.shouldScheduleAuthenticationFallback())
        assertTrue(deduper.shouldEmitForAuthenticationFallback())
        assertFalse(deduper.shouldEmitForHubEvent())
        assertFalse(deduper.shouldScheduleAuthenticationFallback())
    }

    @Test
    fun `sign in completed deduper suppresses native authentication after hub event`() {
        val deduper = SignInCompletedEventDeduper()

        assertTrue(deduper.shouldEmitForHubEvent())
        assertFalse(deduper.shouldScheduleAuthenticationFallback())
        assertFalse(deduper.shouldEmitForAuthenticationFallback())
        assertFalse(deduper.shouldEmitForHubEvent())
    }

    @Test
    fun `sign in completed deduper suppresses refresh authentication after completion`() {
        val deduper = SignInCompletedEventDeduper()

        assertTrue(deduper.shouldScheduleAuthenticationFallback())
        assertTrue(deduper.shouldEmitForHubEvent())
        assertFalse(deduper.shouldScheduleAuthenticationFallback())
        assertFalse(deduper.shouldEmitForAuthenticationFallback())
    }

    @Test
    fun `sign in completed deduper allows next sign in after reset`() {
        val deduper = SignInCompletedEventDeduper()

        assertTrue(deduper.shouldEmitForHubEvent())
        deduper.reset()

        assertTrue(deduper.shouldEmitForHubEvent())
    }

    @Test
    fun `sign in completed dedup key is stable across refreshed access tokens`() {
        val initialToken = jwtWithPayload(
            """
                {
                  "iat": 1781271673,
                  "exp": 1781275273,
                  "sub": "user-id",
                  "sessionHandle": "session-id",
                  "refreshTokenHash1": "initial-refresh-hash"
                }
            """.trimIndent(),
        )
        val refreshedToken = jwtWithPayload(
            """
                {
                  "iat": 1781271678,
                  "exp": 1781275278,
                  "sub": "user-id",
                  "sessionHandle": "session-id",
                  "refreshTokenHash1": "refreshed-refresh-hash"
                }
            """.trimIndent(),
        )

        assertNotEquals(initialToken, refreshedToken)
        assertEquals("session-id", signInCompletedDedupKey(initialToken))
        assertEquals(signInCompletedDedupKey(initialToken), signInCompletedDedupKey(refreshedToken))
    }

    @Test
    fun `sign in completed dedup key falls back to token when jwt is invalid`() {
        assertEquals("not-a-jwt", signInCompletedDedupKey("not-a-jwt"))
    }

    @Test
    fun `event emitter suppresses sign in completed after auth refresh for same session`() {
        val emitter = RowndEventEmitter<RowndEvent>()
        val initialToken = jwtWithPayload(
            """
                {
                  "iat": 1781271673,
                  "exp": 1781275273,
                  "sub": "user-id",
                  "sessionHandle": "session-id"
                }
            """.trimIndent(),
        )
        val refreshedToken = jwtWithPayload(
            """
                {
                  "iat": 1781271678,
                  "exp": 1781275278,
                  "sub": "user-id",
                  "sessionHandle": "session-id"
                }
            """.trimIndent(),
        )
        var signInCompletedCount = 0
        emitter.addListener { event ->
            if (event.event == RowndEventType.SignInCompleted) {
                signInCompletedCount += 1
            }
        }

        emitter.emit(RowndEvent(RowndEventType.Auth, mapOf("access_token" to initialToken)))
        emitter.emit(RowndEvent(RowndEventType.SignInCompleted))
        emitter.emit(RowndEvent(RowndEventType.Auth, mapOf("access_token" to refreshedToken)))
        emitter.emit(RowndEvent(RowndEventType.SignInCompleted))

        assertEquals(1, signInCompletedCount)
    }

    private fun jwtWithPayload(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("{\"alg\":\"none\"}".toByteArray())
        val body = encoder.encodeToString(payload.toByteArray())
        return "$header.$body."
    }
}
