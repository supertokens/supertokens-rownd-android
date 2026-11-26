package io.rownd.android

import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperTokensSessionBridgeTest {

    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun buildJwt(sub: String, exp: Long): String {
        val header = b64.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = b64.encodeToString("""{"sub":"$sub","exp":$exp}""".toByteArray())
        return "$header.$payload.fakesig"
    }

    @Test
    fun `buildFrontToken extracts uid and ate from a valid JWT`() {
        val exp = 9999999999L
        val jwt = buildJwt("user-123", exp)

        val frontToken = SuperTokensSessionBridge.buildFrontToken(jwt)

        val decoded = String(Base64.getDecoder().decode(frontToken))
        val json = Json.parseToJsonElement(decoded).jsonObject

        assertEquals("user-123", json["uid"]!!.jsonPrimitive.content)
        assertEquals(exp * 1000, json["ate"]!!.jsonPrimitive.long)
        assertTrue(json.containsKey("up"))
    }

    @Test
    fun `buildFrontToken returns safe defaults for a malformed JWT`() {
        val frontToken = SuperTokensSessionBridge.buildFrontToken("not.a.jwt")

        val decoded = String(Base64.getDecoder().decode(frontToken))
        val json = Json.parseToJsonElement(decoded).jsonObject

        assertEquals("", json["uid"]!!.jsonPrimitive.content)
        assertEquals(0L, json["ate"]!!.jsonPrimitive.long)
        assertTrue(json.containsKey("up"))
    }

    @Test
    fun `buildFrontToken returns safe defaults for an empty string`() {
        val frontToken = SuperTokensSessionBridge.buildFrontToken("")

        val decoded = String(Base64.getDecoder().decode(frontToken))
        val json = Json.parseToJsonElement(decoded).jsonObject

        assertEquals("", json["uid"]!!.jsonPrimitive.content)
        assertEquals(0L, json["ate"]!!.jsonPrimitive.long)
    }

    @Test
    fun `buildFrontToken uses userId field when sub is absent`() {
        val header = b64.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val payload = b64.encodeToString("""{"userId":"fallback-id","exp":1000}""".toByteArray())
        val jwt = "$header.$payload.sig"

        val frontToken = SuperTokensSessionBridge.buildFrontToken(jwt)
        val decoded = String(Base64.getDecoder().decode(frontToken))
        val json = Json.parseToJsonElement(decoded).jsonObject

        assertEquals("fallback-id", json["uid"]!!.jsonPrimitive.content)
    }

    private fun sessionJwt(sub: String, handle: String?, tenant: String = "public"): String {
        val header = b64.encodeToString("""{"alg":"HS256"}""".toByteArray())
        val handleClaim = handle?.let { ""","sessionHandle":"$it"""" } ?: ""
        val payload = b64.encodeToString("""{"sub":"$sub","tId":"$tenant"$handleClaim}""".toByteArray())
        return "$header.$payload.sig"
    }

    @Test
    fun `a replacement session of the same user is recognized only across distinct sessions`() {
        val original = sessionJwt("user-a", "s0")

        assertTrue(SuperTokensSessionBridge.isOtherSessionOfSameUser(original, sessionJwt("user-a", "s1")))
        assertFalse(SuperTokensSessionBridge.isOtherSessionOfSameUser(original, sessionJwt("user-a", "s0")))
        assertFalse(SuperTokensSessionBridge.isOtherSessionOfSameUser(original, sessionJwt("user-b", "s1")))
        assertFalse(SuperTokensSessionBridge.isOtherSessionOfSameUser(original, sessionJwt("user-a", "s1", tenant = "other")))
        assertFalse(SuperTokensSessionBridge.isOtherSessionOfSameUser(sessionJwt("user-a", null), sessionJwt("user-a", "s1")))
        assertFalse(SuperTokensSessionBridge.isOtherSessionOfSameUser(original, "legacy-token"))
    }
}
