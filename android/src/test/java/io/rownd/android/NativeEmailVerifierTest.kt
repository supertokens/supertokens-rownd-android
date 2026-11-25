package io.rownd.android

import io.rownd.android.util.NativeEmailVerificationRequest
import io.rownd.android.util.NativeEmailVerificationResult
import io.rownd.android.util.NativeEmailVerificationSession
import io.rownd.android.util.NativeEmailVerificationRejectedException
import io.rownd.android.util.NativeEmailVerificationSupersededException
import io.rownd.android.util.NativeEmailVerifier
import io.rownd.android.util.NativeSessionValidity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NativeEmailVerifierTest {
    private val request = NativeEmailVerificationRequest(
        token = "opaque-token",
        pendingVerificationId = "pending-123",
        apiDomain = "https://api.example.com",
        apiBasePath = "/auth",
    )
    private val warnings = CopyOnWriteArrayList<String>()
    private val verifierJob = SupervisorJob()
    private val verifier = NativeEmailVerifier(
        scope = CoroutineScope(verifierJob + Dispatchers.Default),
        warn = { message, _ -> warnings.add(message) },
    )

    // Tokens are "<user>:<session>"; the Rownd store and the SDK each hold one.
    private class FakeSession(
        var storedAccessToken: String? = "a:s0",
        var storeAccessToken: String? = "a:s0",
        val verifyResponse: suspend FakeSession.() -> NativeEmailVerificationResult = {
            this.storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        },
        val validity: NativeSessionValidity = NativeSessionValidity.Valid,
        val adoptFailures: Int = 0,
        val revocationError: Exception? = null,
    ) : NativeEmailVerificationSession {
        val generation = AtomicLong()
        val verifyCalls = AtomicInteger()
        val adoptCalls = AtomicInteger()
        val adopted = CopyOnWriteArrayList<String?>()
        val reloadedProfiles = CopyOnWriteArrayList<String>()
        val discarded = CopyOnWriteArrayList<String>()
        val revoked = CopyOnWriteArrayList<String>()
        val validations = AtomicInteger()
        val signedOut = CopyOnWriteArrayList<String>()

        override fun signOutGeneration(): Long = generation.get()
        override suspend fun accessToken(): String? = storedAccessToken
        override suspend fun verify(request: NativeEmailVerificationRequest): NativeEmailVerificationResult {
            verifyCalls.incrementAndGet()
            return verifyResponse()
        }
        override fun hasCompleteSession(): Boolean = true
        override suspend fun adopt(isCurrent: () -> Boolean): Boolean {
            if (adoptCalls.incrementAndGet() <= adoptFailures) throw IllegalStateException("adoption failed")
            if (!isCurrent()) return false
            storeAccessToken = storedAccessToken
            adopted.add(storedAccessToken)
            return true
        }
        override fun reloadProfile(accessToken: String, isCurrent: () -> Boolean) {
            reloadedProfiles.add(accessToken)
        }
        override fun storeHoldsSessionOf(accessToken: String): Boolean = storeAccessToken == accessToken
        override fun storeAccessToken(): String? = storeAccessToken
        override fun nativeAccessToken(): String? = storedAccessToken
        override fun isSameSession(first: String, second: String): Boolean = first == second
        override fun isOtherSessionOfSameUser(first: String, second: String): Boolean =
            first != second && first.substringBefore(':') == second.substringBefore(':')
        override suspend fun discardIssuedSession(issuedAccessToken: String): String? {
            discarded.add(issuedAccessToken)
            if (storedAccessToken != issuedAccessToken) return null
            storedAccessToken = null
            if (storeAccessToken == issuedAccessToken) storeAccessToken = null
            return storeAccessToken
        }
        override suspend fun revokeIssuedSession(request: NativeEmailVerificationRequest, issuedAccessToken: String) {
            revoked.add(issuedAccessToken)
            revocationError?.let { throw it }
        }
        override suspend fun validate(): NativeSessionValidity {
            validations.incrementAndGet()
            return validity
        }
        override fun signOutIfCurrent(accessToken: String, isCurrent: () -> Boolean): Boolean {
            val accepted = isCurrent()
            if (accepted) {
                signedOut.add(accessToken)
                storeAccessToken = null
            }
            return accepted
        }
    }

    private fun failingSession(error: Exception, validity: NativeSessionValidity) =
        FakeSession(verifyResponse = { throw error }, validity = validity)

    // Waits for the verification and everything it does after reporting its result.
    private suspend fun verifyAndSettle(session: FakeSession): Result<String> {
        val result = runCatching { withTimeout(5_000) { verifier.verify(request, session).await() } }
        withTimeout(5_000) { verifierJob.children.toList().joinAll() }
        return result
    }

    @Test
    fun `replacement session is adopted after the waiting Hub page is disposed`() = runBlocking {
        val responseGate = CompletableDeferred<Unit>()
        val requestSent = CompletableDeferred<Unit>()
        val session = FakeSession(verifyResponse = {
            requestSent.complete(Unit)
            responseGate.await()
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })
        val verification = verifier.verify(request, session)
        val pageLifecycle = Job()
        val pageWaiter = CoroutineScope(Dispatchers.Default + pageLifecycle).launch {
            verification.await()
        }

        withTimeout(5_000) { requestSent.await() }
        pageWaiter.cancelAndJoin()
        pageLifecycle.cancel()
        responseGate.complete(Unit)

        assertEquals("OK", withTimeout(5_000) { verification.await() })
        assertEquals(1, session.verifyCalls.get())
        assertEquals(listOf<String?>("a:s1"), session.adopted)
        assertEquals(listOf("a:s1"), session.reloadedProfiles)
        assertEquals(0, session.validations.get())
    }

    @Test
    fun `a repeated request for the same token joins the one in flight`() = runBlocking {
        val responseGate = CompletableDeferred<Unit>()
        val session = FakeSession(verifyResponse = {
            responseGate.await()
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        val first = verifier.verify(request, session)
        val second = verifier.verify(request, session)
        responseGate.complete(Unit)

        assertEquals("OK", withTimeout(5_000) { first.await() })
        assertEquals("OK", withTimeout(5_000) { second.await() })
        assertEquals(1, session.verifyCalls.get())
    }

    @Test
    fun `a failure that revoked the session signs the user out`() = runBlocking {
        val session = failingSession(IOException("timeout"), NativeSessionValidity.Revoked)

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(1, session.validations.get())
        assertEquals(listOf("a:s0"), session.signedOut)
        assertTrue(warnings.any { it.startsWith("Native email verification failed") })
        assertTrue(session.adopted.isEmpty())
    }

    @Test
    fun `a failure keeps the session when it cannot be validated`() = runBlocking {
        val session = failingSession(IOException("offline"), NativeSessionValidity.Unknown)

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(1, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
        assertTrue(session.adopted.isEmpty())
        assertTrue(warnings.any { it.startsWith("Native email verification failed") })
    }

    @Test
    fun `a failure keeps a session the server still accepts`() = runBlocking {
        val session = failingSession(IllegalStateException("HTTP 400"), NativeSessionValidity.Valid)

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(1, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
        assertTrue(session.adopted.isEmpty())
        assertEquals("a:s0", session.storeAccessToken)
    }

    @Test
    fun `an error response that restored a session moves the store to it`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            storedAccessToken = "a:s2"
            throw NativeEmailVerificationRejectedException("Email verification failed with status GENERAL_ERROR", "a:s2")
        })

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(listOf<String?>("a:s2"), session.adopted)
        assertEquals("a:s2", session.storeAccessToken)
        assertEquals(listOf("a:s2"), session.reloadedProfiles)
        assertTrue(session.signedOut.isEmpty())
        assertTrue(session.revoked.isEmpty())
    }

    @Test
    fun `a replacement stored before adoption failed is adopted after validation`() = runBlocking {
        val session = FakeSession(adoptFailures = 1)

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(1, session.validations.get())
        assertEquals(listOf<String?>("a:s1"), session.adopted)
        assertEquals("a:s1", session.storeAccessToken)
        assertEquals(listOf("a:s1"), session.reloadedProfiles)
    }

    @Test
    fun `a stored replacement is adopted even when validation is inconclusive`() = runBlocking {
        val session = FakeSession(
            verifyResponse = {
                storedAccessToken = "a:s2"
                throw IllegalStateException("Email verification failed")
            },
            validity = NativeSessionValidity.Unknown,
        )

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals("a:s2", session.storeAccessToken)
        assertEquals(listOf("a:s2"), session.reloadedProfiles)
    }

    @Test
    fun `a store already following the stored replacement only reloads the profile`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            storedAccessToken = "a:s2"
            storeAccessToken = "a:s2"
            throw IllegalStateException("Email verification failed")
        })

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals(0, session.adoptCalls.get())
        assertEquals(listOf("a:s2"), session.reloadedProfiles)
    }

    @Test
    fun `a stored session of another user is not adopted`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            storedAccessToken = "b:s9"
            throw IllegalStateException("Email verification failed")
        })

        assertTrue(verifyAndSettle(session).isFailure)

        assertTrue(session.adopted.isEmpty())
        assertTrue(session.reloadedProfiles.isEmpty())
        assertEquals("a:s0", session.storeAccessToken)
    }

    @Test
    fun `a response whose session was not installed is validated`() = runBlocking {
        val session = FakeSession(
            verifyResponse = { NativeEmailVerificationResult("OK", null) },
            validity = NativeSessionValidity.Revoked,
        )

        assertTrue(verifyAndSettle(session).isFailure)

        assertTrue(session.adopted.isEmpty())
        assertEquals(listOf("a:s0"), session.signedOut)
    }

    @Test
    fun `a session issued after sign-out is discarded and revoked instead of adopted`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            generation.incrementAndGet()
            storeAccessToken = null
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        val error = verifyAndSettle(session).exceptionOrNull()

        assertTrue(error is NativeEmailVerificationSupersededException)
        assertEquals(listOf("a:s1"), session.discarded)
        assertEquals(null, session.storedAccessToken)
        assertEquals(listOf("a:s1"), session.revoked)
        assertTrue(session.adopted.isEmpty())
        assertEquals(0, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
    }

    @Test
    fun `an error response that issued a session after sign-out is discarded and revoked`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            generation.incrementAndGet()
            storeAccessToken = null
            storedAccessToken = "a:s2"
            throw NativeEmailVerificationRejectedException("Email verification failed with status GENERAL_ERROR", "a:s2")
        })

        assertTrue(verifyAndSettle(session).exceptionOrNull() is NativeEmailVerificationSupersededException)

        assertEquals(null, session.storedAccessToken)
        assertEquals(listOf("a:s2"), session.revoked)
        assertEquals(0, session.validations.get())
    }

    @Test
    fun `a sign-in that replaced the issued session in the SDK is untouched and the issued one revoked`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            storedAccessToken = "b:s5"
            storeAccessToken = "b:s5"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        assertTrue(verifyAndSettle(session).exceptionOrNull() is NativeEmailVerificationSupersededException)

        assertEquals("b:s5", session.storedAccessToken)
        assertEquals("b:s5", session.storeAccessToken)
        assertTrue(session.adopted.isEmpty())
        assertTrue(session.signedOut.isEmpty())
        assertEquals(listOf("a:s1"), session.revoked)
    }

    @Test
    fun `a sign-in overwritten by the issued session is signed out and the issued one revoked`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            storeAccessToken = "b:s5"
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        assertTrue(verifyAndSettle(session).exceptionOrNull() is NativeEmailVerificationSupersededException)

        assertTrue(session.adopted.isEmpty())
        assertEquals(null, session.storedAccessToken)
        assertEquals(listOf("b:s5"), session.signedOut)
        assertEquals(null, session.storeAccessToken)
        assertEquals(listOf("a:s1"), session.revoked)
        assertTrue(warnings.any { it.contains("overwrote a session signed in during the verification") })
    }

    @Test
    fun `a failed revocation is logged and nothing else changes`() = runBlocking {
        val session = FakeSession(
            verifyResponse = {
                generation.incrementAndGet()
                storeAccessToken = null
                storedAccessToken = "a:s1"
                NativeEmailVerificationResult("OK", "a:s1")
            },
            revocationError = IOException("offline"),
        )

        assertTrue(verifyAndSettle(session).exceptionOrNull() is NativeEmailVerificationSupersededException)

        assertEquals(listOf("a:s1"), session.revoked)
        assertTrue(warnings.contains("Could not revoke the unadopted email verification session"))
        assertTrue(warnings.none { it.contains("a:s1") })
        assertEquals(null, session.storedAccessToken)
    }

    // On a cold start from a magic link the store is reconciled with the SDK only after the app
    // config loads, so verification can finish while the store holds none of its sessions.
    @Test
    fun `a store not yet reconciled adopts the replacement instead of revoking it`() = runBlocking {
        listOf(null, "", "a:stale", "legacy-credential").forEach { unreconciled ->
            val session = FakeSession(storeAccessToken = unreconciled)

            assertEquals("Store $unreconciled", "OK", verifyAndSettle(session).getOrNull())

            assertEquals("Store $unreconciled", listOf<String?>("a:s1"), session.adopted)
            assertEquals("Store $unreconciled", "a:s1", session.storeAccessToken)
            assertEquals("Store $unreconciled", "a:s1", session.storedAccessToken)
            assertTrue("Store $unreconciled", session.discarded.isEmpty())
            assertTrue("Store $unreconciled", session.revoked.isEmpty())
            assertTrue("Store $unreconciled", session.signedOut.isEmpty())
        }
    }

    @Test
    fun `startup reconciling the store to the initiating session during the request adopts the replacement`() = runBlocking {
        val session = FakeSession(storeAccessToken = null, verifyResponse = {
            storeAccessToken = "a:s0"
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        assertEquals("OK", verifyAndSettle(session).getOrNull())

        assertEquals("a:s1", session.storeAccessToken)
        assertTrue(session.revoked.isEmpty())
    }

    // Migration bootstraps the SDK before it moves the store off the legacy credential.
    @Test
    fun `legacy migration finishing during the request adopts the replacement`() = runBlocking {
        val session = FakeSession(storeAccessToken = "legacy-credential", verifyResponse = {
            storeAccessToken = "a:s0"
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        assertEquals("OK", verifyAndSettle(session).getOrNull())

        assertEquals("a:s1", session.storeAccessToken)
        assertTrue(session.discarded.isEmpty())
        assertTrue(session.revoked.isEmpty())
    }

    @Test
    fun `an error response that restored a session is adopted by a store not yet reconciled`() = runBlocking {
        val session = FakeSession(storeAccessToken = null, verifyResponse = {
            storedAccessToken = "a:s2"
            throw NativeEmailVerificationRejectedException("Email verification failed with status GENERAL_ERROR", "a:s2")
        })

        assertTrue(verifyAndSettle(session).isFailure)

        assertEquals("a:s2", session.storeAccessToken)
        assertEquals(listOf("a:s2"), session.reloadedProfiles)
        assertTrue(session.revoked.isEmpty())
    }

    @Test
    fun `a sign-in that overwrote a store not yet reconciled is still detected`() = runBlocking {
        val session = FakeSession(storeAccessToken = null, verifyResponse = {
            storeAccessToken = "b:s5"
            storedAccessToken = "a:s1"
            NativeEmailVerificationResult("OK", "a:s1")
        })

        assertTrue(verifyAndSettle(session).exceptionOrNull() is NativeEmailVerificationSupersededException)

        assertTrue(session.adopted.isEmpty())
        assertEquals(listOf("b:s5"), session.signedOut)
        assertEquals(listOf("a:s1"), session.revoked)
    }

    @Test
    fun `verification without a session sends no request`() = runBlocking {
        val session = FakeSession(storedAccessToken = null)

        val error = verifyAndSettle(session).exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, session.verifyCalls.get())
        assertEquals(0, session.validations.get())
        assertTrue(session.adopted.isEmpty())
    }
}
