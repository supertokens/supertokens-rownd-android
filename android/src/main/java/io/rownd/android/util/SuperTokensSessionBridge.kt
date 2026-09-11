package io.rownd.android.util

import android.content.Context
import android.os.Looper
import android.util.Log
import com.auth0.android.jwt.JWT
import java.util.Base64
import com.supertokens.session.EventHandler
import com.supertokens.session.FrontToken
import com.supertokens.session.SuperTokens
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.Store
import io.rownd.android.models.repos.GlobalState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

private const val TAG = "Rownd.SuperTokens"
private const val SUPER_TOKENS_PREFS = "supertokens-android-shared-preferences"
private const val ACCESS_TOKEN_STORAGE_KEY = "st-storage-item-st-access-token"
private const val REFRESH_TOKEN_STORAGE_KEY = "st-storage-item-st-refresh-token"
private const val FRONT_TOKEN_STORAGE_KEY = "supertokens-android-fronttoken-key"
private const val LAST_ACCESS_TOKEN_UPDATE_STORAGE_KEY = "st-storage-item-st-last-access-token-update"
private const val ANTI_CSRF_STORAGE_KEY = "supertokens-android-anticsrf-key"

object SuperTokensSessionBridge {
    internal val sessionMutationMutex = Mutex()
    internal var writeSession: (() -> Unit) -> Unit = { write -> write() }
    private val sessionCommitLock = Any()
    private val signOutGeneration = AtomicLong()

    internal fun currentSignOutGeneration(): Long = signOutGeneration.get()

    // Reactive refresh on 401 needs only these header credentials, not the SDK's front-token state.
    internal class SignedOutSession(val accessToken: String, val refreshToken: String?, val antiCSRF: String?)

    // Invalidation, local cleanup, and compatibility state reset are synchronous with native
    // writes. Network revocation uses the captured session and must never clear current storage.
    internal fun beginSignOut(context: Context?, resetAuthState: () -> Unit): SignedOutSession? =
        synchronized(sessionCommitLock) {
            signOutGeneration.incrementAndGet()
            val session = context?.let {
                sharedPrefs(it).getString(ACCESS_TOKEN_STORAGE_KEY, null)?.let { token ->
                    SignedOutSession(token, getRefreshToken(it), getAntiCSRF(it))
                }
            }
            context?.let { clearLocalSession(it) }
            resetAuthState()
            session
        }

    val isInitialized = AtomicBoolean(false)
    private var enableDebugMode = false

    internal var buildSuperTokens: (Context, String, String, Boolean) -> Unit = { context, apiDomain, apiBasePath, enableDebugMode ->
        val builder = SuperTokens.Builder(context, apiDomain)
            .apiBasePath(apiBasePath)
            .tokenTransferMethod("header")

        if (enableDebugMode) {
            builder.eventHandler(object : EventHandler {
                override fun handleEvent(eventType: EventHandler.EventType) {
                    Log.d(TAG, "SuperTokens event: $eventType")
                }
            })
        }

        builder.build()
    }

    fun initializeIfNeeded(context: Context, apiDomain: String, apiBasePath: String = "/auth", enableDebugMode: Boolean = false) {
        if (apiDomain.isBlank()) {
            Log.e(TAG, "config.supertokens.appInfo.apiDomain is missing — SuperTokens cannot initialize")
            return
        }

        if (!isInitialized.compareAndSet(false, true)) return

        this.enableDebugMode = enableDebugMode
        try {
            buildSuperTokens(context, apiDomain, apiBasePath, enableDebugMode)
            Log.d(TAG, "SuperTokens initialized with apiDomain=$apiDomain")
        } catch (e: Exception) {
            isInitialized.set(false)
            Log.e(TAG, "SuperTokens initialization failed: ${e.message}")
        }
    }

