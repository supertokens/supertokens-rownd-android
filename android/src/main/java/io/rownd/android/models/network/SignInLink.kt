package io.rownd.android.models.network

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.util.Log
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.doOnLayout
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInType
import io.rownd.android.RowndSignInUserType
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.UserRepo
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.KtorApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.signInCompletedEventData
import io.rownd.android.views.HubPageSelector
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI
import javax.inject.Inject

@Serializable
data class SignInLink(
    val link: String,
    @SerialName("app_user_id")
    val appUserId: String
)

@Serializable
data class SignInAuthenticationResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String,
    @SerialName("app_id")
    val appId: String,
    @SerialName("app_user_id")
    val appUserId: String
)

class SignInLinkApi @Inject constructor() {
    private var lastHandledDeepLink: String? = null

    @Inject
    lateinit var userRepo: UserRepo

    @Inject
    lateinit var rowndContext: RowndContext

    @Inject
    lateinit var authenticatedApiClient: AuthenticatedApiClient

    @Inject
    lateinit var apiClient: KtorApiClient

    @Inject
    lateinit var config: io.rownd.android.models.RowndConfig

    suspend fun createSignInLink() : SignInLink {
        return authenticatedApiClient.client.post("me/auth/magic").body()
    }

    suspend fun authenticateWithSignInLink(url: String) : SignInAuthenticationResponse {
        return apiClient.client.get(url).body()
    }

    internal suspend fun signInWithLink(url: String) {
        var signInUrl = url
        val urlObj = url.toUri()

        if (urlObj.fragment != null) {
            signInUrl = signInUrl.replace("#${urlObj.fragment}", "")
        }

        // Rewrite links to https, since we sometimes send links via SMS
        // without a protocol attached
        signInUrl = signInUrl.replace("http://", "https://")

        try {
            val authBody = authenticateWithSignInLink(signInUrl)

            Rownd.store.dispatch(
                StateAction.SetAuth(
                    AuthState(
                        accessToken = authBody.accessToken,
                        refreshToken = authBody.refreshToken
                    )
                )
            )

            rowndContext.eventEmitter?.emit(
                RowndEvent(
                    event = RowndEventType.SignInCompleted,
                    data = signInCompletedEventData(
                        method = RowndSignInType.SignInLink,
                        userType = RowndSignInUserType.ExistingUser,
                        appVariantUserType = RowndSignInUserType.ExistingUser,
                    )
                )
            )

            userRepo.loadUserAsync().await()
        } catch (err: Exception) {
            Log.e("Rownd.SignInLink", "Exception thrown during auto sign-in attempt (url: ${urlObj.path}):", err)
        }
    }

    internal fun signInWithLinkIfPresentOnIntentOrClipboard(ctx: Activity) {
        if (Rownd.store.currentState.auth.isAuthenticated) {
            return
        }

        if (openDeepLinkIfPresentOnIntent(ctx)) {
            return
        } else if (config.enableSmartLinkPasteBehavior) {
            if (ctx.hasWindowFocus()) {
                // Look on the clipboard
                signInWithLinkFromClipboardIfPresent(ctx)
            } else {
                val rootView = ctx.findViewById<View>(android.R.id.content)
                rootView.doOnLayout {
                    signInWithLinkFromClipboardIfPresent(ctx)
                }
            }
        }
    }

    internal fun openDeepLinkIfPresentOnIntent(ctx: Activity): Boolean {
        val action: String? = ctx.intent?.action
        val uri = ctx.intent?.data

        if (action != ACTION_VIEW || !isConfiguredDeepLink(uri)) {
            return false
        }

        return openDeepLink(uri)
    }

    private fun signInWithLinkFromClipboardIfPresent(ctx: Activity) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) != true) {
            return
        }

        val clipboardText = clipboard.primaryClip?.getItemAt(0)?.text.toString() ?: return
        val potentialSignInLink = clipboardText.toUri() ?: return

        if (!isSupportedDeepLink(potentialSignInLink)) {
            return
        }

        openDeepLink(potentialSignInLink)
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    private fun openDeepLink(uri: Uri?): Boolean {
        val deepLink = uri?.toString() ?: return false
        if (deepLink == lastHandledDeepLink) {
            return true
        }

        val hubUrl = toHubUrl(uri) ?: return false
        Log.d("Rownd.SignInLink", "Opening deep link in hub: ${uri.path}")
        lastHandledDeepLink = deepLink
        config.pendingHubDeepLinkUrl = hubUrl
        Rownd.displayHub(HubPageSelector.DeepLink)
        return true
    }

    private fun toHubUrl(uri: Uri?): String? {
        return toHubUrl(
            rawUrl = uri?.toString(),
            deepLinkScheme = config.deepLinkScheme,
            hubBaseUrl = config.baseUrl,
        )
    }

    private fun isConfiguredDeepLink(uri: Uri?) : Boolean {
        return isSupportedDeepLink(uri)
    }

    private fun isSupportedDeepLink(uri: Uri?): Boolean {
        return isSupportedDeepLink(
            rawUrl = uri?.toString(),
            deepLinkScheme = config.deepLinkScheme,
            hubBaseUrl = config.baseUrl,
        )
    }

    internal companion object {
        private val allowedHubDeepLinkPaths = setOf(
            "/account/login",
            "/account/verify-email",
        )

        private val stagingHubHosts = setOf(
            "staging.supertokens-rownd-hub.pages.dev",
            "supertokens-rownd-hub.pages.dev",
        )

        fun isSupportedDeepLink(
            rawUrl: String?,
            deepLinkScheme: String,
            hubBaseUrl: String,
        ): Boolean {
            val uri = parseUri(rawUrl) ?: return false
            return isConfiguredSchemeDeepLink(uri, deepLinkScheme) || isAllowedHubHttpsDeepLink(uri, hubBaseUrl)
        }

        fun toHubUrl(
            rawUrl: String?,
            deepLinkScheme: String,
            hubBaseUrl: String,
        ): String? {
            val uri = parseUri(rawUrl) ?: return null
            if (!isSupportedDeepLink(rawUrl, deepLinkScheme, hubBaseUrl)) {
                return null
            }

            val hubPath = when {
                isConfiguredSchemeDeepLink(uri, deepLinkScheme) -> {
                    val host = uri.host?.trim('/') ?: return null
                    val path = uri.rawPath?.trim('/')
                    if (path.isNullOrBlank()) "/$host" else "/$host/$path"
                }
                isAllowedHubHttpsDeepLink(uri, hubBaseUrl) -> uri.rawPath ?: return null
                else -> return null
            }

            if (hubPath !in allowedHubDeepLinkPaths) {
                return null
            }

            val baseUri = parseUri(hubBaseUrl) ?: return null
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""
            return "${baseUri.scheme}://${baseUri.rawAuthority}$hubPath$query$fragment"
        }

        private fun isConfiguredSchemeDeepLink(uri: URI, deepLinkScheme: String): Boolean {
            return uri.scheme == deepLinkScheme
        }

        private fun isAllowedHubHttpsDeepLink(uri: URI, hubBaseUrl: String): Boolean {
            if (uri.scheme != "https") return false

            val host = uri.host ?: return false
            val configuredHubHost = parseUri(hubBaseUrl)?.host

            return host == configuredHubHost ||
                host == "rownd-hub.supertokens.com" ||
                host.endsWith(".rownd-hub.supertokens.com") ||
                host in stagingHubHosts
        }

        private fun parseUri(rawUrl: String?): URI? {
            if (rawUrl.isNullOrBlank()) return null
            return runCatching { URI(rawUrl) }.getOrNull()
        }
    }
}
