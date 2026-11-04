package io.rownd.android.models.repos

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.lifecycle.Lifecycle
import io.opentelemetry.api.trace.StatusCode
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInIntent
import io.rownd.android.RowndSignInOptions
import io.rownd.android.models.Action
import io.rownd.android.models.State
import io.rownd.android.models.Store
import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SignInState
import io.rownd.android.models.domain.User
import io.rownd.android.util.AppLifecycleListener
import io.rownd.android.util.AuthLevel
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class GlobalState(
    val appConfig: AppConfigState = AppConfigState(),
    val auth: AuthState = AuthState(),
    val user: User = User(),
    val signIn: SignInState = SignInState(),

    @Transient
    val isInitialized: Boolean = false,
) : State

object GlobalStateSerializer : Serializer<GlobalState> {

    override val defaultValue = GlobalState()
    val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun readFrom(input: InputStream): GlobalState {
        try {
            return json.decodeFromString(
                GlobalState.serializer(), input.readBytes().decodeToString()
            ).forPersistence()
        } catch (ex: SerializationException) {
            throw CorruptionException("Unable to read GlobalState", ex)
        }
    }

    override suspend fun writeTo(t: GlobalState, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(
                json.encodeToString(GlobalState.serializer(), t.forPersistence())
                    .encodeToByteArray()
            )
        }
    }
}

// The SuperTokens SDK owns native sessions, so only a legacy Rownd credential that still awaits
// migration outlives the process. Profiles are always reloaded from the server.
internal fun GlobalState.forPersistence(): GlobalState {
    val credential = auth.refreshToken?.takeIf { it.isNotBlank() } ?: auth.accessToken?.takeIf { it.isNotBlank() }
    val isLegacy = credential != null && !SuperTokensSessionBridge.isLikelySuperTokensToken(credential) &&
        auth.accessToken?.let(SuperTokensSessionBridge::isLikelySuperTokensToken) != true
    return copy(
        auth = if (isLegacy) AuthState(accessToken = auth.accessToken, refreshToken = auth.refreshToken) else AuthState(),
        user = User(),
    )
}

sealed class StateAction : Action {
    data class SetGlobalState(val value: GlobalState) : StateAction()
    internal data class RestorePersistedState(val expected: GlobalState, val persisted: GlobalState?) : StateAction()
    internal object CompleteInitialization : StateAction()
    data class SetAuth(val value: AuthState) : StateAction()
    // The profile belongs to the session it was loaded for, so another session starts without one.
    internal data class AdoptNativeSession(val accessToken: String) : StateAction()
    internal data class ReconcileNativeSession(
        val expectedAccessToken: String?,
        val accessToken: String?,
        val preserveProfile: Boolean,
        val isCurrent: () -> Boolean,
    ) : StateAction()
    internal data class CompleteLegacyMigration(
        val expected: AuthState,
        val value: AuthState,
        val clearUser: Boolean = false,
        val isCurrent: () -> Boolean = { true },
    ) : StateAction()
    data class SetAuthChallenge(val challengeId: String?, val userIdentifier: String?) : StateAction()
    data class SetSignIn(val value: SignInState): StateAction()
    data class SetAppConfig(val value: AppConfigState) : StateAction()
    data class SetUser(val value: User) : StateAction()
    data class SetUserIsLoading(val value: Boolean) : StateAction()
    internal data class BeginProfileRequest(
        val requestId: Long,
        val accessToken: String?,
        val update: (User) -> User = { it },
    ) : StateAction()
    internal data class CompleteProfileRequest(
        val requestId: Long,
        val accessToken: String?,
        val user: User?,
        // Set by the reducer while it decides, so the caller learns the outcome of this very
        // dispatch instead of re-checking ownership after a newer request may have started. True
        // when the request was the latest of its session and its owner still wanted the result.
        val accepted: AtomicBoolean = AtomicBoolean(),
        val isCurrent: () -> Boolean = { true },
    ) : StateAction()
}

@Singleton
class StateRepo @Inject constructor() {

    @Inject
    lateinit var appConfigRepo: AppConfigRepo

    // Manually inject these
    lateinit var userRepo: UserRepo
    lateinit var authRepo: AuthRepo

    private lateinit var dataStore: DataStore<GlobalState>

    // Shared by profile loads, saves and Hub updates: only the latest one may write the profile.
    private val profileRequestId = AtomicLong()

    internal fun nextProfileRequestId(): Long = profileRequestId.incrementAndGet()

    internal fun isLatestProfileRequest(requestId: Long): Boolean = requestId == profileRequestId.get()

