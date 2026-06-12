package io.rownd.android

import io.rownd.android.models.AuthenticationMessage
import io.rownd.android.models.EventMessage
import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.util.RowndEventType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `hub sign in completed event deserializes with empty data`() {
        val payload = """
            {
              "type": "event",
              "payload": {
                "event": "sign_in_completed",
                "data": {}
              }
            }
        """.trimIndent()

        val message = authMessageJson.decodeFromString(RowndHubInteropMessage.serializer(), payload)
        val eventMessage = message as EventMessage

        assertEquals(RowndEventType.SignInCompleted, eventMessage.payload.event)
        assertTrue(eventMessage.payload.data.isEmpty())
    }

    @Test
    fun `unknown hub event deserializes without throwing`() {
        val payload = """
            {
              "type": "event",
              "payload": {
                "event": "post_authentication_api_request_complete",
                "data": {}
              }
            }
        """.trimIndent()

        val message = authMessageJson.decodeFromString(RowndHubInteropMessage.serializer(), payload)
        val eventMessage = message as EventMessage

        assertEquals(RowndEventType.Unknown, eventMessage.payload.event)
        assertTrue(eventMessage.payload.data.isEmpty())
    }

    @Test
    fun `hub event object data deserializes without throwing`() {
        val payload = """
            {
              "type": "event",
              "payload": {
                "event": "user_data",
                "data": {
                  "data": {
                    "id": "user-id"
                  }
                }
              }
            }
        """.trimIndent()

        val message = authMessageJson.decodeFromString(RowndHubInteropMessage.serializer(), payload)
        val eventMessage = message as EventMessage

        assertEquals(RowndEventType.UserData, eventMessage.payload.event)
        assertEquals("{\"id\":\"user-id\"}", eventMessage.payload.data["data"])
    }
}
