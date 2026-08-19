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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import java.util.concurrent.atomic.AtomicLong
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
    private val loadGeneration = AtomicLong()

    internal fun setIsLoading(value: Boolean) {
        stateRepo.getStore().dispatch(StateAction.SetUserIsLoading(value))
    }

    internal fun loadUserAsync(): Deferred<User?> {
        return CoroutineScope(Dispatchers.IO).async {
            loadUserIfCurrent { true }
        }
    }

    internal suspend fun loadUserIfCurrent(isCurrentAuthentication: () -> Boolean): User? {
        val generation = loadGeneration.incrementAndGet()
        setIsLoading(value = true)
        return try {
            val user: NetworkUser = authenticatedApiClient.client.get(rowndPluginUrl("user")) {
                headers { remove("x-rownd-app-key") }
            }.body()
            if (generation == loadGeneration.get()) {
                setIsLoading(value = false)
                if (isCurrentAuthentication()) {
                    user.asDomainModel(stateRepo, this@UserRepo).also {
                        stateRepo.getStore().dispatch(StateAction.SetUser(it))
                    }
                } else {
                    null
                }
            } else {
                null
            }
        } catch (ex: CancellationException) {
            if (generation == loadGeneration.get()) {
                setIsLoading(value = false)
            }
            throw ex
        } catch (ex: ClientRequestException) {
            if (generation == loadGeneration.get()) {
                setIsLoading(value = false)
                Log.e("RowndUsersApi", "Failed to fetch the user")

                if (ex.response.status == HttpStatusCode.NotFound && isCurrentAuthentication()) {
                    rowndContext.client?.signOut()
                }
            }
            null
        } catch (ex: Exception) {
            if (generation == loadGeneration.get()) {
                setIsLoading(value = false)
                Log.e("RowndUsersApi", "Failed to fetch the user")
            }
            null
        }
    }

    internal fun saveUserAsync(user: User): Deferred<User?> {
        // Create network user based on domain user
        val networkUser = user.asNetworkModel(stateRepo, this)
        return CoroutineScope(Dispatchers.IO).async {
            try {
                setIsLoading(value = true)
                val savedUser: NetworkUser =
                    authenticatedApiClient.client.put(rowndPluginUrl("user")) {
                        headers { remove("x-rownd-app-key") }
                        setBody(
                            networkUser
                        )
                    }.body()
                stateRepo.getStore().dispatch(StateAction.SetUser(savedUser.asDomainModel(stateRepo, this@UserRepo)))
                setIsLoading(value = false)
                return@async savedUser.asDomainModel(stateRepo, this@UserRepo)
            } catch (ex: Exception) {
                setIsLoading(value = false)
                Log.e("RowndUsersApi", "Failed to save the user")
                throw RowndException("Failed to save the user")
            }
        }
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
        stateRepo.getStore().dispatch(StateAction.SetUser(updatedUser))

        return saveUserAsync(updatedUser)
    }

    fun set(field: String, data: Any): Deferred<User?> {
        val existingUser = stateRepo.state.value.user
        val userData = existingUser.data.toMutableMap()
        userData[field] = data
        val updatedUser = existingUser.copy(
            data = userData
        )
        stateRepo.getStore().dispatch(StateAction.SetUser(updatedUser))
        return saveUserAsync(User(data = mapOf(field to data)))
    }

    private fun rowndPluginUrl(path: String): String {
        val st = stateRepo.state.value.appConfig.config.supertokens
        val apiDomain = st.appInfo.apiDomain.trimEnd('/')
        val apiBasePath = st.appInfo.apiBasePath?.trimEnd('/') ?: "/auth"

        return "$apiDomain$apiBasePath/plugin/rownd/$path"
    }

}
