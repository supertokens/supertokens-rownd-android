package io.rownd.android.models.repos

import android.content.Context
import android.util.Log
import com.auth0.android.jwt.JWT
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInIntent
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.network.SignInUpResponse
import io.rownd.android.models.network.SignOutRequestBody
import io.rownd.android.models.network.SignOutResponse
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.LegacyTokenApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndException
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepo @Inject constructor() {
    @Inject
    lateinit var rowndContext: RowndContext

    @Inject
    lateinit var stateRepo: StateRepo

    @Inject
    lateinit var userRepo: UserRepo

    @Inject
    lateinit var signInRepo: SignInRepo

    @Inject
    lateinit var authenticatedApiClient: AuthenticatedApiClient

    @Inject
    lateinit var legacyTokenApiClient: LegacyTokenApiClient

    internal suspend fun getLatestAuthState(): AuthState? {
        val context = rowndContext.client?.appHandleWrapper?.app?.get()?.applicationContext
            ?: return null
        val accessToken = SuperTokensSessionBridge.getAccessToken(context) ?: return null
        return stateRepo.state.value.auth.copy(accessToken = accessToken)
    }

    internal suspend fun getAccessToken(): String? = getLatestAuthState()?.accessToken

    internal suspend fun exchangeGoogleIdToken(
        idToken: String,
        intent: RowndSignInIntent?,
        context: Context = rowndContext.client?.appHandleWrapper?.app?.get()?.applicationContext
            ?: throw RowndException("No application context available"),
    ): SignInUpResponse {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain
        val apiBasePath = st.appInfo.apiBasePath ?: "/auth"

        val response = authenticatedApiClient.client.post("$apiDomain$apiBasePath/signinup") {
            setBody(GoogleSignInUpBody(
                thirdPartyId = "google",
                oAuthTokens = mapOf("id_token" to idToken),
            ))
        }.body<SignInUpResponse>()

        SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, stateRepo.getStore())
        signInRepo.setLastSignInMethod("google")

        return response
    }

    fun signOutUser() {
        val appId = stateRepo.getStore().currentState.appConfig.id
        val signOutRequest = SignOutRequestBody(
            signOutAll = true
        )
        signOutUserAsync(appId, signOutRequest)
    }

    @Synchronized
    @Throws(RowndException::class)
    internal fun signOutUserAsync(appId: String, signOutRequest: SignOutRequestBody): Deferred<SignOutResponse?>{
        return CoroutineScope(Dispatchers.IO).async {
            try {
                signOutUser(appId, signOutRequest)
                Rownd.signOut()
                return@async null
            } catch(ex: Exception) {
                Log.e("Rownd.Auth", "Failed to sign out user from all sessions:", ex)
                throw RowndException("Failed to sign out user from all sessions: ${ex.message}")
            }
        }
    }

    internal fun isJwtExpiredWithMargin(jwt: JWT): Boolean {
        if (jwt.expiresAt == null) {
            return false
        }

        val currentTime = rowndContext.kronosClock?.getCurrentTimeMs() ?: System.currentTimeMillis()
        val currentDateWithMargin = Date(currentTime + (60 * 1000)) // Add 60 sec margin to current Date

        return currentDateWithMargin.after(jwt.expiresAt)
    }

    suspend fun signOutUser(appId: String, requestBody: SignOutRequestBody) : SignOutResponse {
        return authenticatedApiClient.client.post("me/applications/$appId/signout") {
            setBody(requestBody)
        }.body()
    }

    @Serializable
    private data class GoogleSignInUpBody(
        val thirdPartyId: String,
        val oAuthTokens: Map<String, String>,
    )
}
