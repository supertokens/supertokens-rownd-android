package io.rownd.android

import io.rownd.android.RowndSignInUserType
import io.rownd.android.models.network.SignInUpResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

class SignInUpResponseTest {

    @Test
    fun `SignInUpResponse deserializes with rownd user_type new_user`() {
        val payload = """
            {
              "status": "OK",
              "rownd": {
                "user_type": "new_user",
                "app_id": "app_123",
                "app_user_id": "user_456"
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(SignInUpResponse.serializer(), payload)

        assertEquals("OK", response.status)
        assertEquals(RowndSignInUserType.NewUser, response.rownd?.userType)
        assertEquals("app_123", response.rownd?.appId)
        assertEquals("user_456", response.rownd?.appUserId)
    }

    @Test
    fun `SignInUpResponse deserializes when rownd field is absent`() {
        val payload = """{"status": "OK"}"""

        val response = json.decodeFromString(SignInUpResponse.serializer(), payload)

        assertEquals("OK", response.status)
        assertNull(response.rownd)
    }
}
