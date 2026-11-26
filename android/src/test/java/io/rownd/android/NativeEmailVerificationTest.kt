package io.rownd.android

import com.supertokens.session.SuperTokensInterceptor
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestRetry
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
import io.rownd.android.util.IssuedVerificationSession
import io.rownd.android.util.NativeEmailVerificationApiClient
import io.rownd.android.util.NATIVE_EMAIL_VERIFICATION_TIMEOUT_MILLIS
import io.rownd.android.util.NativeEmailVerificationRejectedException
import io.rownd.android.util.NativeEmailVerificationRequest
import io.rownd.android.util.SuperTokensSessionBridge.SessionTokens
import io.rownd.android.util.VerificationRefreshResult
import io.rownd.android.util.VerificationSessionRevocationException
import io.rownd.android.util.revokeIssuedVerificationSession
import io.rownd.android.util.nativeEmailVerificationRequest
import io.rownd.android.util.performNativeEmailVerification
import io.rownd.android.util.probeVerificationSession
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.urlForLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class NativeEmailVerificationTest {
    private val captured = SessionTokens("captured-access", "captured-refresh", "captured-front", "captured-csrf")
    private val issued = IssuedVerificationSession("issued-access", "issued-refresh", "issued-front", null)
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
                    "front-token" to listOf("replacement-front"),
                    "anti-csrf" to listOf("replacement-csrf"),
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
            captured,
        )

        assertEquals("OK", result.status)
        assertEquals(
            SessionTokens("replacement-access", "replacement-refresh", "replacement-front", "replacement-csrf"),
            result.issuedSession?.completeTokens(),
        )
        assertTrue("Results must not print tokens", !result.toString().contains("replacement-access"))
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

    @Test
    fun `a rejected verification reports the session it issued without printing it`() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "{\"status\":\"GENERAL_ERROR\",\"message\":\"failed\"}",
                status = HttpStatusCode.OK,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json"),
                    "st-access-token" to listOf("restored-access"),
                ),
            )
        }
        val client = HttpClient(engine) { install(ContentNegotiation) { json() } }

        val error = runCatching { performNativeEmailVerification(client, verificationRequest(), captured) }.exceptionOrNull()

        assertTrue(error is NativeEmailVerificationRejectedException)
        assertEquals("restored-access", (error as NativeEmailVerificationRejectedException).issuedSession?.accessToken)
        assertTrue(!error.toString().contains("restored-access"))
        client.close()
    }

    @Test
    fun `an unadopted session is revoked with its own token and no other credentials`() = runBlocking {
        var requestUrl: String? = null
        var requestMethod: HttpMethod? = null
        var requestHeaders: Map<String, List<String>> = emptyMap()
        val engine = MockEngine { request ->
            requestUrl = request.url.toString()
            requestMethod = request.method
            requestHeaders = request.headers.entries().associate { it.key.lowercase() to it.value }
            respond(content = "{\"status\":\"OK\"}", status = HttpStatusCode.OK)
        }
        val client = HttpClient(engine)

        revokeIssuedVerificationSession(client, verificationRequest(), issued)

        assertEquals("https://api.example.com/auth/signout", requestUrl)
        assertEquals(HttpMethod.Post, requestMethod)
        assertEquals(listOf("Bearer issued-access"), requestHeaders["authorization"])
        assertEquals(listOf("header"), requestHeaders["st-auth-mode"])
        assertEquals(listOf("session"), requestHeaders["rid"])
        listOf("st-access-token", "st-refresh-token", "front-token", "anti-csrf", "x-rownd-app-key").forEach {
            assertNull("Unexpected $it header", requestHeaders[it])
        }
        client.close()
    }

    @Test
    fun `a failed revocation reports its status without the token`() = runBlocking {
        var attempts = 0
        // Production clients retry server errors; revocation must not.
        val client = HttpClient(MockEngine {
            attempts++
            respond(content = "", status = HttpStatusCode.InternalServerError)
        }) {
            install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 3) }
        }

        val error = runCatching {
            revokeIssuedVerificationSession(client, verificationRequest(), issued)
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("HTTP 500"))
        assertTrue(!error.toString().contains("issued-access"))
        assertEquals(1, attempts)
        client.close()
    }

    private fun verificationRequest() = NativeEmailVerificationRequest(
        token = "opaque-token",
        pendingVerificationId = "pending-123",
        apiDomain = "https://api.example.com",
        apiBasePath = "/auth",
    )

    // The server commits the verification before it answers, which can take longer than the
    // engine's read timeout. The engine here times out after 250ms; the server answers after 1s.
    @Test
    fun `verification waits for a response slower than the engine read timeout`() = runBlocking {
        withLoopbackServer(::respondSlowly) { url ->
            val client = slowEngineClient()
            try {
                val result = performNativeEmailVerification(client, NativeEmailVerificationRequest(
                    token = "opaque-token",
                    pendingVerificationId = "pending-123",
                    apiDomain = url,
                    apiBasePath = "/auth",
                ), captured)
                assertEquals("OK", result.status)
                assertEquals("replacement-access", result.issuedSession?.accessToken)
            } finally {
                client.close()
            }
        }
    }

    // Shows that the server above is slow enough to fail a request that keeps the engine timeout.
    @Test
    fun `a request without the verification timeout fails on the same slow response`() = runBlocking {
        withLoopbackServer(::respondSlowly) { url ->
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

    @Test
    fun `production verification and revocation do not reuse an idle connection`() = runBlocking {
        val connections = AtomicInteger()
        val calls = AtomicInteger()
        withLoopbackServer({ socket ->
            connections.incrementAndGet()
            val input = socket.getInputStream().bufferedReader()
            while (input.readLine() != null) {
                readRequestHeaders(input)
                calls.incrementAndGet()
                socket.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"status\":\"OK\"}".toByteArray()
                )
            }
        }) { endpoint ->
            val okHttp = NativeEmailVerificationApiClient.createOkHttpClient()
            val engine = NativeEmailVerificationApiClient.createEngine(okHttp)
            val client = HttpClient(engine) {
                followRedirects = false
                install(HttpTimeout)
                install(ContentNegotiation) { json() }
            }
            try {
                val request = verificationRequest().copy(apiDomain = endpoint)
                assertEquals("OK", performNativeEmailVerification(client, request, captured).status)
                revokeIssuedVerificationSession(client, request, issued)
                assertEquals(2, calls.get())
                assertEquals("An idle verification socket must not be reused for revocation", 2, connections.get())
                assertEquals(0, okHttp.connectionPool.idleConnectionCount())
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test
    fun `production verification OkHttp client does not intercept retry or follow redirects`() {
        val client = NativeEmailVerificationApiClient.createOkHttpClient()
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse((client.interceptors + client.networkInterceptors).any { it is SuperTokensInterceptor })
    }

    @Test
    fun `closing the verification transport closes its client and engine only`() = runBlocking {
        val owner = verificationTransport()
        val other = verificationTransport()
        val client = owner.client
        try {
            owner.close()
            owner.close()
            withTimeout(5_000) {
                client.coroutineContext.job.join()
                client.engine.coroutineContext.job.join()
            }
            assertTrue(other.client.coroutineContext.job.isActive)
            assertTrue(other.client.engine.coroutineContext.job.isActive)
        } finally {
            other.close()
        }
    }

    @Test
    fun `closing the verification transport evicts its idle OkHttp connection`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getLoopbackAddress(), 0)
            server.enqueue(MockResponse().setResponseCode(200))
            val pool = ConnectionPool()
            val okHttp = NativeEmailVerificationApiClient.createOkHttpClient().newBuilder()
                .connectionPool(pool)
                .build()
            val url = server.url("/").toString().trimEnd('/')
            val transport = verificationTransport(NativeEmailVerificationApiClient.createEngine(okHttp))
            try {
                revokeIssuedVerificationSession(transport.client, verificationRequest().copy(apiDomain = url), issued)
                assertEquals(1, pool.idleConnectionCount())
            } finally {
                transport.close()
            }
            withTimeout(5_000) {
                while (pool.connectionCount() != 0) delay(10)
            }
        }
    }

    @Test
    fun `verification and revocation do not replay after a 503 with immediate retry requested`() = runBlocking {
        assertNoOkHttpReplay(503)
    }

    @Test
    fun `verification and revocation do not replay after a 408 even with OkHttp retries enabled`() = runBlocking {
        assertNoOkHttpReplay(408)
    }

    private suspend fun assertNoOkHttpReplay(status: Int) {
        for (verify in listOf(true, false)) {
            MockWebServer().use { server ->
                server.start(InetAddress.getLoopbackAddress(), 0)
                server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"status":"OK"}"""))
                val url = server.url("/").toString().trimEnd('/')
                val okHttp = NativeEmailVerificationApiClient.createOkHttpClient().newBuilder()
                    .retryOnConnectionFailure(status == 408)
                    .build()
                val engine = NativeEmailVerificationApiClient.createEngine(okHttp)
                val transport = verificationTransport(engine)
                val request = verificationRequest().copy(apiDomain = url)
                try {
                    val error = runCatching {
                        if (verify) performNativeEmailVerification(transport.client, request, captured)
                        else revokeIssuedVerificationSession(transport.client, request, issued)
                    }.exceptionOrNull()
                    val path = if (verify) "/auth/user/email/verify?rowndPendingVerificationId=pending-123" else "/auth/signout"
                    assertEquals("Unexpected request count for $path after HTTP $status", 1, server.requestCount)
                    val received = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("POST", received.method)
                    assertEquals(path, received.path)
                    if (verify) {
                        assertEquals("Email verification failed with HTTP $status", error?.message)
                        assertEquals("""{"method":"token","token":"opaque-token"}""",
                            Json.parseToJsonElement(received.body.readUtf8()).toString())
                    } else {
                        assertEquals(status, (error as VerificationSessionRevocationException).statusCode)
                        assertEquals(0L, received.bodySize)
                    }
                } finally {
                    transport.close()
                }
            }
        }
    }

    private fun verificationTransport(engine: HttpClientEngine = NativeEmailVerificationApiClient.createEngine()) =
        NativeEmailVerificationApiClient(engine, HttpClient(engine) {
            followRedirects = false
            install(HttpTimeout)
            install(ContentNegotiation) { json() }
            install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 3) }
        })

    @Test
    fun `verification does not use Ktor retries even when the client has them enabled`() = runBlocking {
        var attempts = 0
        val client = HttpClient(MockEngine {
            attempts++
            respond("", HttpStatusCode.ServiceUnavailable)
        }) {
            install(ContentNegotiation) { json() }
            install(HttpRequestRetry) { retryOnServerErrors(maxRetries = 3) }
        }
        try {
            val error = runCatching { performNativeEmailVerification(client, verificationRequest(), captured) }.exceptionOrNull()
            assertEquals("Email verification failed with HTTP 503", error?.message)
            assertEquals(1, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun `production verification transport sends captured headers without SuperTokens interceptor or redirects`() = runBlocking {
        val calls = AtomicInteger()
        val received = AtomicReference<Map<String, String>>()
        withLoopbackServer({ socket ->
            calls.incrementAndGet()
            received.set(readRequestHeaders(socket))
            socket.getOutputStream().write(
                "HTTP/1.1 307 Temporary Redirect\r\nLocation: /redirected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
            )
        }) { endpoint ->
            // SuperTokensInterceptor refuses calls without initialized Android SDK context.
            val engine = NativeEmailVerificationApiClient.createEngine()
            val client = HttpClient(engine) {
                followRedirects = false
                install(HttpTimeout)
                install(ContentNegotiation) { json() }
            }
            try {
                val error = runCatching {
                    performNativeEmailVerification(client, verificationRequest().copy(apiDomain = endpoint), captured)
                }.exceptionOrNull()
                assertEquals("Email verification failed with HTTP 307", error?.message)
                assertEquals(1, calls.get())
                val headers = received.get()
                assertEquals("Bearer captured-access", headers["authorization"])
                assertEquals("header", headers["st-auth-mode"])
                assertEquals("emailverification", headers["rid"])
                assertEquals("captured-csrf", headers["anti-csrf"])
                assertNull(headers["cookie"])
            } finally {
                client.close()
                engine.close()
            }
        }
    }

    @Test
    fun `isolated recovery probes S0 refresh and only a 401 with front-token remove confirms revocation`() = runBlocking {
        for ((status, frontToken, expected) in listOf(
            Triple(HttpStatusCode.Unauthorized, "remove", VerificationRefreshResult.Revoked),
            Triple(HttpStatusCode.Unauthorized, "", VerificationRefreshResult.Unknown),
            Triple(HttpStatusCode.InternalServerError, "remove", VerificationRefreshResult.Unknown),
        )) {
            val client = HttpClient(MockEngine { request ->
                assertEquals("https://api.example.com/auth/session/refresh", request.url.toString())
                assertEquals("Bearer captured-refresh", request.headers[HttpHeaders.Authorization])
                assertEquals("captured-csrf", request.headers["anti-csrf"])
                assertEquals("session", request.headers["rid"])
                respond("", status, headersOf("front-token", frontToken))
            })
            try {
                assertEquals(expected, probeVerificationSession(client, verificationRequest(), captured))
            } finally {
                client.close()
            }
        }
    }

    private suspend fun withLoopbackServer(respond: (Socket) -> Unit, block: suspend (String) -> Unit) {
        val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val acceptor = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { socket.use(respond) }
            }
        }
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            acceptor.join()
        }
    }

    private fun readRequestHeaders(socket: Socket): Map<String, String> {
        val input = socket.getInputStream().bufferedReader()
        input.readLine()
        return readRequestHeaders(input)
    }

    private fun readRequestHeaders(input: BufferedReader): Map<String, String> {
        val headers = generateSequence { input.readLine() }
            .takeWhile { it.isNotEmpty() }
            .associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        repeat(headers["content-length"]?.toInt() ?: 0) { input.read() }
        return headers
    }

    private fun respondSlowly(socket: Socket) {
        readRequestHeaders(socket)
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
