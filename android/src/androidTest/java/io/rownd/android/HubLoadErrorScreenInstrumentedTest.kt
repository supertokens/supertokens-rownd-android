package io.rownd.android

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.AppLifecycleListener
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndBottomSheetActivity
import io.rownd.android.views.RowndWebViewModel
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HubLoadErrorScreenInstrumentedTest {
    @Test
    fun readinessTimeoutDisplaysSupportCodeAndCloseDismissesNativeSheet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val previousLifecycle = Rownd.appHandleWrapper
        val previousBaseUrl = Rownd.config.baseUrl
        val previousBackground = Rownd.config.customizations.sheetBackgroundColor
        val previousNightMode = AppCompatDelegate.getDefaultNightMode()
        val errorRendered = CountDownLatch(1)
        Rownd.store = Rownd.stateRepo.getStore()
        Rownd.config.baseUrl = "https://rownd-hub.test"
        Rownd.config.pendingHubDeepLinkUrl = "https://rownd-hub.test/mobile_app"
        Rownd.config.customizations.sheetBackgroundColor = Color(0xff252548)
        instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES) }
        Rownd.appHandleWrapper = AppLifecycleListener(app).apply {
            registerActivityListener(persistentListOf(Lifecycle.State.CREATED)) { activity ->
                if (activity is RowndBottomSheetActivity) {
                    activity.window.decorView.setBackgroundColor(android.graphics.Color.rgb(16, 5, 38))
                    ViewModelProvider(activity, RowndWebViewModel.Factory(app, Rownd))
                        .get(RowndWebViewModel::class.java).webView().observe(activity) { view ->
                            if (view == null) return@observe
                            view.rowndWebViewClient.loadTimeoutMilliseconds = 1000
                            view.webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(webView: WebView?, request: WebResourceRequest?) =
                                    if (request?.url?.host == "rownd-hub.test") {
                                        WebResourceResponse("text/html", "UTF-8", "<html><body></body></html>".byteInputStream())
                                    } else null

                                override fun onPageStarted(webView: WebView?, url: String?, favicon: Bitmap?) {
                                    view.rowndWebViewClient.onPageStarted(webView, url, favicon)
                                }

                                override fun onPageFinished(webView: WebView, url: String) {
                                    view.rowndWebViewClient.onPageFinished(webView, url)
                                    webView.evaluateJavascript("document.querySelector('.error-code code')?.textContent") {
                                        if (it == "\"HUB_INIT_TIMEOUT\"") errorRendered.countDown()
                                    }
                                }
                            }
                        }
                }
            }
        }

        try {
            val intent = Intent(instrumentation.targetContext, RowndBottomSheetActivity::class.java)
                .putExtra("extra_target_page", HubPageSelector.SignIn)
            ActivityScenario.launch<RowndBottomSheetActivity>(intent).use { scenario ->
                assertTrue("The sheet must render the timeout code", errorRendered.await(10, TimeUnit.SECONDS))
                if (InstrumentationRegistry.getArguments().getString("captureHubError") == "true") {
                    // Let the sheet and loading-overlay fade finish before capturing the rendered UI.
                    SystemClock.sleep(500)
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val output = File(instrumentation.targetContext.getExternalFilesDir(null), "hub-load-error.png")
                    output.outputStream().use { stream -> screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                    screenshot.recycle()
                }
                scenario.onActivity { activity ->
                    val webView = ViewModelProvider(activity)[RowndWebViewModel::class.java].webView().value!!
                    webView.evaluateJavascript("document.querySelector('.close-button').click()", null)
                }
                val deadline = SystemClock.elapsedRealtime() + 5000
                while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(50)
                }
                assertEquals("Close must dismiss the native sheet", Lifecycle.State.DESTROYED, scenario.state)
            }
        } finally {
            Rownd.appHandleWrapper?.unregister()
            Rownd.appHandleWrapper = previousLifecycle
            Rownd.config.baseUrl = previousBaseUrl
            Rownd.config.pendingHubDeepLinkUrl = null
            Rownd.config.customizations.sheetBackgroundColor = previousBackground
            instrumentation.runOnMainSync { AppCompatDelegate.setDefaultNightMode(previousNightMode) }
        }
    }
}