    internal fun ownsProfile(requestId: Long, accessToken: String?): Boolean = ownsProfile(store.currentState, requestId, accessToken)

    private fun ownsProfile(state: GlobalState, requestId: Long, accessToken: String?): Boolean =
        isLatestProfileRequest(requestId) && SuperTokensSessionBridge.isSameSession(accessToken, state.auth.accessToken)

    private val store = Store<GlobalState, StateAction>(GlobalState()) { state, action ->
        when (action) {
            is StateAction.CompleteLegacyMigration -> if (
                state.auth.accessToken == action.expected.accessToken &&
                state.auth.refreshToken == action.expected.refreshToken && action.isCurrent()
            ) state.copy(auth = action.value, user = if (action.clearUser) User() else state.user) else state
            is StateAction.SetAuth -> state.copy(auth = action.value)
            is StateAction.AdoptNativeSession -> state.copy(
                auth = AuthState(accessToken = action.accessToken, refreshToken = null),
                user = if (SuperTokensSessionBridge.isSameSession(state.auth.accessToken, action.accessToken)) state.user else User(),
            )
            is StateAction.ReconcileNativeSession -> if (
                state.auth.accessToken == action.expectedAccessToken && action.isCurrent()
            ) state.copy(
                auth = if (action.accessToken == null) AuthState()
                    else if (action.preserveProfile) state.auth.copy(accessToken = action.accessToken, refreshToken = null)
                    else AuthState(accessToken = action.accessToken),
                user = if (action.preserveProfile) state.user else User(),
            ) else state
            is StateAction.SetAuthChallenge -> state.copy(auth = state.auth.copy(
                challengeId = action.challengeId,
                userIdentifier = action.userIdentifier,
            ))
            is StateAction.SetSignIn -> state.copy(signIn = action.value)
            is StateAction.SetAppConfig -> state.copy(appConfig = action.value)
            is StateAction.SetUser -> state.copy(user = action.value)
            is StateAction.SetUserIsLoading -> state.copy(user = state.user.copy(isLoading = action.value))
            is StateAction.BeginProfileRequest -> if (ownsProfile(state, action.requestId, action.accessToken)) {
                state.copy(user = action.update(state.user).copy(isLoading = true))
            } else state
            is StateAction.CompleteProfileRequest -> if (ownsProfile(state, action.requestId, action.accessToken)) {
                val current = action.isCurrent()
                action.accepted.set(current)
                state.copy(user = (action.user?.takeIf { current } ?: state.user).copy(isLoading = false))
            } else state.also { action.accepted.set(false) }
            is StateAction.SetGlobalState -> action.value
            is StateAction.CompleteInitialization -> state.copy(isInitialized = true)
            is StateAction.RestorePersistedState -> {
                val persisted = action.persisted
                fun <T> restore(current: T, expected: T, restored: T?) =
                    if (restored != null && current == expected) restored else current
                state.copy(
                    appConfig = restore(state.appConfig, action.expected.appConfig, persisted?.appConfig),
                    signIn = restore(state.signIn, action.expected.signIn, persisted?.signIn),
                    auth = restore(state.auth, action.expected.auth, persisted?.auth),
                )
            }
        }
    }

    fun setup(ds: DataStore<GlobalState>): Store<GlobalState, StateAction> {
        dataStore = ds
        // Re-inflate store from persistence
        CoroutineScope(Dispatchers.IO).launch {
            val initial = store.currentState
            try {
                store.dispatch(StateAction.RestorePersistedState(initial, dataStore.data.first()))
            } catch (err: Exception) {
                Log.d("Rownd.StateRepo", "Failed to load existing state from device", err)
                store.dispatch(StateAction.RestorePersistedState(initial, null))
            }

            // Fetch latest app config
            appConfigRepo.loadAppConfigAsync(this@StateRepo).await()

            // Auth challenges are UI state. Do not reopen the sign-in sheet on SDK startup.
            store.currentState.auth.let { auth ->
                if (auth.challengeId != null || auth.userIdentifier != null) {
                    store.dispatch(StateAction.SetAuthChallenge(null, null))
                }
            }

            // Observers treat an initialized state without a session as signed out, so the
            // session must be settled first.
            val startupAccessToken = try {
                reconcileStartupSession()
            } finally {
                store.dispatch(StateAction.CompleteInitialization)
            }
            hydrateProfileOnFirstResume(startupAccessToken)

            tmpForceInstantUserConversionIfRequested(CoroutineScope(Dispatchers.IO))

            // Persist all state updates to cache when changes occur
            store.stateAsStateFlow().collect {
                val updatedState = it
                dataStore.updateData {
                    updatedState
                }
            }
        }

        return store
    }

