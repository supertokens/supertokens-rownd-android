package io.rownd.android

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.models.domain.AppConfigConfig
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.AppLifecycleListener
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmailChangeGoogleCompletionInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val requested = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private val token = JwtGenerator().generateTestJwt(sessionHandle = "google-SR")
    private lateinit var rownd: RowndClient
    private var body = ""
    private var current = true
    private var completed = false
    private var userType = "existing_user"
    private val profileRequested = CompletableDeferred<Unit>()
    private val releaseProfile = CompletableDeferred<Unit>()

    @Before fun setUp() {
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.Builder(context, "https://api.example").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        val engine = MockEngineConfig().apply {
            addHandler { request ->
                if (request.url.encodedPath.endsWith("/user")) {
                    profileRequested.complete(Unit)
                    releaseProfile.await()
                    return@addHandler respond("""{"data":{"email":"original-google-account@example.com"}}""",
                        HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
                body = request.body.toByteArray().decodeToString()
                requested.complete(Unit)
                release.await()
                // Model the normal published interceptor's response adoption, not a continuation token RPC.
                SuperTokensSessionBridge.bootstrapSession(context, token, "google-refresh", replaceExisting = true)
                respond("""{"status":"OK","rownd":{"user_type":"$userType"}}""", HttpStatusCode.OK,
                    headersOf("Content-Type", "application/json"))
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder().fakeNetworkModule(FakeNetworkModule(engine)).build())
        rownd.appHandleWrapper = AppLifecycleListener(context.applicationContext as Application)
        rownd.stateRepo.getStore().dispatch(StateAction.SetAppConfig(AppConfigState(config = AppConfigConfig(
            supertokens = SuperTokensConfig(SuperTokensAppInfo("https://api.example", "/auth")),
        ))))
    }

    @After fun tearDown() {
        release.complete(Unit)
        releaseProfile.complete(Unit)
        rownd.appHandleWrapper?.unregister()
        rownd.authenticatedApiClient.client.close()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
    }

    @Test fun ordinaryProviderExchangeAdoptsBeforeOwnedCompletionWithoutLinking() = runBlocking {
        val signIn = async(Dispatchers.IO) {
            rownd.signInWithGoogle.completeEmailChangeGoogleSignIn("provider-id-token", { current }) {
                assertEquals(token, rownd.state.value.auth.accessToken)
                completed = true
                true
            }
        }
        withTimeout(5_000) { requested.await() }
        assertFalse(completed)
        val payload = JSONObject(body)
        assertEquals("google", payload.getString("thirdPartyId"))
        assertEquals("sign_in", payload.getString("intent"))
        assertFalse(payload.getBoolean("shouldTryLinkingWithSessionUser"))
        release.complete(Unit)
        withTimeout(5_000) { signIn.await() }
        assertTrue(completed)
    }

    @Test fun ownedProviderCompletionLoadsAuthoritativeNativeProfileWithoutVerification() = runBlocking {
        checkProviderProfile(false)
    }

    @Test fun ownedProviderProfileCannotOverwriteNewerSessionAfterSignOut() = runBlocking {
        checkProviderProfile(true)
    }

    private suspend fun checkProviderProfile(supersede: Boolean) {
        release.complete(Unit)
        rownd.signInWithGoogle.completeEmailChangeGoogleSignIn("provider-id-token", { current }) {
            completed = true
            true
        }
        assertTrue(completed)
        withTimeout(5_000) { profileRequested.await() }
        // The successfully returned/closed presentation is no longer the sign-in owner.
        current = false
        val newerToken = JwtGenerator().generateTestJwt(sessionHandle = "unrelated-provider-session")
        if (supersede) {
            SuperTokensSessionBridge.beginSignOut(context) {
                rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(io.rownd.android.models.domain.AuthState()))
            }
            SuperTokensSessionBridge.bootstrapSession(context, newerToken, "newer-refresh", replaceExisting = true)
            SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.stateRepo.getStore())
        }
        releaseProfile.complete(Unit)
        withTimeout(5_000) {
            while (if (supersede) rownd.state.value.user.isLoading
                else rownd.state.value.user.data["email"] != "original-google-account@example.com") delay(20)
        }
        assertEquals(if (supersede) null else "original-google-account@example.com", rownd.state.value.user.data["email"])
        assertEquals(if (supersede) newerToken else token, rownd.state.value.auth.accessToken)
    }

    @Test fun supersededProviderResponseCannotResumeOrSynchronizeRowndAuth() = runBlocking {
        val signIn = async(Dispatchers.IO) {
            runCatching {
                rownd.signInWithGoogle.completeEmailChangeGoogleSignIn("provider-id-token", { current }) {
                    completed = true
                    true
                }
            }
        }
        withTimeout(5_000) { requested.await() }
        current = false
        release.complete(Unit)
        withTimeout(5_000) { signIn.await() }
        assertFalse(completed)
        assertEquals(null, rownd.state.value.auth.accessToken)
        // Upstream can still write native tokens before our ownership check: known 0.5.4 limitation.
        assertEquals(token, SuperTokensSessionBridge.getAccessToken(context))
    }

    @Test fun ownershipLostDuringProviderTokenReadCannotCommitCompatibilityAuth() = runBlocking {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val gated = AtomicBoolean(false)
        val owned = AtomicBoolean(true)
        val generation = SuperTokensSessionBridge.currentSignOutGeneration()
        val gatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = super.getSharedPreferences(name, mode)
                return object : SharedPreferences by prefs {
                    override fun getString(key: String?, defaultValue: String?): String? {
                        val value = prefs.getString(key, defaultValue)
                        if (key == "st-storage-item-st-access-token" && gated.compareAndSet(false, true)) {
                            entered.countDown()
                            check(proceed.await(5, TimeUnit.SECONDS)) { "Token read was not released" }
                        }
                        return value
                    }
                }
            }
        }
        release.complete(Unit)
        val signIn = async(Dispatchers.IO) {
            runCatching {
                rownd.authRepo.exchangeGoogleIdToken("provider-id-token", RowndSignInIntent.SignIn,
                    context = gatedContext, emailChangeSignIn = true, isCurrent = { owned.get() })
                completed = true
            }
        }
        try {
            assertTrue("Must pause inside token retrieval, after the response ownership check", entered.await(5, TimeUnit.SECONDS))
            assertEquals(null, rownd.state.value.auth.accessToken)
            owned.set(false)
            proceed.countDown()
            withTimeout(5_000) { signIn.await() }
            assertEquals(generation, SuperTokensSessionBridge.currentSignOutGeneration())
            assertEquals("Lost ownership must prevent Rownd SetAuth", null, rownd.state.value.auth.accessToken)
            assertFalse(completed)
            // The published interceptor's native write remains a separate upstream limitation.
            assertEquals(token, SuperTokensSessionBridge.getAccessToken(context))
        } finally { proceed.countDown() }
    }

    @Test fun newProviderAccountDoesNotResumeSavedDraft() = runBlocking {
        userType = "new_user"
        release.complete(Unit)
        rownd.signInWithGoogle.completeEmailChangeGoogleSignIn("provider-id-token", { true }) {
            completed = true
            true
        }
        assertFalse(completed)
    }
}
