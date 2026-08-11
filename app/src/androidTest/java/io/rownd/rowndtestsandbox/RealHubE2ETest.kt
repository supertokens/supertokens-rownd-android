package io.rownd.rowndtestsandbox

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.Rownd
import io.rownd.android.util.SuperTokensSessionBridge
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
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RealHubE2ETest {
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

        waitForSignedInApp()
        assertEquals(1, SandboxObservability.events.value.signInCompletedCount)
        assertProtectedRequestSucceeds()
        val originalAccessToken = runBlocking { Rownd.getAccessToken() }
        val originalSessionHandle = sessionHandle(originalAccessToken)

        dispatchActionView(customSchemeLink)
        val replay = waitForCompletedConsumes(2)

        assertEquals(2, replay.getInt("count"))
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

        waitForResource("e2e.action.manage-account").click()
        waitForText("Sign out")
        onWebView()
            .withElement(findElement(Locator.ID, "rownd-ui-profile-sign-out"))
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
                Rownd.state.value.user.data["user_id"] == "harness-user"
        }
        assertFalse("test must observe a new nickname", Rownd.state.value.user.data["nickname"] == nickname)

        waitForResource("e2e.action.manage-account").click()
        waitForText("Personal information")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-mobile-tab-personal']"))
            .perform(webClick())
        waitForText("Nickname")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-field-nickname']"))
            .perform(webKeys(nickname))
        waitForText("Save edits")
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
        waitForResource("e2e.action.open-auth").click()
        waitForText("Email")
        onWebView()
            .withElement(findElement(Locator.ID, "rph-sign-in-identifier-input"))
            .perform(webKeys(email))
            .withElement(findElement(Locator.ID, "rownd-ui-login-continue-button"))
            .perform(webClick())
        waitForText("Enter your sign-in code")
    }

    private fun submitOtp(code: String) {
        onWebView()
            .withElement(findElement(Locator.ID, "rph-passwordless-code-input"))
            .perform(webKeys(code))
            .withElement(findElement(Locator.ID, "rownd-ui-passwordless-code-submit"))
            .perform(webClick())
    }

    private fun waitForSignedInApp() {
        waitUntil("native authentication") {
            Rownd.state.value.auth.isAuthenticated &&
                runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        }
        waitForText("Post-login page")
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

    private fun waitForResource(resourceName: String, timeoutMs: Long = 15_000) =
        device.wait(Until.findObject(By.res(resourceName)), timeoutMs)
            ?: throw AssertionError("Timed out waiting for resource '$resourceName'")

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
