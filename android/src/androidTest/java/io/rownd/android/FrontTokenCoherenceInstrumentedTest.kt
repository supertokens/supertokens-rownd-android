package io.rownd.android

import android.app.Application
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.FrontToken
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class FrontTokenCoherenceInstrumentedTest {
    @Test
    fun harnessIssuedTokensRemainCoherentThroughHubAdoptionAndProtectedRequest() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val config = HarnessClient.getConfig()
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.Builder(context, config.androidUrl).apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.config.apiUrl = config.androidUrl
        Rownd.store = Rownd.stateRepo.getStore()
        Rownd.store.dispatch(StateAction.SetAuth(AuthState()))
        val response = OkHttpClient().newCall(Request.Builder().url("${config.androidUrl}/test/st-session")
            .header("st-auth-mode", "header")
            .post("""{"userId":"coherent-front-token-user"}""".toRequestBody("application/json".toMediaType())).build()).execute()
        val tokens = response.use {
            assertEquals(200, it.code)
            JSONObject(requireNotNull(it.body).string()).put("front_token", requireNotNull(it.header("front-token")))
        }
        val access = tokens.getString("access_token")
        val expected = claims(access)
        val suppliedFront = tokens.getString("front_token")
        val generation = SuperTokensSessionBridge.currentSignOutGeneration()
        lateinit var web: RowndWebView
        instrumentation.runOnMainSync {
            web = RowndWebView(context, null).apply { rowndClient = Rownd; targetPage = HubPageSelector.SignIn }
        }
        fun snapshot(stage: String) {
            val front = SuperTokensSessionBridge.getFrontToken(context)
            val nativeAccess = context.getSharedPreferences("supertokens-android-shared-preferences", 0)
                .getString("st-storage-item-st-access-token", null)
            println("FRONT_TOKEN_STAGE stage=$stage generation=${SuperTokensSessionBridge.currentSignOutGeneration()} " +
                "nativeAccessSha256=${nativeAccess?.let(::hash)} frontSha256=${front?.let(::hash)} " +
                "suppliedFrontMatches=${front == suppliedFront} rowndAccessMatches=${Rownd.state.value.auth.accessToken == access}")
        }
        val originalWrite = SuperTokensSessionBridge.writeSession
        val bridge = web.rowndJavascriptInterface
        val originalSync = bridge.syncAuthenticationState
        SuperTokensSessionBridge.writeSession = { write -> originalWrite(write); snapshot("after-bootstrap-write") }
        bridge.syncAuthenticationState = { current -> originalSync(current).also { snapshot("after-token-retrieval-sync") } }
        try {
            bridge.postSecureMessage(JSONObject().put("type", "authentication").put("payload", tokens).toString())
            val deadline = System.currentTimeMillis() + 5_000
            while (Rownd.state.value.auth.accessToken != access && System.currentTimeMillis() < deadline) Thread.sleep(25)
            assertTrue("Rownd must adopt the same harness-issued token", access == Rownd.state.value.auth.accessToken)
            assertTrue("Native access token must match", access == SuperTokensSessionBridge.getAccessToken(context))
            assertTrue("Native front token must match", suppliedFront == SuperTokensSessionBridge.getFrontToken(context))
            assertEquals(expected.getString("sub"), FrontToken.getToken(context).getString("uid"))
            val client = OkHttpClient.Builder().addInterceptor(SuperTokensInterceptor()).build()
            val protected = client.newCall(Request.Builder().url("${config.androidUrl}/test/protected").build()).execute().use {
                println("FRONT_TOKEN_PROTECTED status=${it.code} accessHeader=${it.header("st-access-token") != null} frontHeader=${it.header("front-token") != null}")
                assertEquals(200, it.code)
                JSONObject(requireNotNull(it.body).string())
            }
            val current = claims(requireNotNull(SuperTokensSessionBridge.getAccessToken(context)))
            assertEquals(expected.getString("sub"), protected.getString("userId"))
            assertEquals(expected.getString("sessionHandle"), protected.getJSONObject("accessTokenPayload").getString("sessionHandle"))
            assertEquals(expected.getString("sub"), current.getString("sub"))
            assertEquals(expected.getString("sessionHandle"), current.getString("sessionHandle"))
            assertEquals(current.getString("sub"), FrontToken.getToken(context).getString("uid"))
            assertEquals(generation, SuperTokensSessionBridge.currentSignOutGeneration())
            assertTrue(Rownd.state.value.auth.isAuthenticated)
            println("FRONT_TOKEN_COHERENCE protectedStatus=200 sameOwner=true sameSession=true frontOwnerMatches=true generationUnchanged=true ownerSha256=${hash(current.getString("sub"))} sessionSha256=${hash(current.getString("sessionHandle"))}")
        } finally {
            snapshot("assertion-finished")
            SuperTokensSessionBridge.writeSession = originalWrite
            bridge.syncAuthenticationState = originalSync
            instrumentation.runOnMainSync { web.destroy() }
            SuperTokensSessionBridge.clearLocalSession(context)
            SuperTokens.resetForTests()
            SuperTokensSessionBridge.isInitialized.set(false)
        }
    }

    private fun claims(token: String) = JSONObject(String(Base64.decode(token.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
