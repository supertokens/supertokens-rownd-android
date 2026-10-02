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
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class LegacyRefreshRecoveryInstrumentedTest {
    private lateinit var rownd: RowndClient
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jwt = JwtGenerator()
    private val credentials = mutableListOf<String?>()
    private var status = HttpStatusCode.OK
    private var sessionHeaders = headersOf()
    private lateinit var migratedAccess: String

    @Before
    fun setUp() = runBlocking {
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
        SuperTokens.Builder(context, "https://api.example.com")
            .apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        migratedAccess = jwt.generateTestJwt(sessionHandle = "migrated-session")
        sessionHeaders = headersOf(
            "st-access-token" to listOf(migratedAccess),
            "st-refresh-token" to listOf("native-refresh"),
            "front-token" to listOf(Base64.getEncoder().encodeToString(
                """{"uid":"1234567890","ate":${System.currentTimeMillis() + 120000},"up":{}}""".toByteArray())),
        )
        val config = MockEngineConfig().apply {
            addHandler { request ->
                assertEquals("/auth/plugin/rownd/migrate", request.url.encodedPath)
                credentials += request.headers[HttpHeaders.Authorization]
                respond("{}", status, sessionHeaders)
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder()
            .fakeNetworkModule(FakeNetworkModule(config)).build())
        rownd.config.apiUrl = "https://api.example.com"
        rownd.authRepo.legacyMigrationApiClient = LegacyMigrationApiClient(rownd.rowndContext, MockEngine(config))
    }

    @After
    fun tearDown() {
        rownd.authRepo.legacyMigrationApiClient.client.close()
        rownd.authenticatedApiClient.client.close()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
    }

    @Test
    fun refreshPreferredRegardlessOfAccessExpiryOrPresence() = runBlocking {
        for (access in listOf(jwt.generateTestJwt(),
            jwt.generateTestJwt(expires = Date(System.currentTimeMillis() - 3600000)), null, "", "malformed-access")) {
            SuperTokensSessionBridge.clearLocalSession(context)
            credentials.clear()
            seed(AuthState(accessToken = access, refreshToken = "legacy-refresh"))
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(listOf("Bearer legacy-refresh"), credentials)
            assertEquals(AuthState(accessToken = migratedAccess), rownd.state.value.auth)
            assertEquals("native-refresh", SuperTokensSessionBridge.getRefreshToken(context))
        }
    }

    @Test
    fun missingOrBlankRefreshFallsBackToAccess() = runBlocking {
        val access = jwt.generateTestJwt()
        for (refresh in listOf(null, "", "  ")) {
            SuperTokensSessionBridge.clearLocalSession(context)
            credentials.clear()
            seed(AuthState(accessToken = access, refreshToken = refresh))
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(listOf("Bearer $access"), credentials)
            assertEquals(AuthState(accessToken = migratedAccess), rownd.state.value.auth)
        }
    }

    @Test
    fun rejectionAndIncompleteSuccessExhaustRetriesWithoutAdoption() = runBlocking {
        for (response in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.BadRequest, HttpStatusCode.OK)) {
            status = response
            sessionHeaders = headersOf("st-access-token", migratedAccess)
            credentials.clear()
            seed(AuthState(refreshToken = "rejected-refresh"))
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(listOf("Bearer rejected-refresh", "Bearer rejected-refresh"), credentials)
            assertEquals(AuthState(), rownd.state.value.auth)
            assertEquals(User(), rownd.state.value.user)
            assertNull(SuperTokensSessionBridge.getRefreshToken(context))
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(2, credentials.size)
        }
    }

    @Test
    fun superTokensStateAndEmptyCredentialsNeverMigrate() = runBlocking {
        for (auth in listOf(AuthState(accessToken = migratedAccess, refreshToken = "native-refresh"),
            AuthState(refreshToken = migratedAccess), AuthState(accessToken = " ", refreshToken = " "))) {
            seed(auth)
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals(auth, rownd.state.value.auth)
            assertEquals(0, credentials.size)
        }
    }

    @Test
    fun existingNativeSessionWinsOverRefreshOnlyLegacyState() = runBlocking {
        SuperTokensSessionBridge.bootstrapSession(context, migratedAccess, "native-refresh")
        seed(AuthState(refreshToken = "legacy-refresh"))
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(0, credentials.size)
        assertEquals(AuthState(accessToken = migratedAccess), rownd.state.value.auth)
        assertEquals("native-refresh", SuperTokensSessionBridge.getRefreshToken(context))
    }

    private fun seed(auth: AuthState) {
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(auth))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(User(data = mapOf("user_id" to "legacy-user"), isLoading = true)))
    }
}
