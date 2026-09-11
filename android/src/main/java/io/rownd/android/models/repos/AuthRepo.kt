package io.rownd.android.models.repos

import android.content.Context
import android.util.Log
import com.auth0.android.jwt.JWT
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.headers
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInIntent
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.network.SignInUpResponse
import io.rownd.android.models.network.SignOutRequestBody
import io.rownd.android.models.network.SignOutResponse
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.LegacyMigrationApiClient
import io.rownd.android.util.LegacyTokenApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndException
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
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

    @Inject
    lateinit var legacyMigrationApiClient: LegacyMigrationApiClient

    private val legacyMigrationMutex = Mutex()
    private var legacyMigrationJob: Deferred<Unit>? = null
    private val migrationGeneration = AtomicLong()
    private data class LegacyAttempt(
        val generation: Long,
        val auth: AuthState,
        val signOutGeneration: Long = SuperTokensSessionBridge.currentSignOutGeneration(),
    )

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

    private fun isCurrent(attempt: LegacyAttempt): Boolean {
        val auth = stateRepo.state.value.auth
        return migrationGeneration.get() == attempt.generation &&
            SuperTokensSessionBridge.currentSignOutGeneration() == attempt.signOutGeneration &&
            auth.accessToken == attempt.auth.accessToken && auth.refreshToken == attempt.auth.refreshToken
    }

    private suspend fun getUsableSuperTokensAccessToken(context: Context): String? {
        val accessToken = SuperTokensSessionBridge.getAccessToken(context) ?: return null
        return accessToken.takeIf {
            !SuperTokensSessionBridge.getRefreshToken(context).isNullOrBlank() &&
                !SuperTokensSessionBridge.getFrontToken(context).isNullOrBlank()
        }
    }

    private fun updateAttempt(attempt: LegacyAttempt, auth: AuthState, clearUser: Boolean = false) {
        stateRepo.getStore().dispatch(StateAction.CompleteLegacyMigration(
            expected = attempt.auth, value = auth, clearUser = clearUser,
            isCurrent = { migrationGeneration.get() == attempt.generation &&
                SuperTokensSessionBridge.currentSignOutGeneration() == attempt.signOutGeneration },
        ))
    }

    // All native Hub authentication commits use this lock too. A session established while the
    // request was in flight takes precedence over any migration failure or returned credentials.
    private suspend fun finishMigration(
        context: Context, attempt: LegacyAttempt, clearLegacy: Boolean = false,
    ) = SuperTokensSessionBridge.sessionMutationMutex.withLock {
        if (!isCurrent(attempt)) return@withLock
        val accessToken = try {
            getUsableSuperTokensAccessToken(context)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.w("Rownd.Auth", "Could not read a usable SuperTokens session during migration completion")
            null
        }
        if (!isCurrent(attempt)) return@withLock
        if (accessToken != null) {
            updateAttempt(attempt, AuthState(accessToken = accessToken))
        } else {
            updateAttempt(attempt, if (clearLegacy) AuthState() else attempt.auth.copy(isLoading = false),
                clearUser = clearLegacy)
        }
    }

    private suspend fun runLegacyMigration(context: Context) {
        val auth = stateRepo.state.value.auth
        val token = auth.accessToken ?: return
        if (isLikelySuperTokensToken(token)) return
        var attempt = LegacyAttempt(migrationGeneration.incrementAndGet(), auth)
        try {
            if (getUsableSuperTokensAccessToken(context) != null) {
                finishMigration(context, attempt)
                return
            }
            if (!isCurrent(attempt)) return
            updateAttempt(attempt, attempt.auth.copy(isLoading = true))
            if (isJwtExpiredWithMargin(JWT(token))) {
                val refreshToken = attempt.auth.refreshToken
                if (refreshToken.isNullOrEmpty()) {
                    finishMigration(context, attempt, clearLegacy = true)
                    return
                }
                val refreshed = try {
                    legacyTokenApiClient.refreshLegacyToken(refreshToken)
                } catch (ex: ClientRequestException) {
                    // This is the legacy refresh endpoint's invalid-token contract, not migration's.
                    if (ex.response.status == HttpStatusCode.BadRequest || ex.response.status == HttpStatusCode.Unauthorized) {
                        finishMigration(context, attempt, clearLegacy = true)
                        return
                    }
                    throw ex
                }
                val accessToken = refreshed.accessToken?.takeIf { it.isNotBlank() }
                    ?: throw RowndException("Legacy refresh response missing access token")
                SuperTokensSessionBridge.sessionMutationMutex.withLock {
                    if (!isCurrent(attempt)) return
                    if (getUsableSuperTokensAccessToken(context) == null && isCurrent(attempt)) {
                        val rotated = attempt.auth.copy(accessToken = accessToken,
                            refreshToken = refreshed.refreshToken ?: refreshToken)
                        updateAttempt(attempt, rotated.copy(isLoading = true))
                        attempt = attempt.copy(auth = rotated)
                    }
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.e("Rownd.Auth", "Legacy session migration preflight failed", ex)
            finishMigration(context, attempt)
            return
        }
        migrateLegacyAccessToken(context, attempt)
    }

    private suspend fun migrateLegacyAccessToken(context: Context, attempt: LegacyAttempt) {
        if (!isCurrent(attempt)) return
        val request = try {
            prepareLegacyMigration(attempt.auth.accessToken!!)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Log.e("Rownd.Auth", "Legacy migration request preparation failed", ex)
            finishMigration(context, attempt)
            return
        }
        var requestStarted = false
        repeat(2) { retry ->
            try {
                if (!isCurrent(attempt)) return
                if (getUsableSuperTokensAccessToken(context) != null) {
                    finishMigration(context, attempt)
                    return
                }
                requestStarted = true
                val response = request.execute()
                when {
                    response.status.value in 200..299 -> {
                        val accessToken = response.headers["st-access-token"]
                        val refreshToken = response.headers["st-refresh-token"]
                        val frontToken = response.headers["front-token"]
                        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank() || frontToken.isNullOrBlank()) {
                            throw RowndException("Migration response missing SuperTokens session headers")
                        }
                        validateMigrationSessionHeaders(accessToken, frontToken)
                        SuperTokensSessionBridge.sessionMutationMutex.withLock {
                            if (!isCurrent(attempt)) return
                            val existingAccessToken = getUsableSuperTokensAccessToken(context)
                            if (!isCurrent(attempt)) return
                            if (existingAccessToken == null) {
                                SuperTokensSessionBridge.bootstrapSessionIfCurrent(context, accessToken, refreshToken, frontToken,
                                    response.headers["anti-csrf"], replaceExisting = false,
                                    expectedSignOutGeneration = attempt.signOutGeneration, isCurrent = { isCurrent(attempt) })
                            }
                            val adoptedAccessToken = existingAccessToken ?: getUsableSuperTokensAccessToken(context)
                            if (!isCurrent(attempt)) return
                            if (adoptedAccessToken != null) {
                                updateAttempt(attempt, AuthState(accessToken = adoptedAccessToken))
                            } else {
                                throw RowndException("Migration response did not establish a usable SuperTokens session")
                            }
                        }
                        return
                    }
                    else -> throw RowndException("Legacy session migration failed with HTTP ${response.status.value}")
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                if (retry == 1) {
                    Log.e("Rownd.Auth", "Legacy session migration failed", ex)
                    // Pre-request SDK/preflight failures retain credentials. Once migration was
                    // attempted, exhausted failures abandon only the still-owned legacy session.
                    finishMigration(context, attempt, clearLegacy = requestStarted)
                }
            }
        }
    }

    private fun validateMigrationSessionHeaders(accessToken: String, frontToken: String) {
        val valid = runCatching {
            val now = System.currentTimeMillis()
            val front = Json.parseToJsonElement(String(Base64.getDecoder().decode(frontToken), Charsets.UTF_8)).jsonObject
            val uid = front["uid"]?.jsonPrimitive
            val expiry = front["ate"]?.jsonPrimitive
            isLikelySuperTokensToken(accessToken) && (JWT(accessToken).expiresAt?.time ?: 0) > now &&
                uid?.isString == true && uid.content.isNotBlank() && front["up"] is JsonObject &&
                expiry?.isString == false && (expiry.longOrNull ?: 0) > now
        }.getOrDefault(false)
        if (!valid) throw RowndException("Migration response contains malformed or expired session headers")
    }

    private suspend fun prepareLegacyMigration(legacyAccessToken: String): HttpStatement {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain.ifBlank { rowndContext.config.apiUrl }
        val apiBasePath = st.appInfo.apiBasePath ?: "/auth"

        val endpoint = "$apiDomain$apiBasePath/plugin/rownd/migrate"
        val request = legacyMigrationApiClient.client.preparePost(endpoint) {
            expectSuccess = false
            headers {
                remove("x-rownd-app-key")
                append(HttpHeaders.Authorization, "Bearer $legacyAccessToken")
                append("rid", "session")
                append("fdi-version", "1.18")
                append("st-auth-mode", "header")
            }
        }
        // Ktor accepts some URLs (e.g. non-HTTP schemes) that OkHttp rejects before dispatch.
        endpoint.toHttpUrl()
        return request
    }

    internal fun sessionRevocationUrl(): String? {
        val appInfo = stateRepo.state.value.appConfig.config.supertokens.appInfo
        val apiDomain = appInfo.apiDomain.ifBlank {
            rowndContext.config.supertokens.appInfo.apiDomain.ifBlank { rowndContext.config.apiUrl }
        }
        if (apiDomain.isBlank()) return null
        val apiBasePath = appInfo.apiBasePath ?: rowndContext.config.supertokens.appInfo.apiBasePath ?: rowndContext.config.apiBasePath
        return "$apiDomain$apiBasePath/signout"
    }

    internal suspend fun revokeSignedOutSession(session: SuperTokensSessionBridge.SignedOutSession, url: String?) {
        if (url == null) throw RowndException("Cannot revoke session without an API domain")
        var response = postSignedOutSession(url, session.accessToken, session.antiCSRF)
        if (response.status == HttpStatusCode.Unauthorized) {
            val refreshToken = session.refreshToken?.takeIf { it.isNotBlank() }
                ?: throw RowndException("Session revocation requires refresh but no captured refresh credential is available")
            // Refresh only the captured session. SDK refresh would install A's rotated tokens over B.
            val refreshed = postSignedOutSession("${url.removeSuffix("/signout")}/session/refresh", refreshToken, session.antiCSRF)
            if (refreshed.status == HttpStatusCode.Unauthorized) return
            if (refreshed.status.value !in 200..299) {
                throw RowndException("Signed-out session refresh failed with HTTP ${refreshed.status.value}; remote revocation unconfirmed")
            }
            val accessToken = refreshed.headers["st-access-token"]?.takeIf { it.isNotBlank() }
                ?: throw RowndException("Signed-out session refresh missing access token; remote revocation unconfirmed")
            val antiCSRF = refreshed.headers["anti-csrf"] ?: session.antiCSRF
            response = postSignedOutSession(url, accessToken, antiCSRF)
        }
        if (response.status.value !in 200..299) {
            throw RowndException("Session revocation failed with HTTP ${response.status.value}; remote revocation unconfirmed")
        }
    }

    private suspend fun postSignedOutSession(url: String, token: String, antiCSRF: String?): HttpResponse =
        legacyMigrationApiClient.client.post(url) {
            expectSuccess = false
            retry { maxRetries = 0 }
            headers {
                remove("x-rownd-app-key")
                append(HttpHeaders.Authorization, "Bearer $token")
                append("rid", "session")
                append("fdi-version", "1.18")
                append("st-auth-mode", "header")
                antiCSRF?.takeIf { it.isNotBlank() }?.let { append("anti-csrf", it) }
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

        val signinUpUrl = buildString {
            append(apiDomain)
            append(apiBasePath)
            append("/signinup")
            rowndContext.config.appVariantId?.takeIf { it.isNotEmpty() }?.let {
                append("?app_variant_id=")
                append(java.net.URLEncoder.encode(it, Charsets.UTF_8.name()))
            }
        }
        val response = authenticatedApiClient.client.post(signinUpUrl) {
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
            headers { remove("x-rownd-app-key") }
            setBody(requestBody)
        }.body()
    }

    @Serializable
    private data class GoogleSignInUpBody(
        val thirdPartyId: String,
        val oAuthTokens: Map<String, String>,
    )
}
