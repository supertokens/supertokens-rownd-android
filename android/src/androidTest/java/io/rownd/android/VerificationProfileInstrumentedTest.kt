package io.rownd.android

import android.app.Application
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.NewIntentTestActivity
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class VerificationProfileInstrumentedTest {
    private lateinit var rownd: RowndClient
    private val requested = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var status = HttpStatusCode.OK
    private var currentVerification = true
    private var onVerification: suspend () -> Unit = {}
    private var verifications = 0

    @Before
    fun setUp() {
        val engine = MockEngineConfig().apply {
            addHandler { request ->
                if (request.url.encodedPath.endsWith("/email/verify")) {
                    verifications++
                    onVerification()
                    return@addHandler respond("""{"status":"OK"}""", HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"))
                }
                requested.complete(Unit)
                release.await()
                respond("""{"data":{"email":"verified@example.com"}}""", status,
                    headersOf("Content-Type", "application/json"))
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder().fakeNetworkModule(FakeNetworkModule(engine)).build())
        rownd.config.apiUrl = "https://api.example.com"
        rownd.authenticatedApiClient.client = rownd.authenticatedApiClient.client.config {
            install(HttpRequestRetry) { maxRetries = 0 }
        }
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(accessToken = "S1")))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(User(data = mapOf("email" to "old@example.com"))))
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        rownd.authenticatedApiClient.client.close()
    }

    @Test
    fun verificationRefreshReplacesCachedProfile() = runBlocking {
        val load = rownd.userRepo.loadUserAsync { currentVerification }
        withTimeout(5_000) { requested.await() }
        assertEquals("old@example.com", rownd.state.value.user.data["email"])
        release.complete(Unit)
        assertEquals("verified@example.com", withTimeout(5_000) { load.await() }?.data?.get("email"))
        assertEquals("verified@example.com", rownd.state.value.user.data["email"])
    }

    @Test
    fun supersedingLoginWinsOverDelayedVerificationProfile() = runBlocking {
        val load = rownd.userRepo.loadUserAsync { currentVerification }
        withTimeout(5_000) { requested.await() }
        val newer = User(data = mapOf("email" to "new-login@example.com"))
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(accessToken = "S2")))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(newer))
        release.complete(Unit)
        assertNull(withTimeout(5_000) { load.await() })
        assertEquals(newer, rownd.state.value.user)
    }

    @Test
    fun signOutWinsOverDelayedVerificationProfile() = runBlocking {
        val load = rownd.userRepo.loadUserAsync { currentVerification }
        withTimeout(5_000) { requested.await() }
        currentVerification = false
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(User()))
        release.complete(Unit)
        assertNull(withTimeout(5_000) { load.await() })
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
    }

    @Test
    fun supersededVerificationCannotWriteEvenWithSameAccessToken() = runBlocking {
        val load = rownd.userRepo.loadUserAsync { currentVerification }
        withTimeout(5_000) { requested.await() }
        currentVerification = false
        release.complete(Unit)
        assertNull(withTimeout(5_000) { load.await() })
        assertEquals("old@example.com", rownd.state.value.user.data["email"])
    }

    @Test
    fun profileFailurePreservesAdoptedSession() = runBlocking {
        status = HttpStatusCode.ServiceUnavailable
        val load = rownd.userRepo.loadUserAsync { currentVerification }
        withTimeout(5_000) { requested.await() }
        release.complete(Unit)
        assertNull(withTimeout(5_000) { load.await() })
        assertEquals("S1", rownd.state.value.auth.accessToken)
        assertEquals("old@example.com", rownd.state.value.user.data["email"])
    }

    @Test
    fun verificationAckDoesNotWaitForProfileAndClosingHubDoesNotCancelLoad() {
        assertVerificationAckWithDelayedProfile(HttpStatusCode.OK)
    }

    @Test
    fun profileFailureDoesNotFailAckOrReplayConsumedVerification() {
        assertVerificationAckWithDelayedProfile(HttpStatusCode.ServiceUnavailable)
    }

    private fun assertVerificationAckWithDelayedProfile(profileStatus: HttpStatusCode) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val previousConfig = Rownd.config.supertokens
        val previousBaseUrl = Rownd.config.baseUrl
        val view = AtomicReference<RowndWebView>()
        val loaded = CountDownLatch(1)
        val jwt = JwtGenerator()
        val originalToken = jwt.generateTestJwt(sessionHandle = "verification-original")
        val replacementToken = jwt.generateTestJwt(sessionHandle = "verification-replacement")
        status = profileStatus
        SuperTokens.resetForTests()
        SuperTokens.Builder(context, "https://api.example.com").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.config.baseUrl = "https://hub.example.com"
        Rownd.config.supertokens = SuperTokensConfig(appInfo = SuperTokensAppInfo(apiDomain = "https://api.example.com", apiBasePath = "/auth"))
        SuperTokensSessionBridge.bootstrapSession(context, originalToken, "original-refresh", replaceExisting = true)
        onVerification = {
            SuperTokensSessionBridge.bootstrapSession(context, replacementToken, "replacement-refresh", replaceExisting = true)
        }
        val url = "https://hub.example.com/account/verify-email?token=test-token&rowndPendingVerificationId=test-pending&apiDomain=https%3A%2F%2Fapi.example.com&apiBasePath=%2Fauth"
        val scenario = ActivityScenario.launch(NewIntentTestActivity::class.java)
        try {
            instrumentation.runOnMainSync {
                view.set(RowndWebView(context, null).apply {
                    rowndClient = rownd
                    targetPage = HubPageSelector.DeepLink
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse(
                            "text/html", "UTF-8", """<script>window.addEventListener('rownd:native-email-verification', e => window.verificationResult = e.detail);</script>""".byteInputStream())
                        override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
                    }
                    loadUrl(url)
                })
            }
            scenario.onActivity { it.setContentView(view.get()) }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                view.get().rowndJavascriptInterface.postSecureMessage("""{"type":"verify_email","payload":{"request_id":"profile-test"}}""")
            }
            withTimeout(5_000) { requested.await() }
            withTimeout(5_000) {
                while (evaluateJavascript(view.get(), "window.verificationResult?.status") != "\"OK\"") delay(25)
            }
            assertEquals(1, verifications)
            assertEquals(replacementToken, rownd.state.value.auth.accessToken)
            instrumentation.runOnMainSync { view.get().destroy() }
            view.set(null)
            release.complete(Unit)
            withTimeout(5_000) { while (rownd.state.value.user.isLoading) delay(25) }
            if (profileStatus == HttpStatusCode.OK) {
                withTimeout(5_000) { while (rownd.state.value.user.data["email"] != "verified@example.com") delay(25) }
                assertEquals("verified@example.com", rownd.state.value.user.data["email"])
            }
            assertEquals(replacementToken, rownd.state.value.auth.accessToken)
            assertEquals(1, verifications)
        } finally {
            release.complete(Unit)
            instrumentation.runOnMainSync { view.get()?.destroy() }
            scenario.close()
            Rownd.config.supertokens = previousConfig
            Rownd.config.baseUrl = previousBaseUrl
            SuperTokensSessionBridge.clearLocalSession(context)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    private fun evaluateJavascript(view: RowndWebView, script: String): String {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result.set(it); completed.countDown() }
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
