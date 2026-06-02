package io.rownd.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignOutInstrumentedTest {

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

        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.store = Rownd.stateRepo.getStore()
        Rownd.config.apiUrl = harnessConfig.androidUrl
        Rownd.config.apiBasePath = "/auth"
        Rownd.config.appKey = harnessConfig.appKey
        Rownd.store.dispatch(StateAction.SetAppConfig(testAppConfig()))
        Rownd.store.dispatch(StateAction.SetAuth(AuthState()))
        Rownd.store.dispatch(StateAction.SetUser(User()))
    }

    @Test
    fun localSignOutClearsSuperTokensSessionAndCompatibilityState() {
        bootstrapHarnessSession("local-signout-user")

        assertProtectedStatus(200)

        Rownd.signOut()

        waitUntil {
            runBlocking { !SuperTokensSessionBridge.doesSessionExist(InstrumentationRegistry.getInstrumentation().targetContext) }
        }

        assertNull("signOut should clear AuthState.accessToken", Rownd.state.value.auth.accessToken)
        assertFalse("signOut should clear SuperTokens local session", runBlocking { SuperTokensSessionBridge.doesSessionExist(InstrumentationRegistry.getInstrumentation().targetContext) })
        assertProtectedStatus(401)
    }

    @Test
    fun allSessionsSignOutRevokesServerSessionThenClearsLocalState() {
        bootstrapHarnessSession("all-signout-user")

        assertProtectedStatus(200)

        Rownd.signOut(RowndSignOutScope.All)

        waitUntil {
            runBlocking { !SuperTokensSessionBridge.doesSessionExist(InstrumentationRegistry.getInstrumentation().targetContext) }
        }

        assertNull("all-session signOut should clear AuthState.accessToken", Rownd.state.value.auth.accessToken)
        assertProtectedStatus(401)
    }

    private fun bootstrapHarnessSession(userId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession(userId)
        SuperTokensSessionBridge.bootstrapSession(context, stSession.accessToken, stSession.refreshToken)
        runBlocking { SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, Rownd.store) }
        assertTrue("test setup should create a SuperTokens session", runBlocking { SuperTokensSessionBridge.doesSessionExist(context) })
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

    private fun assertProtectedStatus(status: Int) {
        val response = stClient.newCall(
            Request.Builder()
                .url("${harnessConfig.androidUrl}/test/protected")
                .build()
        ).execute()

        response.use {
            assertEquals(status, it.code)
        }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }

        assertTrue("condition was not met within ${timeoutMs}ms", predicate())
    }
}