    // Called from configure(...) as a fallback — observes state until app config is ready, then inits SuperTokens once.
    fun observeAndInitialize(context: Context, stateRepo: StateRepo, enableDebugMode: Boolean = false) {
        this.enableDebugMode = enableDebugMode

        CoroutineScope(Dispatchers.IO).launch {
            stateRepo.state
                .filter { it.appConfig.id.isNotEmpty() && !it.appConfig.isLoading }
                .take(1)
                .collect { state ->
                    val st = state.appConfig.config.supertokens
                    initializeIfNeeded(context, st.appInfo.apiDomain, st.appInfo.apiBasePath ?: "/auth", enableDebugMode)
                    if (!isInitialized.get()) return@collect

                    try {
                        stateRepo.authRepo.migrateLegacySessionIfNeeded(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Legacy session migration failed: ${e.message}")
                    }
                }
        }
    }

    // MARK: - Session queries (must run on Dispatchers.IO)

    suspend fun doesSessionExist(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            SuperTokens.doesSessionExist(context)
        }

    suspend fun getAccessToken(context: Context): String? =
        withContext(Dispatchers.IO) {
            SuperTokens.getAccessToken(context)
        }

    private fun getAccessTokenForAuthentication(context: Context): String? {
        val token = SuperTokens.getAccessToken(context)
        // The native getter suppresses refresh API/transport errors. Retained
        // credentials mean absence has not been established and callers can retry.
        if (token == null && !getRefreshToken(context).isNullOrBlank()) {
            throw ServerException("Session refresh failed temporarily; retry when the service is available")
        }
        return token
    }

    internal suspend fun resolveAuthState(
        context: Context,
        store: Store<GlobalState, StateAction>,
        forceRefresh: Boolean = false,
        afterTokenRead: suspend () -> Unit = {},
    ): AuthState? = withContext(Dispatchers.IO) {
        if (!isInitialized.get()) throw ServerException("Session SDK is not initialized; retry after configuration")
        sessionMutationMutex.withLock {
            val generation = currentSignOutGeneration()
            val previousToken = store.currentState.auth.accessToken
            val nativeTokenBefore = storedAccessToken(context)
            if (forceRefresh) attemptRefresh(context)
            val token = getAccessTokenForAuthentication(context)
            afterTokenRead()
            currentCoroutineContext().ensureActive()

            synchronized(sessionCommitLock) {
                if (generation != currentSignOutGeneration()) {
                    // Native refresh can finish after synchronous local sign-out.
                    // Remove only credentials belonging to that signed-out session.
                    if (sameSession(nativeTokenBefore, storedAccessToken(context))) {
                        clearLocalSession(context)
                    }
                    if (!getRefreshToken(context).isNullOrBlank()) {
                        throw ServerException("Session changed during token retrieval; retry")
                    }
                    return@synchronized null
                }
                if (storedAccessToken(context) != token || store.currentState.auth.accessToken != previousToken ||
                    (token == null && !getRefreshToken(context).isNullOrBlank())) {
                    throw ServerException("Session changed during token retrieval; retry")
                }
                // A missing native session must not discard legacy credentials still
                // awaiting migration. Only reconcile native compatibility state here.
                if (token == null && sessionHandle(previousToken) == null) return@synchronized null

                store.dispatch(StateAction.ReconcileNativeSession(
                    expectedAccessToken = previousToken,
                    accessToken = token,
                    preserveProfile = sameSession(previousToken, token),
                    isCurrent = {
                        generation == currentSignOutGeneration() && storedAccessToken(context) == token &&
                            (token != null || getRefreshToken(context).isNullOrBlank())
                    },
                ))
                val auth = store.currentState.auth
                if (auth.accessToken != token) {
                    throw ServerException("Session changed during token retrieval; retry")
                }
                auth.takeIf { token != null }
            }
        }
    }

    private fun storedAccessToken(context: Context): String? =
        sharedPrefs(context).getString(ACCESS_TOKEN_STORAGE_KEY, null)

    private fun sessionHandle(token: String?): String? =
        token?.let { runCatching { JWT(it).getClaim("sessionHandle").asString() }.getOrNull() }

    private fun sameSession(first: String?, second: String?): Boolean {
        if (first == null || second == null) return false
        return runCatching {
            val old = JWT(first)
            val current = JWT(second)
            val handle = old.getClaim("sessionHandle").asString()
            !handle.isNullOrBlank() && handle == current.getClaim("sessionHandle").asString() &&
                (old.subject ?: old.getClaim("userId").asString()) == (current.subject ?: current.getClaim("userId").asString()) &&
                (old.getClaim("tId").asString() ?: old.getClaim("tenantId").asString()) ==
                    (current.getClaim("tId").asString() ?: current.getClaim("tenantId").asString())
        }.getOrDefault(false)
    }

    fun getRefreshToken(context: Context): String? =
        sharedPrefs(context).getString(REFRESH_TOKEN_STORAGE_KEY, null)

    fun getFrontToken(context: Context): String? =
        sharedPrefs(context).getString(FRONT_TOKEN_STORAGE_KEY, null)

    fun getAntiCSRF(context: Context): String? =
        sharedPrefs(context).getString(ANTI_CSRF_STORAGE_KEY, null)

    suspend fun attemptRefresh(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            debugLog("Attempting SuperTokens session refresh")
            try {
                SuperTokens.attemptRefreshingSession(context) && getAccessTokenForAuthentication(context) != null
            } catch (error: IOException) {
                throw ServerException("Session refresh failed temporarily; retry when the service is available")
                    .also { it.initCause(error) }
            }
        }

    suspend fun signOut(context: Context) =
        withContext(Dispatchers.IO) {
            try {
                debugLog("Signing out of SuperTokens session")
                SuperTokens.signOut(context)
            } finally {
                clearLocalSession(context)
            }
        }

    suspend fun awaitInitialized(timeoutMs: Long = 5_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!isInitialized.get() && System.currentTimeMillis() < deadline) {
            delay(50)
        }
        return isInitialized.get()
    }

