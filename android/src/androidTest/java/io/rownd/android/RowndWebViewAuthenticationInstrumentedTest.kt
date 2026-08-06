package io.rownd.android

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndJavascriptInterface
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RowndWebViewAuthenticationInstrumentedTest {
    private val webViews = mutableListOf<RowndWebView>()

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig
        private lateinit var stClient: OkHttpClient
        private val jwtGenerator = JwtGenerator()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            harnessConfig = HarnessClient.getConfig()
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            try {
                SuperTokens.Builder(context, harnessConfig.androidUrl)
                    .apiBasePath("/auth")
                    .tokenTransferMethod("header")
                    .build()
            } catch (_: Exception) {
                // Already initialized by another test class in the same run
            }
            SuperTokensSessionBridge.isInitialized.set(true)

            stClient = OkHttpClient.Builder()
                .addInterceptor(SuperTokensInterceptor())
                .build()
        }

        private fun sharedPrefs(context: Context) =
            context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE)
    }

    @Before
    fun resetState() {
        HarnessClient.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }
        sharedPrefs(context).edit().clear().commit()
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.store = Rownd.stateRepo.getStore()
        Rownd.store.dispatch(StateAction.SetAuth(AuthState()))
    }

    @After
    fun destroyWebViews() {
        if (webViews.isEmpty()) {
            return
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webViews.forEach(RowndWebView::destroy)
        }
        webViews.clear()
    }

    @Test
    fun authenticationMessageOutsideSignInDoesNotBootstrapSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("ignored-auth-user")
        val interop = buildAuthenticationMessage(stSession.accessToken, stSession.refreshToken)

        val bridge = createJavascriptInterface(HubPageSelector.ManageAccount)
        bridge.postMessage(interop)

        Thread.sleep(300)

        val exists = runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        assertFalse("authentication messages outside SignIn must be ignored", exists)
    }

    @Test
    fun authenticationMessageWithNullRefreshTokenDoesNotBootstrapSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val interop = buildAuthenticationMessageWithRefreshJson(
            accessToken = accessToken,
            refreshTokenJson = "null",
            frontTokenJson = "null",
        )

        val bridge = createJavascriptInterface(HubPageSelector.SignIn)
        bridge.postMessage(interop)

        Thread.sleep(300)

        assertFalse("authentication without refresh_token must not create a SuperTokens session", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
    }

    @Test
    fun authenticationMessageUsesProvidedFrontToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()
        val frontToken = SuperTokensSessionBridge.buildFrontToken(jwtGenerator.generateTestJwt())
        val antiCSRF = "anti-csrf-token"
        val interop = buildAuthenticationMessage(accessToken, refreshToken, frontToken, antiCSRF)

        val bridge = createJavascriptInterface(HubPageSelector.SignIn)
        bridge.postMessage(interop)

        waitUntil {
            sharedPrefs(context).getString("supertokens-android-fronttoken-key", null) == frontToken
        }

        assertEquals(frontToken, sharedPrefs(context).getString("supertokens-android-fronttoken-key", null))
        assertEquals(antiCSRF, SuperTokensSessionBridge.getAntiCSRF(context))
    }

    @Test
    fun authChallengeInitiatedDoesNotBootstrapSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val interop = """
            {
              "type": "auth_challenge_initiated",
              "payload": {
                "challenge_id": "challenge-123",
                "user_identifier": "test@example.com"
              }
            }
        """.trimIndent()

        val bridge = createJavascriptInterface(HubPageSelector.SignIn)
        bridge.postMessage(interop)

        Thread.sleep(300)

        val exists = runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        assertFalse("auth_challenge_initiated must not create a SuperTokens session", exists)
        assertEquals("challenge-123", Rownd.stateRepo.state.value.auth.challengeId)
        assertEquals("test@example.com", Rownd.stateRepo.state.value.auth.userIdentifier)
    }

    @Test
    fun userInputCodeOtpCompletesFromSignedOutState() {
        assertUserInputCodeOtpCompletion(existingSession = false)
    }

    @Test
    fun userInputCodeOtpReplacesExistingSession() {
        assertUserInputCodeOtpCompletion(existingSession = true)
    }

    @Test
    fun syntheticAuthenticationMessageCreatesUsableSuperTokensSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("webview-auth-user")
        val interop = buildAuthenticationMessage(stSession.accessToken, stSession.refreshToken)

        val bridge = createJavascriptInterface(HubPageSelector.SignIn)
        bridge.postMessage(interop)

        waitUntil { runBlocking { SuperTokensSessionBridge.doesSessionExist(context) } }

        val request = Request.Builder()
            .url("${harnessConfig.androidUrl}/health")
            .build()
        stClient.newCall(request).execute().use { response ->
            assertEquals("Bootstrapped session must authenticate harness requests", 200, response.code)
        }

        assertNotNull("Rownd.getAccessToken must return the synced SuperTokens token", runBlocking { Rownd.getAccessToken() })
    }

    @Test
    fun syntheticAuthenticationMessageFromDeepLinkCreatesUsableSuperTokensSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("webview-deeplink-auth-user")
        val interop = buildAuthenticationMessage(stSession.accessToken, stSession.refreshToken)

        val bridge = createJavascriptInterface(HubPageSelector.DeepLink)
        bridge.postMessage(interop)

        waitUntil { runBlocking { SuperTokensSessionBridge.doesSessionExist(context) } }

        val request = Request.Builder()
            .url("${harnessConfig.androidUrl}/health")
            .build()
        stClient.newCall(request).execute().use { response ->
            assertEquals("Deep link bootstrapped session must authenticate harness requests", 200, response.code)
        }
    }

    @Test
    fun destroyingWebViewCancelsPendingSignInCompletedFallback() {
        val stSession = HarnessClient.createSTSession("destroyed-webview-auth-user")
        val completionEvent = CountDownLatch(1)
        val listener: (RowndEvent) -> Unit = {
            if (it.event == RowndEventType.SignInCompleted) {
                completionEvent.countDown()
            }
        }

        Rownd.addEventListener(listener)
        try {
            val bridge = createJavascriptInterface(HubPageSelector.DeepLink)
            bridge.postMessage(buildAuthenticationMessage(stSession.accessToken, stSession.refreshToken))

            waitUntil { Rownd.stateRepo.state.value.auth.accessToken == stSession.accessToken }
            destroyWebViews()

            assertFalse(
                "A destroyed WebView must not emit its delayed sign_in_completed fallback",
                completionEvent.await(2, TimeUnit.SECONDS),
            )
        } finally {
            Rownd.removeEventListener(listener)
        }
    }

    @Test
    fun syntheticAuthenticationMessageEmitsSignInCompletedOnceForSession() {
        val stSession = HarnessClient.createSTSession("webview-event-user")
        val interop = buildAuthenticationMessage(
            stSession.accessToken,
            stSession.refreshToken,
            userType = RowndSignInUserType.NewUser,
            appVariantUserType = RowndSignInUserType.NewUser,
        )
        val events = mutableListOf<RowndEvent>()
        val listener: (RowndEvent) -> Unit = { events.add(it) }

        Rownd.addEventListener(listener)
        try {
            val bridge = createJavascriptInterface(HubPageSelector.SignIn)
            bridge.postMessage(interop)

            waitUntil { events.map { it.event } == listOf(RowndEventType.SignInCompleted) }
            assertEquals("new_user", events.single().data["user_type"])
            assertEquals("new_user", events.single().data["app_variant_user_type"])

            bridge.postMessage(interop)
            Thread.sleep(300)

            assertEquals(
                "sign_in_completed must only fire once for the same access token",
                listOf(RowndEventType.SignInCompleted),
                events.map { it.event },
            )
        } finally {
            Rownd.removeEventListener(listener)
        }
    }

    @Test
    fun syntheticAuthenticationMessageRefreshesExpiredBootstrappedSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("webview-refresh-user")
        val expiredToken = jwtGenerator.generateTestJwt(
            expires = Date.from(Instant.now().minusSeconds(3600))
        )
        val interop = buildAuthenticationMessage(expiredToken, stSession.refreshToken)

        val bridge = createJavascriptInterface(HubPageSelector.SignIn)
        bridge.postMessage(interop)

        waitUntil { runBlocking { SuperTokensSessionBridge.doesSessionExist(context) } }

        val request = Request.Builder()
            .url("${harnessConfig.androidUrl}/health")
            .build()
        stClient.newCall(request).execute().close()

        val counters = HarnessClient.getCounters()
        assertEquals("Expired bootstrapped sessions must refresh through SuperTokens", 1, counters.stRefresh)
    }

    private fun assertUserInputCodeOtpCompletion(existingSession: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousSession = if (existingSession) HarnessClient.createSTSession("otp-existing-user") else null
        if (previousSession != null) {
            SuperTokensSessionBridge.bootstrapSession(
                context,
                previousSession.accessToken,
                previousSession.refreshToken,
            )
            Rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = previousSession.accessToken)))
        }

        val authenticatedSession = HarnessClient.createSTSession("otp-authenticated-user")
        val completionCount = AtomicInteger()
        val completionEvent = AtomicReference<RowndEvent>()
        val dismissalCount = AtomicInteger()
        val listener: (RowndEvent) -> Unit = {
            if (it.event == RowndEventType.SignInCompleted) {
                completionEvent.set(it)
                completionCount.incrementAndGet()
            }
        }
        val bridge = createJavascriptInterface(HubPageSelector.SignIn) {
            dismissalCount.incrementAndGet()
        }

        Rownd.addEventListener(listener)
        try {
            bridge.postMessage(buildAuthChallengeInitiatedMessage())

            assertEquals("otp-challenge", Rownd.stateRepo.state.value.auth.challengeId)
            assertEquals("otp@example.com", Rownd.stateRepo.state.value.auth.userIdentifier)
            if (previousSession != null) {
                assertEquals(previousSession.accessToken, runBlocking { SuperTokensSessionBridge.getAccessToken(context) })
            } else {
                assertFalse(runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
            }

            bridge.postMessage(buildAuthenticationMessage(
                authenticatedSession.accessToken,
                authenticatedSession.refreshToken,
                userType = RowndSignInUserType.ExistingUser,
            ))
            bridge.postMessage("""{"type":"auth_challenge_cleared"}""")
            bridge.postMessage(buildSignInCompletedMessage())

            waitUntil {
                runBlocking { SuperTokensSessionBridge.getAccessToken(context) } == authenticatedSession.accessToken &&
                    Rownd.stateRepo.state.value.auth.accessToken == authenticatedSession.accessToken
            }

            assertEquals(authenticatedSession.refreshToken, SuperTokensSessionBridge.getRefreshToken(context))
            assertEquals(authenticatedSession.accessToken, Rownd.stateRepo.state.value.auth.accessToken)
            assertEquals(null, Rownd.stateRepo.state.value.auth.challengeId)
            assertEquals(null, Rownd.stateRepo.state.value.auth.userIdentifier)

            waitUntil { dismissalCount.get() == 1 }
            assertEquals("OTP completion must be emitted once", 1, completionCount.get())
            assertEquals("email", completionEvent.get().data["method"])
            assertEquals("OTP completion must dismiss once", 1, dismissalCount.get())
        } finally {
            Rownd.removeEventListener(listener)
        }
    }

    private fun createJavascriptInterface(
        targetPage: HubPageSelector,
        dismiss: () -> Unit = {},
    ): RowndJavascriptInterface {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val webViewRef = AtomicReference<RowndWebView>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webViewRef.set(RowndWebView(context, null).apply {
                rowndClient = Rownd
                this.targetPage = targetPage
                this.dismiss = dismiss
            })
        }

        return webViewRef.get().let {
            webViews.add(it)
            it.rowndJavascriptInterface
        }
    }

    private fun buildAuthChallengeInitiatedMessage(): String = """
        {
          "type": "auth_challenge_initiated",
          "payload": {
            "challenge_id": "otp-challenge",
            "user_identifier": "otp@example.com"
          }
        }
    """.trimIndent()

    private fun buildSignInCompletedMessage(): String = """
        {
          "type": "event",
          "payload": {
            "event": "sign_in_completed",
            "data": { "method": "email" }
          }
        }
    """.trimIndent()

    private fun buildAuthenticationMessage(
        accessToken: String,
        refreshToken: String,
        frontToken: String = SuperTokensSessionBridge.buildFrontToken(accessToken),
        antiCSRF: String? = null,
        userType: RowndSignInUserType? = null,
        appVariantUserType: RowndSignInUserType? = null,
    ): String =
        buildAuthenticationMessageWithRefreshJson(
            accessToken = accessToken,
            refreshTokenJson = "\"$refreshToken\"",
            frontTokenJson = "\"$frontToken\"",
            antiCSRFJson = antiCSRF?.let { "\"$it\"" } ?: "null",
            userTypeJson = userType?.let { "\"${it.value}\"" } ?: "null",
            appVariantUserTypeJson = appVariantUserType?.let { "\"${it.value}\"" } ?: "null",
        )

    private fun buildAuthenticationMessageWithRefreshJson(
        accessToken: String,
        refreshTokenJson: String,
        frontTokenJson: String,
        antiCSRFJson: String = "null",
        userTypeJson: String = "null",
        appVariantUserTypeJson: String = "null",
    ): String = """
        {
          "type": "authentication",
          "payload": {
            "access_token": "$accessToken",
            "refresh_token": $refreshTokenJson,
            "front_token": $frontTokenJson,
            "anti_csrf": $antiCSRFJson,
            "user_type": $userTypeJson,
            "app_variant_user_type": $appVariantUserTypeJson
          }
        }
    """.trimIndent()

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Condition was not met before timeout", condition())
    }
}
