package io.rownd.android.util

import android.content.Context
import android.util.Log
import io.rownd.android.RowndClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "Rownd.hub"

internal enum class NativeSessionValidity { Valid, Revoked, Unknown }

internal interface NativeEmailVerificationSession {
    fun signOutGeneration(): Long
    suspend fun accessToken(): String?
    suspend fun verify(request: NativeEmailVerificationRequest): NativeEmailVerificationResult
    fun hasCompleteSession(): Boolean
    suspend fun adopt(isCurrent: () -> Boolean): Boolean
    fun reloadProfile(accessToken: String, isCurrent: () -> Boolean)
    fun storeHoldsSessionOf(accessToken: String): Boolean
    fun isSameSession(first: String, second: String): Boolean
    fun isOtherSessionOfSameUser(first: String, second: String): Boolean

    // Clears the issued session locally if the SDK holds it. Returns the session the Rownd store
    // holds whose native credentials it overwrote.
    suspend fun discardIssuedSession(issuedAccessToken: String): String?
    suspend fun revokeIssuedSession(request: NativeEmailVerificationRequest, issuedAccessToken: String)

    // Revoked only when the server rejected the session; transport and server errors are Unknown.
    suspend fun validate(): NativeSessionValidity
    fun signOutIfCurrent(accessToken: String, isCurrent: () -> Boolean): Boolean
}

// The user signed out, or moved to another session, while the server issued a session for them.
internal class NativeEmailVerificationSupersededException(cause: Throwable) :
    IllegalStateException("The session changed during native email verification", cause)

// The server consumes the token, revokes the current session and issues its replacement in one
// call. Abandoning that call leaves the app on a revoked session, so the request and adoption of
// its session run in an SDK-owned scope and outlive the Hub page that asked for them.
//
// This does not survive process death: if the process dies after the request is sent but before
// the SDK stores the replacement, the app relaunches on the revoked session and is signed out when
// its first refresh fails.
internal class NativeEmailVerifier(
    // Must never be cancelled: cancelling would abandon a request the server may have committed.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val warn: (String, Throwable?) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
    private val inFlight = HashMap<NativeEmailVerificationRequest, Deferred<String>>()

    // A repeated request for the same token joins the one in flight: a second call would fail on
    // the consumed token and report a failure for a verification that succeeded.
    fun verify(request: NativeEmailVerificationRequest, session: NativeEmailVerificationSession): Deferred<String> =
        synchronized(inFlight) {
            inFlight[request]?.let { return@synchronized it }
            val result = CompletableDeferred<String>()
            inFlight[request] = result
            scope.launch {
                try {
                    run(request, session, result)
                } catch (error: Exception) {
                    // Nothing awaits this job, so an escaping exception would crash the host app.
                    warn("Native email verification failed", error)
                    result.completeExceptionally(error)
                } finally {
                    synchronized(inFlight) { inFlight.remove(request, result) }
                }
            }
            result
        }

    private suspend fun run(
        request: NativeEmailVerificationRequest,
        session: NativeEmailVerificationSession,
        result: CompletableDeferred<String>,
    ) {
        val signOutGeneration = session.signOutGeneration()
        val isCurrent = { signOutGeneration == session.signOutGeneration() }
        val previousAccessToken = runCatching { session.accessToken() }.getOrNull()
        if (previousAccessToken == null) {
            val error = IllegalStateException("Native email verification requires an existing session")
            warn("Native email verification failed", error)
            result.completeExceptionally(error)
            return
        }

        val verification = runCatching { session.verify(request) }
        val issuedAccessToken = verification.getOrNull()?.issuedAccessToken
            ?: (verification.exceptionOrNull() as? NativeEmailVerificationRejectedException)?.issuedAccessToken
        val outcome = verification.mapCatching { response ->
            check(isCurrent()) { "Signed out during native email verification" }
            val replacementAccessToken = session.accessToken()
            check(
                replacementAccessToken != null &&
                    replacementAccessToken != previousAccessToken &&
                    (issuedAccessToken == null || session.isSameSession(issuedAccessToken, replacementAccessToken)) &&
                    session.hasCompleteSession()
            ) { "Native replacement session was not adopted" }
            // A sign-in to another session during the request must not be replaced by this one.
            check(session.adopt {
                isCurrent() && (session.storeHoldsSessionOf(previousAccessToken) ||
                    session.storeHoldsSessionOf(replacementAccessToken))
            }) { "Native session was not adopted" }
            // The result must neither wait for profile I/O nor depend on it.
            session.reloadProfile(replacementAccessToken, isCurrent)
            response.status
        }

        outcome.onSuccess { result.complete(it) }
        val error = outcome.exceptionOrNull() ?: return
        warn("Native email verification failed", error)

        if (issuedAccessToken != null && (!isCurrent() || (
                !session.storeHoldsSessionOf(previousAccessToken) &&
                    !session.storeHoldsSessionOf(issuedAccessToken)))) {
            warn("Discarding the session issued by a superseded email verification", null)
            discard(session, request, issuedAccessToken)
            result.completeExceptionally(NativeEmailVerificationSupersededException(error))
            return
        }
        result.completeExceptionally(error)
        if (!isCurrent()) return

        // The server may have revoked this session even though the response never arrived. Settle
        // that now, rather than leave the app signed in until a refresh fails on the next launch.
        // This is a point-in-time check: if the server is still committing the verification when
        // the refresh runs, the session can be revoked moments after it passed.
        when (session.validate()) {
            NativeSessionValidity.Valid -> adoptStoredSession(session, previousAccessToken, isCurrent)
            NativeSessionValidity.Unknown -> {
                warn("Native session could not be validated after a failed email verification; keeping it", null)
                adoptStoredSession(session, previousAccessToken, isCurrent)
            }
            NativeSessionValidity.Revoked ->
                if (session.signOutIfCurrent(previousAccessToken, isCurrent)) {
                    warn("Native session was revoked during a failed email verification; signed out", null)
                }
        }
    }

    // A failed verification can still leave a replacement session in the SDK: the server can
    // answer an error while issuing one, or adoption can fail after the SDK stored it. The Rownd
    // store would stay on the session the server revoked, so it follows the SDK as it does
    // elsewhere. This holds even when validation was inconclusive: the SDK's session is the newer.
    private suspend fun adoptStoredSession(
        session: NativeEmailVerificationSession,
        previousAccessToken: String,
        isCurrent: () -> Boolean,
    ) {
        val storedAccessToken = runCatching { session.accessToken() }.getOrNull() ?: return
        if (!session.isOtherSessionOfSameUser(previousAccessToken, storedAccessToken)) return
        // The SDK's own session events may already have moved the store to the stored session.
        if (session.storeHoldsSessionOf(previousAccessToken) &&
            !session.adopt { isCurrent() && session.storeHoldsSessionOf(previousAccessToken) }) {
            warn("Native email verification left a replacement session the store could not adopt", null)
            return
        }
        if (!session.storeHoldsSessionOf(storedAccessToken)) return
        session.reloadProfile(storedAccessToken, isCurrent)
        warn("Adopted the replacement session stored during a failed email verification", null)
    }

    // Nobody will adopt the issued session, so it must survive neither in the SDK, where it would
    // resurrect the user or be attached to another user's requests, nor on the server.
    private suspend fun discard(
        session: NativeEmailVerificationSession,
        request: NativeEmailVerificationRequest,
        issuedAccessToken: String,
    ) {
        val overwritten = session.discardIssuedSession(issuedAccessToken)
        if (overwritten != null) {
            // A sign-in that completed during the request lost its native session to the issued
            // one. The SDK keeps one session, so the overwritten one cannot be restored or revoked.
            warn("Native email verification response overwrote a session signed in during the verification; signing it out", null)
            session.signOutIfCurrent(overwritten) { session.storeHoldsSessionOf(overwritten) }
        }
        scope.launch {
            try {
                session.revokeIssuedSession(request, issuedAccessToken)
            } catch (error: Exception) {
                warn("Could not revoke the unadopted email verification session", error)
            }
        }
    }
}

