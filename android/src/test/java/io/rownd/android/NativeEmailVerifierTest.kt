package io.rownd.android

import io.rownd.android.util.IssuedVerificationSession
import io.rownd.android.util.NativeEmailVerificationRejectedException
import io.rownd.android.util.NativeEmailVerificationRequest
import io.rownd.android.util.NativeEmailVerificationResult
import io.rownd.android.util.NativeEmailVerificationSession
import io.rownd.android.util.NativeEmailVerificationSupersededException
import io.rownd.android.util.NativeEmailVerifier
import io.rownd.android.util.SuperTokensSessionBridge.SessionIdentity
import io.rownd.android.util.SuperTokensSessionBridge.SessionTokens
import io.rownd.android.util.SuperTokensSessionBridge.VerificationOwner
import io.rownd.android.util.VerificationRefreshResult
import io.rownd.android.util.VerificationSessionRevocationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class NativeEmailVerifierTest {
    private val request = NativeEmailVerificationRequest("opaque-token", "pending-123", "https://api.example.com", "/auth")
    private val s0 = tokens("a", "s0")
    private val s1 = tokens("a", "s1")
    private val b = tokens("b", "b0")

    private inner class FakeSession : NativeEmailVerificationSession {
        var native: SessionTokens? = s0
        var store: String? = s0.accessToken
        var signOuts = 0L
        var signIns = 0L
        var endpoint = request.apiDomain
        var captured: VerificationOwner? = null
        val sends = mutableListOf<Pair<NativeEmailVerificationRequest, SessionTokens>>()
        val revoked = mutableListOf<Pair<NativeEmailVerificationRequest, String>>()
        val profileLoads = mutableListOf<String>()
        var response: suspend () -> NativeEmailVerificationResult = { NativeEmailVerificationResult("OK", s1.issued()) }
        var probeResponse: suspend () -> VerificationRefreshResult = { VerificationRefreshResult.Unknown }
        var revokeError: Exception? = null
        var probes = 0

        fun signIn(tokens: SessionTokens) {
            signIns++
            native = tokens
            store = tokens.accessToken
        }

        override fun captureOwner(): VerificationOwner? = native?.let {
            val identity = if (it == b) SessionIdentity("b0", "b", "public") else SessionIdentity("s0", "a", "public")
            VerificationOwner(it, identity, signOuts, signIns, store).also { owner -> captured = owner }
        }

        override fun owns(owner: VerificationOwner) =
            signOuts == owner.signOutGeneration && signIns == owner.signInGeneration &&
                (native == null || native?.refreshToken == owner.tokens.refreshToken)

        override suspend fun verify(request: NativeEmailVerificationRequest, tokens: SessionTokens): NativeEmailVerificationResult {
            sends.add(request to tokens)
            return response()
        }

        override fun install(owner: VerificationOwner, tokens: SessionTokens, refreshedFrom: SessionTokens?): Boolean {
            if (!owns(owner) || (refreshedFrom != null && refreshedFrom != native)) return false
            native = tokens
            store = tokens.accessToken
            return true
        }

        override fun reloadProfile(owner: VerificationOwner, accessToken: String) {
            profileLoads.add(accessToken)
        }

        override suspend fun revoke(request: NativeEmailVerificationRequest, issued: IssuedVerificationSession) {
            revoked.add(request to issued.accessToken)
            revokeError?.let { throw it }
        }

        override fun recoveryTokens(owner: VerificationOwner) = native?.takeIf { owns(owner) }

        override suspend fun probe(request: NativeEmailVerificationRequest, tokens: SessionTokens): VerificationRefreshResult {
            probes++
            return probeResponse()
        }

        override fun clearRevoked(owner: VerificationOwner, probedTokens: SessionTokens?): Boolean {
            if (!owns(owner) || (native != null && probedTokens != native)) return false
            native = null
            store = null
            signOuts++
            return true
        }
    }

    @Test
    fun `verification uses S0 even if B commits before send`() = runTest {
        val session = FakeSession()
        val verifier = NativeEmailVerifier(backgroundScope, warn = {})
        val result = verifier.verify(request, session)
        session.signIn(b)
        runCurrent()

        assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
        assertEquals(listOf(request to s0), session.sends)
        assertEquals(b, session.native)
        assertEquals(b.accessToken, session.store)
        assertEquals(listOf(request to s1.accessToken), session.revoked)
        assertTrue(session.profileLoads.isEmpty())
    }

    @Test
    fun `failed revocation at the captured endpoint is logged without credentials and preserves B`() = runTest {
        val release = CompletableDeferred<Unit>()
        val session = FakeSession().apply {
            response = { release.await(); NativeEmailVerificationResult("OK", s1.issued()) }
            revokeError = VerificationSessionRevocationException(503)
        }
        val warnings = mutableListOf<String>()
        val result = NativeEmailVerifier(backgroundScope, warnings::add).verify(request.copy(apiDomain = session.endpoint), session)
        runCurrent()
        session.signIn(b)
        session.endpoint = "https://new.example.com"
        release.complete(Unit)
        runCurrent()

        assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
        assertEquals(b, session.native)
        assertEquals(b.accessToken, session.store)
        assertEquals(listOf(request to s1.accessToken), session.revoked)
        assertTrue(session.profileLoads.isEmpty())
        assertEquals(0, session.probes)
        assertEquals(listOf("Could not revoke the unadopted email verification session: HTTP 503"), warnings)
    }

    @Test
    fun `cold start legacy hydration does not supersede verification`() = runTest {
        val session = FakeSession().apply {
            store = null
            response = {
                store = "legacy"
                NativeEmailVerificationResult("OK", s1.issued())
            }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()

        assertNull(session.captured!!.storeAccessToken)
        assertEquals("OK", result.await())
        assertEquals(s1, session.native)
        assertEquals(s1.accessToken, session.store)
        assertEquals(listOf(s1.accessToken), session.profileLoads)
        assertTrue(session.revoked.isEmpty())
    }

    @Test
    fun `SDK clearing S0 still allows S1 unless a sign-in committed`() = runTest {
        for (newSignIn in listOf(false, true)) {
            val session = FakeSession().apply {
                response = {
                    if (newSignIn) signIn(b)
                    native = null
                    NativeEmailVerificationResult("OK", s1.issued())
                }
            }
            val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
            runCurrent()

            if (newSignIn) {
                assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
                assertEquals(listOf(request to s1.accessToken), session.revoked)
            } else {
                assertEquals("OK", result.await())
                assertEquals(s1, session.native)
            }
        }
    }

    @Test
    fun `sign out while verification runs does not resurrect the session`() = runTest {
        val session = FakeSession().apply {
            response = {
                signOuts++
                native = null
                store = null
                NativeEmailVerificationResult("OK", s1.issued())
            }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
        assertNull(session.native)
        assertEquals(listOf(request to s1.accessToken), session.revoked)
    }

    @Test
    fun `identical requests from one owner join and outlive a cancelled waiter but other owners do not join`() = runTest {
        val release = CompletableDeferred<Unit>()
        val session = FakeSession().apply {
            response = { release.await(); NativeEmailVerificationResult("OK", s1.issued()) }
        }
        val verifier = NativeEmailVerifier(backgroundScope, warn = {})
        val original = verifier.verify(request, session)
        val waiter = launch { original.await() }
        runCurrent()
        waiter.cancel()
        assertSame(original, verifier.verify(request, session))
        val otherOwner = FakeSession().apply {
            signIn(b)
            response = { release.await(); NativeEmailVerificationResult("OK", tokens("b", "b1").issued()) }
        }
        val other = verifier.verify(request, otherOwner)
        val otherEndpoint = verifier.verify(request.copy(apiBasePath = "/other"), session)
        assertNotSame(original, other)
        assertNotSame(original, otherEndpoint)
        runCurrent()
        assertEquals(listOf(s0, s0), session.sends.map { it.second })
        assertEquals(listOf(b), otherOwner.sends.map { it.second })
        release.complete(Unit)
        runCurrent()
        assertEquals("OK", original.await())
        assertEquals("OK", other.await())
    }

    @Test
    fun `reinstalling S0 does not join a flight from an earlier auth generation`() = runTest {
        for (signOut in listOf(false, true)) {
            val release = CompletableDeferred<Unit>()
            val session = FakeSession().apply {
                response = { release.await(); NativeEmailVerificationResult("OK", s1.issued()) }
            }
            val verifier = NativeEmailVerifier(backgroundScope, warn = {})
            val original = verifier.verify(request, session)
            runCurrent()

            if (signOut) session.signOuts++ else session.signIn(s0)
            val s2 = tokens("a", "s2")
            session.response = { NativeEmailVerificationResult("OK", s2.issued()) }
            val current = verifier.verify(request, session)
            assertNotSame(original, current)
            assertSame(current, verifier.verify(request, session))
            runCurrent()
            assertEquals(listOf(request to s0, request to s0), session.sends)
            assertEquals("OK", current.await())
            assertFalse(original.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertTrue(runCatching { original.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
            assertEquals(s2, session.native)
            assertEquals(listOf(request to s1.accessToken), session.revoked)
        }
    }

    @Test
    fun `stalled recovery releases the in-flight request without clearing S0`() = runTest {
        var probeCancelled = false
        val session = FakeSession().apply {
            response = { throw IOException("verify response lost") }
            probeResponse = {
                try {
                    awaitCancellation()
                } finally {
                    probeCancelled = true
                }
            }
        }
        val verifier = NativeEmailVerifier(backgroundScope, warn = {}, recoveryTimeoutMillis = 100)
        val failed = verifier.verify(request, session)
        runCurrent()
        assertTrue(runCatching { failed.await() }.exceptionOrNull() is IOException)
        assertSame(failed, verifier.verify(request, session))

        advanceTimeBy(100)
        runCurrent()
        assertTrue(probeCancelled)
        assertEquals(s0, session.native)
        assertEquals(s0.accessToken, session.store)
        session.response = { NativeEmailVerificationResult("OK", s1.issued()) }
        val retry = verifier.verify(request, session)
        assertNotSame(failed, retry)
        runCurrent()
        assertEquals("OK", retry.await())
    }

    @Test
    fun `recovery clears only confirmed revocations and never clears B`() = runTest {
        for (probe in listOf(VerificationRefreshResult.Unknown, VerificationRefreshResult.Revoked)) {
            for (signInDuringProbe in listOf(false, true)) {
                val session = FakeSession().apply {
                    response = { throw IOException("verify response lost") }
                    probeResponse = {
                        if (signInDuringProbe) signIn(b)
                        probe
                    }
                }
                NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
                runCurrent()
                assertEquals(1, session.probes)
                val expected = when {
                    signInDuringProbe -> b
                    probe == VerificationRefreshResult.Revoked -> null
                    else -> s0
                }
                assertEquals(expected, session.native)
                assertEquals(expected?.accessToken, session.store)
            }
        }
    }

    @Test
    fun `an error response with a complete S1 reports failure but still adopts S1`() = runTest {
        val session = FakeSession().apply {
            response = { throw NativeEmailVerificationRejectedException("Request failed", s1.issued()) }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationRejectedException)
        assertEquals(s1, session.native)
        assertEquals(s1.accessToken, session.store)
        assertEquals(listOf(s1.accessToken), session.profileLoads)
        assertEquals(0, session.probes)
    }

    @Test
    fun `incomplete replacement is revoked without installing it`() = runTest {
        val session = FakeSession().apply {
            response = {
                NativeEmailVerificationResult("OK", IssuedVerificationSession(s1.accessToken, null, null, null))
            }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.isFailure)
        assertEquals(s0, session.native)
        assertEquals(listOf(request to s1.accessToken), session.revoked)
        assertTrue(session.profileLoads.isEmpty())
    }

    @Test
    fun `a successful recovery probe can rotate S0 without changing session ownership`() = runTest {
        val refreshed = s0.copy(accessToken = s0.accessToken.substringBeforeLast('.') + ".rotated", refreshToken = "rotated-refresh")
        val session = FakeSession().apply {
            response = { throw IOException("verify response lost") }
            probeResponse = { VerificationRefreshResult.Refreshed(refreshed) }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.exceptionOrNull() is IOException)
        assertEquals(refreshed, session.native)
        assertEquals(refreshed.accessToken, session.store)
    }

    @Test
    fun `a verification without an SDK owner does not send or probe`() = runTest {
        val session = FakeSession().apply { native = null }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        assertTrue(runCatching { result.await() }.isFailure)
        assertTrue(session.sends.isEmpty())
        assertEquals(0, session.probes)
    }

    @Test
    fun `an error response after sign-out can carry an unowned S1 to revoke`() = runTest {
        val session = FakeSession().apply {
            response = {
                signOuts++
                native = null
                store = null
                throw NativeEmailVerificationRejectedException("Request failed", s1.issued())
            }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.exceptionOrNull() is NativeEmailVerificationSupersededException)
        assertEquals(listOf(request to s1.accessToken), session.revoked)
        assertNull(session.native)
        assertEquals(0, session.probes)
    }

    @Test
    fun `an unrelated session in a response is neither installed nor revoked as S1`() = runTest {
        val session = FakeSession().apply {
            response = { NativeEmailVerificationResult("OK", b.issued()) }
        }
        val result = NativeEmailVerifier(backgroundScope, warn = {}).verify(request, session)
        runCurrent()
        assertTrue(runCatching { result.await() }.isFailure)
        assertEquals(s0, session.native)
        assertTrue(session.revoked.isEmpty())
    }

    private fun tokens(user: String, handle: String): SessionTokens {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sub":"$user","sessionHandle":"$handle","tId":"public"}""".toByteArray()
        )
        return SessionTokens("e30.$payload.signature", "refresh-$handle", "front-$handle", "csrf-$handle")
    }

    private fun SessionTokens.issued() = IssuedVerificationSession(accessToken, refreshToken, frontToken, antiCSRF)
}
