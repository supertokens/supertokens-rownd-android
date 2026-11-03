package io.rownd.android

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndJavascriptInterface
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * Pylon #3832: what a Hub document may write into the app is decided by the session the document
 * was given, and the Hub is given exactly the SDK's session (or none, for a deep link).
 */
@RunWith(AndroidJUnit4::class)
class HubDocumentSessionInstrumentedTest {
    private val fixture = EmailChangeReproFixture()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val webViews = mutableListOf<RowndWebView>()

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() {
        if (webViews.isNotEmpty()) instrumentation.runOnMainSync { webViews.forEach(RowndWebView::destroy) }
        webViews.clear()
        fixture.tearDown()
    }

    @Test
    fun deepLinkDocumentHasNoSessionAndCannotWriteUserDataUntilItAuthenticates() {
        val userId = fixture.uniqueUserId("deep-link")
        val process = signedInProcess(userId)
        val before = process.state.user
        process.client.config.pendingHubDeepLinkUrl = "$REPRO_HUB_URL/mobile_app"

        val request = runBlocking { process.client.config.hubLoaderRequest() }

        assertEquals("A deep link loads verbatim, without rph_init", "$REPRO_HUB_URL/mobile_app", request.url)
        assertNull(request.accessToken)
        val document = document(process, HubPageSelector.DeepLink, request.accessToken)
        document.postSecureMessage(fixture.userDataUpdateMessage(mapOf("user_id" to userId, "email" to "unauthenticated@example.com")))
        assertEquals("A document without a session wrote a profile", before, process.state.user)

        // The profile the deep link's session loads differs from the cached one, which marks its arrival.
        val loadedEmail = "changed-$userId@example.com"
        HarnessClient.changeUserEmail(userId, loadedEmail)
        val replacement = HarnessClient.createSTSession(userId)
        document.postSecureMessage(fixture.authenticationMessage(replacement))
        fixture.waitUntil("The deep link's sign-in must be adopted and its profile loaded", 10_000) {
            process.state.auth.accessToken == replacement.accessToken &&
                process.state.user.data["email"] == loadedEmail && !process.state.user.isLoading
        }

        document.postSecureMessage(fixture.userDataUpdateMessage(mapOf("user_id" to userId, "email" to "after-sign-in@example.com")))
        assertEquals("after-sign-in@example.com", process.state.user.data["email"])
    }

    @Test
    fun authChallengesFromADocumentOfAReplacedSessionAreIgnored() {
        val userId = fixture.uniqueUserId("challenge")
        val process = signedInProcess(userId)
        val oldDocument = document(process, HubPageSelector.ManageAccount, process.state.auth.accessToken)
        fixture.signInThroughHub(process, HarnessClient.createSTSession(userId))

        oldDocument.postSecureMessage(challengeInitiated("old-challenge"))
        assertNull("Challenge from the replaced session's document was stored", process.state.auth.challengeId)

        val currentDocument = document(process, HubPageSelector.ManageAccount, process.state.auth.accessToken)
        currentDocument.postSecureMessage(challengeInitiated("current-challenge"))
        assertEquals("current-challenge", process.state.auth.challengeId)

        oldDocument.postSecureMessage("""{"type":"auth_challenge_cleared"}""")
        assertEquals("The replaced session's document cleared the current challenge", "current-challenge", process.state.auth.challengeId)
    }

    @Test
    fun rphInitComesFromTheSdkSessionEvenIfTheStoreHasNotAdoptedItYet() {
        val userId = fixture.uniqueUserId("rph-init")
        val process = fixture.launch()
        process.awaitStartup()
        val session = HarnessClient.createSTSession(userId)
        SuperTokensSessionBridge.bootstrapSession(fixture.context, session.accessToken, session.refreshToken)
        assertFalse("The store must not know the session yet", process.state.auth.isAuthenticated)

        val rphInit = requireNotNull(fixture.rphInit(process)) { "The SDK has a session, so the Hub must receive it" }

        assertEquals(session.accessToken, rphInit.getString("access_token"))
        assertEquals(session.refreshToken, rphInit.getString("refresh_token"))
        assertTrue("Without a cached profile the Hub resolves the user from the token", rphInit.isNull("app_user_id"))
        assertEquals(200, fixture.profileStatus(rphInit.getString("access_token")))
        assertEquals(session.accessToken, process.state.auth.accessToken)
    }

    @Test
    fun hubOpenedWithoutSessionRestoreGetsNoRphInit() {
        val process = signedInProcess(fixture.uniqueUserId("no-restore"))

        val url = runBlocking { process.client.config.hubLoaderUrl(restoreSession = false) }

        assertNull(url.toUri().encodedFragment)
    }

    private fun signedInProcess(userId: String): EmailChangeReproFixture.AppProcess {
        val process = fixture.launch()
        process.awaitStartup()
        fixture.signInThroughHub(process, HarnessClient.createSTSession(userId))
        return process
    }

    private fun document(
        process: EmailChangeReproFixture.AppProcess,
        targetPage: HubPageSelector,
        accessToken: String?,
    ): RowndJavascriptInterface {
        val view = AtomicReference<RowndWebView>()
        instrumentation.runOnMainSync {
            view.set(RowndWebView(fixture.context, null).apply {
                rowndClient = process.client
                this.targetPage = targetPage
                dismiss = {}
                documentAccessToken = accessToken
            })
        }
        webViews += view.get()
        return view.get().rowndJavascriptInterface
    }

    private fun challengeInitiated(challengeId: String) =
        """{"type":"auth_challenge_initiated","payload":{"challenge_id":"$challengeId","user_identifier":"user@example.com"}}"""
}
