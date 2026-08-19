package io.rownd.android

import android.app.Instrumentation
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.NewIntentTestActivity
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndWebView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RowndWebViewInstrumentedTest {
    private lateinit var instrumentation: Instrumentation
    private lateinit var webView: RowndWebView
    private lateinit var javascriptProbe: JavascriptProbe
    private lateinit var originalBaseUrl: String
    private val completedLoads = Semaphore(0)

    @Before
    fun setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        originalBaseUrl = Rownd.config.baseUrl
        Rownd.config.baseUrl = HUB_ORIGIN
        javascriptProbe = JavascriptProbe()

        instrumentation.runOnMainSync {
            webView = RowndWebView(instrumentation.targetContext, null).apply {
                addJavascriptInterface(javascriptProbe, JAVASCRIPT_PROBE_NAME)
                setIsLoading = { isLoading ->
                    if (!isLoading) {
                        completedLoads.release()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        Rownd.config.baseUrl = originalBaseUrl
        if (::webView.isInitialized) {
            instrumentation.runOnMainSync {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
        }
    }

    @Test
    fun opaqueUrlDoesNotMatchOrClearPendingTargetPageRequest() {
        instrumentation.runOnMainSync {
            webView.prepareTargetPageRequest(
                HubPageSelector.SignIn,
                null,
                requestUrl(requestId = 1, config = "pending"),
            )
        }

        assertNull(webView.pendingTargetPageRequest("about:blank"))
        assertTrue(webView.hasPendingTargetPageRequest())
    }

    @Test
    fun appleCallbackDoesNotRequestSignInAgain() {
        prepareAndLoadTarget(
            marker = "apple",
            targetUrl = requestUrl(requestId = 1, config = "initial"),
        )

        loadDocument("$HUB_ORIGIN/mobile_app?code=authorization-code&state=opaque-state")
        awaitCompletedLoad("The Apple callback document did not finish loading")
        awaitJavascriptQueue()

        assertEquals(
            "Apple callback completion must not restart sign-in",
            listOf("apple"),
            javascriptProbe.targetMarkers,
        )
    }

    @Test
    fun explicitRearmAfterPriorRequestInvokesTargetExactlyOnce() {
        prepareAndLoadTarget(
            marker = "initial",
            targetUrl = requestUrl(requestId = 1, config = "initial"),
        )
        assertEquals(listOf("initial"), javascriptProbe.targetMarkers)

        prepareAndLoadTarget(
            marker = "retry",
            targetUrl = requestUrl(requestId = 2, config = "retry"),
        )

        assertEquals(
            "An explicitly re-armed request must invoke only its own target once",
            listOf("initial", "retry"),
            javascriptProbe.targetMarkers,
        )
        assertEquals(2, javascriptProbe.targetInvocationCount.get())
    }

    @Test
    fun detachingAndReattachingSameWebViewPreservesStateWithoutReplayingTarget() {
        ActivityScenario.launch(NewIntentTestActivity::class.java).use { scenario ->
            val container = AtomicReference<FrameLayout>()
            scenario.onActivity { activity ->
                container.set(FrameLayout(activity).also { root ->
                    activity.setContentView(root)
                    root.addView(webView)
                })
            }

            prepareAndLoadTarget(
                marker = "attached",
                targetUrl = requestUrl(requestId = 1, config = "attached"),
            )
            assertEquals(
                "\"state-before-detach\"",
                evaluateJavascript("window.testState = 'state-before-detach'; window.testState"),
            )

            scenario.onActivity {
                container.get().removeView(webView)
                container.get().addView(webView)
            }
            instrumentation.waitForIdleSync()
            awaitJavascriptQueue()

            assertEquals(
                "Reattaching the same WebView must preserve its JavaScript page state",
                "\"state-before-detach\"",
                evaluateJavascript("window.testState"),
            )
            assertEquals(
                "Reattaching the same WebView must not replay its target",
                listOf("attached"),
                javascriptProbe.targetMarkers,
            )
        }
    }

    @Test
    fun customHubCanonicalRedirectPreservingSdkRequestIdInvokesTargetExactlyOnce() {
        val requestedUrl = requestUrl(requestId = 1, config = "custom")
        val canonicalUrl = "$HUB_ORIGIN/mobile_app/?rph_sdk_request_id=1&config=custom"

        prepareAndLoadTarget(
            marker = "redirected",
            targetUrl = requestedUrl,
            loadedUrl = canonicalUrl,
        )

        assertEquals(
            "A canonical redirect preserving the SDK request ID must invoke the target once",
            listOf("redirected"),
            javascriptProbe.targetMarkers,
        )
        assertEquals(1, javascriptProbe.targetInvocationCount.get())
    }

    private fun prepareAndLoadTarget(
        marker: String,
        targetUrl: String,
        loadedUrl: String = targetUrl,
    ) {
        instrumentation.runOnMainSync {
            webView.prepareTargetPageRequest(
                HubPageSelector.SignIn,
                """{"marker":"$marker"}""",
                targetUrl,
            )
        }
        loadDocument(loadedUrl)
        javascriptProbe.awaitTargetInvocation(
            "The target for '$marker' was not invoked after loading $loadedUrl",
        )
        awaitCompletedLoad("The target request for '$marker' did not finish loading")
        awaitJavascriptQueue()
    }

    private fun loadDocument(url: String) {
        instrumentation.runOnMainSync {
            webView.loadDataWithBaseURL(
                url,
                HUB_DOCUMENT,
                "text/html",
                "UTF-8",
                null,
            )
        }
        javascriptProbe.awaitDocumentLoad("The Hub document did not load for $url")
        javascriptProbe.awaitPageFinished("The Hub document did not finish loading for $url")
        evaluateJavascript(
            """window.rowndAndroidSDK.postMessage(JSON.stringify({type: "hub_loaded"}))""",
        )
    }

    private fun requestUrl(requestId: Int, config: String): String =
        "$HUB_ORIGIN/mobile_app?config=$config&rph_sdk_request_id=$requestId"

    private fun awaitCompletedLoad(message: String) {
        assertTrue(
            message,
            completedLoads.tryAcquire(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    private fun awaitJavascriptQueue() {
        evaluateJavascript("true")
    }

    private fun evaluateJavascript(script: String): String {
        val result = AtomicReference<String>()
        val javascriptQueueDrained = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(script) {
                result.set(it)
                javascriptQueueDrained.countDown()
            }
        }
        assertTrue(
            "The WebView JavaScript queue did not drain",
            javascriptQueueDrained.await(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        return result.get()
    }

    private class JavascriptProbe {
        val targetInvocationCount = AtomicInteger()
        val targetMarkers = CopyOnWriteArrayList<String>()
        private val documentLoads = Semaphore(0)
        private val finishedPageLoads = Semaphore(0)
        private val targetInvocations = Semaphore(0)

        @JavascriptInterface
        fun documentLoaded() {
            documentLoads.release()
        }

        @JavascriptInterface
        fun recordTarget(marker: String) {
            targetMarkers.add(marker)
            targetInvocationCount.incrementAndGet()
            targetInvocations.release()
        }

        @JavascriptInterface
        fun pageFinished() {
            finishedPageLoads.release()
        }

        fun awaitDocumentLoad(message: String) {
            assertTrue(
                message,
                documentLoads.tryAcquire(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }

        fun awaitPageFinished(message: String) {
            assertTrue(
                message,
                finishedPageLoads.tryAcquire(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }

        fun awaitTargetInvocation(message: String) {
            assertTrue(
                message,
                targetInvocations.tryAcquire(WEBVIEW_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            )
        }
    }

    private companion object {
        const val HUB_ORIGIN = "https://rownd-hub.test"
        const val JAVASCRIPT_PROBE_NAME = "testJavascriptProbe"
        const val WEBVIEW_TIMEOUT_SECONDS = 5L
        val HUB_DOCUMENT = """
            <!doctype html>
            <html>
              <body>
                <script>
                  window.rownd = {
                    setSessionStorage: function() {
                      $JAVASCRIPT_PROBE_NAME.pageFinished();
                    },
                    requestSignIn: function(options) {
                      $JAVASCRIPT_PROBE_NAME.recordTarget(options && options.marker);
                    }
                  };
                  $JAVASCRIPT_PROBE_NAME.documentLoaded();
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}
