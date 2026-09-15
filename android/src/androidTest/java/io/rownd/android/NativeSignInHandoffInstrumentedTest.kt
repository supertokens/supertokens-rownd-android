package io.rownd.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
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
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndBottomSheetActivity
import io.rownd.android.views.RowndWebView
import io.rownd.android.views.RowndWebViewModel
import kotlinx.collections.immutable.persistentListOf
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
        private const val DOCUMENT = """<html><head><script>window.capabilityAtDocumentStart = window.__rowndNativeSignInHandoff === true;</script></head><body>Manage Account</body></html>"""
    }
}