    fun clearLocalSession(context: Context) {
        FrontToken.removeToken(context)
        sharedPrefs(context).edit()
            .remove(ACCESS_TOKEN_STORAGE_KEY)
            .remove(REFRESH_TOKEN_STORAGE_KEY)
            .remove(FRONT_TOKEN_STORAGE_KEY)
            .remove(LAST_ACCESS_TOKEN_UPDATE_STORAGE_KEY)
            .remove(ANTI_CSRF_STORAGE_KEY)
            .apply()
    }

    // MARK: - Bootstrap (Hub-complete auth)
    //
    // Android WebView requests do not pass through SuperTokensInterceptor, so response-header
    // capture does not occur for Hub-complete flows. Write the four SharedPreferences values
    // directly so doesSessionExist() returns true immediately.
    // Must only be called off the main thread. Guards against double-injection.

    fun bootstrapSession(
        context: Context,
        accessToken: String,
        refreshToken: String,
        frontToken: String? = null,
        antiCSRF: String? = null,
        replaceExisting: Boolean = false,
    ) {
        bootstrapSessionIfCurrent(context, accessToken, refreshToken, frontToken, antiCSRF,
            replaceExisting, currentSignOutGeneration())
    }

    internal fun bootstrapSessionIfCurrent(
        context: Context,
        accessToken: String,
        refreshToken: String,
        frontToken: String?,
        antiCSRF: String?,
        replaceExisting: Boolean,
        expectedSignOutGeneration: Long,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "bootstrapSession must be called off the main thread"
        }
        if (expectedSignOutGeneration != currentSignOutGeneration() || !isCurrent()) return false
        if (!replaceExisting && SuperTokens.doesSessionExist(context)) return false
        debugLog("Bootstrapping SuperTokens session from Rownd Hub auth result")

        val resolvedFrontToken = frontToken ?: buildFrontToken(accessToken)
        var committed = false
        writeSession {
            synchronized(sessionCommitLock) {
                if (expectedSignOutGeneration != currentSignOutGeneration() || !isCurrent()) return@synchronized
                if (!replaceExisting && !getFrontToken(context).isNullOrBlank() &&
                    !getRefreshToken(context).isNullOrBlank() &&
                    !sharedPrefs(context).getString(ACCESS_TOKEN_STORAGE_KEY, null).isNullOrBlank()) return@synchronized
                if (replaceExisting) clearLocalSession(context)
                val editor = sharedPrefs(context).edit()
                    .putString(ACCESS_TOKEN_STORAGE_KEY, accessToken)
                    .putString(REFRESH_TOKEN_STORAGE_KEY, refreshToken)
                    .putString(LAST_ACCESS_TOKEN_UPDATE_STORAGE_KEY, "${System.currentTimeMillis()}")

                if (!antiCSRF.isNullOrEmpty()) {
                    editor.putString(ANTI_CSRF_STORAGE_KEY, antiCSRF)
                }

                editor.apply()
                FrontToken.setToken(context, resolvedFrontToken)
                committed = true
            }
        }
        return committed
    }

    // MARK: - Rownd compatibility state sync

    suspend fun syncRowndAuthStateFromSuperTokens(context: Context, store: io.rownd.android.models.Store<io.rownd.android.models.repos.GlobalState, StateAction>): Boolean {
        val generation = currentSignOutGeneration()
        val accessToken = getAccessToken(context) ?: return false
        return synchronized(sessionCommitLock) {
            if (generation != currentSignOutGeneration() ||
                sharedPrefs(context).getString(ACCESS_TOKEN_STORAGE_KEY, null) != accessToken) return@synchronized false
            store.dispatch(StateAction.SetAuth(AuthState(accessToken = accessToken, refreshToken = null)))
            true
        }
    }

    // MARK: - Helpers

    @Serializable
    private data class JwtPayload(
        val sub: String? = null,
        val userId: String? = null,
        val exp: Long? = null,
    )

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun padBase64(s: String): String {
        val pad = (4 - s.length % 4) % 4
        return s + "=".repeat(pad)
    }

    internal fun buildFrontToken(accessToken: String): String {
        var uid = ""
        var ate = 0L
        runCatching {
            val parts = accessToken.split(".")
            if (parts.size >= 2) {
                val payloadJson = String(Base64.getUrlDecoder().decode(padBase64(parts[1])))
                val payload = lenientJson.decodeFromString(JwtPayload.serializer(), payloadJson)
                uid = payload.sub?.ifEmpty { null } ?: payload.userId ?: ""
                ate = (payload.exp ?: 0L) * 1000
            }
        }
        val frontTokenJson = """{"uid":"$uid","ate":$ate,"up":{}}"""
        return Base64.getEncoder().withoutPadding().encodeToString(frontTokenJson.toByteArray())
    }

    private fun sharedPrefs(context: Context) =
        context.getSharedPreferences(SUPER_TOKENS_PREFS, Context.MODE_PRIVATE)

    private fun debugLog(message: String) {
        if (enableDebugMode) {
            Log.d(TAG, message)
        }
    }
}
