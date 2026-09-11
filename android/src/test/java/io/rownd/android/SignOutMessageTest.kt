package io.rownd.android

import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.models.SignOutMessage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SignOutMessageTest {
    @Test
    fun `sign out preserves explicit user intent and accepts older messages`() {
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
                """{"type":"sign_out"$payload}""",
            ) as SignOutMessage
            assertEquals(expected, message.payload?.wasUserInitiated)
        }
    }
}
