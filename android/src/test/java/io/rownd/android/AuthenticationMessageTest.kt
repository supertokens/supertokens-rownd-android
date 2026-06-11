package io.rownd.android

import io.rownd.android.models.AuthenticationMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

private val authMessageJson = Json { ignoreUnknownKeys = true }

class AuthenticationMessageTest {

    @Test
    fun `AuthenticationMessage deserializes sign in user types`() {
        val payload = """
            {
              "type": "authentication",
              "payload": {
                "access_token": "access-token",
                "refresh_token": "refresh-token",
                "front_token": "front-token",
                "anti_csrf": "anti-csrf",
                "user_type": "new_user",
                "app_variant_user_type": "new_user"
              }
            }
        """.trimIndent()

        val message = authMessageJson.decodeFromString(AuthenticationMessage.serializer(), payload)

        assertEquals(RowndSignInUserType.NewUser, message.payload.userType)
        assertEquals(RowndSignInUserType.NewUser, message.payload.appVariantUserType)
    }
}
