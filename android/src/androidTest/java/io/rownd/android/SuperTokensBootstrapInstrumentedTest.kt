package io.rownd.android

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class SuperTokensBootstrapInstrumentedTest {

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig
        private val jwtGenerator = JwtGenerator()

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            harnessConfig = HarnessClient.getConfig()
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            SuperTokens.Builder(context, harnessConfig.androidUrl)
                .apiBasePath("/auth")
                .tokenTransferMethod("header")
                .build()
            SuperTokensSessionBridge.isInitialized.set(true)
        }

        private fun sharedPrefs(context: Context) =
            context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE)
    }

    @Before
    fun clearSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.store = Rownd.stateRepo.getStore()
    }

    @Test
    fun bootstrapSessionMakesSessionExistReturnTrue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()

        SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken)

        val exists = runBlocking { SuperTokensSessionBridge.doesSessionExist(context) }
        assertTrue("doesSessionExist must return true after bootstrapSession", exists)
    }

    @Test
    fun getAccessTokenReturnsBootstrappedToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()

        SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken)

        val retrieved = runBlocking { SuperTokensSessionBridge.getAccessToken(context) }
        assertEquals("getAccessToken must return the bootstrapped access token", accessToken, retrieved)
    }

    @Test
    fun secondBootstrapSessionIsNoOp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstToken = jwtGenerator.generateTestJwt()
        val secondToken = jwtGenerator.generateTestJwt()

        SuperTokensSessionBridge.bootstrapSession(context, firstToken, jwtGenerator.generateTestJwt())
        SuperTokensSessionBridge.bootstrapSession(context, secondToken, jwtGenerator.generateTestJwt())

        val retrieved = runBlocking { SuperTokensSessionBridge.getAccessToken(context) }
        assertEquals("Second bootstrapSession must not overwrite an existing session", firstToken, retrieved)
    }

    @Test
    fun bootstrapSessionOnMainThreadThrows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        var caught: IllegalStateException? = null

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                SuperTokensSessionBridge.bootstrapSession(context, accessToken, "refresh")
            } catch (e: IllegalStateException) {
                caught = e
            }
        }

        assertNotNull("bootstrapSession must throw IllegalStateException on the main thread", caught)
    }

    @Test
    fun bootstrapSessionWritesAllFourSharedPreferenceKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()
        val frontToken = SuperTokensSessionBridge.buildFrontToken(accessToken)
        val antiCSRF = "anti-csrf-token"

        val beforeTimestamp = System.currentTimeMillis()
        SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken, frontToken, antiCSRF)
        val afterTimestamp = System.currentTimeMillis()

        val prefs = sharedPrefs(context)

        assertEquals(
            "st-storage-item-st-access-token must hold the access token",
            accessToken,
            prefs.getString("st-storage-item-st-access-token", null),
        )
        assertEquals(
            "st-storage-item-st-refresh-token must hold the refresh token",
            refreshToken,
            prefs.getString("st-storage-item-st-refresh-token", null),
        )
        assertNotNull(
            "supertokens-android-fronttoken-key must be written",
            prefs.getString("supertokens-android-fronttoken-key", null),
        )
        assertEquals(
            "supertokens-android-anticsrf-key must hold the anti-CSRF token",
            antiCSRF,
            prefs.getString("supertokens-android-anticsrf-key", null),
        )
        assertEquals(refreshToken, SuperTokensSessionBridge.getRefreshToken(context))
        assertEquals(frontToken, SuperTokensSessionBridge.getFrontToken(context))
        assertEquals(antiCSRF, SuperTokensSessionBridge.getAntiCSRF(context))
        val lastUpdate = prefs.getString("st-storage-item-st-last-access-token-update", null)?.toLongOrNull()
        assertNotNull("st-storage-item-st-last-access-token-update must be written", lastUpdate)
        assertTrue(
            "st-storage-item-st-last-access-token-update must be within the call window",
            lastUpdate!! in beforeTimestamp..afterTimestamp,
        )
    }

    @Test
    fun clearLocalSessionRemovesStoredTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SuperTokensSessionBridge.bootstrapSession(
            context,
            jwtGenerator.generateTestJwt(),
            jwtGenerator.generateTestJwt(),
            "front-token",
            "anti-csrf-token",
        )

        SuperTokensSessionBridge.clearLocalSession(context)

        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
        assertNull(SuperTokensSessionBridge.getFrontToken(context))
        assertNull(SuperTokensSessionBridge.getAntiCSRF(context))
    }

    @Test
    fun rphInitFallsBackToStoredSuperTokensSessionTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()
        val frontToken = SuperTokensSessionBridge.buildFrontToken(accessToken)
        val antiCSRF = "anti-csrf-token"
        val appId = "app-test"
        val appUserId = "user-test"

        SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken, frontToken, antiCSRF)
        Rownd.store.dispatch(StateAction.SetAppConfig(AppConfigState(id = appId, isLoading = false)))
        Rownd.store.dispatch(StateAction.SetUser(User(data = mapOf("user_id" to appUserId))))

        val rphInit = AuthState(accessToken = accessToken, refreshToken = null)
            .toRphInitHash(Rownd.userRepo, context)
        assertNotNull("rph_init must be generated from SuperTokens stored refresh token", rphInit)

        val decoded = JSONObject(String(Base64.getDecoder().decode(rphInit)))
        assertEquals(accessToken, decoded.getString("access_token"))
        assertEquals(refreshToken, decoded.getString("refresh_token"))
        assertEquals(frontToken, decoded.getString("front_token"))
        assertEquals(antiCSRF, decoded.getString("anti_csrf"))
        assertEquals(appId, decoded.getString("app_id"))
        assertEquals(appUserId, decoded.getString("app_user_id"))
    }

    @Test
    fun bootstrapSessionDoesNotOverwriteExistingSharedPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val firstAccess = jwtGenerator.generateTestJwt()
        val firstRefresh = jwtGenerator.generateTestJwt()

        SuperTokensSessionBridge.bootstrapSession(context, firstAccess, firstRefresh)

        val prefs = sharedPrefs(context)
        val tokenAfterFirst = prefs.getString("st-storage-item-st-access-token", null)

        SuperTokensSessionBridge.bootstrapSession(context, jwtGenerator.generateTestJwt(), jwtGenerator.generateTestJwt())

        assertEquals(
            "SharedPreferences must not change when a session already exists",
            tokenAfterFirst,
            prefs.getString("st-storage-item-st-access-token", null),
        )
    }
}
