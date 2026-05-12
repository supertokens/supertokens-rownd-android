package io.rownd.android.models.repos

import android.util.Log
import com.auth0.android.jwt.JWT
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInIntent
import io.rownd.android.RowndSignInJsOptions
import io.rownd.android.RowndSignInLoginStep
import io.rownd.android.RowndSignInUserType
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.network.Auth
import io.rownd.android.models.network.SignOutRequestBody
import io.rownd.android.models.network.SignOutResponse
import io.rownd.android.models.network.TokenRequestBody
import io.rownd.android.models.network.TokenResponse
import io.rownd.android.util.AuthLevel
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.InvalidRefreshTokenException
import io.rownd.android.util.LegacyTokenApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndException
import io.rownd.android.util.ServerException
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

    internal suspend fun getAccessToken(idToken: String): TokenResponse? {
        return getAccessToken(idToken, intent = null, type = AccessTokenType.default)
    }

    internal suspend fun getAccessToken(idToken: String, intent: RowndSignInIntent?, type: AccessTokenType): TokenResponse? {
        val appId = stateRepo.getStore().currentState.appConfig.id
        val tokenRequest = TokenRequestBody(
            appId = appId,
            idToken = idToken,
            intent = intent
        )

        if (stateRepo.state.value.user.authLevel == AuthLevel.Instant) {
            tokenRequest.instantUserId = stateRepo.state.value.user.data["user_id"]?.toString()
        }

        return fetchTokenAsync(tokenRequest, intent, type).await()
    }

    fun signOutUser() {
        val appId = stateRepo.getStore().currentState.appConfig.id
        val signOutRequest = SignOutRequestBody(
            signOutAll = true
        )
        signOutUserAsync(appId, signOutRequest)
    }

    @Serializable
    internal enum class AccessTokenType {
        @SerialName("default")
        default,
        @SerialName("google")
        google,
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

    @Synchronized
    internal fun fetchTokenAsync(tokenRequest: TokenRequestBody, intent: RowndSignInIntent?, type: AccessTokenType): Deferred<TokenResponse?> {
        return CoroutineScope(Dispatchers.IO).async {
            try {
                val tokenResponse = exchangeToken(tokenRequest)

                if (type != AccessTokenType.default) {
                    if (tokenResponse.userType === RowndSignInUserType.NewUser && intent === RowndSignInIntent.SignIn) {
                        Rownd.requestSignIn(
                            RowndSignInJsOptions(
                                intent = intent,
                                loginStep = RowndSignInLoginStep.NoAccount,
                                token = tokenRequest.idToken
                            )
                        )
                        return@async null
                    }
                    Rownd.requestSignIn(
                        RowndSignInJsOptions(
                            intent = intent,
                            loginStep = RowndSignInLoginStep.Success,
                            userType = tokenResponse.userType,
                            appVariantUserType = tokenResponse.appVariantUserType
                        )
                    )
                }

                if (type === AccessTokenType.google) {
                    signInRepo.setLastSignInMethod("google")
                }

                stateRepo.getStore().dispatch(
                    StateAction.SetAuth(
                        AuthState(
                            accessToken = tokenResponse.accessToken,
                            refreshToken = tokenResponse.refreshToken
                        )
                    )
                )
                userRepo?.loadUserAsync()
                return@async tokenResponse
            } catch (ex: ClientRequestException) {
                Log.e("RowndAuthApi", "Fetching token failed: ${ex.message}")
                if (ex.response.status == HttpStatusCode.BadRequest) {
                    // The token refresh failed, so we need to sign-out
                    Rownd.signOut()
                    throw InvalidRefreshTokenException(ex.message)
                }

                throw ServerException(ex.message)
                return@async null
            } catch (ex: ServerResponseException) {
                throw ServerException(ex.message)
                return@async null
            } catch (ex: Exception) {
                return@async null
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

    suspend fun exchangeToken(requestBody: TokenRequestBody) : TokenResponse {
        return legacyTokenApiClient.client.post("hub/auth/token") {
            setBody(requestBody)
        }.body()
    }

    suspend fun signOutUser(appId: String, requestBody: SignOutRequestBody) : SignOutResponse {
        return authenticatedApiClient.client.post("me/applications/$appId/signout") {
            setBody(requestBody)
        }.body()
    }
}