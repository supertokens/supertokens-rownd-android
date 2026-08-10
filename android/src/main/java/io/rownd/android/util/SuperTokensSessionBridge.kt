package io.rownd.android.util

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.Base64
import com.supertokens.session.EventHandler
import com.supertokens.session.FrontToken
import com.supertokens.session.SuperTokens
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Rownd.SuperTokens"
private const val SUPER_TOKENS_PREFS = "supertokens-android-shared-preferences"
private const val ACCESS_TOKEN_STORAGE_KEY = "st-storage-item-st-access-token"
private const val REFRESH_TOKEN_STORAGE_KEY = "st-storage-item-st-refresh-token"
private const val FRONT_TOKEN_STORAGE_KEY = "supertokens-android-fronttoken-key"
private const val LAST_ACCESS_TOKEN_UPDATE_STORAGE_KEY = "st-storage-item-st-last-access-token-update"
private const val ANTI_CSRF_STORAGE_KEY = "supertokens-android-anticsrf-key"

object SuperTokensSessionBridge {

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

    fun getRefreshToken(context: Context): String? =
        sharedPrefs(context).getString(REFRESH_TOKEN_STORAGE_KEY, null)

    fun getFrontToken(context: Context): String? =
        sharedPrefs(context).getString(FRONT_TOKEN_STORAGE_KEY, null)

    fun getAntiCSRF(context: Context): String? =
        sharedPrefs(context).getString(ANTI_CSRF_STORAGE_KEY, null)

    suspend fun attemptRefresh(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            debugLog("Attempting SuperTokens session refresh")
            runCatching { SuperTokens.attemptRefreshingSession(context) }.isSuccess
                && SuperTokens.doesSessionExist(context)
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
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "bootstrapSession must be called off the main thread"
        }
        if (!replaceExisting && SuperTokens.doesSessionExist(context)) return
        if (replaceExisting) {
            clearLocalSession(context)
        }
        debugLog("Bootstrapping SuperTokens session from Rownd Hub auth result")

        val resolvedFrontToken = frontToken ?: buildFrontToken(accessToken)
        val editor = sharedPrefs(context).edit()
            .putString(ACCESS_TOKEN_STORAGE_KEY, accessToken)
            .putString(REFRESH_TOKEN_STORAGE_KEY, refreshToken)
            .putString(LAST_ACCESS_TOKEN_UPDATE_STORAGE_KEY, "${System.currentTimeMillis()}")

        if (!antiCSRF.isNullOrEmpty()) {
            editor.putString(ANTI_CSRF_STORAGE_KEY, antiCSRF)
        }

        editor.apply()
        FrontToken.setToken(context, resolvedFrontToken)
    }

    // MARK: - Rownd compatibility state sync

    suspend fun syncRowndAuthStateFromSuperTokens(context: Context, store: io.rownd.android.models.Store<io.rownd.android.models.repos.GlobalState, StateAction>): Boolean {
        val accessToken = getAccessToken(context) ?: return false
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = accessToken, refreshToken = null)))
        return true
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