    // A cached token proves nothing about the session: the server may have revoked it while the
    // app was closed. Only the SDK's view of the session is adopted.
    private suspend fun reconcileStartupSession(): String? {
        if (!SuperTokensSessionBridge.awaitInitialized()) return null
        return try {
            authRepo.getLatestAuthState()?.accessToken
        } catch (err: CancellationException) {
            throw err
        } catch (err: Exception) {
            Log.d("Rownd.StateRepo", "Failed to verify the session during startup", err)
            authRepo.adoptUnverifiedSession()
        }
    }

    // Accessed only on Main, including lifecycle callbacks and completion cleanup.
    private class PendingStartupHydration(val accessToken: String, var started: Boolean = false)
    private var pendingStartupHydration: PendingStartupHydration? = null
    private var startupResumeListenerRegistered = false

    // Initialization usually finishes before the first activity resumes, and a profile request
    // made while backgrounded is not needed yet.
    internal suspend fun hydrateProfileOnFirstResume(startupAccessToken: String?) {
        if (startupAccessToken == null) return
        withContext(Dispatchers.Main) {
            if (!SuperTokensSessionBridge.isSameSession(startupAccessToken, store.currentState.auth.accessToken)) return@withContext
            if (pendingStartupHydration?.let {
                    SuperTokensSessionBridge.isSameSession(it.accessToken, startupAccessToken)
                } == true) return@withContext
            pendingStartupHydration = PendingStartupHydration(startupAccessToken)
            val loadIfStillSignedIn = {
                val pending = pendingStartupHydration
                if (pending != null && !pending.started) {
                    pending.started = true
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val auth = try {
                                authRepo.getLatestAuthState()
                            } catch (err: Exception) {
                                Log.d("Rownd.StateRepo", "Failed to verify the session before loading the profile", err)
                                null
                            }
                            if (SuperTokensSessionBridge.sameSession(pending.accessToken, auth?.accessToken)) {
                                userRepo.loadUserAsync().await()
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                if (pendingStartupHydration === pending) pendingStartupHydration = null
                            }
                        }
                    }
                }
            }
            val lifecycle = authRepo.rowndContext.client?.appHandleWrapper
            if (AppLifecycleListener.isAppInForeground || lifecycle == null) {
                loadIfStillSignedIn()
            } else if (!startupResumeListenerRegistered) {
                startupResumeListenerRegistered = true
                lifecycle.registerActivityListener(persistentListOf(Lifecycle.State.RESUMED), once = true) {
                    startupResumeListenerRegistered = false
                    loadIfStillSignedIn()
                }
            }
        }
    }

    private fun tmpForceInstantUserConversionIfRequested(scope: CoroutineScope) {
        if (!Rownd.config.forceInstantUserConversion) {
            return
        }

        scope.launch {
            Rownd.state
                .map { it.auth.isAuthenticated to it.user }
                .distinctUntilChanged()
                .collect { (isAuthenticated, user) ->
                    if (
                        isAuthenticated &&
                        user.authLevel == AuthLevel.Instant
                    ) {
                        val signInOptions = RowndSignInOptions(
                            intent = RowndSignInIntent.SignUp,
                            title = "Add a sign-in method",
                            subtitle = "To ensure you can always access your account, please add a sign-in method."
                        )
                        Rownd.requestSignIn(signInOptions)
                    }
                }
        }
    }

    val state = store.stateAsStateFlow()

    fun getStore(): Store<GlobalState, StateAction> {
        return store
    }

    companion object {
        private var dataStore: DataStore<GlobalState>? = null
        
        @Synchronized
        fun defaultDataStore(context: Context): DataStore<GlobalState> {
            // DataStore must be a singleton
            dataStore?.let {
                return it
            }

            val ds = DataStoreFactory.create(
                storage = FileStorage(GlobalStateSerializer) {
                    context.applicationContext.dataStoreFile(Rownd.config.stateFileName)
                },
                corruptionHandler = ReplaceFileCorruptionHandler { ex ->
                    // Handle cases where on-device state has become corrupt.
                    // First, log the exception and send a trace
                    Log.w("Rownd.StateRepo", "Failed to load existing state from device", ex)
                    val tracer = Rownd.rowndContext.telemetry?.getTracer()
                    val span = tracer?.spanBuilder("globalStateCorruption")?.startSpan()
                    span?.setStatus(StatusCode.ERROR)
                    span?.recordException(ex)
                    span?.end()

                    // Finally, reset the state to default (unfortunately, signing-out the user)
                    return@ReplaceFileCorruptionHandler GlobalState()
                }
            )
            dataStore = ds
            return ds
        }
    }
}
