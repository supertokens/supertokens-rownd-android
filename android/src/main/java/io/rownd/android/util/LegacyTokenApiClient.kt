package io.rownd.android.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.rownd.android.models.network.TokenRequestBody
import io.rownd.android.models.network.TokenResponse
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Used only for POST /hub/auth/token on api.rownd.io during startup legacy session migration.
// Must not have SuperTokensInterceptor registered — it sends a legacy Rownd access token, not ST headers.
// Remove this class once the migration window closes.
@Singleton
class LegacyTokenApiClient @Inject constructor(
    rowndContext: RowndContext
) {
    internal var baseUrl = "https://api.rownd.io"

    val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }

        expectSuccess = true

        defaultRequest {
            contentType(ContentType.Application.Json)
            headers {
                rowndContext.config.appKey?.let { this.append("x-rownd-app-key", it) }
            }
        }
    }

    suspend fun refreshLegacyToken(refreshToken: String): TokenResponse {
        return client.post("$baseUrl/hub/auth/token") {
            setBody(TokenRequestBody(refreshToken = refreshToken))
        }.body()
    }
}
