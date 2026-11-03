package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.rownd.android.models.domain.User
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

        val second = fixture.reopen(first)
        second.awaitStartup()
        assertEquals(session.accessToken, second.state.auth.accessToken)
        Thread.sleep(500)
        assertEquals("The profile must not load while backgrounded", User(), second.state.user)

        second.resume()
        fixture.waitUntil("Profile must load on the first resume", 10_000) {
            second.state.user.data["user_id"] == userId
        }
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

        assertEquals(current.accessToken, process.state.auth.accessToken)
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
            assertTrue(fixture.nativeSessionPresent())
        } finally {
            HarnessClient.setRefreshUnavailable(false)
        }
    }
}
