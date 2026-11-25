package io.rownd.android.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.rownd.android.views.HubPageSelector
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// The server commits the verification and issues a replacement session in one call that can
// take several seconds. The Hub waits 50s for the native result, so this must stay below that.
internal const val NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS = 45_000L

internal data class NativeEmailVerificationRequest(
    val token: String,
    val pendingVerificationId: String,
    val apiDomain: String,
    val apiBasePath: String,
)

// The SDK interceptor stores the issued session before this result exists; its access token is
// kept so that a session nobody will adopt can be found, cleared and revoked.
internal class NativeEmailVerificationResult(
    val status: String,
    val issuedAccessToken: String?,
) {
    override fun toString() = "NativeEmailVerificationResult(status=$status, issuedSession=${issuedAccessToken != null})"
}

// The server can reject the verification and still issue a session, which the SDK has then stored.
internal class NativeEmailVerificationRejectedException(
    message: String,
    val issuedAccessToken: String?,
) : IllegalStateException(message)

@Serializable
private data class NativeEmailVerificationBody(
    val method: String,
    val token: String,
)

@Serializable
private data class NativeEmailVerificationResponse(
    val status: String,
)

private fun effectivePort(scheme: String?, port: Int): Int? = when {
    port >= 0 -> port
    scheme.equals("https", ignoreCase = true) -> 443
    scheme.equals("http", ignoreCase = true) -> 80
    else -> null
}

private fun isSecureTokenUrl(uri: URI): Boolean {
    if (uri.scheme.equals("https", ignoreCase = true)) return true
    if (!uri.scheme.equals("http", ignoreCase = true)) return false

    return uri.host.equals("localhost", ignoreCase = true) ||
        uri.host == "127.0.0.1" ||
        uri.host == "::1"
}

private fun normalizedPath(path: String?): String = path.orEmpty().trim('/').let {
    if (it.isEmpty()) "/" else "/$it"
}

internal fun nativeEmailVerificationRequest(
    targetPage: HubPageSelector,
    currentUrl: String?,
    trustedBaseUrl: String,
    trustedApiDomain: String,
    trustedApiBasePath: String,
): NativeEmailVerificationRequest? = runCatching {
    if (targetPage != HubPageSelector.DeepLink || currentUrl == null) {
        return@runCatching null
    }

    val current = URI(currentUrl)
    val trusted = URI(trustedBaseUrl)
    val trustedApi = URI(trustedApiDomain)
    if (!isSecureTokenUrl(current) || !isSecureTokenUrl(trustedApi)) {
        return@runCatching null
    }

    val query = current.rawQuery
        ?.split('&')
        ?.map { queryPart ->
            val parts = queryPart.split('=', limit = 2)
            URLDecoder.decode(parts[0], StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(parts.getOrNull(1) ?: "", StandardCharsets.UTF_8.name())
        }
        ?: emptyList()
    fun singleNonEmptyQueryValue(name: String): String? {
        return query.filter { it.first == name }.map { it.second }.singleOrNull()?.takeIf { it.isNotEmpty() }
    }

    val token = singleNonEmptyQueryValue("token") ?: return@runCatching null
    val pendingVerificationId = singleNonEmptyQueryValue("rowndPendingVerificationId") ?: return@runCatching null
    val apiDomain = singleNonEmptyQueryValue("apiDomain") ?: return@runCatching null
    val apiBasePath = singleNonEmptyQueryValue("apiBasePath") ?: return@runCatching null
    val candidateApi = URI(apiDomain)

    val trustedHubOrigin = current.scheme.equals(trusted.scheme, ignoreCase = true) &&
        current.host.equals(trusted.host, ignoreCase = true) &&
        effectivePort(current.scheme, current.port) == effectivePort(trusted.scheme, trusted.port)
    val trustedApiOrigin = candidateApi.scheme.equals(trustedApi.scheme, ignoreCase = true) &&
        candidateApi.host.equals(trustedApi.host, ignoreCase = true) &&
        effectivePort(candidateApi.scheme, candidateApi.port) == effectivePort(trustedApi.scheme, trustedApi.port)

    if (
        !trustedHubOrigin ||
        current.userInfo != null ||
        current.path != "/account/verify-email" ||
        !trustedApiOrigin ||
        candidateApi.userInfo != null ||
        normalizedPath(candidateApi.path) != normalizedPath(trustedApi.path) ||
        candidateApi.rawQuery != null ||
        candidateApi.rawFragment != null ||
        normalizedPath(apiBasePath) != normalizedPath(trustedApiBasePath)
    ) {
        return@runCatching null
    }

    NativeEmailVerificationRequest(
        token = token,
        pendingVerificationId = pendingVerificationId,
        apiDomain = trustedApiDomain.trimEnd('/'),
        apiBasePath = normalizedPath(trustedApiBasePath).trimEnd('/'),
    )
}.getOrNull()

internal suspend fun performNativeEmailVerification(
    client: HttpClient,
    request: NativeEmailVerificationRequest,
): NativeEmailVerificationResult {
    val response = client.post("${request.apiDomain}${request.apiBasePath}/user/email/verify") {
        // Rejections are read here: they can still carry an issued session.
        expectSuccess = false
        retry { maxRetries = 0 }
        timeout {
            requestTimeoutMillis = NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
            // The OkHttp engine bounds the wait for the response by its read timeout (10s by
            // default) and only replaces that with the socket timeout, not the request timeout.
            socketTimeoutMillis = NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
        }
        parameter("rowndPendingVerificationId", request.pendingVerificationId)
        contentType(ContentType.Application.Json)
        setBody(NativeEmailVerificationBody(method = "token", token = request.token))
    }
    val issuedAccessToken = response.headers["st-access-token"]?.takeIf { it.isNotBlank() }
    if (response.status != HttpStatusCode.OK) {
        throw NativeEmailVerificationRejectedException(
            "Email verification failed with HTTP ${response.status.value}", issuedAccessToken)
    }
    val status = runCatching { response.body<NativeEmailVerificationResponse>().status }.getOrNull()
    if (status != "OK") {
        throw NativeEmailVerificationRejectedException("Email verification failed with status $status", issuedAccessToken)
    }
    return NativeEmailVerificationResult(status, issuedAccessToken)
}

// Revokes a session issued by a verification that nobody will adopt, using that session's own
// access token. [client] must not carry the SuperTokens interceptor: it would attach whatever
// session the SDK holds, possibly another user's, and store the tokens of the response.
internal suspend fun revokeIssuedVerificationSession(
    client: HttpClient,
    request: NativeEmailVerificationRequest,
    issuedAccessToken: String,
) {
    val response = client.post("${request.apiDomain}${request.apiBasePath}/signout") {
        expectSuccess = false
        retry { maxRetries = 0 }
        timeout {
            requestTimeoutMillis = NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
            socketTimeoutMillis = NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
        }
        headers {
            remove("x-rownd-app-key")
            set(HttpHeaders.Authorization, "Bearer $issuedAccessToken")
            set("st-auth-mode", "header")
            set("rid", "session")
        }
    }
    if (response.status.value !in 200..299) {
        throw RowndException("Revoking the unadopted verification session failed with HTTP ${response.status.value}")
    }
}
