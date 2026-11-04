package io.rownd.android

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.NewIntentTestActivity
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
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
import java.util.concurrent.CopyOnWriteArrayList
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
    private val startedUrls = CopyOnWriteArrayList<String>()
    private var scenario: ActivityScenario<NewIntentTestActivity>? = null

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() {
        if (webViews.isNotEmpty()) instrumentation.runOnMainSync { webViews.forEach(RowndWebView::destroy) }
        webViews.clear()
        scenario?.close()
        fixture.tearDown()
    }

    @Test
    fun deepLinkDocumentIsStampedWithNoSessionWhenItStartsLoadingAndCannotWriteUserDataUntilItAuthenticates() {
        val userId = fixture.uniqueUserId("deep-link")
        val process = signedInProcess(userId)
        val before = process.state.user
        val view = hubView(process, HubPageSelector.DeepLink).also(::attach)
        assertTrue("A view starts out stamped with the session the app has", process.state.auth.accessToken == view.documentAccessToken)

        process.client.config.pendingHubDeepLinkUrl = "$REPRO_HUB_URL/mobile_app"
        instrumentation.runOnMainSync { view.loadNewPage(HubPageSelector.DeepLink, null) }
        fixture.waitUntil("The deep link document must start loading") { startedUrls.any { it.startsWith("$REPRO_HUB_URL/mobile_app?") } }

        assertNull("A deep link loads verbatim, without rph_init, so its document has no session", view.documentAccessToken)
        val document = view.rowndJavascriptInterface
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
    fun reloadedDocumentIsStampedWithTheSessionItsRphInitCarries() {
        val userId = fixture.uniqueUserId("reload")
        val process = signedInProcess(userId)
        val original = process.state.auth.accessToken!!
        val view = hubView(process, HubPageSelector.ManageAccount).also(::attach)
        val replacement = HarnessClient.createSTSession(userId)
        fixture.signInThroughHub(process, replacement)
        assertEquals("The document predates the replacement", fixture.handle(original), fixture.handle(view.documentAccessToken!!))
        view.rowndJavascriptInterface.postSecureMessage(challengeInitiated("stale-challenge"))
        assertNull(process.state.auth.challengeId)

        instrumentation.runOnMainSync { view.loadNewPage(HubPageSelector.ManageAccount, null) }
        fixture.waitUntil("The reloaded document must start loading") { startedUrls.any { it.contains("rph_init=") } }

        assertEquals(fixture.handle(replacement.accessToken), fixture.handle(view.documentAccessToken!!))
        view.rowndJavascriptInterface.postSecureMessage(challengeInitiated("current-challenge"))
        assertEquals("current-challenge", process.state.auth.challengeId)
    }

    @Test
    fun authChallengesFromADocumentOfAReplacedSessionAreIgnored() {
        val userId = fixture.uniqueUserId("challenge")
        val process = signedInProcess(userId)
        val oldDocument = hubView(process, HubPageSelector.ManageAccount).rowndJavascriptInterface
        fixture.signInThroughHub(process, HarnessClient.createSTSession(userId))

        oldDocument.postSecureMessage(challengeInitiated("old-challenge"))
        assertNull("Challenge from the replaced session's document was stored", process.state.auth.challengeId)

        val currentDocument = hubView(process, HubPageSelector.ManageAccount).rowndJavascriptInterface
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

        assertTrue("Hub receives the SDK access token", session.accessToken == rphInit.getString("access_token"))
        assertTrue("Hub receives the SDK refresh token", session.refreshToken == rphInit.getString("refresh_token"))
        assertTrue("Without a cached profile the Hub resolves the user from the token", rphInit.isNull("app_user_id"))
        assertEquals(200, fixture.profileStatus(rphInit.getString("access_token")))
        assertTrue("Rownd adopts the SDK access token", session.accessToken == process.state.auth.accessToken)
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

    private fun attach(view: RowndWebView) {
        val launched = ActivityScenario.launch(NewIntentTestActivity::class.java)
        scenario = launched
        launched.onActivity { it.setContentView(view) }
    }

    // A view whose document requests are served locally; the real client still sees every page start.
    private fun hubView(process: EmailChangeReproFixture.AppProcess, targetPage: HubPageSelector): RowndWebView {
        val view = AtomicReference<RowndWebView>()
        instrumentation.runOnMainSync {
            view.set(RowndWebView(fixture.context, null).apply {
                rowndClient = process.client
                this.targetPage = targetPage
                dismiss = {}
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                        WebResourceResponse("text/html", "UTF-8", "<html></html>".byteInputStream())

                    override fun onPageStarted(webView: WebView, url: String, favicon: Bitmap?) {
                        rowndWebViewClient.onPageStarted(webView, url, favicon)
                        startedUrls += url
                    }
                }
            })
        }
        webViews += view.get()
        return view.get()
    }

    private fun challengeInitiated(challengeId: String) =
        """{"type":"auth_challenge_initiated","payload":{"challenge_id":"$challengeId","user_identifier":"user@example.com"}}"""
}
