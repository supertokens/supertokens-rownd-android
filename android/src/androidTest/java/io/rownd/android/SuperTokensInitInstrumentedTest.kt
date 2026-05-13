package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import kotlinx.coroutines.runBlocking
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuperTokensInitInstrumentedTest {

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            harnessConfig = HarnessClient.getConfig()
        }

        private fun validStateRepo(apiDomain: String): StateRepo {
            val repo = StateRepo()
            repo.getStore().dispatch(
                StateAction.SetAppConfig(
                    AppConfigState(
                        id = "app_test",
                        isLoading = false,
                        config = AppConfigConfig(
                            supertokens = SuperTokensConfig(
                                appInfo = SuperTokensAppInfo(
                                    apiDomain = apiDomain,
                                    apiBasePath = "/auth",
                                )
                            )
                        )
                    )
                )
            )
            return repo
        }

        private fun awaitInitialized(timeoutMs: Long = 5_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!SuperTokensSessionBridge.isInitialized.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        }
    }

    @Before
    fun resetBridge() {
        SuperTokensSessionBridge.isInitialized.set(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }
    }

    @Test
    fun validConfigInitializesSuperTokensWithNoExistingSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stateRepo = validStateRepo(harnessConfig.androidUrl)

        SuperTokensSessionBridge.observeAndInitialize(context, stateRepo)
        awaitInitialized()

        assertTrue("isInitialized should be true after valid config", SuperTokensSessionBridge.isInitialized.get())

        val exists = runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        assertFalse("doesSessionExist must return false on a fresh initialization", exists)
    }

    @Test
    fun emptyApiDomainKeepsIsInitializedFalse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val repo = StateRepo()
        repo.getStore().dispatch(
            StateAction.SetAppConfig(
                AppConfigState(
                    id = "app_test",
                    isLoading = false,
                    config = AppConfigConfig(
                        supertokens = SuperTokensConfig(
                            appInfo = SuperTokensAppInfo(apiDomain = "")
                        )
                    )
                )
            )
        )

        SuperTokensSessionBridge.observeAndInitialize(context, repo)

        Thread.sleep(500)

        assertFalse("isInitialized must remain false when apiDomain is empty", SuperTokensSessionBridge.isInitialized.get())
    }

    @Test
    fun alreadyInitializedGuardPreventsReinit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        SuperTokens.Builder(context, harnessConfig.androidUrl)
            .apiBasePath("/auth")
            .tokenTransferMethod("header")
            .build()
        SuperTokensSessionBridge.isInitialized.set(true)

        val stateRepo = validStateRepo(harnessConfig.androidUrl)

        SuperTokensSessionBridge.observeAndInitialize(context, stateRepo)

        Thread.sleep(500)

        assertTrue("isInitialized must remain true after a redundant call", SuperTokensSessionBridge.isInitialized.get())
    }
}
