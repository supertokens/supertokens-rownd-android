package io.rownd.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UserPluginInstrumentedTest {

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig

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
    fun loadUserUsesPluginRouteWithOnlySuperTokensAuth() {
        bootstrapHarnessSession("plugin-load-user")

        val user = Rownd.userRepo.loadUserAsync().let { runBlocking { it.await() } }
        val request = HarnessClient.getLastRequest("/auth/plugin/rownd/user")

        assertNotNull("loadUserAsync should return the plugin user", user)
        assertEquals("GET", request.method)
        assertEquals("SuperTokens should inject one Authorization header", 1, request.authorizationHeaderCount)
        assertFalse("plugin user route must not receive x-rownd-app-key", request.hasAppKey)
    }

    @Test
    fun saveUserUsesPluginRouteAndReflectsUpdatedField() {
        bootstrapHarnessSession("plugin-save-user")

        val saved = Rownd.userRepo.set("nickname", "Ada").let { runBlocking { it.await() } }
        val request = HarnessClient.getLastRequest("/auth/plugin/rownd/user")

        assertEquals("Ada", saved?.data?.get("nickname"))
        assertEquals("PUT", request.method)
        assertEquals("SuperTokens should inject one Authorization header", 1, request.authorizationHeaderCount)
        assertFalse("plugin user route must not receive x-rownd-app-key", request.hasAppKey)
    }

    @Test
    fun signedOutUserRequestDoesNotSendAuthorizationOrAppKey() {
        Rownd.signOut()

        val user = Rownd.userRepo.loadUserAsync().let { runBlocking { it.await() } }
        val request = HarnessClient.getLastRequest("/auth/plugin/rownd/user")

        assertNull("signed-out user load should not return a user", user)
        assertEquals("GET", request.method)
        assertEquals("signed-out request should not include Authorization", 0, request.authorizationHeaderCount)
        assertFalse("plugin user route must not receive x-rownd-app-key", request.hasAppKey)
    }

    private fun bootstrapHarnessSession(userId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession(userId)
        SuperTokensSessionBridge.bootstrapSession(context, stSession.accessToken, stSession.refreshToken)
        runBlocking { SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, Rownd.store) }
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
}
