package io.rownd.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.AppLifecycleListener
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.util.HubSessionStorage
import io.rownd.android.util.JwtGenerator
import io.rownd.android.models.EmailChangeDescriptor
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import java.util.concurrent.atomic.AtomicInteger
import com.supertokens.session.SuperTokens
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndBottomSheetActivity
import io.rownd.android.views.RowndWebView
import io.rownd.android.views.RowndWebViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.runBlocking
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay

@RunWith(AndroidJUnit4::class)
class NativeSignInHandoffInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var previousLifecycle: AppLifecycleListener? = null
    private lateinit var previousBaseUrl: String
    private var scenario: ActivityScenario<RowndBottomSheetActivity>? = null

    @Before
    fun setUp() {
        previousBaseUrl = Rownd.config.baseUrl
        previousLifecycle = Rownd.appHandleWrapper
        Rownd.config.baseUrl = HUB_ORIGIN
        Rownd.config.pendingHubDeepLinkUrl = "$HUB_ORIGIN/mobile_app"
        Rownd.store = Rownd.stateRepo.getStore()
        Rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = JwtGenerator().generateTestJwt(sessionHandle = "source-session"))))
        Rownd.appHandleWrapper = AppLifecycleListener(app).apply {
            registerActivityListener(persistentListOf(Lifecycle.State.CREATED)) { activity ->
                if (activity is RowndBottomSheetActivity) {
                    ViewModelProvider(activity, RowndWebViewModel.Factory(this@NativeSignInHandoffInstrumentedTest.app, Rownd))
                        .get(RowndWebViewModel::class.java)
                        .webView().observe(activity) { view ->
                            // Serve the fixture before the first navigation, so a late DNS
                            // failure cannot replace the document with the offline page.
                            view?.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                                    WebResourceResponse("text/html", "UTF-8", DOCUMENT.byteInputStream())

                                override fun onPageStarted(webView: WebView?, url: String?, favicon: Bitmap?) {
                                    view?.rowndWebViewClient?.onPageStarted(webView, url, favicon)
                                }

                                override fun onPageFinished(webView: WebView, url: String) {
                                    view?.rowndWebViewClient?.onPageFinished(webView, url)
                                    // A tiny fixture can execute inline script before onPageStarted.
                                    webView.evaluateJavascript("rowndAndroidSDK.postMessage('{\"type\":\"hub_loaded\"}')", null)
                                    if (Rownd.state.value.auth.accessToken != null) view.rowndWebViewClient.onHubAuthentication()
                                }
                            }
                        }
                }
            }
        }
        val intent = Intent(instrumentation.targetContext, RowndBottomSheetActivity::class.java)
            .putExtra("extra_target_page", HubPageSelector.ManageAccount)
        scenario = ActivityScenario.launch(intent)
        awaitWebView { it.targetPage == HubPageSelector.ManageAccount && it.isAttachedToWindow }
        val source = currentWebView()!!
        awaitCondition { evaluate(source, "window.capabilityAtDocumentStart") == "true" }
        onMain {
            assertTrue("Trusted document URL: ${source.url}", source.url?.startsWith(HUB_ORIGIN) == true)
            assertTrue("Presentation must own the handoff callback", source.nativeSignInHandoff != null)
        }
    }

    @After
    fun tearDown() {
        scenario?.close()
        Rownd.appHandleWrapper?.unregister()
        Rownd.appHandleWrapper = previousLifecycle
        Rownd.config.baseUrl = previousBaseUrl
        Rownd.config.pendingHubDeepLinkUrl = null
        Rownd.emailChangeContinuation?.clear()
    }

    private fun emailChangeMessage() = """{"type":"sign_in","payload":{"was_user_initiated":true,"email_change":{"version":2,"request_id":"email-request-123456","local_draft":{"owner_id":"owner","tenant_id":"public","configuration":"config","changes":{"email":"destination@example.test"}},"expires_at":${System.currentTimeMillis() + 60_000}}}}"""

    @Test
    fun resumeAllowsConsumedBootstrapFragmentButRejectsDifferentRoutesQueriesAndFragments() {
        val source = currentWebView()!!
        for ((initialFragment, destination, accepted) in listOf(
            Triple("rph_init=fixture", "/mobile_app", true),
            Triple("rph_init=fixture", "/mobile_app#rph_init=fixture", true),
            Triple("rph_init=fixture", "/other", false),
            Triple("rph_init=fixture", "/mobile_app?other=1", false),
            Triple("rph_init=fixture", "/mobile_app#other", false),
            Triple("unrelated=fragment", "/mobile_app", false),
            Triple("rph_init=fixture&unrelated=fragment", "/mobile_app", false),
        )) {
            evaluate(source, "history.replaceState({}, '', '/mobile_app#$initialFragment'); delete window.__rowndEmailChangeResume; true")
            awaitCondition {
                var matches = false
                onMain { matches = source.url?.endsWith("#$initialFragment") == true }
                matches
            }
            val descriptor = EmailChangeDescriptor(2, "request-123456789", EmailChangeDescriptor.LocalDraft(
                "owner", "public", "config", EmailChangeDescriptor.Changes("destination@example.test")), System.currentTimeMillis() + 60_000)
            var script = ""
            onMain {
                assertTrue(Rownd.emailChangeContinuation.begin(descriptor))
                assertTrue(Rownd.emailChangeContinuation.readyForSignIn(descriptor.requestId))
                assertTrue(Rownd.emailChangeContinuation.authenticated(descriptor.requestId))
                source.emailChangeIsCurrent = { true }
                source.nativeEmailChangeDelivered = null
                source.configureEmailChange(descriptor, resume = true)
                script = source.takeEmailChangeResumeScript()
            }
            assertTrue(script.isNotEmpty())
            assertEquals(accepted.toString(), evaluate(source, "(function(){ history.replaceState({}, '', '$destination'); $script return true; })()"))
            assertEquals(if (accepted) "\"destination@example.test\"" else "null", evaluate(source, "window.__rowndEmailChangeResume?.local_draft.changes.email || null"))
            assertEquals(null, Rownd.emailChangeContinuation.read())
            onMain { assertEquals("", source.takeEmailChangeResumeScript()) }
        }
    }

    @Test
    fun recreationDuringCookieCleanupContinuesSameAttemptWithoutRepeatingSignOut() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val original = HubSessionStorage.removeCookies
        val reached = CountDownLatch(1)
        val finishCookies = AtomicReference<() -> Unit>()
        HubSessionStorage.removeCookies = { callback -> finishCookies.set { original(callback) }; reached.countDown() }
        try {
            val source = currentWebView()!!
            val before = SuperTokensSessionBridge.currentSignOutGeneration()
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            val requestId = Rownd.emailChangeContinuation.read()!!.descriptor.requestId
            assertEquals(before + 1, SuperTokensSessionBridge.currentSignOutGeneration())
            scenario!!.recreate()
            scenario!!.recreate()
            assertEquals("cleanup", Rownd.emailChangeContinuation.read()?.phase)
            assertFalse(Rownd.state.value.auth.isAuthenticated)
            onMain { finishCookies.getAndSet(null)?.invoke() }
            runBlocking { HubSessionStorage.awaitCleanup() }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            awaitCondition { evaluate(signIn, "window.__rowndEmailChangeSignIn") == "true" }
            assertEquals(before + 1, SuperTokensSessionBridge.currentSignOutGeneration())
            assertEquals(requestId, Rownd.emailChangeContinuation.read()?.descriptor?.requestId)
            assertEquals("sign_in", Rownd.emailChangeContinuation.read()?.phase)
            assertTrue(source.isDestroyed)
            val token = JwtGenerator().generateTestJwt(sessionHandle = "recreated-SR")
            onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"$token","refresh_token":"recreated-refresh"}}""") }
            val profile = awaitWebView { it !== signIn && it.targetPage == HubPageSelector.ManageAccount }
            awaitCondition { evaluate(profile, "window.__rowndEmailChangeResume?.local_draft.changes.email") == "\"destination@example.test\"" }
            assertEquals(token, Rownd.state.value.auth.accessToken)
            assertEquals(null, Rownd.emailChangeContinuation.read())
        } finally {
            HubSessionStorage.removeCookies = original
            onMain { finishCookies.getAndSet(null)?.invoke() }
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun v2RejectsOldOversizedAndSubframeHandoffsBeforeCleanup() {
        val source = currentWebView()!!
        val generation = SuperTokensSessionBridge.currentSignOutGeneration()
        assertEquals("2", evaluate(source, "window.__rowndNativeEmailChangeContinuation"))
        evaluate(source, "window.rejections = []; window.addEventListener('rownd:email-change-handoff', e => rejections.push(e.detail.status)); true")
        val message = emailChangeMessage()
        onMain {
            source.rowndJavascriptInterface.postSecureMessage(message.replace("\"version\":2", "\"version\":1"))
            source.rowndJavascriptInterface.postSecureMessage(message.replace("destination@example.test", "a".repeat(33 * 1024)))
        }
        evaluate(source, """var frame = document.createElement('iframe'); frame.srcdoc = `<script>parent.frameReady = true; rowndAndroidSDK.postMessage('$message');</script>`; document.body.appendChild(frame);""")
        awaitCondition { evaluate(source, "window.frameReady") == "true" }
        awaitCondition { evaluate(source, "rejections.length === 2 && rejections.every(status => status === 'REJECTED')") == "true" }
        assertEquals(generation, SuperTokensSessionBridge.currentSignOutGeneration())
        assertEquals(null, Rownd.emailChangeContinuation.read())
        assertSame(source, currentWebView())
    }

    @Test
    fun cancelledOwnedOtpCannotAdoptAfterMutationLockIsReleased() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        try {
            val source = currentWebView()!!
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            val token = JwtGenerator().generateTestJwt(sessionHandle = "cancelled-OTP")
            runBlocking { SuperTokensSessionBridge.sessionMutationMutex.lock() }
            try {
                onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"$token","refresh_token":"cancelled-refresh"}}""") }
                scenario!!.close()
            } finally { SuperTokensSessionBridge.sessionMutationMutex.unlock() }
            instrumentation.waitForIdleSync()
            assertEquals(null, SuperTokensSessionBridge.getRefreshToken(app))
            assertFalse(Rownd.state.value.auth.isAuthenticated)
            assertEquals(null, Rownd.emailChangeContinuation.read())
        } finally {
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun emailChangeWaitsForCookieCleanupAndRetiresOldBridge() {
        val source = currentWebView()!!
        val original = HubSessionStorage.removeCookies
        val reachedCookies = CountDownLatch(1)
        val finishCookies = AtomicReference<() -> Unit>()
        HubSessionStorage.removeCookies = { callback ->
            finishCookies.set { original(callback) }
            reachedCookies.countDown()
        }
        Rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = "old-profile-token")))
        try {
            assertEquals("2", evaluate(source, "window.__rowndNativeEmailChangeContinuation"))
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            assertTrue(reachedCookies.await(5, TimeUnit.SECONDS))
            assertEquals(null, Rownd.state.value.auth.accessToken)
            assertSame(source, currentWebView())
            onMain {
                source.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"stale","refresh_token":"stale"}}""")
                source.rowndJavascriptInterface.postSecureMessage("""{"type":"sign_out","payload":{"was_user_initiated":true}}""")
                source.rowndJavascriptInterface.postSecureMessage("""{"type":"close_hub_view_controller"}""")
                finishCookies.get().invoke()
            }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            assertTrue(source.isDestroyed)
            awaitCondition { evaluate(signIn, "window.__rowndEmailChangeSignIn") == "true" }
            assertTrue(signIn.jsFunctionArgsAsJson.contains("\"intent\":\"sign_in\""))
            assertEquals("sign_in", Rownd.emailChangeContinuation?.read()?.phase)
        } finally {
            HubSessionStorage.removeCookies = original
            onMain { finishCookies.get()?.invoke() }
        }
    }

    @Test
    fun alreadySupersededManageAccountCannotClearNewerSession() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val source = currentWebView()!!
        val token = JwtGenerator().generateTestJwt(sessionHandle = "newer-operation-SR")
        val previousClient = Rownd.authRepo.legacyMigrationApiClient.client
        val previousApiUrl = Rownd.config.apiUrl
        val revocations = AtomicInteger()
        val revocationClient = HttpClient(MockEngine {
            revocations.incrementAndGet()
            respond("""{"status":"OK"}""", headers = headersOf("Content-Type", "application/json"))
        })
        Rownd.authRepo.legacyMigrationApiClient.client = revocationClient
        Rownd.config.apiUrl = "https://api.example"
        try {
            // This is requestSignIn/provider admission's revision boundary, with Activity delivery held.
            Rownd.signInHandoffRevision.incrementAndGet()
            SuperTokensSessionBridge.bootstrapSession(app, token, "newer-refresh", replaceExisting = true)
            runBlocking { SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(app, Rownd.store) }
            val generation = SuperTokensSessionBridge.currentSignOutGeneration()
            onMain {
                source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage())
                assertEquals("A stale source must not begin local sign-out", generation, SuperTokensSessionBridge.currentSignOutGeneration())
                assertEquals(token, Rownd.state.value.auth.accessToken)
                assertEquals(null, Rownd.emailChangeContinuation?.read())
            }
            assertEquals(token, runBlocking { SuperTokensSessionBridge.getAccessToken(app) })
            assertEquals("newer-refresh", SuperTokensSessionBridge.getRefreshToken(app))
            assertEquals(0, revocations.get())
            scenario!!.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.SignIn))
            }
            awaitWebView { it.targetPage == HubPageSelector.SignIn }
        } finally {
            Rownd.authRepo.legacyMigrationApiClient.client = previousClient
            Rownd.config.apiUrl = previousApiUrl
            revocationClient.close()
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun newlyDeliveredManageAccountOwnsItsAdmissionRevision() {
        val previous = currentWebView()!!
        scenario!!.onActivity { activity ->
            instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.ManageAccount))
        }
        val current = awaitWebView { it !== previous && it.targetPage == HubPageSelector.ManageAccount }
        awaitCondition { evaluate(current, "window.capabilityAtDocumentStart") == "true" }
        onMain { current.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
        val signIn = awaitWebView { it !== current && it.targetPage == HubPageSelector.SignIn }
        awaitCondition { evaluate(signIn, "window.__rowndEmailChangeSignIn") == "true" }
    }

    @Test
    fun admittedCleanupCannotClearSessionInstalledAfterAdmission() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val source = currentWebView()!!
        val original = HubSessionStorage.removeCookies
        val requested = CountDownLatch(1)
        val finishCookies = AtomicReference<() -> Unit>()
        HubSessionStorage.removeCookies = { callback ->
            finishCookies.set { original(callback) }
            requested.countDown()
        }
        try {
            val oldToken = JwtGenerator().generateTestJwt(sessionHandle = "admission-S0")
            SuperTokensSessionBridge.bootstrapSession(app, oldToken, "old-refresh", replaceExisting = true)
            runBlocking { SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(app, Rownd.store) }
            val generation = SuperTokensSessionBridge.currentSignOutGeneration()
            val newToken = JwtGenerator().generateTestJwt(sessionHandle = "admission-newer")
            onMain {
                source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage())
                assertEquals(generation + 1, SuperTokensSessionBridge.currentSignOutGeneration())
                assertEquals(null, Rownd.state.value.auth.accessToken)
                Rownd.signInHandoffRevision.incrementAndGet()
                runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    SuperTokensSessionBridge.bootstrapSession(app, newToken, "new-refresh", replaceExisting = true)
                }
                Rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = newToken)))
            }
            assertTrue(requested.await(5, TimeUnit.SECONDS))
            scenario!!.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.SignIn))
            }
            onMain { finishCookies.getAndSet(null).invoke() }
            awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            assertEquals(newToken, runBlocking { SuperTokensSessionBridge.getAccessToken(app) })
            assertEquals("new-refresh", SuperTokensSessionBridge.getRefreshToken(app))
            assertEquals(newToken, Rownd.state.value.auth.accessToken)
            assertEquals(generation + 1, SuperTokensSessionBridge.currentSignOutGeneration())
        } finally {
            HubSessionStorage.removeCookies = original
            onMain { finishCookies.getAndSet(null)?.invoke() }
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun otpProfileLoadSurvivesOwnedReturnAndOrdinaryHubClose() {
        checkOtpProfileLoad(false)
    }

    @Test
    fun otpProfileLoadCannotOverwriteNewerSessionAfterSignOut() {
        checkOtpProfileLoad(true)
    }

    private fun checkOtpProfileLoad(supersede: Boolean) {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val previousClient = Rownd.authenticatedApiClient.client
        val previousAppConfig = Rownd.state.value.appConfig
        Rownd.store.dispatch(StateAction.SetAppConfig(previousAppConfig.copy(config = previousAppConfig.config.copy(
            supertokens = io.rownd.android.models.domain.SuperTokensConfig(
                io.rownd.android.models.domain.SuperTokensAppInfo("https://api.example", "/auth")),
        ))))
        val requested = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val client = HttpClient(MockEngine {
            requests.incrementAndGet()
            requested.countDown()
            release.await()
            respond("""{"data":{"email":"original-account@example.com"}}""",
                headers = headersOf("Content-Type", "application/json"))
        }) { install(ContentNegotiation) { json() } }
        Rownd.authenticatedApiClient.client = client
        try {
            val source = currentWebView()!!
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            val token = JwtGenerator().generateTestJwt(sessionHandle = "profile-SR")
            onMain {
                signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"$token","refresh_token":"refresh-SR"}}""")
                // Hold Main's queued replacement until the native GET is definitely in flight.
                assertTrue("Native GET must start", requested.await(5, TimeUnit.SECONDS))
            }
            val profile = awaitWebView { it !== signIn && it.targetPage == HubPageSelector.ManageAccount }
            onMain { profile.requestDismiss() }
            awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
            val newerToken = JwtGenerator().generateTestJwt(sessionHandle = "unrelated-profile-session")
            if (supersede) {
                SuperTokensSessionBridge.beginSignOut(app) {
                    Rownd.store.dispatch(StateAction.SetAuth(AuthState()))
                }
                SuperTokensSessionBridge.bootstrapSession(app, newerToken, "newer-refresh", replaceExisting = true)
                runBlocking { SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(app, Rownd.store) }
            }
            release.complete(Unit)
            runBlocking { withTimeout(5_000) {
                while (if (supersede) Rownd.state.value.user.isLoading
                    else Rownd.state.value.user.data["email"] != "original-account@example.com") delay(20)
            } }
            assertEquals(1, requests.get())
            assertEquals(if (supersede) null else "original-account@example.com", Rownd.state.value.user.data["email"])
            assertEquals(if (supersede) newerToken else token, Rownd.state.value.auth.accessToken)
        } finally {
            release.complete(Unit)
            Rownd.authenticatedApiClient.client = previousClient
            Rownd.store.dispatch(StateAction.SetAppConfig(previousAppConfig))
            client.close()
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun failedDomCleanupCanRetryBeforeOrdinarySignInInSameProcess() {
        val source = currentWebView()!!
        val originalRemove = HubSessionStorage.removeCookies
        val cookieRequested = CountDownLatch(1)
        val finishCookies = AtomicReference<() -> Unit>()
        HubSessionStorage.removeCookies = { callback ->
            finishCookies.set { originalRemove(callback) }
            cookieRequested.countDown()
        }
        try {
            onMain { CookieManager.getInstance().setCookie(HUB_ORIGIN, "old_session=retained; Secure") }
            evaluate(source, "Object.defineProperty(window, 'localStorage', {get() { throw new Error('DOM cleanup fixture'); }}); true")
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
            assertEquals(null, Rownd.emailChangeContinuation?.read())
            scenario = ActivityScenario.launch(Intent(instrumentation.targetContext, RowndBottomSheetActivity::class.java)
                .putExtra("extra_target_page", HubPageSelector.SignIn))
            assertTrue("A fresh cleanup must be attempted instead of replaying the failed barrier", cookieRequested.await(5, TimeUnit.SECONDS))
            assertEquals(null, currentWebView())
            onMain { finishCookies.getAndSet(null)?.invoke() }
            awaitWebView { it.targetPage == HubPageSelector.SignIn }
            onMain { assertFalse(CookieManager.getInstance().getCookie(HUB_ORIGIN).orEmpty().contains("old_session")) }
        } finally {
            HubSessionStorage.removeCookies = originalRemove
            onMain { finishCookies.getAndSet(null)?.invoke() }
            runBlocking { HubSessionStorage.clearAndAwait(Rownd.config) }
        }
    }

    @Test
    fun emailChangeReturnsAfterNormalAdoptionAndDeliversOnlyOnce() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val source = currentWebView()!!
        try {
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"event","payload":{"event":"sign_in_completed"}}""") }
            instrumentation.waitForIdleSync()
            assertSame(signIn, currentWebView())
            val token = JwtGenerator().generateTestJwt(sessionHandle = "email-SR")
            onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"$token","refresh_token":"refresh-SR"}}""") }
            val profile = awaitWebView { it !== signIn && it.targetPage == HubPageSelector.ManageAccount }
            assertEquals(token, Rownd.state.value.auth.accessToken)
            awaitCondition { evaluate(profile, "window.__rowndEmailChangeResume?.local_draft.changes.email") == "\"destination@example.test\"" }
            onMain {
                profile.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"foreign","refresh_token":"foreign"}}""")
            }
            assertEquals(token, Rownd.state.value.auth.accessToken)
            assertEquals(null, Rownd.emailChangeContinuation?.read())
            assertEquals(null, profile.emailChangeDescriptor)
            onMain { assertEquals("", profile.takeEmailChangeResumeScript()) }
            onMain { profile.rowndJavascriptInterface.postSecureMessage(SIGN_IN) }
            val generic = awaitWebView { it !== profile && it.targetPage == HubPageSelector.SignIn }
            awaitCondition { evaluate(generic, "typeof window.__rowndEmailChangeSignIn") == "\"undefined\"" }
        } finally {
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun supersedingPresentationWaitsForCleanupButCannotBorrowContinuation() {
        checkQueuedPresentationCleanup(false, false)
    }

    @Test
    fun queuedPresentationWaitsForLateCookieCallbackAfterTimeout() {
        checkQueuedPresentationCleanup(true, false)
    }

    @Test
    fun queuedPresentationInvalidatedAfterTimeoutCannotOpenOnLateCookieCallback() {
        checkQueuedPresentationCleanup(true, true)
    }

    private fun checkQueuedPresentationCleanup(timeout: Boolean, invalidate: Boolean) {
        val source = currentWebView()!!
        val original = HubSessionStorage.removeCookies
        val reached = CountDownLatch(1)
        val finishCookies = AtomicReference<() -> Unit>()
        HubSessionStorage.removeCookies = { callback -> finishCookies.set { original(callback) }; reached.countDown() }
        try {
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            assertTrue(reached.await(5, TimeUnit.SECONDS))
            scenario!!.onActivity { activity ->
                instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.DeepLink))
            }
            assertSame(source, currentWebView())
            if (timeout) {
                // Exercise the real five-second cleanup deadline while retaining its destructive callback.
                runBlocking { delay(5_500) }
                assertTrue(HubSessionStorage.hasPendingCleanup)
                assertSame(source, currentWebView())
            }
            if (invalidate) {
                Rownd.signInHandoffRevision.incrementAndGet()
                SuperTokensSessionBridge.beginSignOut(null) {}
            }
            onMain { finishCookies.getAndSet(null).invoke() }
            runBlocking { HubSessionStorage.awaitCleanup() }
            instrumentation.waitForIdleSync()
            if (invalidate) {
                assertSame(source, currentWebView())
                return
            }
            val newer = awaitWebView { it !== source && it.targetPage == HubPageSelector.DeepLink }
            assertEquals(null, Rownd.emailChangeContinuation?.read())
            onMain { source.nativeEmailChangeAuthenticated?.invoke() }
            instrumentation.waitForIdleSync()
            assertSame(newer, currentWebView())
        } finally {
            HubSessionStorage.removeCookies = original
            onMain { finishCookies.get()?.invoke() }
        }
    }

    @Test
    fun emailChangeChallengeIsRecordedOnlyByOwnedSignInAndCloseDiscardsIt() {
        val source = currentWebView()!!
        onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
        val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
        val challenge = """{"type":"auth_challenge_initiated","payload":{"challenge_id":"owned-challenge","user_identifier":"original@example.com"}}"""
        onMain {
            source.rowndJavascriptInterface.postSecureMessage(challenge)
        }
        assertEquals(null, Rownd.emailChangeContinuation?.read()?.challengeId)
        onMain { signIn.rowndJavascriptInterface.postSecureMessage(challenge) }
        assertEquals("owned-challenge", Rownd.emailChangeContinuation?.read()?.challengeId)
        onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"close_hub_view_controller"}""") }
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
        assertEquals(null, Rownd.emailChangeContinuation?.read())
    }

    @Test
    fun supersedingGenericSignInCannotInheritEmailChangeMarker() {
        val source = currentWebView()!!
        onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
        val owned = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
        awaitCondition { evaluate(owned, "window.__rowndEmailChangeSignIn") == "true" }
        scenario!!.onActivity { activity ->
            instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.SignIn))
        }
        val generic = awaitWebView { it !== owned && it.targetPage == HubPageSelector.SignIn }
        awaitCondition { evaluate(generic, "typeof window.__rowndEmailChangeSignIn") == "\"undefined\"" }
        assertTrue(owned.isDestroyed)
        assertEquals(null, generic.emailChangeDescriptor)
        assertEquals(null, Rownd.emailChangeContinuation?.read())
    }

    @Test
    fun ownedNativeAdoptionEmitsCompletionWithoutWaitingForRetiredHubEvent() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(app, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val completions = AtomicInteger()
        val listener: (RowndEvent) -> Unit = { if (it.event == RowndEventType.SignInCompleted) completions.incrementAndGet() }
        Rownd.addEventListener(listener)
        try {
            val source = currentWebView()!!
            onMain { source.rowndJavascriptInterface.postSecureMessage(emailChangeMessage()) }
            val signIn = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            val token = JwtGenerator().generateTestJwt(sessionHandle = "completion-SR")
            onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"$token","refresh_token":"refresh-SR"}}""") }
            awaitWebView { it !== signIn && it.targetPage == HubPageSelector.ManageAccount }
            awaitCondition { completions.get() == 1 }
            onMain { signIn.rowndJavascriptInterface.postSecureMessage("""{"type":"event","payload":{"event":"sign_in_completed"}}""") }
            instrumentation.waitForIdleSync()
            assertEquals(1, completions.get())
        } finally {
            Rownd.removeEventListener(listener)
            SuperTokensSessionBridge.clearLocalSession(app)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    @Test
    fun acceptedSignOutClearsCredentialsWhileMutationIsLockedAndSurvivesRecovery() {
        val source = currentWebView()!!
        val prefs = app.getSharedPreferences("supertokens-android-shared-preferences", 0)
        val generation = SuperTokensSessionBridge.currentSignOutGeneration()
        // A refresh token without an access token avoids network revocation in this fixture.
        prefs.edit().putString("st-storage-item-st-refresh-token", "old-refresh").commit()
        Rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = "old-access", refreshToken = "old-refresh")))
        runBlocking { SuperTokensSessionBridge.sessionMutationMutex.lock() }
        try {
            onMain {
                source.rowndJavascriptInterface.postSecureMessage("""{"type":"sign_out","payload":{"was_user_initiated":true}}""")
                assertEquals(generation + 1, SuperTokensSessionBridge.currentSignOutGeneration())
                assertEquals(null, SuperTokensSessionBridge.getRefreshToken(app))
                assertEquals(null, Rownd.store.currentState.auth.accessToken)
                source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
                assertTrue(source.isDestroyed)
            }
            awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
            prefs.edit().putString("st-storage-item-st-refresh-token", "new-refresh").commit()
        } finally {
            SuperTokensSessionBridge.sessionMutationMutex.unlock()
        }
        instrumentation.waitForIdleSync()
        assertEquals("new-refresh", SuperTokensSessionBridge.getRefreshToken(app))
        assertEquals(generation + 1, SuperTokensSessionBridge.currentSignOutGeneration())
        prefs.edit().remove("st-storage-item-st-refresh-token").commit()
    }

    @Test
    fun activeRecoveryRestoresSignInWithoutARetainedWebView() {
        val source = currentWebView()!!
        onMain { source.rowndJavascriptInterface.postSecureMessage(SIGN_IN) }
        val replacement = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
        val restoredIntent = snapshotPresentation()
        scenario!!.close()
        scenario = ActivityScenario.launch(restoredIntent)
        val restored = awaitWebView { it.targetPage == HubPageSelector.SignIn }
        assertNotSame(replacement, restored)
        assertEquals(RowndSignInOptions().toJsonString(), restored.jsFunctionArgsAsJson)
        scenario!!.recreate()
        awaitWebView { it.targetPage == HubPageSelector.SignIn }
    }

    @Test
    fun pendingRecoveryRestoresSignInWithoutReturningToManageAccount() {
        restorePendingRecovery(invalidate = false)
        awaitWebView { it.targetPage == HubPageSelector.SignIn }
    }

    @Test
    fun invalidatedPendingRecoveryIsNotRevivedByRestoration() {
        restorePendingRecovery(invalidate = true)
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
    }

    @Test
    fun pendingRecoveryFromAnEarlierProcessIsNotRevived() {
        restorePendingRecovery(invalidate = false, previousProcess = true)
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
    }

    private fun restorePendingRecovery(invalidate: Boolean, previousProcess: Boolean = false) {
        val source = currentWebView()!!
        lateinit var restoredIntent: Intent
        scenario!!.onActivity { activity ->
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            assertTrue(source.isDestroyed)
            restoredIntent = snapshotPresentation(activity)
            if (previousProcess) restoredIntent.getBundleExtra("extra_sign_in_handoff")!!.putString("process", "previous-process")
            if (invalidate) SuperTokensSessionBridge.beginSignOut(null) {}
        }
        scenario!!.close()
        scenario = ActivityScenario.launch(restoredIntent)
    }

    private fun snapshotPresentation(): Intent {
        lateinit var result: Intent
        scenario!!.onActivity { result = snapshotPresentation(it) }
        return result
    }

    private fun snapshotPresentation(activity: RowndBottomSheetActivity): Intent {
        val saved = Bundle()
        instrumentation.callActivityOnSaveInstanceState(activity, saved)
        return Intent(instrumentation.targetContext, RowndBottomSheetActivity::class.java)
            .replaceExtras(requireNotNull(saved.getBundle("rownd_presentation")))
    }

    @Test
    fun trustedClickDestroysSourceBeforeCreatingNewSignInAndIgnoresDelayedMessages() {
        val source = currentWebView()!!
        assertEquals("true", evaluate(source, "window.capabilityAtDocumentStart"))
        val signOutGeneration = SuperTokensSessionBridge.currentSignOutGeneration()
        val auth = Rownd.store.currentState.auth
        val user = Rownd.store.currentState.user
        onMain { CookieManager.getInstance().setCookie(HUB_ORIGIN, "handoff_test=preserved; Secure") }
        onMain { source.evaluateJavascript("rowndAndroidSDK.postMessage('$SIGN_IN'); rowndAndroidSDK.postMessage('$SIGN_IN')", null) }
        val target = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
        assertTrue(source.isDestroyed)
        assertNotSame(source, target)
        onMain {
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            source.rowndJavascriptInterface.postSecureMessage("""{"type":"authentication","payload":{"access_token":"stale","refresh_token":"stale"}}""")
            source.rowndJavascriptInterface.postSecureMessage("""{"type":"close_hub_view_controller"}""")
            source.rowndJavascriptInterface.postSecureMessage("""{"type":"user_data_update","payload":{"data":{"email":"stale@example.com"}}}""")
            source.rowndJavascriptInterface.postSecureMessage("""{"type":"sign_out","payload":{"was_user_initiated":true}}""")
        }
        instrumentation.waitForIdleSync()
        assertSame(target, currentWebView())
        assertFalse(target.isDestroyed)
        assertEquals(auth, Rownd.store.currentState.auth)
        assertEquals(user, Rownd.store.currentState.user)
        assertEquals(signOutGeneration, SuperTokensSessionBridge.currentSignOutGeneration())
        onMain {
            assertTrue(CookieManager.getInstance().getCookie(HUB_ORIGIN).contains("handoff_test=preserved"))
            CookieManager.getInstance().setCookie(HUB_ORIGIN, "handoff_test=; Max-Age=0; Secure")
        }
    }

    @Test
    fun wrongTargetsImplicitIntentAndLegacyBridgeCannotHandoff() {
        val source = currentWebView()!!
        onMain {
            for (target in HubPageSelector.entries.filter { it != HubPageSelector.ManageAccount }) {
                source.targetPage = target
                source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
                assertFalse(source.isDestroyed)
            }
            source.targetPage = HubPageSelector.ManageAccount
            source.rowndJavascriptInterface.postMessage(SIGN_IN)
            for (payload in listOf("", ",\"payload\":{}", ",\"payload\":{\"was_user_initiated\":false}")) {
                source.rowndJavascriptInterface.postSecureMessage("""{"type":"sign_in"$payload}""")
            }
        }
        instrumentation.waitForIdleSync()
        assertSame(source, currentWebView())
        assertFalse(source.isDestroyed)
    }

    @Test
    fun trustedSubframeAndUntrustedDocumentCannotHandoff() {
        val source = currentWebView()!!
        evaluate(source, """var frame = document.createElement('iframe'); frame.srcdoc = `<script>parent.frameBridgePresent = typeof rowndAndroidSDK !== 'undefined'; rowndAndroidSDK.postMessage('$SIGN_IN');</script>`; document.body.appendChild(frame);""")
        awaitCondition { evaluate(source, "window.frameBridgePresent") == "true" }
        instrumentation.waitForIdleSync()
        assertFalse(source.isDestroyed)
        onMain { source.loadDataWithBaseURL("https://untrusted.example", DOCUMENT, "text/html", "UTF-8", "https://untrusted.example") }
        awaitCondition { evaluate(source, "location.origin") == "\"https://untrusted.example\"" }
        assertEquals("\"undefined\"", evaluate(source, "typeof window.__rowndNativeSignInHandoff"))
        onMain { source.rowndJavascriptInterface.postSecureMessage(SIGN_IN) }
        assertFalse(source.isDestroyed)
    }

    @Test
    fun inactiveOrUnownedSourceCannotReplaceThePresentation() {
        val source = currentWebView()!!
        scenario!!.onActivity { activity ->
            val other = RowndWebView(activity, null).apply { targetPage = HubPageSelector.ManageAccount }
            try {
                activity.replaceManageAccountWithSignIn(other)
                assertFalse(source.isDestroyed)
            } finally {
                other.destroy()
            }
        }
        scenario!!.moveToState(Lifecycle.State.STARTED)
        onMain { source.rowndJavascriptInterface.postSecureMessage(SIGN_IN) }
        assertFalse(source.isDestroyed)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertSame(source, currentWebView())
    }

    @Test
    fun newerHostPresentationSupersedesQueuedHandoff() {
        val source = currentWebView()!!
        scenario!!.onActivity { activity ->
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            assertTrue(source.isDestroyed)
            instrumentation.callActivityOnNewIntent(activity, Intent().putExtra("extra_target_page", HubPageSelector.DeepLink))
        }
        val target = awaitWebView { it !== source && it.targetPage == HubPageSelector.DeepLink }
        instrumentation.waitForIdleSync()
        assertSame(target, currentWebView())
    }

    @Test
    fun newerNativeVerificationCancelsQueuedHandoffAtReceipt() {
        val source = currentWebView()!!
        val previousSuperTokensConfig = Rownd.config.supertokens
        Rownd.config.supertokens = previousSuperTokensConfig.copy(
            appInfo = previousSuperTokensConfig.appInfo.copy(apiDomain = "https://api.handoff.test"),
        )
        val verificationUrl = "$HUB_ORIGIN/account/verify-email?token=test&rowndPendingVerificationId=test&apiDomain=https://api.handoff.test&apiBasePath=${Rownd.config.supertokens.appInfo.apiBasePath ?: Rownd.config.apiBasePath}"
        lateinit var verificationView: RowndWebView
        onMain {
            verificationView = RowndWebView(instrumentation.targetContext, null).apply {
                rowndClient = Rownd
                targetPage = HubPageSelector.DeepLink
                loadDataWithBaseURL(verificationUrl, DOCUMENT, "text/html", "UTF-8", verificationUrl)
            }
        }
        try {
            awaitCondition { evaluate(verificationView, "window.capabilityAtDocumentStart") == "true" }
            onMain {
                source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
                assertTrue(source.isDestroyed)
                verificationView.rowndJavascriptInterface.postSecureMessage("""{"type":"verify_email","payload":{"request_id":"new-verification"}}""")
                verificationView.destroy()
            }
            instrumentation.waitForIdleSync()
            awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
        } finally {
            onMain { verificationView.destroy() }
            Rownd.config.supertokens = previousSuperTokensConfig
        }
    }

    @Test
    fun signOutBeforeReplacementIsReadyPreventsSignInTargetFromLoading() {
        val source = currentWebView()!!
        val replacement = AtomicReference<RowndWebView?>()
        scenario!!.onActivity { activity ->
            ViewModelProvider(activity)[RowndWebViewModel::class.java].webView().observe(activity) { view ->
                if (view != null && view !== source && replacement.compareAndSet(null, view)) {
                    SuperTokensSessionBridge.beginSignOut(null) {}
                }
            }
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
        }
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
        assertTrue(source.isDestroyed)
        assertTrue(requireNotNull(replacement.get()).isDestroyed)
        assertEquals(HubPageSelector.Unknown, replacement.get()!!.targetPage)
    }

    @Test
    fun providerAcceptanceRetiresManageAccountBeforeNativeWorkCanReenterTheBridge() {
        val source = currentWebView()!!
        var starts = 0
        onMain {
            source.rowndJavascriptInterface.startNativeGoogleSignIn = {
                starts++
                source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
                source.rowndJavascriptInterface.postSecureMessage(GOOGLE)
                assertEquals(null, source.nativeSignInHandoff)
            }
            source.rowndJavascriptInterface.postSecureMessage(GOOGLE)
            assertEquals(1, starts)
            assertTrue(source.isDestroyed)
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
        }
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
        assertEquals(1, starts)
    }

    @Test
    fun recoveryAcceptancePreventsOldManageAccountFromStartingProviderWork() {
        val source = currentWebView()!!
        var starts = 0
        onMain {
            source.rowndJavascriptInterface.startNativeGoogleSignIn = { starts++ }
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            source.rowndJavascriptInterface.postSecureMessage(GOOGLE)
        }
        val replacement = awaitWebView { it !== source && it.targetPage == HubPageSelector.SignIn }
        assertEquals(0, starts)
        assertFalse(replacement.isDestroyed)
    }

    @Test
    fun newerNativeProviderAcceptanceCancelsQueuedRecovery() {
        val source = currentWebView()!!
        onMain {
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            Rownd.signInWithGoogle.signIn(intent = null, wasUserInitiated = false)
        }
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
        assertTrue(source.isDestroyed)
    }

    @Test
    fun signOutGenerationCancelsQueuedHandoff() {
        val source = currentWebView()!!
        onMain {
            source.rowndJavascriptInterface.postSecureMessage(SIGN_IN)
            SuperTokensSessionBridge.beginSignOut(null) {}
        }
        instrumentation.waitForIdleSync()
        assertTrue(source.isDestroyed)
        awaitCondition { scenario!!.state == Lifecycle.State.DESTROYED }
    }

    private fun currentWebView(): RowndWebView? {
        if (scenario!!.state == Lifecycle.State.DESTROYED) return null
        val result = AtomicReference<RowndWebView?>()
        scenario!!.onActivity { activity ->
            result.set(ViewModelProvider(activity)[RowndWebViewModel::class.java].webView().value)
        }
        return result.get()
    }

    private fun awaitWebView(predicate: (RowndWebView) -> Boolean): RowndWebView {
        var result: RowndWebView? = null
        awaitCondition {
            result = currentWebView()?.takeIf(predicate)
            result != null
        }
        return result!!
    }

    private fun evaluate(view: RowndWebView, script: String): String? {
        val completed = CountDownLatch(1)
        val result = AtomicReference<String?>()
        onMain { view.evaluateJavascript(script) { result.set(it); completed.countDown() } }
        assertTrue("JavaScript did not finish", completed.await(5, TimeUnit.SECONDS))
        return result.get()
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for WebView", condition())
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    companion object {
        private const val HUB_ORIGIN = "https://handoff.rownd.test"
        private const val SIGN_IN = """{"type":"sign_in","payload":{"was_user_initiated":true}}"""
        private const val GOOGLE = """{"type":"trigger_sign_in_with_google"}"""
        private const val DOCUMENT = """<html><head><script>
            window.capabilityAtDocumentStart = window.__rowndNativeSignInHandoff === true;
            window.rownd = {requestSignIn: function() {}, user: {manageAccount: function() {}}};
        </script></head><body>Manage Account</body></html>"""
    }
}
