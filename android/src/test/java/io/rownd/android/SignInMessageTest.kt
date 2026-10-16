package io.rownd.android

import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.models.SignInMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInMessageTest {
    @Test
    fun `only the email delta belongs in the native envelope`() {
        for (changes in listOf("""{"email":"destination@example.test","nickname":"already-saved"}""",
            """{"nickname":"already-saved"}""", """{"email":42}""")) {
            val message = """{"type":"sign_in","payload":{"was_user_initiated":true,"email_change":{"version":2,"request_id":"request-123456789","expires_at":1790000000000,"local_draft":{"owner_id":"owner","tenant_id":"public","configuration":"config","changes":$changes}}}}"""
            assertTrue(runCatching { Json { ignoreUnknownKeys = true }.decodeFromString(RowndHubInteropMessage.serializer(), message) }.isFailure)
        }
    }
    @Test
    fun `only explicit user intent authorizes sign in`() {
        val cases = listOf(
            "" to null,
            """, "payload": null""" to null,
            """, "payload": {}""" to null,
            """, "payload": {"was_user_initiated": null}""" to null,
            """, "payload": {"was_user_initiated": false}""" to false,
            """, "payload": {"was_user_initiated": true}""" to true,
        )
        for ((payload, expected) in cases) {
            val message = Json.decodeFromString(
                RowndHubInteropMessage.serializer(),
                """{"type":"sign_in"$payload}""",
            ) as SignInMessage
            assertEquals(expected, message.payload?.wasUserInitiated)
        }
    }

    @Test
    fun `string user intent is rejected`() {
        assertTrue(runCatching {
            Json.decodeFromString(
                RowndHubInteropMessage.serializer(),
                """{"type":"sign_in","payload":{"was_user_initiated":"true"}}""",
            )
        }.isFailure)
    }

    @Test
    fun `email continuation accepts only V2 local form fields with strict types`() {
        val descriptor = """{"version":2,"request_id":"request-123456789","local_draft":{"owner_id":"owner","tenant_id":"public","configuration":"config","changes":{"email":"destination@example.test"}},"expires_at":1790000000000}"""
        fun decode(value: String) = Json { ignoreUnknownKeys = true }.decodeFromString(
            RowndHubInteropMessage.serializer(),
            """{"type":"sign_in","payload":{"was_user_initiated":true,"email_change":$value}}""",
        ) as SignInMessage
        assertEquals("request-123456789", decode(descriptor).payload?.emailChange?.requestId)
        for (invalid in listOf(descriptor.replace("\"version\":2", "\"version\":\"2\""),
            descriptor.replace("\"request_id\":\"request-123456789\"", "\"request_id\":42"),
            descriptor.replace("destination@example.test", "a".repeat(33 * 1024)),
            descriptor.replace("1790000000000", "1790000000000.5"),
            """{"version":1,"request_id":"old-request-123456","draft_id":"old-draft","expires_at":1790000000000}""",
            descriptor.dropLast(1) + ",\"access_token\":\"credential\"}",
            descriptor.dropLast(1) + ",\"return_url\":\"https://evil.example\"}")) {
            assertTrue(runCatching { decode(invalid) }.isFailure)
        }
    }
}
