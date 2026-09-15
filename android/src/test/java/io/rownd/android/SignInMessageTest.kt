package io.rownd.android

import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.models.SignInMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInMessageTest {
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
}
