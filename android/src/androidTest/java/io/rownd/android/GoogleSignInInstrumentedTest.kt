package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.ktor.client.engine.okhttp.OkHttp
import io.rownd.android.models.RowndConfig
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.AuthRepo
import io.rownd.android.models.repos.SignInRepo
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventEmitter
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SignInWithGoogle
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSignInInstrumentedTest {

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
                // Already initialized by another test class in the same run
            }
            SuperTokensSessionBridge.isInitialized.set(true)
        }

        private fun buildTestContext(stateRepo: StateRepo): Pair<AuthRepo, StateRepo> {
            val rowndContext = RowndContext(RowndConfig(appKey = harnessConfig.appKey))

            val signInRepo = SignInRepo()
            signInRepo.stateRepo = stateRepo

            val engine = OkHttp.create { addInterceptor(SuperTokensInterceptor()) }
            val authenticatedApiClient = AuthenticatedApiClient(engine, rowndContext)

            val authRepo = AuthRepo()
            authRepo.rowndContext = rowndContext
            authRepo.stateRepo = stateRepo
            authRepo.signInRepo = signInRepo
            authRepo.authenticatedApiClient = authenticatedApiClient

            return Pair(authRepo, stateRepo)
        }

        private fun stateRepoWithSuperTokensConfig(): StateRepo {
            val repo = StateRepo()
            repo.getStore().dispatch(
                StateAction.SetAppConfig(
                    AppConfigState(
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
                )
            )
            return repo
        }
    }

    @Before
    fun resetHarnessAndSession() {
        HarnessClient.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }
    }

    @Test
    fun googleExchangeCreatesSuperTokensSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authRepo, _) = buildTestContext(stateRepoWithSuperTokensConfig())

        runBlocking { authRepo.exchangeGoogleIdToken("test-id-token-${System.currentTimeMillis()}", intent = null, context = context) }

        val exists = runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        assertTrue("SuperTokens session must exist after Google exchange", exists)

        val token = runBlocking { SuperTokensSessionBridge.getAccessToken(context) }
        assertNotNull("getAccessToken must return a non-null token after Google exchange", token)
    }

    @Test
    fun googleExchangeSyncsAuthStateToStore() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authRepo, stateRepo) = buildTestContext(stateRepoWithSuperTokensConfig())

        runBlocking { authRepo.exchangeGoogleIdToken("test-id-token-${System.currentTimeMillis()}", intent = null, context = context) }

        val accessToken = stateRepo.state.value.auth.accessToken
        assertNotNull("AuthState.accessToken must be updated after Google exchange", accessToken)
    }

    @Test
    fun googleExchangeSetsLastSignInMethod() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authRepo, stateRepo) = buildTestContext(stateRepoWithSuperTokensConfig())

        runBlocking { authRepo.exchangeGoogleIdToken("test-id-token-${System.currentTimeMillis()}", intent = null, context = context) }

        assertEquals("google", stateRepo.state.value.signIn.lastSignIn)
    }

    @Test
    fun googleExchangeDoesNotIncrementMigrateCounter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (authRepo, _) = buildTestContext(stateRepoWithSuperTokensConfig())

        runBlocking { authRepo.exchangeGoogleIdToken("test-id-token-${System.currentTimeMillis()}", intent = null, context = context) }

        val counters = HarnessClient.getCounters()
        assertEquals("migrate counter must be 0 after Google sign-in", 0, counters.migrate)
    }

    @Test
    fun googleFailureEmitsSignInFailedEventWithMethodAndError() {
        val rowndContext = RowndContext(RowndConfig())
        val emitter = RowndEventEmitter<RowndEvent>()
        var captured: RowndEvent? = null
        emitter.addListener { captured = it }
        rowndContext.eventEmitter = emitter

        val signInWithGoogle = SignInWithGoogle(rowndContext)
        signInWithGoogle.emitSignInFailed("exchange failed")

        assertEquals(RowndEventType.SignInFailed, captured?.event)
        assertEquals(RowndSignInType.Google.value, captured?.data?.get("method"))
        assertEquals("exchange failed", captured?.data?.get("error"))
    }
}
