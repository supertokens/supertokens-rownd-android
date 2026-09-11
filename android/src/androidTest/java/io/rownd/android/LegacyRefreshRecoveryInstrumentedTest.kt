package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.LegacyMigrationApiClient
import io.rownd.android.util.LegacyTokenApiClient
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.Date

@RunWith(AndroidJUnit4::class)
class LegacyRefreshRecoveryInstrumentedTest {
    private lateinit var rownd: RowndClient
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jwtGenerator = JwtGenerator()
    private var migrationCalls = 0

    @Before
    fun setUp() = runBlocking {
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
        SuperTokens.Builder(context, "https://api.example.com")
            .apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val config = MockEngineConfig().apply {
            addHandler {
                migrationCalls += 1
                assertEquals("rotated-refresh", rownd.state.value.auth.refreshToken)
                respond("{}", HttpStatusCode.BadRequest)
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder()
            .fakeNetworkModule(FakeNetworkModule(config)).build())
        rownd.config.apiUrl = "https://api.example.com"
        rownd.authRepo.legacyMigrationApiClient = LegacyMigrationApiClient(rownd.rowndContext, MockEngine(config))
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            accessToken = jwtGenerator.generateTestJwt(expires = Date(System.currentTimeMillis() - 3600000)),
            refreshToken = "old-refresh",
        )))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(User(data = mapOf("user_id" to "legacy-user"), isLoading = true)))
    }

    @After
    fun tearDown() {
        rownd.authRepo.legacyMigrationApiClient.client.close()
        rownd.authRepo.legacyTokenApiClient.client.close()
        rownd.authenticatedApiClient.client.close()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
    }

    @Test
    fun transientRefreshFailuresPreserveCredentials() = runBlocking {
        val original = rownd.state.value.auth
        val originalUser = rownd.state.value.user
        for (status in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
            useRefreshEngine(MockEngine { respond("{}", status) })
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(original, rownd.state.value.auth)
            assertEquals(originalUser, rownd.state.value.user)
        }
        useRefreshEngine(MockEngine { throw IOException("offline") })
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(original, rownd.state.value.auth)
        assertEquals(originalUser, rownd.state.value.user)
        assertEquals(0, migrationCalls)
    }

    @Test
    fun incompleteRefreshResponsePreservesCredentials() = runBlocking {
        val original = rownd.state.value.auth
        useRefreshEngine(MockEngine {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(original, rownd.state.value.auth)
        assertEquals(0, migrationCalls)
    }

    @Test
    fun unauthorizedRefreshClearsCredentials() = runBlocking {
        useRefreshEngine(MockEngine { respond("{}", HttpStatusCode.Unauthorized) })
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertNull(rownd.state.value.auth.accessToken)
        assertNull(rownd.state.value.auth.refreshToken)
        assertEquals(0, migrationCalls)
    }

    @Test
    fun invalidRefreshClearsCredentials() = runBlocking {
        useRefreshEngine(MockEngine { respond("""{"message":"Invalid refresh token"}""", HttpStatusCode.BadRequest) })
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertNull(rownd.state.value.auth.accessToken)
        assertNull(rownd.state.value.auth.refreshToken)
        assertEquals(0, migrationCalls)
    }

    @Test
    fun rotatedCredentialsPersistBeforeMigrationThenClearAfterMigrationFailure() = runBlocking {
        val refreshedAccessToken = jwtGenerator.generateTestJwt(expires = Date(System.currentTimeMillis() + 3600000))
        var refreshCalls = 0
        useRefreshEngine(MockEngine {
            refreshCalls += 1
            respond("""{"access_token":"$refreshedAccessToken","refresh_token":"rotated-refresh"}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertTrue("Failed migration must abandon the rotated credentials", rownd.state.value.auth == AuthState())
        assertEquals(User(), rownd.state.value.user)
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(1, refreshCalls)
        assertEquals(2, migrationCalls)
    }

    private fun useRefreshEngine(engine: MockEngine) {
        rownd.authRepo.legacyTokenApiClient = LegacyTokenApiClient(rownd.authRepo.rowndContext, engine)
    }
}
