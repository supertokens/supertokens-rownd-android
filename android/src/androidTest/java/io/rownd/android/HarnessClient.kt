package io.rownd.android

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for communicating with the SuperTokens integration test harness from
 * Android instrumentation tests. The harness runs on the host machine and is reachable
 * from the emulator at 10.0.2.2 (the loopback alias to the host).
 *
 * All methods are blocking — call them from a coroutine or background thread.
 */
object HarnessClient {

    val HARNESS_URL: String = InstrumentationRegistry.getArguments().getString("harnessUrl")
        ?: System.getenv("HARNESS_URL")
        ?: "http://10.0.2.2:3137"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class LegacySessionResponse(
        val access_token: String,
        val refresh_token: String,
        val app_id: String,
        val user_id: String,
    )

    @Serializable
    data class HarnessCounters(
        @SerialName("legacyRefresh") val legacyRefresh: Int,
        @SerialName("migrate") val migrate: Int,
        @SerialName("stRefresh") val stRefresh: Int,
    )

    @Serializable
    data class STSessionResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("user_id") val userId: String,
    )

    @Serializable
    data class PendingEmailVerificationResponse(
        val token: String,
        val pendingVerificationId: String,
        val userId: String,
    )

    @Serializable
    data class RequestCapture(
        val method: String,
        val path: String,
        val authorizationHeaderCount: Int,
        val hasAppKey: Boolean,
    )

    @Serializable
    data class MagicLinkCapture(
        val email: String? = null,
        val phoneNumber: String? = null,
        val urlWithLinkCode: String? = null,
        val userInputCode: String? = null,
    )

    /**
     * Configuration returned by the harness /config endpoint.
     * Call this once in @BeforeClass to configure the SDK for tests:
     *   - set Rownd.configure(baseUrl = config.hubUrl, apiUrl = config.androidUrl)
     */
    @Serializable
    data class HarnessConfig(
        /** Harness API URL reachable from the emulator (use as apiDomain for SuperTokens). */
        val androidUrl: String,
        /** Hub JS server URL reachable from the emulator (use as baseUrl in RowndConfig). */
        val hubUrl: String,
        val appId: String,
        val appKey: String,
    )

    fun getConfig(): HarnessConfig {
        val response = get("/config", "default")
        return json.decodeFromString(response)
    }

    fun reset(namespace: String = "default") {
        post("/reset", mapOf("namespace" to namespace), namespace)
    }

    fun createLegacySession(
        namespace: String = "default",
        userId: String = "legacy-user",
        expired: Boolean = true,
    ): LegacySessionResponse {
        val body: Map<String, Any> = mapOf(
            "namespace" to namespace,
            "userId" to userId,
            "expired" to expired,
        )
        val response = post("/test/legacy-session", body, namespace)
        return json.decodeFromString(response)
    }

    fun createSTSession(userId: String = "test-user"): STSessionResponse {
        val body: Map<String, Any> = mapOf("userId" to userId)
        val response = post("/test/st-session", body, "default")
        return json.decodeFromString(response)
    }

    fun createPendingEmailVerification(userId: String): PendingEmailVerificationResponse {
        val response = post(
            "/test/pending-email-verification",
            mapOf("userId" to userId),
            "default",
        )
        return json.decodeFromString(response)
    }

    fun getCounters(namespace: String = "default"): HarnessCounters {
        val response = get("/counters", namespace)
        return json.decodeFromString(response)
    }

    fun getLastRequest(path: String, namespace: String = "default"): RequestCapture {
        val encodedPath = java.net.URLEncoder.encode(path, "UTF-8")
        val response = get("/test/last-request?path=$encodedPath", namespace)
        return json.decodeFromString(response)
    }

    fun getCapture(email: String, namespace: String = "default"): MagicLinkCapture? {
        return try {
            val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
            val response = get("/captures/latest?email=$encodedEmail", namespace)
            json.decodeFromString(response)
        } catch (_: Exception) {
            null
        }
    }

    fun getCaptureByPhone(phoneNumber: String, namespace: String = "default"): MagicLinkCapture? {
        return try {
            val encoded = java.net.URLEncoder.encode(phoneNumber, "UTF-8")
            val response = get("/captures/latest?phoneNumber=$encoded", namespace)
            json.decodeFromString(response)
        } catch (_: Exception) {
            null
        }
    }

    private fun get(path: String, namespace: String): String {
        val url = URL("$HARNESS_URL$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-Rownd-E2E-Namespace", namespace)
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (status !in 200..299) {
            throw RuntimeException("Harness GET $path returned $status: $body")
        }

        return body
    }

    private fun post(path: String, body: Map<String, Any>, namespace: String): String {
        val url = URL("$HARNESS_URL$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Rownd-E2E-Namespace", namespace)
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        // Use a minimal JSON serialization for map values that are String, Int, or Boolean
        val jsonBody = buildString {
            append('{')
            body.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(',')
                append('"').append(k).append('"').append(':')
                when (v) {
                    is String -> append('"').append(v.replace("\"", "\\\"")).append('"')
                    is Boolean -> append(v.toString())
                    is Number -> append(v.toString())
                    else -> append('"').append(v.toString()).append('"')
                }
            }
            append('}')
        }

        OutputStreamWriter(conn.outputStream).use { it.write(jsonBody) }

        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val responseBody = BufferedReader(InputStreamReader(stream)).use { it.readText() }

        if (status !in 200..299) {
            throw RuntimeException("Harness POST $path returned $status: $responseBody")
        }

        return responseBody
    }
}
