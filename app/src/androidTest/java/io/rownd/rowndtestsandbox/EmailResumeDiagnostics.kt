package io.rownd.rowndtestsandbox

import android.app.KeyguardManager
import android.app.Instrumentation
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import io.rownd.android.Rownd
import io.rownd.android.views.RowndBottomSheetActivity
import io.rownd.android.views.RowndWebViewModel
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal enum class EmailResumePhase { REAUTH_TIMEOUT, REAUTH_OBSERVED }

internal fun captureEmailResumeDiagnostics(instrumentation: Instrumentation, phase: EmailResumePhase, backend: () -> JSONObject) {
    // Allowlisted predicates only: never serialize DOM/accessibility text, URLs, values, or credentials.
    try {
        val started = SystemClock.uptimeMillis()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val result = JSONObject()
            .put("phase", phase.name)
            .put("firstHeadingObserved", device.findObject(By.text("Verify your new email")) != null)
            .put("firstPredicateMs", SystemClock.uptimeMillis() - started)
            .put("authenticated", Rownd.state.value.auth.isAuthenticated)
            .put("screenOn", device.isScreenOn)
            .put("keyguardLocked", (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
            .put("foreground", when (device.currentPackageName) {
                context.packageName -> "app"
                "com.android.chrome" -> "chrome"
                "com.android.systemui" -> "system-ui"
                else -> "other"
            })

        val windows = JSONArray()
        for (window in instrumentation.uiAutomation.windows) {
            var visited = 0
            var pendingHeading = 0
            var webViews = 0
            fun inspect(node: AccessibilityNodeInfo) {
                if (++visited > 1024) return
                if (node.text?.toString() == "Verify your new email") pendingHeading++
                if (node.className?.toString() == "android.webkit.WebView") webViews++
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { inspect(it) }
                }
            }
            window.root?.let { inspect(it) }
            windows.put(JSONObject().put("type", window.type).put("active", window.isActive)
                .put("focused", window.isFocused).put("visited", visited)
                .put("pendingHeading", pendingHeading).put("webViews", webViews))
        }
        result.put("accessibilityWindows", windows)
        result.put("accessibilityAtMs", SystemClock.uptimeMillis() - started)

        val script = instrumentation.context.assets.open("email-resume-diagnostics.js")
            .bufferedReader().use { it.readText() }
        val roots = JSONArray()
        val pending = mutableListOf<CountDownLatch>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in Stage.entries.filter { it != Stage.DESTROYED }) {
                for (activity in monitor.getActivitiesInStage(stage).filterIsInstance<RowndBottomSheetActivity>()) {
                    val root = JSONObject().put("stage", stage.name).put("focused", activity.hasWindowFocus())
                    roots.put(root)
                    val webView = ViewModelProvider(activity)[RowndWebViewModel::class.java].webView().value
                    root.put("webView", webView != null)
                    if (webView != null) {
                        val rect = Rect()
                        root.put("shown", webView.isShown).put("attached", webView.isAttachedToWindow)
                            .put("progress", webView.progress).put("globalVisible", webView.getGlobalVisibleRect(rect))
                            .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom)))
                            .put("size", JSONArray(listOf(webView.width, webView.height)))
                        val done = CountDownLatch(1)
                        pending.add(done)
                        webView.evaluateJavascript(script) { value ->
                            try {
                                root.put("dom", JSONTokener(value).nextValue() as JSONObject)
                                root.put("domAtMs", SystemClock.uptimeMillis() - started)
                            } catch (failure: Exception) {
                                root.put("domError", failure.javaClass.simpleName)
                            } finally {
                                done.countDown()
                            }
                        }
                    }
                }
            }
        }
        // This runs only after the assertion result; it cannot turn a timeout into a pass.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        for (done in pending) done.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        result.put("backend", backend())
        result.put("backendAtMs", SystemClock.uptimeMillis() - started)
        instrumentation.runOnMainSync {
            result.put("hubRoots", roots)
            result.put("domComplete", pending.all { it.count == 0L })
            println("EMAIL_RESUME_DIAGNOSTIC $result")
        }
    } catch (failure: Exception) {
        println("EMAIL_RESUME_DIAGNOSTIC " + JSONObject().put("phase", phase.name).put("diagnosticError", failure.javaClass.simpleName))
    }
}
