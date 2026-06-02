package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacySessionMigrationInstrumentedTest {

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig
        private lateinit var stClient: OkHttpClient

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
                // Already initialized by another test class in the same instrumentation run.
            }
            SuperTokensSessionBridge.isInitialized.set(true)

            stClient = OkHttpClient.Builder()
                .addInterceptor(SuperTokensInterceptor())
                .build()
        }
    }

    @Before
    fun resetHarnessAndSession() {
        HarnessClient.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }

        Rownd.config.apiUrl = harnessConfig.androidUrl
        Rownd.config.apiBasePath = "/auth"
        Rownd.config.appKey = harnessConfig.appKey
        Rownd.authRepo.legacyTokenApiClient.baseUrl = harnessConfig.androidUrl
        Rownd.stateRepo.getStore().dispatch(StateAction.SetAppConfig(testAppConfig()))
        Rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
    }

    @Test
    fun validLegacyTokenMigratesWithoutLegacyRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacySession = HarnessClient.createLegacySession(userId = "legacy-valid-user", expired = false)
        Rownd.stateRepo.getStore().dispatch(
            StateAction.SetAuth(
                AuthState(
                    accessToken = legacySession.access_token,
                    refreshToken = legacySession.refresh_token,
                )
            )
        )

        runBlocking { Rownd.authRepo.migrateLegacySessionIfNeeded(context) }

        val counters = HarnessClient.getCounters()
        assertEquals("valid legacy token should not call legacy refresh", 0, counters.legacyRefresh)
        assertEquals("valid legacy token should call migrate once", 1, counters.migrate)
        assertTrue("migration should create a SuperTokens session", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
        assertNotNull("Rownd auth state should sync to the SuperTokens access token", Rownd.state.value.auth.accessToken)
        assertNull("Rownd auth state should no longer keep a legacy refresh token", Rownd.state.value.auth.refreshToken)

        assertProtectedEndpointUser("legacy-valid-user")
    }

    @Test
    fun expiredLegacyTokenRefreshesThenMigrates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacySession = HarnessClient.createLegacySession(userId = "legacy-expired-user", expired = true)
        Rownd.stateRepo.getStore().dispatch(
            StateAction.SetAuth(
                AuthState(
                    accessToken = legacySession.access_token,
                    refreshToken = legacySession.refresh_token,
                )
            )
        )

        runBlocking { Rownd.authRepo.migrateLegacySessionIfNeeded(context) }

        val counters = HarnessClient.getCounters()
        assertEquals("expired legacy token should refresh once", 1, counters.legacyRefresh)
        assertEquals("expired legacy token should migrate once", 1, counters.migrate)
        assertTrue("migration should create a SuperTokens session", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })

        assertProtectedEndpointUser("legacy-expired-user")
    }

    @Test
    fun invalidLegacyRefreshClearsAuthAndDoesNotMigrate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacySession = HarnessClient.createLegacySession(userId = "legacy-invalid-refresh-user", expired = true)
        Rownd.stateRepo.getStore().dispatch(
            StateAction.SetAuth(
                AuthState(
                    accessToken = legacySession.access_token,
                    refreshToken = "invalid-refresh-token",
                )
            )
        )

        runBlocking { Rownd.authRepo.migrateLegacySessionIfNeeded(context) }

        val counters = HarnessClient.getCounters()
        assertEquals("expired legacy token should attempt one legacy refresh", 1, counters.legacyRefresh)
        assertEquals("failed legacy refresh must not call migrate", 0, counters.migrate)
        assertFalse("failed legacy refresh must not create a SuperTokens session", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
        assertNull("failed legacy refresh should clear Rownd auth", Rownd.state.value.auth.accessToken)
    }

    @Test
    fun existingSuperTokensSessionSkipsMigrationAndClearsLegacyAuth() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("existing-st-user")
        SuperTokensSessionBridge.bootstrapSession(context, stSession.accessToken, stSession.refreshToken)

        val legacySession = HarnessClient.createLegacySession(userId = "legacy-skip-user", expired = false)
        Rownd.stateRepo.getStore().dispatch(
            StateAction.SetAuth(
                AuthState(
                    accessToken = legacySession.access_token,
                    refreshToken = legacySession.refresh_token,
                )
            )
        )

        runBlocking { Rownd.authRepo.migrateLegacySessionIfNeeded(context) }

        val counters = HarnessClient.getCounters()
        assertEquals("existing SuperTokens session should not call legacy refresh", 0, counters.legacyRefresh)
        assertEquals("existing SuperTokens session should not call migrate", 0, counters.migrate)
        assertTrue("existing SuperTokens session should remain", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
        assertNull("legacy Rownd auth should be cleared when SuperTokens already owns the session", Rownd.state.value.auth.accessToken)
    }

    private fun testAppConfig(): AppConfigState {
        return AppConfigState(
            id = harnessConfig.appId,
            isLoading = false,
            config = AppConfigConfig(
                supertokens = SuperTokensConfig(
                    appInfo = SuperTokensAppInfo(
                        apiDomain = harnessConfig.androidUrl,
                        apiBasePath = "/auth",
                    )
                )
            )
        )
    }

    private fun assertProtectedEndpointUser(userId: String) {
        val response = stClient.newCall(
            Request.Builder()
                .url("${harnessConfig.androidUrl}/test/protected")
                .build()
        ).execute()

        response.use {
            assertEquals("protected endpoint should accept the migrated SuperTokens session", 200, it.code)
            assertTrue("protected endpoint should return migrated user id", it.body?.string()?.contains("\"userId\":\"$userId\"") == true)
        }
    }
}
