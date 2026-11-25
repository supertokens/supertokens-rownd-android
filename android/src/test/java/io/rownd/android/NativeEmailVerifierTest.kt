package io.rownd.android

import io.rownd.android.util.NativeEmailVerificationRequest
import io.rownd.android.util.NativeEmailVerificationResult
import io.rownd.android.util.NativeEmailVerificationSession
import io.rownd.android.util.NativeEmailVerificationSignedOutException
import io.rownd.android.util.NativeEmailVerifier
import io.rownd.android.util.NativeSessionValidity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    private val verifier = NativeEmailVerifier(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        warn = { message, _ -> warnings.add(message) },
    )

    private class FakeSession(
        var storedAccessToken: String? = "old-access",
        val verifyResponse: suspend FakeSession.() -> NativeEmailVerificationResult = {
            this.storedAccessToken = "new-access"
            NativeEmailVerificationResult("OK", "new-access", "new-refresh", null)
        },
        val validity: NativeSessionValidity = NativeSessionValidity.Valid,
    ) : NativeEmailVerificationSession {
        val generation = AtomicLong()
        val verifyCalls = AtomicInteger()
        val adopted = CopyOnWriteArrayList<String?>()
        val reloadedProfiles = CopyOnWriteArrayList<String>()
        val discarded = CopyOnWriteArrayList<NativeEmailVerificationResult>()
        val validations = AtomicInteger()
        val signedOut = CopyOnWriteArrayList<String>()
        val finished = CompletableDeferred<Unit>()

        override fun signOutGeneration(): Long = generation.get()
        override suspend fun accessToken(): String? = storedAccessToken
        override suspend fun verify(request: NativeEmailVerificationRequest): NativeEmailVerificationResult {
            verifyCalls.incrementAndGet()
            return verifyResponse()
        }
        override fun hasCompleteSession(): Boolean = true
        override suspend fun adopt(isCurrent: () -> Boolean): Boolean {
            if (!isCurrent()) return false
            adopted.add(storedAccessToken)
            return true
        }
        override fun reloadProfile(accessToken: String, isCurrent: () -> Boolean) {
            reloadedProfiles.add(accessToken)
            finished.complete(Unit)
        }
        override suspend fun discardIssuedSession(result: NativeEmailVerificationResult) {
            discarded.add(result)
            finished.complete(Unit)
        }
        override suspend fun validate(): NativeSessionValidity {
            validations.incrementAndGet()
            if (validity != NativeSessionValidity.Revoked) finished.complete(Unit)
            return validity
        }
        override fun signOutIfCurrent(accessToken: String, isCurrent: () -> Boolean): Boolean {
            val accepted = isCurrent()
            if (accepted) signedOut.add(accessToken)
            finished.complete(Unit)
            return accepted
        }
    }

    private fun failingSession(error: Exception, validity: NativeSessionValidity) =
        FakeSession(verifyResponse = { throw error }, validity = validity)

    private suspend fun awaitFailure(session: FakeSession): Throwable {
        try {
            verifier.verify(request, session).await()
        } catch (error: Exception) {
            withTimeout(5_000) { session.finished.await() }
            return error
        }
        fail("Verification was expected to fail")
        throw IllegalStateException()
    }

    @Test
    fun `replacement session is adopted after the waiting Hub page is disposed`() = runBlocking {
        val responseGate = CompletableDeferred<Unit>()
        val requestSent = CompletableDeferred<Unit>()
        val session = FakeSession(verifyResponse = {
            requestSent.complete(Unit)
            responseGate.await()
            storedAccessToken = "new-access"
            NativeEmailVerificationResult("OK", "new-access", "new-refresh", null)
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
        withTimeout(5_000) { session.finished.await() }
        assertEquals(1, session.verifyCalls.get())
        assertEquals(listOf<String?>("new-access"), session.adopted)
        assertEquals(listOf("new-access"), session.reloadedProfiles)
        assertEquals(0, session.validations.get())
    }

    @Test
    fun `a repeated request for the same token joins the one in flight`() = runBlocking {
        val responseGate = CompletableDeferred<Unit>()
        val session = FakeSession(verifyResponse = {
            responseGate.await()
            storedAccessToken = "new-access"
            NativeEmailVerificationResult("OK", "new-access", "new-refresh", null)
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

        awaitFailure(session)

        assertEquals(1, session.validations.get())
        assertEquals(listOf("old-access"), session.signedOut)
        assertTrue(warnings.any { it.startsWith("Native email verification failed") })
        assertTrue(session.adopted.isEmpty())
    }

    @Test
    fun `a failure keeps the session when it cannot be validated`() = runBlocking {
        val session = failingSession(IOException("offline"), NativeSessionValidity.Unknown)

        awaitFailure(session)

        assertEquals(1, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
        assertTrue(warnings.any { it.startsWith("Native email verification failed") })
    }

    @Test
    fun `a failure keeps a session the server still accepts`() = runBlocking {
        val session = failingSession(IllegalStateException("HTTP 400"), NativeSessionValidity.Valid)

        awaitFailure(session)

        assertEquals(1, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
    }

    @Test
    fun `a response whose session was not installed is validated`() = runBlocking {
        val session = FakeSession(
            verifyResponse = { NativeEmailVerificationResult("OK", null, null, null) },
            validity = NativeSessionValidity.Revoked,
        )

        awaitFailure(session)

        assertTrue(session.adopted.isEmpty())
        assertEquals(listOf("old-access"), session.signedOut)
    }

    @Test
    fun `a session issued after sign-out is discarded instead of adopted`() = runBlocking {
        val session = FakeSession(verifyResponse = {
            generation.incrementAndGet()
            storedAccessToken = "new-access"
            NativeEmailVerificationResult("OK", "new-access", "new-refresh", null)
        })

        val error = awaitFailure(session)

        assertTrue(error is NativeEmailVerificationSignedOutException)
        assertEquals(listOf("new-access"), session.discarded.map { it.issuedAccessToken })
        assertTrue(session.adopted.isEmpty())
        assertEquals(0, session.validations.get())
        assertTrue(session.signedOut.isEmpty())
    }

    @Test
    fun `verification without a session sends no request`() = runBlocking {
        val session = FakeSession(storedAccessToken = null)

        val error = runCatching { withTimeout(5_000) { verifier.verify(request, session).await() } }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, session.verifyCalls.get())
        assertEquals(0, session.validations.get())
        assertFalse(session.finished.isCompleted)
        assertNull(session.adopted.firstOrNull())
    }
}
