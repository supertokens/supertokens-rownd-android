package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auth0.android.jwt.JWT
import io.rownd.android.models.domain.User
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSessionInstrumentedTest {
    private val fixture = EmailChangeReproFixture()

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() = fixture.tearDown()

    @Test
    fun restoredSessionLoadsProfileOnFirstResume() {
        val userId = fixture.uniqueUserId("startup")
        val session = HarnessClient.createSTSession(userId)
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, session)
        val refreshBefore = HarnessClient.getCounters().stRefresh

        val second = fixture.reopen(first)
        second.awaitStartup()
        val restored = requireNotNull(second.state.auth.accessToken)
        val sdkToken = runBlocking { SuperTokensSessionBridge.getAccessToken(fixture.context) }
        val initialClaims = JWT(session.accessToken)
        val restoredClaims = JWT(restored)
        assertTrue("Restored session handle must match the signed-in session", fixture.handle(session.accessToken) == fixture.handle(restored))
        assertTrue("Restored token must retain the expected subject", initialClaims.subject == restoredClaims.subject && restoredClaims.subject == userId)
        assertTrue("Restored token must retain the public tenant", initialClaims.getClaim("tId").asString() == "public" && restoredClaims.getClaim("tId").asString() == "public")
        assertTrue("SDK and Rownd must agree on the restored access token", sdkToken == restored)
        assertEquals("Restoring an unexpired session must not refresh", refreshBefore, HarnessClient.getCounters().stRefresh)
        assertEquals("Server must accept the restored access token", 200, fixture.profileStatus(restored))
        Thread.sleep(500)
        assertEquals("The profile must not load while backgrounded", User(), second.state.user)

        second.resume()
        fixture.waitUntil("Profile must load on the first resume", 10_000) {
            second.state.user.data["user_id"] == userId && second.state.user.data["email"] == "$userId@example.com"
        }
    }

    @Test
    fun appIsNotInitializedUntilTheStartupRefreshSettlesTheSession() {
        val session = HarnessClient.createSTSession(fixture.uniqueUserId("refresh-pending"))
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, session)
        fixture.advancePastAccessTokenExpiry()
        HarnessClient.armResponseGate("POST", REFRESH_PATH)

        val second = fixture.reopen(first)
        fixture.waitUntil("Startup must refresh the expired session", 10_000) { HarnessClient.responseGateReached() }

        assertFalse(
            "An initialized state without a session reads as signed out, but the session is still being verified",
            second.state.isInitialized,
        )

        HarnessClient.releaseResponseGate()
        second.awaitStartup()
        assertTrue(second.state.isInitialized)
        assertEquals(fixture.handle(session.accessToken), second.state.auth.accessToken?.let(fixture::handle))
    }

    @Test
    fun appWithoutSessionInitializesOnceStartupFindsNone() {
        val process = fixture.launch()
        process.awaitStartup()

        assertTrue(process.state.isInitialized)
        assertFalse(process.state.auth.isAuthenticated)
    }

    @Test
    fun resumeBeforeInitializationLoadsProfile() {
        val userId = fixture.uniqueUserId("resume-first")
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, HarnessClient.createSTSession(userId))

        val restoreGate = CompletableDeferred<Unit>()
        val second = fixture.reopen(first, restoreGate)
        second.resume()
        restoreGate.complete(Unit)
        second.awaitStartup()

        fixture.waitUntil("Profile must load when the app is already in the foreground", 10_000) {
            second.state.user.data["user_id"] == userId
        }
    }

    @Test
    fun sessionClearedBeforeResumeDoesNotLoadProfile() {
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, HarnessClient.createSTSession(fixture.uniqueUserId("cleared")))

        val second = fixture.reopen(first)
        second.awaitStartup()
        SuperTokensSessionBridge.clearLocalSession(fixture.context)
        second.resume()

        fixture.waitUntil("Resume must follow the cleared native session", 5_000) {
            !second.state.auth.isAuthenticated
        }
        Thread.sleep(500)
        assertEquals(User(), second.state.user)
    }

    @Test
    fun upgradeDiscardsPersistedSessionStateWithoutSigningOut() {
        val userId = fixture.uniqueUserId("upgrade")
        val stale = HarnessClient.createSTSession(userId)
        val current = HarnessClient.createSTSession(userId)
        HarnessClient.changeUserEmail(userId, "current-$userId@example.com")
        runBlocking {
            SuperTokensSessionBridge.bootstrapSession(fixture.context, current.accessToken, current.refreshToken)
        }

        val process = fixture.launchWithPersistedState(
            """{"auth":{"access_token":"${stale.accessToken}"},"user":{"data":{"user_id":"$userId","email":"old@example.com"}}}"""
        )
        process.awaitStartup()

        assertTrue("The current access token must remain installed", current.accessToken == process.state.auth.accessToken)
        assertEquals(User(), process.state.user)
        process.resume()
        fixture.waitUntil("Profile must come from the server", 10_000) {
            process.state.user.data["email"] == "current-$userId@example.com"
        }
    }

    @Test
    fun unreachableRefreshOnStartupKeepsTheStoredSession() {
        val session = HarnessClient.createSTSession(fixture.uniqueUserId("offline"))
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, session)
        HarnessClient.setRefreshUnavailable(true)
        try {
            fixture.advancePastAccessTokenExpiry()

            val second = fixture.reopen(first)
            second.awaitStartup()

            assertEquals(fixture.handle(session.accessToken), second.state.auth.accessToken?.let(fixture::handle))
            assertTrue("An unreachable server must not keep the app uninitialized", second.state.isInitialized)
            assertTrue(fixture.nativeSessionPresent())
        } finally {
            HarnessClient.setRefreshUnavailable(false)
        }
    }

    private companion object {
        const val REFRESH_PATH = "/auth/session/refresh"
    }
}
