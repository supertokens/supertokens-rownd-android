package io.rownd.android.util

import android.content.Context
import android.os.Looper
import android.util.Log
import java.util.Base64
import com.supertokens.session.SuperTokens
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "Rownd.SuperTokens"

object SuperTokensSessionBridge {

    val isInitialized = AtomicBoolean(false)

    // Called from configure(appKey) — observes state until app config is ready, then inits SuperTokens once.
    fun observeAndInitialize(context: Context, stateRepo: StateRepo) {
        CoroutineScope(Dispatchers.IO).launch {
            stateRepo.state
                .filter { it.appConfig.id.isNotEmpty() && !it.appConfig.isLoading }
                .take(1)
                .collect { state ->
                    if (isInitialized.get()) return@collect

                    val st = state.appConfig.config.supertokens
                    if (st.appInfo.apiDomain.isEmpty()) {
                        Log.e(TAG, "config.supertokens.appInfo.apiDomain is missing — SuperTokens cannot initialize")
                        return@collect
                    }

                    try {
                        SuperTokens.Builder(context, st.appInfo.apiDomain)
                            .apiBasePath(st.appInfo.apiBasePath ?: "/auth")
                            .tokenTransferMethod("header")
                            .build()
                        isInitialized.set(true)
                        Log.d(TAG, "SuperTokens initialized with apiDomain=${st.appInfo.apiDomain}")
                    } catch (e: Exception) {
                        Log.e(TAG, "SuperTokens initialization failed: ${e.message}")
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

    suspend fun attemptRefresh(context: Context): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { SuperTokens.attemptRefreshingSession(context) }.isSuccess
                && SuperTokens.doesSessionExist(context)
        }

    suspend fun signOut(context: Context) =
        withContext(Dispatchers.IO) {
            SuperTokens.signOut(context)
        }

    // MARK: - Bootstrap (Hub-complete auth)
    //
    // Android WebView requests do not pass through SuperTokensInterceptor, so response-header
    // capture does not occur for Hub-complete flows. Write the four SharedPreferences values
    // directly so doesSessionExist() returns true immediately.
    // Must only be called off the main thread. Guards against double-injection.

    fun bootstrapSession(context: Context, accessToken: String, refreshToken: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "bootstrapSession must be called off the main thread"
        }
        if (SuperTokens.doesSessionExist(context)) return

        val prefs = context.getSharedPreferences(
            "supertokens-android-shared-preferences",
            Context.MODE_PRIVATE
        )
        prefs.edit()
            .putString("st-storage-item-st-access-token", accessToken)
            .putString("st-storage-item-st-refresh-token", refreshToken)
            .putString("supertokens-android-fronttoken-key", buildFrontToken(accessToken))
            .putString("st-storage-item-st-last-access-token-update", "${System.currentTimeMillis()}")
            .apply()
    }

    // MARK: - Rownd compatibility state sync

    suspend fun syncRowndAuthStateFromSuperTokens(context: Context, store: io.rownd.android.models.Store<io.rownd.android.models.repos.GlobalState, StateAction>) {
        val accessToken = getAccessToken(context) ?: return
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = accessToken, refreshToken = null)))
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
}
