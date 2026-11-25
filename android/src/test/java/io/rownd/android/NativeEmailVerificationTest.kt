package io.rownd.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.rownd.android.models.VerifyEmailMessage
import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.util.NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
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
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

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
        var requestTimeoutMillis: Long? = null
        var socketTimeoutMillis: Long? = null
        val engine = MockEngine { request ->
            requestMethod = request.method
            requestUrl = request.url.toString()
            requestBody = request.body.toByteArray().decodeToString()
            requestTimeoutMillis = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
            socketTimeoutMillis = request.getCapabilityOrNull(HttpTimeoutCapability)?.socketTimeoutMillis
            respond(
                content = "{\"status\":\"OK\"}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "st-access-token" to listOf("replacement-access"),
                    "st-refresh-token" to listOf("replacement-refresh"),
                    "anti-csrf" to listOf(""),
                ),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }

        val result = performNativeEmailVerification(
            client,
            NativeEmailVerificationRequest(
                token = "opaque-token",
                pendingVerificationId = "pending id",
                apiDomain = "https://api.example.com",
                apiBasePath = "/auth",
            ),
        )

        assertEquals("OK", result.status)
        assertEquals("replacement-access", result.issuedAccessToken)
        assertEquals("replacement-refresh", result.issuedRefreshToken)
        assertEquals(null, result.issuedAntiCSRF)
        assertEquals(NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS, requestTimeoutMillis)
        assertEquals(NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS, socketTimeoutMillis)
        assertEquals(HttpMethod.Post, requestMethod)
        assertEquals(
            "https://api.example.com/auth/user/email/verify?rowndPendingVerificationId=pending+id",
            requestUrl,
        )
        assertEquals("{\"method\":\"token\",\"token\":\"opaque-token\"}", requestBody)
        client.close()
    }

    // The server commits the verification before it answers, which can take longer than the
    // engine's read timeout. The engine here times out after 250ms; the server answers after 1s.
    @Test
    fun `verification waits for a response slower than the engine read timeout`() = runBlocking {
        withSlowServer { url ->
            val client = slowEngineClient()
            try {
                val result = performNativeEmailVerification(client, NativeEmailVerificationRequest(
                    token = "opaque-token",
                    pendingVerificationId = "pending-123",
                    apiDomain = url,
                    apiBasePath = "/auth",
                ))
                assertEquals("OK", result.status)
                assertEquals("replacement-access", result.issuedAccessToken)
            } finally {
                client.close()
            }
        }
    }

    // Shows that the server above is slow enough to fail a request that keeps the engine timeout.
    @Test
    fun `a request without the verification timeout fails on the same slow response`() = runBlocking {
        withSlowServer { url ->
            val client = slowEngineClient()
            try {
                val error = runCatching { client.post("$url/auth/user/email/verify").bodyAsText() }.exceptionOrNull()
                assertTrue("Expected a read timeout, got $error", error is IOException)
            } finally {
                client.close()
            }
        }
    }

    private fun slowEngineClient() = HttpClient(OkHttp) {
        engine { config { readTimeout(250, TimeUnit.MILLISECONDS) } }
        install(HttpTimeout)
        install(ContentNegotiation) { json() }
    }

    // Answers every request after 1s with an OK verification and a replacement access token.
    private suspend fun withSlowServer(block: suspend (String) -> Unit) {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(::respondSlowly) }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            acceptor.join()
        }
    }

    private fun respondSlowly(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val contentLength = generateSequence { input.readLine() }
            .takeWhile { it.isNotEmpty() }
            .firstNotNullOfOrNull { line ->
                line.takeIf { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toInt()
            } ?: 0
        repeat(contentLength) { input.read() }
        Thread.sleep(1_000)
        val body = "{\"status\":\"OK\"}"
        runCatching {
            socket.getOutputStream().write((
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "st-access-token: replacement-access\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
                ).toByteArray())
        }
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
