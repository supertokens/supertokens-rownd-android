package io.rownd.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auth0.android.jwt.JWT
import com.supertokens.session.SuperTokens
import io.rownd.android.di.component.DaggerRowndGraph
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.ServerException
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class SessionRefreshFailureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
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
        try {
            setRefreshUnavailable(false)
        } finally {
            SuperTokensSessionBridge.clearLocalSession(context)
            rownd.authenticatedApiClient.client.close()
            rownd.authRepo.legacyMigrationApiClient.client.close()
            rownd.authRepo.legacyTokenApiClient.client.close()
            rownd.appHandleWrapper?.unregister()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun successfulRefreshSynchronizesAuthAndPreservesProfile() = runBlocking {
        val session = createSession()
        val profile = User(data = mapOf("user_id" to session.userId, "email" to "cached@example.com"))
        rownd.store.dispatch(StateAction.SetUser(profile))
        rownd.store.dispatch(StateAction.SetAuth(rownd.state.value.auth.copy(isVerifiedUser = true)))
        awaitExpiry(session)

        val refreshed = rownd.getAccessToken(throwIfMissing = true)
        assertNotNull(refreshed)
        assertTrue("Native access token should rotate", refreshed != session.accessToken)
        assertTrue("Refresh token should rotate", SuperTokensSessionBridge.getRefreshToken(context) != session.refreshToken)
        assertEquals(handle(session.accessToken), handle(refreshed!!))
        assertEquals(200, request("/test/protected", refreshed))
        assertEquals(1, HarnessClient.getCounters().stRefresh)

        assertTrue("Compatibility token must match the native session", rownd.state.value.auth.accessToken == refreshed)
        assertTrue(rownd.state.value.auth.isAccessTokenValid)
        assertTrue(rownd.state.value.auth.isAuthenticated)
        assertTrue(rownd.state.value.auth.isVerifiedUser)
        assertNull(rownd.state.value.auth.refreshToken)
        assertEquals(profile, rownd.state.value.user)
    }

    @Test
    fun refresh503ThrowsRetryableErrorsAndPreservesCredentialsForRecovery() = runBlocking {
        val session = createSession()
        val profile = User(data = mapOf("user_id" to session.userId))
        rownd.store.dispatch(StateAction.SetUser(profile))
        awaitExpiry(session)
        setRefreshUnavailable(true)

        assertRetryableFailure { rownd.getAccessToken() }
        assertRetryableFailure { rownd.getAccessToken(throwIfMissing = true) }
        assertRetryableFailure { rownd.getAccessTokenAndThrowIfMissing() }
        assertRetryableFailure { rownd._refreshToken() }
        assertTrue("Refresh credentials survive", SuperTokensSessionBridge.getRefreshToken(context) == session.refreshToken)
        assertTrue("Rownd identity survives", rownd.state.value.auth.accessToken == session.accessToken)
        assertTrue(rownd.state.value.auth.isAuthenticated)
        assertEquals(profile, rownd.state.value.user)
        assertTrue("Core session still exists", HarnessClient.sessionExists(handle(session.accessToken)))

        setRefreshUnavailable(false)
        val recovered = rownd.getAccessToken(throwIfMissing = true)!!
        assertEquals(handle(session.accessToken), handle(recovered))
        assertEquals(200, request("/test/protected", recovered))
        assertTrue(rownd.state.value.auth.accessToken == recovered)
        assertTrue(rownd.state.value.auth.isAccessTokenValid)
        assertEquals(0, HarnessClient.getCounters().migrate)
        assertEquals(0, HarnessClient.getCounters().legacyRefresh)
    }

    @Test
    fun revokedNativeSessionClearsCompatibilityAuthAndProfile() = runBlocking {
        val session = createSession()
        rownd.store.dispatch(StateAction.SetUser(User(data = mapOf("user_id" to session.userId))))
        assertEquals(200, request("/auth/signout", session.accessToken, post = true))
        awaitExpiry(session)

        assertNull(rownd.getAccessToken())
        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
        assertFalse(HarnessClient.sessionExists(handle(session.accessToken)))
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
    }

    @Test
    fun forcedRefreshSynchronizesCompatibilityState() = runBlocking {
        val session = createSession()
        val refreshed = rownd._refreshToken()!!
        assertEquals(handle(session.accessToken), handle(refreshed))
        assertTrue(rownd.state.value.auth.accessToken == refreshed)
        assertTrue(rownd.state.value.auth.isAccessTokenValid)
        assertEquals(1, HarnessClient.getCounters().stRefresh)
    }

    @Test
    fun concurrentTokenReadsShareTheRefreshedSession() = runBlocking {
        val session = createSession()
        awaitExpiry(session)
        val tokens = List(5) { async { rownd.getAccessToken(throwIfMissing = true) } }.awaitAll()
        assertEquals(1, tokens.toSet().size)
        assertNotNull(tokens.first())
        assertTrue(rownd.state.value.auth.accessToken == tokens.first())
        assertEquals(1, HarnessClient.getCounters().stRefresh)
    }

    @Test
    fun signOutAfterTokenReadCannotRestoreCompatibilityAuth() = runBlocking {
        createSession()
        val resolved = SuperTokensSessionBridge.resolveAuthState(context, rownd.store, afterTokenRead = {
            rownd.signOut()
        })
        assertNull(resolved)
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
    }

    @Test
    fun refreshResponseAfterLocalSignOutCannotRestoreNativeCredentials() = runBlocking {
        val session = createSession()
        awaitExpiry(session)
        HarnessClient.armSignOutGate("/auth/session/refresh", 0)
        val read = async { rownd.getAccessToken() }
        try {
            withTimeout(10_000) {
                while (!HarnessClient.signOutGateReached()) delay(25)
            }
            // Isolate local sign-out from remote revocation so the in-flight
            // refresh succeeds and attempts to write the old session back.
            SuperTokensSessionBridge.beginSignOut(context) {
                rownd.store.dispatch(StateAction.SetAuth(AuthState()))
                rownd.store.dispatch(StateAction.SetUser(User()))
            }
            HarnessClient.releaseSignOutGate()
            assertNull(withTimeout(10_000) { read.await() })
            assertEquals(AuthState(), rownd.state.value.auth)
            assertEquals(User(), rownd.state.value.user)
            assertNull(SuperTokensSessionBridge.getRefreshToken(context))
            assertNull(SuperTokensSessionBridge.getAccessToken(context))
            assertTrue(HarnessClient.sessionExists(handle(session.accessToken)))
        } finally {
            HarnessClient.releaseSignOutGate()
            read.cancelAndJoin()
        }
    }

    @Test
    fun newSessionAfterTokenReadCannotBeOverwritten() = runBlocking {
        createSession()
        val newer = HarnessClient.createSTSession("refresh-replacement-user")
        val newerProfile = User(data = mapOf("user_id" to newer.userId))
        assertRetryableFailure {
            SuperTokensSessionBridge.resolveAuthState(context, rownd.store, afterTokenRead = {
                SuperTokensSessionBridge.bootstrapSession(context, newer.accessToken, newer.refreshToken, replaceExisting = true)
                rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = newer.accessToken)))
                rownd.store.dispatch(StateAction.SetUser(newerProfile))
            })
        }
        assertTrue(rownd.state.value.auth.accessToken == newer.accessToken)
        assertEquals(newerProfile, rownd.state.value.user)
        assertTrue(SuperTokensSessionBridge.getRefreshToken(context) == newer.refreshToken)
        assertTrue(rownd.getAccessToken() == newer.accessToken)
    }

    @Test
    fun cancellationBeforeCommitDoesNotPublishAuth() = runBlocking {
        val session = createSession()
        awaitExpiry(session)
        val tokenRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = async {
            SuperTokensSessionBridge.resolveAuthState(context, rownd.store, afterTokenRead = {
                tokenRead.complete(Unit)
                release.await()
            })
        }
        tokenRead.await()
        read.cancelAndJoin()
        assertTrue(rownd.state.value.auth.accessToken == session.accessToken)
        assertNotNull(rownd.getAccessToken())
        assertTrue(rownd.state.value.auth.isAccessTokenValid)
    }

    @Test
    fun missingNativeSessionDoesNotDiscardPendingLegacyMigration() = runBlocking {
        val legacy = AuthState(
            accessToken = JwtGenerator().generateTestJwt(appUserId = "pending-legacy-user", expires = Date(0)),
            refreshToken = "pending-legacy-refresh",
        )
        rownd.store.dispatch(StateAction.SetAuth(legacy))
        assertNull(rownd.getAccessToken())
        assertTrue(rownd.state.value.auth == legacy)
    }

    private suspend fun assertRetryableFailure(operation: suspend () -> Any?) {
        try {
            operation()
            fail("Expected a retryable ServerException")
        } catch (_: ServerException) {
        }
    }

    private fun createSession(): HarnessClient.STSessionResponse {
        val session = HarnessClient.createSTSession("refresh-investigation-user", shortLivedAccess = true)
        assertEquals("Core must accept the signed fixture before expiry", 200, request("/test/protected", session.accessToken))
        SuperTokensSessionBridge.bootstrapSession(context, session.accessToken, session.refreshToken)
        rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = session.accessToken)))
        return session
    }

    private fun awaitExpiry(session: HarnessClient.STSessionResponse) {
        val expiry = JWT(session.accessToken).expiresAt!!.time
        Thread.sleep((expiry - System.currentTimeMillis() + 100).coerceAtLeast(0))
        assertEquals("Core must reject the expired fixture", 401, request("/test/protected", session.accessToken))
    }

    private fun handle(token: String) = requireNotNull(JWT(token).getClaim("sessionHandle").asString())

    private fun setRefreshUnavailable(unavailable: Boolean) {
        val request = Request.Builder().url("$origin/test/refresh-availability")
            .post("""{"unavailable":$unavailable}""".toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    private fun request(path: String, token: String, post: Boolean = false): Int {
        val request = Request.Builder().url("$origin$path")
            .header("Authorization", "Bearer $token").header("rid", "session")
            .header("fdi-version", "1.18").header("st-auth-mode", "header")
        if (post) request.post("".toRequestBody())
        return http.newCall(request.build()).execute().use { it.code }
    }
}
