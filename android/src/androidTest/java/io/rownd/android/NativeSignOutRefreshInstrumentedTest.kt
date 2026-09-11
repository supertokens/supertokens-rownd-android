package io.rownd.android

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.rownd.android.di.component.DaggerRowndGraph
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeSignOutRefreshInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val http = OkHttpClient()
    private lateinit var rownd: RowndClient
    private lateinit var origin: String

    @Before
    fun setUp() {
        HarnessClient.reset()
        origin = HarnessClient.getConfig().androidUrl
        SuperTokens.Builder(context, origin).apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        SuperTokensSessionBridge.clearLocalSession(context)
        rownd = RowndClient(DaggerRowndGraph.create())
        rownd.store = rownd.stateRepo.getStore()
        rownd._registerActivityLifecycle(context.applicationContext as Application)
        rownd.config.applicationContext = context
        val appConfig = rownd.state.value.appConfig
        rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
            supertokens = SuperTokensConfig(SuperTokensAppInfo(origin, "/auth")),
        ))))
    }

    @After
    fun tearDown() {
        HarnessClient.releaseSignOutGate()
        rownd.authRepo.legacyMigrationApiClient.client.close()
        rownd.authenticatedApiClient.client.close()
        SuperTokensSessionBridge.clearLocalSession(context)
    }

    @Test
    fun expiredAccessSignOutRevokesRealBackendSession() {
        val old = expiredSession()
        rownd.signOut()
        assertLocallySignedOut()
        assertRevoked(old)
    }

    @Test
    fun newSessionDuringOldRefreshSurvives() = assertNewSessionSurvives("/auth/session/refresh", 0)

    @Test
    fun newSessionDuringOldRevokeRetrySurvives() = assertNewSessionSurvives("/auth/signout", 1)

    private fun assertNewSessionSurvives(path: String, skip: Int) {
        val old = expiredSession()
        HarnessClient.armSignOutGate(path, skip)
        val executor = Executors.newSingleThreadExecutor()
        val signOut = executor.submit { rownd.signOut() }
        try {
            await("Old sign-out must reach $path") { HarnessClient.signOutGateReached() }
            assertLocallySignedOut()
            val newer = HarnessClient.createSTSession("new-signout-session-B")
            install(newer)
            val user = User(data = mapOf("user_id" to newer.userId, "email" to "new-session@example.com"), isLoading = true)
            rownd.store.dispatch(StateAction.SetUser(user))
            // Async revocation must keep using the endpoint captured at sign-out invocation.
            val appConfig = rownd.state.value.appConfig
            rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
                supertokens = SuperTokensConfig(SuperTokensAppInfo("http://127.0.0.1:1", "/different-auth")),
            ))))
            assertSdkRequestUses(newer.userId)
            val front = SuperTokensSessionBridge.getFrontToken(context)
            val antiCSRF = SuperTokensSessionBridge.getAntiCSRF(context)
            HarnessClient.releaseSignOutGate()
            signOut.get(10, TimeUnit.SECONDS)
            assertRevoked(old)
            assertTrue("B access token must survive", SuperTokens.getAccessToken(context) == newer.accessToken)
            assertTrue("B refresh token must survive", SuperTokensSessionBridge.getRefreshToken(context) == newer.refreshToken)
            assertTrue("B front token must survive", SuperTokensSessionBridge.getFrontToken(context) == front)
            assertTrue("B anti-CSRF token must survive", SuperTokensSessionBridge.getAntiCSRF(context) == antiCSRF)
            assertTrue("B compatibility auth must survive", rownd.state.value.auth.accessToken == newer.accessToken)
            assertEquals(user, rownd.state.value.user)
            assertSdkRequestUses(newer.userId)
        } finally {
            HarnessClient.releaseSignOutGate()
            signOut.get(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun expiredSession(): HarnessClient.STSessionResponse {
        val session = HarnessClient.createSTSession("expired-signout-session-A", shortLivedAccess = true)
        // Verify the Core-signed access token before expiry, then let it actually expire.
        assertEquals(200, request("/test/protected", session.accessToken, post = false))
        assertTrue(HarnessClient.sessionExists(handle(session)))
        install(session)
        val expiry = payload(session).getLong("exp") * 1000
        Thread.sleep((expiry - System.currentTimeMillis() + 100).coerceAtLeast(0))
        assertEquals(401, request("/test/protected", session.accessToken, post = false))
        return session
    }

    private fun install(session: HarnessClient.STSessionResponse) {
        SuperTokensSessionBridge.bootstrapSession(context, session.accessToken, session.refreshToken)
        rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = session.accessToken)))
    }

    private fun assertLocallySignedOut() {
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
        val prefs = context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE)
        assertNull(prefs.getString("st-storage-item-st-access-token", null))
        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
        assertNull(SuperTokensSessionBridge.getFrontToken(context))
    }

    private fun assertRevoked(session: HarnessClient.STSessionResponse) {
        assertFalse("Ordinary sign-out must revoke the old session in Core", HarnessClient.sessionExists(handle(session)))
        assertEquals("Old refresh credential must no longer work", 401, request("/auth/session/refresh", session.refreshToken))
    }

    private fun assertSdkRequestUses(userId: String) = runBlocking {
        val response = rownd.authenticatedApiClient.client.get("$origin/test/protected")
        assertEquals(200, response.status.value)
        assertEquals(userId, JSONObject(response.bodyAsText()).getString("userId"))
        assertEquals(userId, SuperTokens.getUserId(context))
    }

    private fun payload(session: HarnessClient.STSessionResponse) =
        JSONObject(String(Base64.getUrlDecoder().decode(session.accessToken.split('.')[1])))

    private fun handle(session: HarnessClient.STSessionResponse) = payload(session).getString("sessionHandle")

    private fun request(path: String, token: String, post: Boolean = true): Int {
        val request = Request.Builder().url("$origin$path")
            .header("Authorization", "Bearer $token").header("rid", "session")
            .header("fdi-version", "1.18").header("st-auth-mode", "header")
        if (post) request.post("".toRequestBody())
        return http.newCall(request.build()).execute().use { it.code }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(message, System.currentTimeMillis() < deadline)
            Thread.sleep(25)
        }
    }
}
