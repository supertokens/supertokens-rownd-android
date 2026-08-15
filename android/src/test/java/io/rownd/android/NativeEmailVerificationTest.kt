package io.rownd.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rownd.android.models.VerifyEmailMessage
import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.util.NativeEmailVerificationRequest
import io.rownd.android.util.nativeEmailVerificationRequest
import io.rownd.android.util.performNativeEmailVerification
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.urlForLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeEmailVerificationTest {
    @Test
    fun `verify email message decodes with its request id`() {
        val message = Json.decodeFromString(
            RowndHubInteropMessage.serializer(),
            """{"type":"verify_email","payload":{"request_id":"request-123"}}""",
        ) as VerifyEmailMessage

        assertEquals("request-123", message.payload.requestId)
    }

    @Test
    fun `verification requests require token and trusted pending verification page`() {
        val trustedBaseUrl = "https://hub.example.com"
        val trustedApiDomain = "https://api.example.com"
        val trustedApiBasePath = "/auth"
        val pendingUrl = "$trustedBaseUrl/account/verify-email?token=opaque-token&rowndPendingVerificationId=pending-123&apiDomain=https%3A%2F%2Fapi.example.com&apiBasePath=%2Fauth"

        val request = nativeEmailVerificationRequest(HubPageSelector.DeepLink, pendingUrl, trustedBaseUrl, trustedApiDomain, trustedApiBasePath)
        assertEquals("opaque-token", request?.token)
        assertEquals("pending-123", request?.pendingVerificationId)
        assertEquals(null, nativeEmailVerificationRequest(HubPageSelector.ManageAccount, pendingUrl, trustedBaseUrl, trustedApiDomain, trustedApiBasePath))
        assertEquals(null, nativeEmailVerificationRequest(
            HubPageSelector.DeepLink,
            "https://hub.example.com.evil.test/account/verify-email?rowndPendingVerificationId=pending-123",
            trustedBaseUrl,
            trustedApiDomain,
            trustedApiBasePath,
        ))
        assertEquals(null, nativeEmailVerificationRequest(
            HubPageSelector.DeepLink,
            "$trustedBaseUrl/account/verify-email",
            trustedBaseUrl,
            trustedApiDomain,
            trustedApiBasePath,
        ))
    }

    @Test
    fun `verification requests reject duplicate or untrusted parameters`() {
        val trustedBaseUrl = "https://hub.example.com"
        val trustedApiDomain = "https://api.example.com"
        val trustedApiBasePath = "/auth"
        val poisonedUrls = listOf(
            "$trustedBaseUrl/account/verify-email?token=token&rowndPendingVerificationId=pending-123&apiDomain=https%3A%2F%2Fevil.example.com&apiBasePath=%2Fauth",
            "$trustedBaseUrl/account/verify-email?token=token&rowndPendingVerificationId=pending-123&apiDomain=https%3A%2F%2Fapi.example.com&apiBasePath=%2Fevil",
            "$trustedBaseUrl/account/verify-email?token=token&token=other&rowndPendingVerificationId=pending-123&apiDomain=https%3A%2F%2Fapi.example.com&apiBasePath=%2Fauth",
            "$trustedBaseUrl/account/verify-email?token=token&rowndPendingVerificationId=pending-123&apiDomain=https%3A%2F%2Fapi.example.com&apiDomain=https%3A%2F%2Fevil.example.com&apiBasePath=%2Fauth",
        )

        poisonedUrls.forEach { url ->
            assertEquals(null, nativeEmailVerificationRequest(
                HubPageSelector.DeepLink,
                url,
                trustedBaseUrl,
                trustedApiDomain,
                trustedApiBasePath,
            ))
        }
    }

    @Test
    fun `verification requires https except for localhost development`() {
        val query = "token=token&rowndPendingVerificationId=pending&apiDomain=http%3A%2F%2Flocalhost%3A3001&apiBasePath=%2Fauth"

        assertTrue(nativeEmailVerificationRequest(
            HubPageSelector.DeepLink,
            "http://localhost:3000/account/verify-email?$query",
            "http://localhost:3000",
            "http://localhost:3001",
            "/auth",
        ) != null)
        assertEquals(null, nativeEmailVerificationRequest(
            HubPageSelector.DeepLink,
            "http://hub.example.com/account/verify-email?$query",
            "http://hub.example.com",
            "http://localhost:3001",
            "/auth",
        ))
    }

    @Test
    fun `verification posts token through the native HTTP client`() = runBlocking {
        var requestMethod: HttpMethod? = null
        var requestUrl: String? = null
        var requestBody: String? = null
        val engine = MockEngine { request ->
            requestMethod = request.method
            requestUrl = request.url.toString()
            requestBody = request.body.toByteArray().decodeToString()
            respond(
                content = "{\"status\":\"OK\"}",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }

        val status = performNativeEmailVerification(
            client,
            NativeEmailVerificationRequest(
                token = "opaque-token",
                pendingVerificationId = "pending id",
                apiDomain = "https://api.example.com",
                apiBasePath = "/auth",
            ),
        )

        assertEquals("OK", status)
        assertEquals(HttpMethod.Post, requestMethod)
        assertEquals(
            "https://api.example.com/auth/user/email/verify?rowndPendingVerificationId=pending+id",
            requestUrl,
        )
        assertEquals("{\"method\":\"token\",\"token\":\"opaque-token\"}", requestBody)
        client.close()
    }

    @Test
    fun `web view urls are logged without credentials query or fragment`() {
        assertEquals(
            "https://hub.example.com/account/verify-email",
            urlForLogging(
                "https://user:password@hub.example.com/account/verify-email?token=secret#rph_init=session",
            ),
        )
    }
}
