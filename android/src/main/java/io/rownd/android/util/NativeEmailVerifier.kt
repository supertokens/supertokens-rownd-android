package io.rownd.android.util

import android.content.Context
import android.util.Log
import io.rownd.android.RowndClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "Rownd.hub"

internal enum class NativeSessionValidity { Valid, Revoked, Unknown }

internal interface NativeEmailVerificationSession {
    fun signOutGeneration(): Long
    suspend fun accessToken(): String?
    suspend fun verify(request: NativeEmailVerificationRequest): NativeEmailVerificationResult
    fun hasCompleteSession(): Boolean
    suspend fun adopt(isCurrent: () -> Boolean): Boolean
    fun reloadProfile(accessToken: String, isCurrent: () -> Boolean)
    suspend fun discardIssuedSession(result: NativeEmailVerificationResult)

    // Revoked only when the server rejected the session; transport and server errors are Unknown.
    suspend fun validate(): NativeSessionValidity
    fun signOutIfCurrent(accessToken: String, isCurrent: () -> Boolean): Boolean
}

internal class NativeEmailVerificationSignedOutException :
    IllegalStateException("The session was signed out during native email verification")

// The server consumes the token, revokes the current session and issues its replacement in one
// call. Abandoning that call leaves the app on a revoked session, so the request and adoption of
// its session run in an SDK-owned scope and outlive the Hub page that asked for them.
internal class NativeEmailVerifier(
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
    ) = withContext(NonCancellable) {
        val signOutGeneration = session.signOutGeneration()
        val isCurrent = { signOutGeneration == session.signOutGeneration() }
        val previousAccessToken = runCatching { session.accessToken() }.getOrNull()
        if (previousAccessToken == null) {
            val error = IllegalStateException("Native email verification requires an existing session")
            warn("Native email verification failed", error)
            result.completeExceptionally(error)
            return@withContext
        }

        val outcome = runCatching {
            val response = session.verify(request)
            if (!isCurrent()) {
                session.discardIssuedSession(response)
                throw NativeEmailVerificationSignedOutException()
            }
            val replacementAccessToken = session.accessToken()
            check(
                replacementAccessToken != null &&
                    replacementAccessToken != previousAccessToken &&
                    session.hasCompleteSession()
            ) { "Native replacement session was not adopted" }
            check(session.adopt(isCurrent)) { "Native session was not adopted" }
            // The result must neither wait for profile I/O nor depend on it.
            session.reloadProfile(replacementAccessToken, isCurrent)
            response.status
        }

        outcome.onSuccess { result.complete(it) }
        val error = outcome.exceptionOrNull() ?: return@withContext
        warn("Native email verification failed", error)
        result.completeExceptionally(error)
        if (error is NativeEmailVerificationSignedOutException || !isCurrent()) return@withContext

        // The server may have revoked this session even though the response never arrived. Settle
        // that now, rather than leave the app signed in until a refresh fails on the next launch.
        when (session.validate()) {
            NativeSessionValidity.Valid -> Unit
            NativeSessionValidity.Unknown ->
                warn("Native session could not be validated after a failed email verification; keeping it", null)
            NativeSessionValidity.Revoked ->
                if (session.signOutIfCurrent(previousAccessToken, isCurrent)) {
                    warn("Native session was revoked during a failed email verification; signed out", null)
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

    override suspend fun discardIssuedSession(result: NativeEmailVerificationResult) {
        val accessToken = result.issuedAccessToken ?: return
        SuperTokensSessionBridge.discardSessionIssuedAfterSignOut(context, client.stateRepo.getStore(), accessToken)
        try {
            client.authRepo.revokeSignedOutSession(
                SuperTokensSessionBridge.SignedOutSession(accessToken, result.issuedRefreshToken, result.issuedAntiCSRF),
                client.authRepo.sessionRevocationUrl(),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Remote revocation of the session issued after sign-out unconfirmed", error)
        }
    }

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
