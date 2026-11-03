package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reproductions for Pylon #3832: after an email change the app shows the old account and email,
 * Manage Account opens with an empty email, and the user is signed out only on the second reopen.
 *
 * The email change completes outside the app (the verification link opens in a browser), so the
 * server revokes the session the app holds and the app never receives the replacement session.
 */
@RunWith(AndroidJUnit4::class)
class EmailChange3832ReproInstrumentedTest {
    private val fixture = EmailChangeReproFixture()

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() = fixture.tearDown()

    @Test
    fun customerFlowIsConsistentFromTheFirstReopen() {
        val userId = fixture.uniqueUserId("customer")
        val newEmail = "changed-$userId@example.com"
        val session = HarnessClient.createSTSession(userId)

        val first = fixture.launch()
        first.awaitStartup()
        first.resume()
        fixture.signInThroughHub(first, session)
        assertEquals("$userId@example.com", first.state.user.data["email"])

        HarnessClient.changeUserEmail(userId, newEmail)
        HarnessClient.revokeSession(fixture.handle(session.accessToken))

        val timeline = mutableListOf<String>()
        val problems = mutableListOf<String>()

        val second = fixture.reopen(first)
        second.awaitStartup()
        assertTrue("Precondition: cached access token is still valid on the first reopen", second.state.auth.isAccessTokenValid)
        second.resume()
        val firstReopenConsistent = fixture.eventually(10_000) { isConsistent(second, newEmail) }
        timeline += "reopen 1: ${describe(second)}"
        if (!firstReopenConsistent) problems += "reopen 1 shows a stale signed-in state"
        fixture.rphInit(second)?.let { rphInit ->
            fixture.rphInitProblems(rphInit, userId).forEach { problems += "reopen 1 rph_init: $it" }
        }
        val signedInAfterFirstReopen = second.state.auth.isAuthenticated

        fixture.advancePastAccessTokenExpiry()
        val third = fixture.reopen(second)
        third.awaitStartup()
        third.resume()
        fixture.eventually(10_000) { isConsistent(third, newEmail) }
        timeline += "reopen 2: ${describe(third)}"
        if (signedInAfterFirstReopen && !third.state.auth.isAuthenticated) {
            problems += "signed out only on the second reopen"
        }

        assertTrue("Problems: $problems\nTimeline:\n${timeline.joinToString("\n")}", problems.isEmpty())
    }

    @Test
    fun reopenShowsEmailChangedOnTheServer() {
        val userId = fixture.uniqueUserId("profile")
        val newEmail = "changed-$userId@example.com"
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, HarnessClient.createSTSession(userId))
        HarnessClient.changeUserEmail(userId, newEmail)

        val second = fixture.reopen(first)
        second.awaitStartup()
        second.resume()

        fixture.waitUntil("Reopen must hydrate the profile; still showing ${second.state.user.data["email"]}", 10_000) {
            second.state.user.data["email"] == newEmail
        }
    }

    @Test
    fun reopenAfterServerRevocationSignsOutOnTheFirstReopen() {
        val userId = fixture.uniqueUserId("revoked")
        val session = HarnessClient.createSTSession(userId)
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, session)
        HarnessClient.revokeSession(fixture.handle(session.accessToken))

        val second = fixture.reopen(first)
        second.awaitStartup()
        second.resume()

        fixture.waitUntil("Revoked session must sign out on the first reopen; state: ${describe(second)}", 10_000) {
            !second.state.auth.isAuthenticated && second.state.user.data.isEmpty() && !fixture.nativeSessionPresent()
        }
    }

    @Test
    fun manageAccountAfterReopenGetsAUsableRphInit() {
        val userId = fixture.uniqueUserId("rph")
        val newEmail = "changed-$userId@example.com"
        val session = HarnessClient.createSTSession(userId)
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, session)
        HarnessClient.changeUserEmail(userId, newEmail)
        HarnessClient.revokeSession(fixture.handle(session.accessToken))

        val second = fixture.reopen(first)
        second.awaitStartup()
        second.resume()
        fixture.eventually(10_000) { isConsistent(second, newEmail) }

        val rphInit = fixture.rphInit(second)
        val problems = rphInit?.let { fixture.rphInitProblems(it, userId) }.orEmpty()
        assertTrue("Manage Account rph_init is unusable: $problems", problems.isEmpty())
    }

    @Test
    fun startupRestoreCannotOverwriteANewerSignIn() {
        val userId = fixture.uniqueUserId("restore")
        val newEmail = "changed-$userId@example.com"
        val first = fixture.launch()
        first.awaitStartup()
        fixture.signInThroughHub(first, HarnessClient.createSTSession(userId))

        val restoreGate = CompletableDeferred<Unit>()
        val second = fixture.reopen(first, restoreGate)
        HarnessClient.changeUserEmail(userId, newEmail)
        val replacement = HarnessClient.createSTSession(userId)
        fixture.signInThroughHub(second, replacement)
        assertEquals(newEmail, second.state.user.data["email"])

        restoreGate.complete(Unit)
        second.awaitStartup()

        // Every profile response reissues the session's access token, so the stored token is
        // the sign-in's session, not necessarily its exact token string.
        val nativeToken = runBlocking { SuperTokensSessionBridge.getAccessToken(fixture.context) }
        val stateToken = second.state.auth.accessToken
        assertTrue(
            "Restored state replaced the newer sign-in: ${describe(second)}, native session ${nativeToken?.let(fixture::handle)}",
            SuperTokensSessionBridge.sameSession(stateToken, replacement.accessToken) && second.state.user.data["email"] == newEmail &&
                SuperTokensSessionBridge.sameSession(nativeToken, replacement.accessToken),
        )
    }

    private fun isConsistent(process: EmailChangeReproFixture.AppProcess, newEmail: String): Boolean {
        val auth = process.state.auth
        val token = auth.accessToken
        val signedOut = token == null && process.state.user.data.isEmpty() && !fixture.nativeSessionPresent()
        val signedInCurrent = token != null && fixture.profileStatus(token) == 200 &&
            process.state.user.data["email"] == newEmail
        return signedOut || signedInCurrent
    }

    private fun describe(process: EmailChangeReproFixture.AppProcess): String {
        val token = process.state.auth.accessToken
        return "signedIn=${token != null}, session=${token?.let(fixture::handle)}, " +
            "serverAcceptsToken=${token?.let { fixture.profileStatus(it) == 200 }}, " +
            "email=${process.state.user.data["email"]}, nativeSession=${fixture.nativeSessionPresent()}"
    }
}
