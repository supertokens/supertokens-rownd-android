package io.rownd.android

import android.content.Context
import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndJavascriptInterface
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RowndWebViewAuthenticationInstrumentedTest {

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
    fun syntheticAuthenticationMessageEmitsSignInCompletedOnceForSession() {
        val stSession = HarnessClient.createSTSession("webview-event-user")
        val interop = buildAuthenticationMessage(stSession.accessToken, stSession.refreshToken)
        val events = mutableListOf<RowndEventType>()
        val listener: (io.rownd.android.util.RowndEvent) -> Unit = { events.add(it.event) }

        Rownd.addEventListener(listener)
        try {
            val bridge = createJavascriptInterface(HubPageSelector.SignIn)
            bridge.postMessage(interop)

            waitUntil { events == listOf(RowndEventType.SignInCompleted) }

            bridge.postMessage(interop)
            Thread.sleep(300)

            assertEquals(
                "sign_in_completed must only fire once for the same access token",
                listOf(RowndEventType.SignInCompleted),
                events,
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

    private fun createJavascriptInterface(targetPage: HubPageSelector): RowndJavascriptInterface {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val webViewRef = AtomicReference<RowndWebView>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webViewRef.set(RowndWebView(context, null).apply {
                rowndClient = Rownd
                this.targetPage = targetPage
                dismiss = {}
            })
        }

        return RowndJavascriptInterface(webViewRef.get(), {}, {})
    }

    private fun buildAuthenticationMessage(
        accessToken: String,
        refreshToken: String,
        frontToken: String = SuperTokensSessionBridge.buildFrontToken(accessToken),
        antiCSRF: String? = null,
    ): String =
        buildAuthenticationMessageWithRefreshJson(
            accessToken = accessToken,
            refreshTokenJson = "\"$refreshToken\"",
            frontTokenJson = "\"$frontToken\"",
            antiCSRFJson = antiCSRF?.let { "\"$it\"" } ?: "null",
        )

    private fun buildAuthenticationMessageWithRefreshJson(
        accessToken: String,
        refreshTokenJson: String,
        frontTokenJson: String,
        antiCSRFJson: String = "null",
    ): String = """
        {
          "type": "authentication",
          "payload": {
            "access_token": "$accessToken",
            "refresh_token": $refreshTokenJson,
            "front_token": $frontTokenJson,
            "anti_csrf": $antiCSRFJson
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
