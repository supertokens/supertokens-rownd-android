package io.rownd.android.models.repos

import android.util.Log
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.rownd.android.models.domain.User
import io.rownd.android.util.AuthenticatedApiClient
import io.rownd.android.util.RowndContext
import io.rownd.android.util.RowndException
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton
import io.rownd.android.models.network.User as NetworkUser

@Singleton
class UserRepo @Inject constructor() {
    @Inject
    lateinit var rowndContext: RowndContext

    @Inject
    lateinit var stateRepo: StateRepo

    @Inject
    lateinit var authenticatedApiClient: AuthenticatedApiClient

    private class ProfileRequest(val id: Long, val accessToken: String?, val isCurrent: () -> Boolean)

    // The session is identified by its handle, so a refresh during the request keeps the response.
    private fun beginRequest(isCurrent: () -> Boolean = { true }, update: (User) -> User = { it }): ProfileRequest {
        val accessToken = stateRepo.state.value.auth.accessToken
        val request = ProfileRequest(stateRepo.nextProfileRequestId(), accessToken, isCurrent)
        stateRepo.getStore().dispatch(StateAction.BeginProfileRequest(request.id, accessToken, update))
        return request
    }

    private fun owns(request: ProfileRequest): Boolean =
        request.isCurrent() && stateRepo.ownsProfile(request.id, request.accessToken)

    private fun complete(request: ProfileRequest, user: User? = null): Boolean {
        stateRepo.getStore().dispatch(StateAction.CompleteProfileRequest(request.id, request.accessToken, user, request.isCurrent))
        return owns(request)
    }

    // A response for a replaced session is dropped. Unless a newer request already covers the
    // current session, load its profile instead.
    private fun scheduleFreshLoad(request: ProfileRequest) {
        val accessToken = stateRepo.state.value.auth.accessToken ?: return
        if (stateRepo.isLatestProfileRequest(request.id) &&
            !SuperTokensSessionBridge.isSameSession(request.accessToken, accessToken)) {
            loadUserAsync(allowFreshLoad = false)
        }
    }

    internal fun loadUserAsync(isCurrentAuthentication: () -> Boolean = { true }): Deferred<User?> =
        loadUserAsync(isCurrentAuthentication, allowFreshLoad = true)

    private fun loadUserAsync(isCurrentAuthentication: () -> Boolean = { true }, allowFreshLoad: Boolean): Deferred<User?> {
        val request = beginRequest(isCurrentAuthentication)
        return CoroutineScope(Dispatchers.IO).async { loadUser(request, allowFreshLoad) }
    }

    internal suspend fun loadUserIfCurrent(isCurrentAuthentication: () -> Boolean): User? =
        loadUser(beginRequest(isCurrentAuthentication), allowFreshLoad = true)

    private suspend fun loadUser(request: ProfileRequest, allowFreshLoad: Boolean): User? {
        return try {
            val user: NetworkUser = authenticatedApiClient.client.get(rowndPluginUrl("user")) {
                headers { remove("x-rownd-app-key") }
            }.body()
            val result = user.asDomainModel(stateRepo, this@UserRepo)
            if (complete(request, result)) {
                result
            } else {
                if (allowFreshLoad) scheduleFreshLoad(request)
                null
            }
        } catch (ex: CancellationException) {
            complete(request)
            throw ex
        } catch (ex: ClientRequestException) {
            Log.e("RowndUsersApi", "Failed to fetch the user")
            val owned = owns(request)
            complete(request)
            if (!owned) {
                if (allowFreshLoad) scheduleFreshLoad(request)
            } else if (ex.response.status == HttpStatusCode.NotFound) {
                rowndContext.client?.signOut()
            } else if (ex.response.status == HttpStatusCode.Unauthorized) {
                // The SDK clears a session the server rejects during refresh; follow it.
                try {
                    stateRepo.authRepo.getLatestAuthState()
                } catch (err: CancellationException) {
                    throw err
                } catch (err: Exception) {
                    Log.d("RowndUsersApi", "Failed to reconcile the session after an unauthorized response", err)
                }
            }
            null
        } catch (ex: Exception) {
            complete(request)
            Log.e("RowndUsersApi", "Failed to fetch the user")
            null
        }
    }

    internal fun saveUserAsync(user: User): Deferred<User?> = saveUserAsync(user, beginRequest())

    private fun saveUserAsync(user: User, request: ProfileRequest): Deferred<User?> {
        // Create network user based on domain user
        val networkUser = user.asNetworkModel(stateRepo, this)
        return CoroutineScope(Dispatchers.IO).async {
            try {
                val savedUser: NetworkUser =
                    authenticatedApiClient.client.put(rowndPluginUrl("user")) {
                        headers { remove("x-rownd-app-key") }
                        setBody(
                            networkUser
                        )
                    }.body()
                val result = savedUser.asDomainModel(stateRepo, this@UserRepo)
                if (complete(request, result)) result else null
            } catch (ex: CancellationException) {
                complete(request)
                throw ex
            } catch (ex: Exception) {
                complete(request)
                Log.e("RowndUsersApi", "Failed to save the user")
                throw RowndException("Failed to save the user")
            }
        }
    }

    // The Hub saved the profile itself; that supersedes any request still in flight.
    internal fun applyHubUpdate(user: User, accessToken: String) {
        if (!SuperTokensSessionBridge.isSameSession(accessToken, stateRepo.state.value.auth.accessToken)) return
        val request = ProfileRequest(stateRepo.nextProfileRequestId(), accessToken) { true }
        if (!complete(request, user)) scheduleFreshLoad(request)
    }

    fun get(): User {
        return stateRepo.state.value.user
    }

    fun refresh(): Deferred<User?> {
        return loadUserAsync()
    }

    fun <T> get(field: String): T? {
        val value = stateRepo.state.value.user.data[field] ?: return null

        return try {
            value as T
        } catch (error: Exception) {
            null
        }
    }

    fun set(data: Map<String, Any>): Deferred<User?> {
        val updatedUser = User(
            data = data
        )
        return saveUserAsync(updatedUser, beginRequest { updatedUser })
    }

    fun set(field: String, data: Any): Deferred<User?> {
        val request = beginRequest { it.copy(data = it.data + (field to data)) }
        return saveUserAsync(User(data = mapOf(field to data)), request)
    }

    private fun rowndPluginUrl(path: String): String {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain.trimEnd('/')
        val apiBasePath = st.appInfo.apiBasePath?.trimEnd('/') ?: "/auth"

        return "$apiDomain$apiBasePath/plugin/rownd/$path"
    }

}
