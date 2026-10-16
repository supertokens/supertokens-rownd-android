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
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.clearElement
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.espresso.web.webdriver.DriverAtoms.webScrollIntoView
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.Rownd
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.RowndBottomSheetActivity
import io.rownd.android.views.RowndWebView
import io.rownd.android.views.RowndWebViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
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
        if (InstrumentationRegistry.getArguments().getString("preserveDraft") == "true") return
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
        assertCompletedConsumesRemain(1)
    }

    @Test
    fun draftEmailVerificationReplacesSessionThroughReleasedSdk() {
        qualifyDraftEmailChange(secureAged = false)
    }

    @Test
    fun secureAgedEmailChangeUsesExistingAccountSignInThenVerifies() {
        assertEquals("Secure qualification requires the newly frozen numeric-policy harness", 600,
            getHarness("/config").getInt("emailChangeSessionMaxAgeSeconds"))
        qualifyDraftEmailChange(secureAged = true)
    }

    private fun qualifyDraftEmailChange(secureAged: Boolean) {
        launchApp()
        val email = uniqueEmail("draft-old")
        val destination = uniqueEmail("draft-new")
        openEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))
        waitForSignedInApp()
        val originalAccessToken = requireNotNull(runBlocking { Rownd.getAccessToken() })
        val originalHandle = requireNotNull(sessionHandle(originalAccessToken))
        val original = getHarness("/test/email-change-session?handle=$originalHandle")
        val minimumAge = InstrumentationRegistry.getArguments().getString("minimumSessionAgeSeconds")?.toLong() ?: if (secureAged) 605L else 0L
        if (secureAged) assertTrue("Actual aged qualification must exceed the numeric 600-second gate", minimumAge > 600)
        val remaining = minimumAge * 1000 - (System.currentTimeMillis() - original.getLong("timeCreated"))
        if (remaining > 0) SystemClock.sleep(remaining)
        assertTrue(System.currentTimeMillis() - original.getLong("timeCreated") >= minimumAge * 1000)
        println("DRAFT_EMAIL_INITIATION coreCreatedAt=${original.getLong("timeCreated")} ageMs=${System.currentTimeMillis() - original.getLong("timeCreated")}")
        clickResource("e2e.action.manage-account")
        waitForWebElement("[data-testid='rownd-ui-profile-field-email']")
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-field-email']"))
            .perform(clearElement()).perform(webKeys(destination))
        onWebView().withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-profile-save']"))
            .perform(webScrollIntoView()).perform(webClick())
        waitForText("Confirm your email change")
        waitForText("You are about to change your email address to $destination. Please confirm this action.")
        assertEquals(email, getBackendUserData().getString("email"))
        onWebView().withElement(findElement(Locator.XPATH, "//button[normalize-space()='Continue']")).perform(webClick())
        var verificationSessionHandle = originalHandle
        var verificationAccessToken = originalAccessToken
        if (secureAged) {
            waitForText("Please sign in again to confirm it’s you before changing your email address.")
            assertEquals(0, harnessCounters().getInt("verificationEmail"))
            val rejectedUpdates = getHarness("/test/email-updates").getJSONArray("updates")
            assertEquals(1, rejectedUpdates.length())
            assertEquals(403, rejectedUpdates.getJSONObject(0).getInt("statusCode"))
            waitUntil("S0 cleared before normal sign-in") { !Rownd.state.value.auth.isAuthenticated }
            assertEquals(0, harnessCounters().getInt("verificationEmail"))
            enterEmailChallenge(email)
            assertEquals(0, harnessCounters().getInt("verificationEmail"))
            submitOtp(waitForCapture(email).getString("userInputCode"))
            waitForText("Verify your new email")
            verificationAccessToken = requireNotNull(runBlocking { Rownd.getAccessToken() })
            verificationSessionHandle = requireNotNull(sessionHandle(verificationAccessToken))
            assertFalse(originalHandle == verificationSessionHandle)
            val resumed = getHarness("/test/email-change-session?handle=$verificationSessionHandle")
            assertEquals(original.getString("userId"), resumed.getString("userId"))
            assertEquals("public", resumed.getString("tenantId"))
            println("SECURE_EMAIL_SR_ADOPTED sameOwner=true newHandle=true destinationMailAfterExistingAccountSignIn=true")
        }
        waitForText("Verify your new email")
        waitForText("We have sent a confirmation email to the $destination address. Click on the email confirmation link to finalize the process.")
        assertEquals(email, getBackendUserData().getString("email"))
        assertEquals(verificationSessionHandle, sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertEquals("public", original.getString("tenantId"))
        assertEquals(if (secureAged) 2 else 1, harnessCounters().getInt("passwordlessConsume"))
        val updates = getHarness("/test/email-updates").getJSONArray("updates")
        assertEquals("Initial PUT plus only the required same-owner retry", if (secureAged) 2 else 1, updates.length())
        if (secureAged) assertEquals(403, updates.getJSONObject(0).getInt("statusCode"))
        assertEquals(200, updates.getJSONObject(updates.length() - 1).getInt("statusCode"))
        val pending = getHarness("/test/email-change-session?handle=$verificationSessionHandle").getJSONArray("pending")
        assertEquals(1, pending.length())
        assertEquals(destination, pending.getJSONObject(0).getString("value"))
        assertEquals("PENDING", pending.getJSONObject(0).getString("status"))
        assertEquals(1, harnessCounters().getInt("verificationEmail"))
        assertEquals(if (secureAged) 2 else 1, harnessCounters().getInt("passwordlessEmail"))
        val link = Uri.parse(waitForCapture(destination).getString("emailVerifyLink"))
        val callback = Uri.Builder().scheme(BuildConfig.DEEP_LINK_SCHEME).authority("account")
            .appendPath("verify-email").encodedQuery(link.encodedQuery).build()
        context.getSharedPreferences("draft-email-qualification", 0).edit()
            .putString("callback", callback.toString()).putString("destination", destination)
            .putString("originalHandle", verificationSessionHandle).putString("owner", original.getString("userId")).commit()
        if (InstrumentationRegistry.getArguments().getString("prepareDraftOnly") == "true") return
        dispatchVerificationThroughChrome(callback)
        waitForText("Email verified")
        val replacementHandle = requireNotNull(sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertFalse(verificationSessionHandle == replacementHandle)
        val replacement = getHarness("/test/email-change-session?handle=$replacementHandle")
        assertEquals(original.getString("userId"), replacement.getString("userId"))
        assertEquals("public", replacement.getString("tenantId"))
        assertAccessTokenRejected(originalAccessToken)
        if (secureAged) assertAccessTokenRejected(verificationAccessToken)
        assertFalse(runBlocking { SuperTokensSessionBridge.getRefreshToken(context) }.isNullOrEmpty())
        assertFalse(SuperTokensSessionBridge.getFrontToken(context).isNullOrEmpty())
        assertEquals(0, replacement.getJSONArray("pending").length())
        assertEquals(destination, getBackendUserData().getString("email"))
        assertEquals(if (secureAged) 2 else 1, harnessCounters().getInt("passwordlessConsume"))
        println("DRAFT_EMAIL_REPLACED sameOwner=true pendingCleared=true canonicalPromoted=true cachedEmailMatches=${Rownd.state.value.user.data["email"] == destination}")
        if (InstrumentationRegistry.getArguments().getString("refreshProfileAfterVerification") == "true") {
            assertEquals(destination, runBlocking { Rownd.user.refresh().await() }?.data?.get("email"))
            println("DRAFT_EMAIL_EXPLICIT_PUBLIC_REFRESH profileMatches=true")
        }
        waitUntil("verified canonical email in native profile", 30_000) { Rownd.state.value.user.data["email"] == destination }
        device.findObject(By.text("Close"))?.click()
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForResource("e2e.action.manage-account")
        assertEquals(replacementHandle, sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertEquals(destination, getBackendUserData().getString("email"))
    }

    @Test
    fun coldVerifiedEmailPersistsAndRefreshesAfterProcessRestart() {
        val saved = context.getSharedPreferences("draft-email-qualification", 0)
        val destination = requireNotNull(saved.getString("destination", null))
        launchApp()
        val handle = requireNotNull(sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertFalse(saved.getString("originalHandle", null) == handle)
        val state = getHarness("/test/email-change-session?handle=$handle")
        assertEquals(saved.getString("owner", null), state.getString("userId"))
        assertEquals("public", state.getString("tenantId"))
        assertEquals(0, state.getJSONArray("pending").length())
        val before = harnessCounters().getInt("stRefresh")
        postHarness("/test/refresh/reset", "{}")
        OkHttpClient.Builder().addInterceptor(SuperTokensInterceptor()).build()
            .newCall(Request.Builder().url("${BuildConfig.API_URL}/test/refresh").build()).execute().use {
                assertEquals(200, it.code)
        }
        assertTrue(harnessCounters().getInt("stRefresh") > before)
        assertEquals(handle, sessionHandle(runBlocking { Rownd.getAccessToken() }))
        assertEquals(destination, getBackendUserData().getString("email"))
        println("DRAFT_EMAIL_COLD_RESTART sameOwner=true publicTenant=true replacementHandle=true refreshSucceeded=true cachedEmailMatches=${Rownd.state.value.user.data["email"] == destination}")
        if (InstrumentationRegistry.getArguments().getString("refreshProfileAfterRestart") == "true") {
            assertEquals(destination, runBlocking { Rownd.user.refresh().await() }?.data?.get("email"))
            println("DRAFT_EMAIL_COLD_EXPLICIT_PUBLIC_REFRESH profileMatches=true")
        }
        waitUntil("persisted verified profile", 30_000) { Rownd.state.value.user.data["email"] == destination }
    }

    @Test
    fun signedOutManageAccountHandsOffToNewWebViewAndCompletesNativeSignIn() {
        launchApp()
        instrumentation.runOnMainSync { Rownd.manageAccount() }
        waitForText("Sign in or sign up")
        val source = requireNotNull(activeHubWebView())

        // Target the DOM rather than moving sheet coordinates. The handoff destroys this
        // WebView, so observe the replacement below instead of awaiting a JS callback.
        instrumentation.runOnMainSync {
            source.evaluateJavascript(
                """
                    document.evaluate(
                        "//button[normalize-space()='Sign in or sign up']",
                        document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null
                    ).singleNodeValue.click();
                """.trimIndent(),
                null,
            )
        }

        waitUntil("replacement Hub WebView") { activeHubWebView()?.let { it !== source } == true }
        assertNotSame(source, activeHubWebView())
        val email = uniqueEmail("manage-recovery")
        enterEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))

        waitForSignedInApp()
        assertEquals(1, SandboxObservability.events.value.signInCompletedCount)
        assertProtectedRequestSucceeds()
        assertCompletedConsumesRemain(1)
    }

    @Test
    fun nativeV2ResumeThroughRealHubWithoutAgeWait() {
        launchApp()
        val email = uniqueEmail("resume-diagnostic")
        openEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))
        waitForSignedInApp()
        val original = getHarness("/test/email-change-session?handle=${sessionHandle(requireNotNull(runBlocking { Rownd.getAccessToken() }))}")
        clickResource("e2e.action.manage-account")
        waitForWebElement("[data-testid='rownd-ui-profile-field-email']")
        val envelope = JSONObject().put("version", 2).put("request_id", "diagnostic-request-123456")
            .put("expires_at", System.currentTimeMillis() + 60_000)
            .put("local_draft", JSONObject().put("owner_id", original.getString("userId")).put("tenant_id", "public")
                .put("configuration", "").put("changes", JSONObject().put("email", uniqueEmail("resume-destination"))))
        val message = JSONObject().put("type", "sign_in").put("payload", JSONObject().put("was_user_initiated", true).put("email_change", envelope))
        val source = requireNotNull(activeHubWebView())
        instrumentation.runOnMainSync {
            source.evaluateJavascript("""
                const message = $message;
                message.payload.email_change.local_draft.configuration = JSON.stringify([${JSONObject.quote(BuildConfig.API_URL)}, '/auth', 'public', '', ${JSONObject.quote(BuildConfig.APP_KEY)}]);
                window.rowndAndroidSDK.postMessage(JSON.stringify(message));
            """.trimIndent(), null)
        }
        waitUntil("S0 cleared") { !Rownd.state.value.auth.isAuthenticated }
        waitForText("Please sign in again to confirm it’s you before changing your email address.")
        enterEmailChallenge(email)
        submitOtp(waitForCapture(email).getString("userInputCode"))
        waitForText("Verify your new email")
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
        assertCompletedConsumesRemain(1)
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
        val accessTokenBeforeSignOut = requireNotNull(runBlocking { Rownd.getAccessToken() })

        recreateAppAfterHubCloses()
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
        assertAccessTokenRejected(accessTokenBeforeSignOut)
        recreateAppAfterHubCloses()
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

    private fun recreateAppAfterHubCloses() {
        // ActivityScenario restores the pre-recreation lifecycle state. Recreating while
        // the Hub still covers the app can restore PAUSED behind EmptyFloatingActivity.
        waitUntil("Hub to close and app to resume before recreation") {
            !hasActiveBottomSheetActivity() && scenario!!.state == Lifecycle.State.RESUMED
        }
        scenario!!.recreate()
        scenario!!.moveToState(Lifecycle.State.RESUMED)
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        val preservingSession = InstrumentationRegistry.getArguments().getString("preserveDraft") == "true"
        waitForResource(if (preservingSession) "e2e.action.manage-account" else "e2e.action.open-auth")
    }

    private fun openEmailChallenge(email: String) {
        clickResource("e2e.action.open-auth")
        enterEmailChallenge(email)
    }

    private fun enterEmailChallenge(email: String) {
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
        waitForWebElement("[data-testid='rownd-ui-passwordless-waiting-use-code']")
        onWebView()
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-passwordless-waiting-use-code']"))
            .perform(webScrollIntoView())
            .perform(webClick())
        waitForWebElement("#rph-passwordless-code-input")
        onWebView()
            .withElement(findElement(Locator.ID, "rph-passwordless-code-input"))
            .perform(webScrollIntoView())
            .perform(webKeys(code))
            .withElement(findElement(Locator.CSS_SELECTOR, "[data-testid='rownd-ui-passwordless-code-submit']"))
            .perform(webScrollIntoView())
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

    private fun assertAccessTokenRejected(accessToken: String) {
        val response = OkHttpClient().newCall(
            Request.Builder()
                .url("${BuildConfig.API_URL}/test/protected")
                .header("Authorization", "Bearer $accessToken")
                .header("rid", "session")
                .header("st-auth-mode", "header")
                .build()
        ).execute()
        response.use {
            assertEquals("Hub sign-out must revoke the server session", 401, it.code)
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

    private fun dispatchVerificationThroughChrome(callback: Uri) {
        val launcher = Uri.parse("$harnessUrl/test/email-verification-launcher")
            .buildUpon().encodedQuery(callback.encodedQuery).build()
        context.startActivity(Intent(Intent.ACTION_VIEW, launcher).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        waitUntil("Chrome verification launcher", 30_000) {
            val link = device.findObject(By.desc("Open email verification"))
                ?: device.findObject(By.text("Open email verification"))
            if (link != null) {
                link.click()
                true
            } else {
                listOf("Accept & continue", "Use without an account", "No thanks")
                    .firstNotNullOfOrNull { device.findObject(By.text(it)) }?.click()
                false
            }
        }
        waitUntil("Chrome dispatch to app", 15_000) {
            device.findObject(By.text("Open"))?.click()
            device.findObject(By.text("Just once"))?.click()
            device.hasObject(By.pkg(context.packageName))
        }
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

    private fun activeHubWebView(): RowndWebView? {
        var webView: RowndWebView? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<RowndBottomSheetActivity>()
                .singleOrNull()
            if (activity != null) {
                webView = ViewModelProvider(activity)[RowndWebViewModel::class.java].webView().value
            }
        }
        return webView
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

    private fun assertCompletedConsumesRemain(expectedCount: Int, settleMs: Long = 1_000) {
        waitForCompletedConsumes(expectedCount)
        val deadline = System.currentTimeMillis() + settleMs
        do {
            val result = getHarness("/test/passwordless/consumes")
            assertEquals("passwordless consume count must remain stable", expectedCount, result.getInt("count"))
            val statuses = result.getJSONArray("statuses")
            assertEquals(expectedCount, statuses.length())
            for (index in 0 until statuses.length()) {
                assertEquals("passwordless consume must succeed", 200, statuses.getInt(index))
            }
            SystemClock.sleep(100)
        } while (System.currentTimeMillis() < deadline)
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
        waitUntil("text '$text'", timeoutMs) { device.findObject(By.text(text)) != null }
    }

    private fun waitForWebElement(selector: String, timeoutMs: Long = 15_000) {
        // Off-screen Hub controls need not appear in UIAutomator's accessibility tree.
        val ready = AtomicBoolean(false)
        val pending = AtomicBoolean(false)
        val script = """
            (() => {
                const element = document.querySelector(${JSONObject.quote(selector)});
                return !!element && !element.disabled && element.getClientRects().length > 0;
            })()
        """.trimIndent()
        waitUntil("Hub element '$selector'", timeoutMs) {
            if (!ready.get() && pending.compareAndSet(false, true)) {
                val webView = activeHubWebView()
                if (webView == null) {
                    pending.set(false)
                } else {
                    instrumentation.runOnMainSync {
                        webView.evaluateJavascript(script) { result ->
                            ready.set(result == "true")
                            pending.set(false)
                        }
                    }
                }
            }
            ready.get()
        }
    }

    private fun waitForTextContaining(text: String, timeoutMs: Long = 15_000) {
        waitUntil("text containing '$text'", timeoutMs) { device.findObject(By.textContains(text)) != null }
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
        waitUntil("resource '$resourceName'", timeoutMs) {
            if (device.findObject(targetSelector) != null) return@waitUntil true

            // The Hub-to-app transition can temporarily leave no Compose roots.
            val nodes = composeRule
                .onAllNodesWithTag(resourceName, useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
            if (nodes.isNotEmpty()) {
                composeRule
                    .onNodeWithTag(resourceName, useUnmergedTree = true)
                    .performScrollTo()
            }
            nodes.isNotEmpty()
        }
    }

    private fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        // UIAutomator and idle/sleep loops do not advance the Compose clock driving the Hub sheet.
        composeRule.waitUntil(
            conditionDescription = description,
            timeoutMillis = timeoutMs,
            condition = condition,
        )
    }
}
