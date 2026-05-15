package io.rownd.android.models.repos

import android.content.Context
import android.util.Log
import com.auth0.android.jwt.JWT
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val legacyMigrationMutex = Mutex()
    private var legacyMigrationJob: Deferred<Unit>? = null

    internal suspend fun getLatestAuthState(): AuthState? {
        val context = rowndContext.client?.appHandleWrapper?.app?.get()?.applicationContext
            ?: return null
        val accessToken = SuperTokensSessionBridge.getAccessToken(context) ?: return null
        return stateRepo.state.value.auth.copy(accessToken = accessToken)
    }

    internal suspend fun getAccessToken(): String? = getLatestAuthState()?.accessToken

    internal suspend fun migrateLegacySessionIfNeeded(context: Context) {
        val job = legacyMigrationMutex.withLock {
            legacyMigrationJob?.takeIf { it.isActive }
                ?: CoroutineScope(Dispatchers.IO).async { runLegacyMigration(context) }
                    .also { legacyMigrationJob = it }
        }

        job.await()
    }

    private suspend fun runLegacyMigration(context: Context) {
        if (SuperTokensSessionBridge.doesSessionExist(context)) {
            val auth = stateRepo.state.value.auth
            if (auth.accessToken != null && !isLikelySuperTokensToken(auth.accessToken)) {
                stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
            }
            return
        }

        val currentAuth = stateRepo.state.value.auth
        var legacyAccessToken = currentAuth.accessToken ?: return
        if (isLikelySuperTokensToken(legacyAccessToken)) return

        if (isJwtExpiredWithMargin(JWT(legacyAccessToken))) {
            val refreshToken = currentAuth.refreshToken
            if (refreshToken.isNullOrEmpty()) {
                stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
                return
            }

            try {
                val refreshed = legacyTokenApiClient.refreshLegacyToken(refreshToken)
                legacyAccessToken = refreshed.accessToken
                    ?: run {
                        stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
                        return
                    }
            } catch (ex: Exception) {
                Log.e("Rownd.Auth", "Failed to refresh legacy Rownd token during migration", ex)
                stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
                return
            }
        }

        migrateLegacyAccessToken(context, legacyAccessToken, retry = true)
    }

    private suspend fun migrateLegacyAccessToken(context: Context, legacyAccessToken: String, retry: Boolean) {
        try {
            val response = postLegacyMigration(legacyAccessToken)
            when (response.status) {
                HttpStatusCode.OK -> {
                    val accessToken = response.headers["st-access-token"]
                    val refreshToken = response.headers["st-refresh-token"]
                    val frontToken = response.headers["front-token"]
                    if (accessToken.isNullOrEmpty() || refreshToken.isNullOrEmpty() || frontToken.isNullOrEmpty()) {
                        throw RowndException("Migration response missing SuperTokens session headers")
                    }

                    SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken, frontToken)
                    SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, stateRepo.getStore())
                }
                HttpStatusCode.Conflict -> {
                    SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, stateRepo.getStore())
                }
                HttpStatusCode.Unauthorized -> {
                    stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
                }
                else -> throw RowndException("Legacy session migration failed with HTTP ${response.status.value}")
            }
        } catch (ex: ClientRequestException) {
            if (ex.response.status == HttpStatusCode.Unauthorized) {
                stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState()))
                return
            }
            if (ex.response.status == HttpStatusCode.Conflict) {
                SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, stateRepo.getStore())
                return
            }
            throw ex
        } catch (ex: Exception) {
            if (retry) {
                migrateLegacyAccessToken(context, legacyAccessToken, retry = false)
            } else {
                Log.e("Rownd.Auth", "Legacy session migration failed", ex)
            }
        }
    }

    private suspend fun postLegacyMigration(legacyAccessToken: String): HttpResponse {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain
        val apiBasePath = st.appInfo.apiBasePath ?: "/auth"

        return authenticatedApiClient.client.post("$apiDomain$apiBasePath/plugin/rownd/migrate") {
            expectSuccess = false
            headers {
                append(HttpHeaders.Authorization, "Bearer $legacyAccessToken")
                append("rid", "session")
                append("fdi-version", "1.18")
                append("st-auth-mode", "header")
            }
        }
    }

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
        val signOutRequest = SignOutRequestBody(
            signOutAll = true
        )
        signOutUserAsync(signOutRequest)
    }

    @Synchronized
    @Throws(RowndException::class)
    internal fun signOutUserAsync(signOutRequest: SignOutRequestBody): Deferred<SignOutResponse?>{
        return CoroutineScope(Dispatchers.IO).async {
            try {
                signOutUser(signOutRequest)
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

    private fun isLikelySuperTokensToken(token: String): Boolean {
        return runCatching {
            val jwt = JWT(token)
            jwt.getClaim("sessionHandle").asString() != null ||
                jwt.getClaim("tId").asString() != null ||
                jwt.getClaim("refreshTokenHash1").asString() != null ||
                jwt.getClaim("parentRefreshTokenHash1").asString() != null
        }.getOrDefault(false)
    }

    suspend fun signOutUser(requestBody: SignOutRequestBody) : SignOutResponse {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain
        val apiBasePath = st.appInfo.apiBasePath ?: "/auth"

        return authenticatedApiClient.client.post("$apiDomain$apiBasePath/plugin/rownd/signout") {
            setBody(requestBody)
        }.body()
    }

    @Serializable
    private data class GoogleSignInUpBody(
        val thirdPartyId: String,
        val oAuthTokens: Map<String, String>,
    )
}
