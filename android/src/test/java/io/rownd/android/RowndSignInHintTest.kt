package io.rownd.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RowndSignInHintTest {
    @Test
    fun `Apple hint serializes the apple Hub method`() {
        assertEquals(RowndSignInHint.Apple, enumValueOf<RowndSignInHint>("Apple"))

        val options = RowndSignInJsOptions(
            postSignInRedirect = null,
            signInType = RowndSignInType.Apple,
        )
        val method = Json.parseToJsonElement(options.toJsonString()).jsonObject["method"]?.jsonPrimitive?.content

        assertEquals("apple", method)
    }
}