internal class RowndNativeEmailVerificationSession(
    private val client: RowndClient,
    private val context: Context,
) : NativeEmailVerificationSession {
    override fun signOutGeneration(): Long = SuperTokensSessionBridge.currentSignOutGeneration()

    override suspend fun accessToken(): String? = SuperTokensSessionBridge.getAccessToken(context)

    override suspend fun verify(request: NativeEmailVerificationRequest): NativeEmailVerificationResult =
        performNativeEmailVerification(client.authenticatedApiClient.client, request)

    override fun hasCompleteSession(): Boolean =
        !SuperTokensSessionBridge.getRefreshToken(context).isNullOrEmpty() &&
            !SuperTokensSessionBridge.getFrontToken(context).isNullOrEmpty()

    override suspend fun adopt(isCurrent: () -> Boolean): Boolean =
        SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(
            context = context,
            store = client.stateRepo.getStore(),
            isCurrent = isCurrent,
        )

    override fun reloadProfile(accessToken: String, isCurrent: () -> Boolean) {
        client.userRepo.loadUserAsync {
            isCurrent() && SuperTokensSessionBridge.isSameSession(accessToken, client.stateRepo.state.value.auth.accessToken)
        }
    }

    override fun storeHoldsSessionOf(accessToken: String): Boolean =
        SuperTokensSessionBridge.isSameSession(accessToken, client.stateRepo.state.value.auth.accessToken)

    override fun isSameSession(first: String, second: String): Boolean =
        SuperTokensSessionBridge.isSameSession(first, second)

    override fun isOtherSessionOfSameUser(first: String, second: String): Boolean =
        SuperTokensSessionBridge.isOtherSessionOfSameUser(first, second)

    override suspend fun discardIssuedSession(issuedAccessToken: String): String? =
        SuperTokensSessionBridge.discardIssuedSession(context, client.stateRepo.getStore(), issuedAccessToken)

    override suspend fun revokeIssuedSession(request: NativeEmailVerificationRequest, issuedAccessToken: String) =
        revokeIssuedVerificationSession(client.authRepo.legacyMigrationApiClient.client, request, issuedAccessToken)

    override suspend fun validate(): NativeSessionValidity = try {
        if (SuperTokensSessionBridge.attemptRefresh(context)) NativeSessionValidity.Valid
        else NativeSessionValidity.Revoked
    } catch (error: Exception) {
        Log.w(TAG, "Native session validation failed", error)
        NativeSessionValidity.Unknown
    }

    override fun signOutIfCurrent(accessToken: String, isCurrent: () -> Boolean): Boolean =
        client.signOutIfCurrent(accessToken, isCurrent)
}
