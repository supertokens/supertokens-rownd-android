package io.rownd.android.util

import android.content.Context
import android.util.Log
import io.rownd.android.RowndClient
import io.rownd.android.util.SuperTokensSessionBridge.SessionIdentity
import io.rownd.android.util.SuperTokensSessionBridge.SessionTokens
import io.rownd.android.util.SuperTokensSessionBridge.VerificationOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Rownd.hub"

internal interface NativeEmailVerificationSession {
    fun captureOwner(): VerificationOwner?
    fun owns(owner: VerificationOwner): Boolean
    suspend fun verify(request: NativeEmailVerificationRequest, tokens: SessionTokens): NativeEmailVerificationResult
    fun install(owner: VerificationOwner, tokens: SessionTokens, refreshedFrom: SessionTokens? = null): Boolean
    fun reloadProfile(owner: VerificationOwner, accessToken: String)
    suspend fun revoke(request: NativeEmailVerificationRequest, issued: IssuedVerificationSession)
    fun recoveryTokens(owner: VerificationOwner): SessionTokens?
    suspend fun probe(request: NativeEmailVerificationRequest, tokens: SessionTokens): VerificationRefreshResult
    fun clearRevoked(owner: VerificationOwner, probedTokens: SessionTokens?): Boolean
}

internal class NativeEmailVerificationSupersededException :
    IllegalStateException("The session changed during native email verification")

