package io.rownd.rowndtestsandbox

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.Rownd
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.RowndBottomSheetActivity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RealHubE2ETest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val harnessUrl = InstrumentationRegistry.getArguments().getString("harnessUrl")
        ?: "http://10.0.2.2:3138"
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun resetAppAndHarness() {
        postHarness("/reset", "{}")
        val cookiesRemoved = AtomicBoolean(false)
        instrumentation.runOnMainSync {
            Rownd.signOut()
            CookieManager.getInstance().removeAllCookies { removed ->
                if (removed) CookieManager.getInstance().flush()
                cookiesRemoved.set(true)
            }
            WebStorage.getInstance().deleteAllData()
        }
        waitUntil("WebView cookies to clear") { cookiesRemoved.get() }
        waitUntil("native session to clear") {
            !Rownd.state.value.auth.isAuthenticated &&
                !runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        }
        SandboxObservability.reset()
    }

    @After
    fun closeActivity() {
        device.pressBack()
        device.waitForIdle()
        scenario?.close()
        scenario = null
    }

    @Test
    fun emailOtpCrossesRealHubBridgeAndCreatesOneUsableNativeSession() {
        launchApp()
        val email = uniqueEmail("otp")

        openEmailChallenge(email)
        waitUntil("Hub challenge to reach native state") {
            Rownd.state.value.auth.challengeId != null &&
                Rownd.state.value.auth.userIdentifier == email
        }

        submitOtp(waitForCapture(email).getString("userInputCode"))

        waitForSignedInApp()
        assertEquals("resolved challenge must be cleared", null, Rownd.state.value.auth.challengeId)
        assertEquals("sign-in completion must be emitted once", 1, SandboxObservability.events.value.signInCompletedCount)
        assertProtectedRequestSucceeds()
    }

    @Test
    fun magicLinkActionViewCompletesOnceAndReplayDoesNotReplaceSession() {
        launchApp()
        val email = uniqueEmail("magic")

        openEmailChallenge(email)
        val customSchemeLink = toCustomScheme(waitForCapture(email).getString("urlWithLinkCode"))
        dispatchActionView(customSchemeLink)

        waitForCompletedConsumes(1)
        waitForSignedInApp()
        waitUntil("RowndBottomSheetActivity to close after magic-link authentication") {
            !hasActiveBottomSheetActivity()
        }
        assertFalse("RowndBottomSheetActivity must close after authentication", hasActiveBottomSheetActivity())
        assertEquals(1, SandboxObservability.events.value.signInCompletedCount)
        assertProtectedRequestSucceeds()
        val originalAccessToken = runBlocking { Rownd.getAccessToken() }
        val originalSessionHandle = sessionHandle(originalAccessToken)

        dispatchActionView(customSchemeLink)
        waitUntil("replay Hub to open") {
            device.findObject(By.res("e2e.action.manage-account")) == null
        }
        waitForResource("e2e.action.manage-account")
        val replay = waitForCompletedConsumes(1)

        assertEquals(1, replay.getInt("count"))
        assertTrue("existing session must survive replay", Rownd.state.value.auth.isAuthenticated)
        assertTrue(runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
        assertEquals(originalSessionHandle, sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertEquals("replay must not emit another completion", 1, SandboxObservability.events.value.signInCompletedCount)
    }

    @Test
    fun restoredSessionCanSignOutInRealManageAccountHubAndStaysSignedOut() {
        launchApp()
        val email = uniqueEmail("manage")
        openEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))
        waitForSignedInApp()

        scenario!!.recreate()
        waitUntil("session to restore after activity recreation") { Rownd.state.value.auth.isAuthenticated }
        assertProtectedRequestSucceeds()

        clickResource("e2e.action.manage-account")
        waitForText("Sign out")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-sign-out']"))
            .perform(webClick())

        waitUntil("Hub sign-out to clear native session") {
            !Rownd.state.value.auth.isAuthenticated &&
                !runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        }
        scenario!!.recreate()
        waitForResource("e2e.action.open-auth")
        assertFalse(Rownd.state.value.auth.isAuthenticated)
        assertFalse(runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })

        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForResource("e2e.action.open-auth")
        assertFalse("signed-out state must survive relaunch", Rownd.state.value.auth.isAuthenticated)
        assertFalse(runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
    }

    @Test
    fun manageAccountProfileUpdatePersistsAndCrossesRealHubBridgeIntoNativeState() {
        launchApp()
        val email = uniqueEmail("profile")
        val nickname = "Hub nickname ${System.nanoTime()}"
        openEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))
        waitForSignedInApp()
        waitUntil("initial native profile load to complete") {
            !Rownd.state.value.user.isLoading &&
                Rownd.state.value.user.data["email"] == email &&
                !(Rownd.state.value.user.data["user_id"] as? String).isNullOrBlank()
        }
        assertFalse("test must observe a new nickname", Rownd.state.value.user.data["nickname"] == nickname)

        clickResource("e2e.action.manage-account")
        waitForText("Personal information")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-mobile-tab-personal']"))
            .perform(webClick())
        waitForText("Nickname")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-field-nickname']"))
            .perform(webKeys(nickname))
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-save']"))
            .perform(webClick())

        waitUntil("nickname to persist in the backend") {
            runCatching { getBackendUserData().optString("nickname") == nickname }.getOrDefault(false)
        }
        waitUntil("real Hub user_data_update to update native state") {
            Rownd.state.value.user.data["nickname"] == nickname
        }
        assertEquals(nickname, getBackendUserData().getString("nickname"))
        assertEquals(nickname, Rownd.state.value.user.data["nickname"])
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        waitForResource("e2e.action.open-auth")
    }

    private fun openEmailChallenge(email: String) {
        clickResource("e2e.action.open-auth")
        waitForTextContaining("Email")
        onWebView()
            .withElement(findElement(Locator.ID, "rph-sign-in-identifier-input"))
            .perform(webKeys(email))
        waitUntil("email continue button to become enabled") {
            device.findObject(By.text("Continue"))?.isEnabled == true
        }
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-login-continue-button']"))
            .perform(webClick())
        waitUntil("email challenge to start") {
            Rownd.state.value.auth.challengeId != null &&
                Rownd.state.value.auth.userIdentifier == email
        }
    }

    private fun submitOtp(code: String) {
        waitForText("Use a code instead")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-passwordless-waiting-use-code']"))
            .perform(webClick())
        waitForText("Enter your sign-in code")
        onWebView()
            .withElement(findElement(Locator.ID, "rph-passwordless-code-input"))
            .perform(webKeys(code))
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-passwordless-code-submit']"))
            .perform(webClick())
    }

    private fun waitForSignedInApp() {
        waitUntil("native authentication") {
            Rownd.state.value.auth.isAuthenticated &&
                runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        }
        waitForResource("e2e.action.manage-account")
        waitUntil("single settled sign-in event") {
            SandboxObservability.events.value.signInCompletedCount == 1
        }
    }

    private fun assertProtectedRequestSucceeds() {
        val response = OkHttpClient.Builder()
            .addInterceptor(SuperTokensInterceptor())
            .build()
            .newCall(Request.Builder().url("${BuildConfig.API_URL}/test/protected").build())
            .execute()
        response.use {
            assertEquals("native SuperTokens session must authorize API requests", 200, it.code)
            assertTrue(it.body?.string()?.contains("userId") == true)
        }
    }

    private fun getBackendUserData(): JSONObject {
        val response = OkHttpClient.Builder()
            .addInterceptor(SuperTokensInterceptor())
            .build()
            .newCall(
                Request.Builder()
                    .url("${BuildConfig.API_URL}/auth/plugin/rownd/user")
                    .build()
            )
            .execute()
        return response.use {
            val body = it.body?.string().orEmpty()
            check(it.code == 200) { "Backend user request returned ${it.code}: $body" }
            JSONObject(body).getJSONObject("data")
        }
    }

    private fun dispatchActionView(uri: Uri) {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    private fun hasActiveBottomSheetActivity(): Boolean {
        var hasActiveActivity = false
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            hasActiveActivity = Stage.entries
                .filter { it != Stage.DESTROYED }
                .any { stage ->
                    monitor.getActivitiesInStage(stage).any { it is RowndBottomSheetActivity }
                }
        }
        return hasActiveActivity
    }

    private fun toCustomScheme(link: String): Uri {
        val source = Uri.parse(link)
        return Uri.Builder()
            .scheme(BuildConfig.DEEP_LINK_SCHEME)
            .authority("account")
            .appendPath("login")
            .encodedQuery(source.encodedQuery)
            .encodedFragment(source.encodedFragment)
            .build()
    }

    private fun uniqueEmail(prefix: String) = "$prefix-${System.nanoTime()}@example.com"

    private fun waitForCapture(email: String): JSONObject {
        lateinit var capture: JSONObject
        waitUntil("passwordless capture for $email") {
            runCatching {
                capture = getHarness("/captures/latest?email=${Uri.encode(email)}")
                true
            }.getOrDefault(false)
        }
        return capture
    }

    private fun harnessCounters() = getHarness("/counters")

    private fun waitForCompletedConsumes(count: Int): JSONObject {
        lateinit var result: JSONObject
        waitUntil("$count completed passwordless consumes") {
            result = getHarness("/test/passwordless/consumes")
            result.getInt("count") >= count && result.getJSONArray("statuses").length() >= count
        }
        return result
    }

    private fun sessionHandle(accessToken: String?): String? {
        if (accessToken == null) return null
        val payload = accessToken.split('.').getOrNull(1) ?: return null
        val decoded = android.util.Base64.decode(payload, android.util.Base64.URL_SAFE)
        return JSONObject(String(decoded)).optString("sessionHandle").ifEmpty { null }
    }

    private fun getHarness(path: String): JSONObject {
        val connection = URL("$harnessUrl$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        check(status in 200..299) { "Harness GET $path returned $status: $body" }
        return JSONObject(body)
    }

    private fun postHarness(path: String, body: String): JSONObject {
        val connection = URL("$harnessUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.outputStream.bufferedWriter().use { it.write(body) }
        val status = connection.responseCode
        val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
            .bufferedReader().use { it.readText() }
        check(status in 200..299) { "Harness POST $path returned $status: $responseBody" }
        return JSONObject(responseBody)
    }

    private fun waitForText(text: String, timeoutMs: Long = 15_000) {
        assertNotNull("Timed out waiting for '$text'", device.wait(Until.findObject(By.text(text)), timeoutMs))
    }

    private fun waitForTextContaining(text: String, timeoutMs: Long = 15_000) {
        assertNotNull("Timed out waiting for text containing '$text'", device.wait(Until.findObject(By.textContains(text)), timeoutMs))
    }

    private fun clickResource(resourceName: String, timeoutMs: Long = 15_000) {
        device.findObject(By.res(resourceName))?.let {
            it.click()
            return
        }

        waitForResource(resourceName, timeoutMs)
        composeRule
            .onNodeWithTag(resourceName, useUnmergedTree = true)
            .performClick()
    }

    private fun waitForResource(resourceName: String, timeoutMs: Long = 15_000) {
        val targetSelector = By.res(resourceName)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        fun remainingMs() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

        while (remainingMs() > 0) {
            if (device.findObject(targetSelector) != null) return

            val nodes = composeRule
                .onAllNodesWithTag(resourceName, useUnmergedTree = true)
                .fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                composeRule
                    .onNodeWithTag(resourceName, useUnmergedTree = true)
                    .performScrollTo()
                return
            }

            SystemClock.sleep(minOf(50, remainingMs()))
        }

        throw AssertionError("Timed out waiting for resource '$resourceName'")
    }

    private fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            instrumentation.waitForIdleSync()
            SystemClock.sleep(50)
        }
        assertTrue("Timed out waiting for $description", condition())
    }
}
