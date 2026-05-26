package io.rownd.android.models

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.AuthRepo
import io.rownd.android.models.repos.SignInRepo
import io.rownd.android.models.repos.StateRepo
import io.rownd.android.models.repos.UserRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import javax.inject.Inject

val json = Json { encodeDefaults = true }

@Serializable
data class RowndConfig(
    var appKey: String? = null,
    var baseUrl: String = "https://rownd-hub.supertokens.com",
    var apiUrl: String = "",
    var apiBasePath: String = "/auth",
    var deepLinkScheme: String = "rowndsupertokens",
    var supertokens: SuperTokensConfig = SuperTokensConfig(),
    var postSignInRedirect: String? = "NATIVE_APP",
    var appleIdCallbackUrl: String? = "https://api.rownd.io/hub/auth/apple/callback",
    var customizations: RowndCustomizations = RowndCustomizations(),
    var defaultRequestTimeout: Long = 15000L,
    var defaultNumApiRetries: Int = 5,
    @Transient
    var subdomainExtension: String = ".rownd.link",
    @Transient
    var forceInstantUserConversion: Boolean = false,
    @Transient
    var enableDebugMode: Boolean = false,
    @Transient
    var enableSmartLinkPasteBehavior: Boolean = true,

    // Internals
    @Transient
    internal var stateFileName: String = "rownd_state.json",
    @Transient
    internal var pendingHubDeepLinkUrl: String? = null,
    @Transient
    internal var applicationContext: Context? = null
) {
    @Inject
    @Transient
    lateinit var userRepo: UserRepo

    @Inject
    @Transient
    lateinit var stateRepo: StateRepo

    @Inject
    @Transient
    lateinit var authRepo: AuthRepo

    @Inject
    @Transient
    lateinit var signInRepo: SignInRepo

    suspend fun hubLoaderUrl(): String {
        consumePendingHubDeepLinkUrl()?.let { return it }

        val jsonConfig = json.encodeToString(serializer(), this)
        val base64Config = Base64.encodeToString(jsonConfig.encodeToByteArray(), Base64.NO_WRAP)

        val uriBuilder = "$baseUrl/mobile_app".toUri().buildUpon()
        uriBuilder.appendQueryParameter("config", base64Config)
        hubScriptQueryParams().forEach { (key, value) ->
            uriBuilder.appendQueryParameter(key, value)
        }

        val signInState = signInRepo.get()
        val signInInitStr = signInState.toSignInInitHash()
        uriBuilder.appendQueryParameter("sign_in", signInInitStr)

        try {
            val authState = authRepo.getLatestAuthState() ?: AuthState()
            authState.toRphInitHash(userRepo, applicationContext)?.let { rphInitStr ->
                uriBuilder.encodedFragment("rph_init=$rphInitStr")
            }
        } catch (error: Exception) {
            Log.d("Rownd.config", "Couldn't compute requested init hash: ${error.message}")
        }

        return uriBuilder.build().toString()
    }

    internal fun consumePendingHubDeepLinkUrl(): String? {
        val url = pendingHubDeepLinkUrl
        pendingHubDeepLinkUrl = null
        return url
    }

    internal fun hubScriptQueryParams(): List<Pair<String, String>> {
        return buildHubScriptQueryParams(
            supertokens = supertokens,
            fallbackApiDomain = apiUrl,
            fallbackApiBasePath = apiBasePath
        )
    }

    internal companion object {
        fun buildHubScriptQueryParams(
            supertokens: SuperTokensConfig,
            fallbackApiDomain: String = "",
            fallbackApiBasePath: String = "/auth",
        ): List<Pair<String, String>> {
            val appInfo = supertokens.appInfo
            val apiDomain = appInfo.apiDomain.ifBlank { fallbackApiDomain }
            val apiBasePath = appInfo.apiBasePath?.ifBlank { fallbackApiBasePath } ?: fallbackApiBasePath

            if (apiDomain.isBlank()) {
                return emptyList()
            }

            return listOf(
                "apiDomain" to apiDomain,
                "apiBasePath" to apiBasePath
            )
        }
    }
}
