package io.rownd.android.models.domain

import android.content.Context
import com.auth0.android.jwt.JWT
import io.rownd.android.Rownd
import io.rownd.android.models.json
import io.rownd.android.models.repos.UserRepo
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.util.toBase64
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonNames

@Serializable
data class AuthState @OptIn(ExperimentalSerializationApi::class) constructor(
    val isLoading: Boolean = false,
    @SerialName("access_token")
    @JsonNames("accessToken")
    val accessToken: String? = null,
    @SerialName("refresh_token")
    @JsonNames("refreshToken")
    val refreshToken: String? = null,
    val isVerifiedUser: Boolean = false,
    val challengeId: String? = null,
    val userIdentifier: String? = null,

    @Transient
    val isAuthenticated: Boolean = accessToken != null
) {
    val isAccessTokenValid: Boolean
        get() {
            if (accessToken == null) {
                return false
            }

            val jwt = JWT(accessToken)

            if (isSuperTokensConfigured() && !isLikelySuperTokensToken(jwt) && isLegacyRowndAccessToken(jwt)) {
                return false
            }

            return !Rownd.authRepo.isJwtExpiredWithMargin(jwt)
        }

    private fun isSuperTokensConfigured(): Boolean {
        return Rownd.config.supertokens.appInfo.apiDomain.isNotBlank()
    }

    private fun isLikelySuperTokensToken(jwt: JWT): Boolean {
        return jwt.getClaim("sessionHandle").asString() != null ||
            jwt.getClaim("tId").asString() != null ||
            jwt.getClaim("refreshTokenHash1").asString() != null ||
            jwt.getClaim("parentRefreshTokenHash1").asString() != null
    }

    private fun isLegacyRowndAccessToken(jwt: JWT): Boolean {
        return jwt.getClaim("https://auth.rownd.io/app_user_id").asString() != null ||
            jwt.getClaim("app_user_id").asString() != null
    }

    internal fun toRphInitHash(userRepo: UserRepo, context: Context? = null): String? {
        val resolvedRefreshToken = refreshToken ?: context?.let { SuperTokensSessionBridge.getRefreshToken(it) }
        if (accessToken.isNullOrBlank() || resolvedRefreshToken.isNullOrBlank()) {
            return null
        }

        val userId: String? = userRepo.get("user_id") as? String

        val rphInit = RphInitObj(
            accessToken = accessToken,
            refreshToken = resolvedRefreshToken,
            frontToken = context?.let { SuperTokensSessionBridge.getFrontToken(it) },
            antiCSRF = context?.let { SuperTokensSessionBridge.getAntiCSRF(it) },
            appId = Rownd.store.currentState.appConfig.id,
            appUserId = userId,
        )

        val encoded = json.encodeToString(RphInitObj.serializer(), rphInit)
        return encoded.toByteArray().toBase64()
    }
}

@Serializable
data class RphInitObj(
    @SerialName("access_token")
    val accessToken: String?,
    @SerialName("refresh_token")
    val refreshToken: String?,
    @SerialName("front_token")
    val frontToken: String? = null,
    @SerialName("anti_csrf")
    val antiCSRF: String? = null,
    @SerialName("app_id")
    val appId: String?,
    @SerialName("app_user_id")
    val appUserId: String?
)