// The request and adoption outlive the Hub page. They cannot survive process death: if the server
// revoked S0 but the app didn't install S1, the app signs out when its next refresh fails.
internal class NativeEmailVerifier(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val warn: (String) -> Unit = { Log.w(TAG, it) },
    private val recoveryTimeoutMillis: Long = NATIVE_EMAIL_RECOVERY_TIMEOUT_MILLIS,
) {
    private data class Key(val request: NativeEmailVerificationRequest, val identity: SessionIdentity)

    private val inFlight = HashMap<Key, Deferred<String>>()

    fun verify(request: NativeEmailVerificationRequest, session: NativeEmailVerificationSession): Deferred<String> {
        val owner = session.captureOwner()
            ?: return CompletableDeferred<String>().apply {
                completeExceptionally(IllegalStateException("Native email verification requires an existing session"))
            }
        val key = Key(request, owner.identity)
        return synchronized(inFlight) {
            inFlight[key]?.let { return@synchronized it }
            val result = CompletableDeferred<String>()
            inFlight[key] = result
            scope.launch {
                try {
                    run(request, session, owner, result)
                } catch (error: Exception) {
                    warn("Native email verification failed (${error.javaClass.simpleName})")
                    result.completeExceptionally(error)
                } finally {
                    synchronized(inFlight) { inFlight.remove(key, result) }
                }
            }
            result
        }
    }

    private suspend fun run(
        request: NativeEmailVerificationRequest,
        session: NativeEmailVerificationSession,
        owner: VerificationOwner,
        result: CompletableDeferred<String>,
    ) {
        val verification = runCatching { session.verify(request, owner.tokens) }
        (verification.exceptionOrNull() as? CancellationException)?.let { throw it }
        verification.exceptionOrNull()?.let { warn("Native email verification failed (${it.javaClass.simpleName})") }
        val issued = verification.getOrNull()?.issuedSession
            ?: (verification.exceptionOrNull() as? NativeEmailVerificationRejectedException)?.issuedSession
        val replacement = issued?.takeIf {
            SuperTokensSessionBridge.isOtherSessionOfSameUser(owner.tokens.accessToken, it.accessToken)
        }
        val tokens = replacement?.completeTokens()

        // Even an error response can carry the only usable session after the server revoked S0.
        if (tokens != null && session.install(owner, tokens)) {
            val error = verification.exceptionOrNull()
            if (error == null) result.complete(verification.getOrThrow().status)
            else result.completeExceptionally(error)
            session.reloadProfile(owner, tokens.accessToken)
            return
        }

        if (replacement != null) {
            scope.launch {
                try {
                    session.revoke(request, replacement)
                } catch (error: VerificationSessionRevocationException) {
                    warn("Could not revoke the unadopted email verification session: HTTP ${error.statusCode}")
                } catch (error: Exception) {
                    warn("Could not revoke the unadopted email verification session (${error.javaClass.simpleName})")
                }
            }
        }
        if (!session.owns(owner)) {
            result.completeExceptionally(NativeEmailVerificationSupersededException())
            return
        }

        result.completeExceptionally(verification.exceptionOrNull()
            ?: IllegalStateException("Native replacement session was not adopted"))
        recover(request, session, owner)
    }

    private suspend fun recover(
        request: NativeEmailVerificationRequest,
        session: NativeEmailVerificationSession,
        owner: VerificationOwner,
    ) {
        if (session.clearRevoked(owner, probedTokens = null)) return
        val tokens = session.recoveryTokens(owner) ?: return
        // Probe only S0 and never call the SDK's blocking refresh here. A late response must
        // not write, or clear, credentials after a sign-in or after another refresh rotated S0.
        val probe = try {
            withTimeoutOrNull(recoveryTimeoutMillis) { session.probe(request, tokens) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        when (probe) {
            is VerificationRefreshResult.Refreshed -> {
                if (!SuperTokensSessionBridge.sameSession(tokens.accessToken, probe.tokens.accessToken) ||
                    !session.install(owner, probe.tokens, refreshedFrom = tokens)) {
                    session.clearRevoked(owner, probedTokens = null)
                }
            }
            VerificationRefreshResult.Revoked -> session.clearRevoked(owner, probedTokens = tokens)
            else -> if (!session.clearRevoked(owner, probedTokens = null)) {
                warn("Native session could not be validated after a failed email verification; keeping it")
            }
        }
    }
}

internal class RowndNativeEmailVerificationSession(
    private val client: RowndClient,
    private val context: Context,
) : NativeEmailVerificationSession {
    private val store = client.stateRepo.getStore()
    private val transport = client.authRepo.legacyMigrationApiClient.client

    override fun captureOwner() = SuperTokensSessionBridge.captureVerificationOwner(context, store)

    override fun owns(owner: VerificationOwner) = SuperTokensSessionBridge.ownsVerification(context, owner)

    override suspend fun verify(request: NativeEmailVerificationRequest, tokens: SessionTokens) =
        performNativeEmailVerification(transport, request, tokens)

    override fun install(owner: VerificationOwner, tokens: SessionTokens, refreshedFrom: SessionTokens?) =
        SuperTokensSessionBridge.installVerificationSession(context, store, owner, tokens, refreshedFrom)

    override fun reloadProfile(owner: VerificationOwner, accessToken: String) {
        client.userRepo.loadUserAsync {
            SuperTokensSessionBridge.ownsVerificationProfile(context, owner, accessToken) &&
                SuperTokensSessionBridge.isSameSession(accessToken, store.currentState.auth.accessToken)
        }
    }

    override suspend fun revoke(request: NativeEmailVerificationRequest, issued: IssuedVerificationSession) =
        revokeIssuedVerificationSession(transport, request, issued)

    override fun recoveryTokens(owner: VerificationOwner) =
        SuperTokensSessionBridge.verificationRecoveryTokens(context, owner)

    override suspend fun probe(request: NativeEmailVerificationRequest, tokens: SessionTokens) =
        probeVerificationSession(transport, request, tokens)

    override fun clearRevoked(owner: VerificationOwner, probedTokens: SessionTokens?): Boolean =
        client.signOutIfCurrent(owner.tokens.accessToken, revokeRemote = false) {
            SuperTokensSessionBridge.isVerificationSessionRevoked(context, owner) ||
                (probedTokens != null && recoveryTokens(owner) == probedTokens)
        }
}
