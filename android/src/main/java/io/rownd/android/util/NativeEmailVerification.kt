package io.rownd.android.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.rownd.android.views.HubPageSelector
import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS = 10_000L

internal data class NativeEmailVerificationRequest(
    val token: String,
    val pendingVerificationId: String,
    val apiDomain: String,
    val apiBasePath: String,
)

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
): String {
    val response = client.post("${request.apiDomain}${request.apiBasePath}/user/email/verify") {
        retry { maxRetries = 0 }
        timeout { requestTimeoutMillis = NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS }
        parameter("rowndPendingVerificationId", request.pendingVerificationId)
        contentType(ContentType.Application.Json)
        setBody(NativeEmailVerificationBody(method = "token", token = request.token))
    }
    check(response.status == HttpStatusCode.OK) {
        "Email verification failed with HTTP ${response.status.value}"
    }
    val status = response.body<NativeEmailVerificationResponse>().status
    check(status == "OK") { "Email verification failed" }
    return status
}
