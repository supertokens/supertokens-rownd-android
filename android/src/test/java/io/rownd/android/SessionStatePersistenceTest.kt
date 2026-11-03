package io.rownd.android

import io.rownd.android.models.domain.AppConfigState
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SignInState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.GlobalState
import io.rownd.android.models.repos.GlobalStateSerializer
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class SessionStatePersistenceTest {
    private val nativeToken = jwt("""{"sub":"user-a","sessionHandle":"handle-a","exp":9999999999}""")
    private val legacyAccessToken = jwt("""{"https://auth.rownd.io/app_user_id":"user-a","exp":9999999999}""")

    @Test
    fun nativeSessionAndProfileAreNeverSerialized() = runBlocking {
        val state = GlobalState(
            appConfig = AppConfigState(id = "app-a"),
            auth = AuthState(accessToken = nativeToken),
            user = User(data = mapOf("email" to "old@example.com")),
            signIn = SignInState(lastSignIn = "email"),
        )

        val raw = write(state)

        assertFalse(raw.contains(nativeToken))
        assertFalse(raw.contains("old@example.com"))
        val restored = read(raw)
        assertEquals(AuthState(), restored.auth)
        assertEquals(User(), restored.user)
        assertEquals(state.appConfig, restored.appConfig)
        assertEquals(state.signIn, restored.signIn)
    }

    @Test
    fun sessionStatePersistedByOlderVersionsIsDiscardedOnRead() = runBlocking {
        val raw = """{"auth":{"access_token":"$nativeToken"},"user":{"data":{"email":"old@example.com"}},
            "signIn":{"last_sign_in":"email"}}"""

        val restored = read(raw)

        assertEquals(AuthState(), restored.auth)
        assertEquals(User(), restored.user)
        assertEquals("email", restored.signIn.lastSignIn)
    }

    @Test
    fun legacyCredentialAwaitingMigrationSurvivesARestart() = runBlocking {
        val auth = AuthState(accessToken = legacyAccessToken, refreshToken = "legacy-refresh", challengeId = "challenge")

        val restored = read(write(GlobalState(auth = auth, user = User(data = mapOf("email" to "old@example.com")))))

        assertEquals(AuthState(accessToken = legacyAccessToken, refreshToken = "legacy-refresh"), restored.auth)
        assertEquals(User(), restored.user)
    }

    @Test
    fun restoreDoesNotOverwriteStateChangedDuringStartup() {
        val repo = StateRepo()
        val store = repo.getStore()
        val initial = store.currentState
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = nativeToken)))
        store.dispatch(StateAction.SetUser(User(data = mapOf("email" to "new@example.com"))))
        store.dispatch(StateAction.SetSignIn(SignInState(lastSignIn = "google")))

        store.dispatch(StateAction.FinishInitialization(initial, GlobalState(
            appConfig = AppConfigState(id = "persisted-app"),
            auth = AuthState(accessToken = legacyAccessToken, refreshToken = "legacy-refresh"),
            signIn = SignInState(lastSignIn = "email"),
        )))

        val state = store.currentState
        assertTrue(state.isInitialized)
        assertEquals(nativeToken, state.auth.accessToken)
        assertEquals("new@example.com", state.user.data["email"])
        assertEquals("google", state.signIn.lastSignIn)
        assertEquals("persisted-app", state.appConfig.id)
    }

    @Test
    fun restoreAppliesPersistedStateWhenNothingChanged() {
        val store = StateRepo().getStore()
        val legacy = AuthState(accessToken = legacyAccessToken, refreshToken = "legacy-refresh")

        store.dispatch(StateAction.FinishInitialization(store.currentState, GlobalState(
            auth = legacy,
            signIn = SignInState(lastSignIn = "email"),
        )))

        assertTrue(store.currentState.isInitialized)
        assertEquals(legacy, store.currentState.auth)
        assertEquals("email", store.currentState.signIn.lastSignIn)
    }

    @Test
    fun failedRestoreStillFinishesInitialization() {
        val store = StateRepo().getStore()

        store.dispatch(StateAction.FinishInitialization(store.currentState, null))

        assertTrue(store.currentState.isInitialized)
        assertEquals(GlobalState(isInitialized = true), store.currentState)
    }

    private fun jwt(payload: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString("""{"alg":"HS256"}""".toByteArray())}.${encoder.encodeToString(payload.toByteArray())}.sig"
    }

    private suspend fun write(state: GlobalState): String =
        ByteArrayOutputStream().also { GlobalStateSerializer.writeTo(state, it) }.toString("UTF-8")

    private suspend fun read(raw: String): GlobalState =
        GlobalStateSerializer.readFrom(ByteArrayInputStream(raw.toByteArray()))
}
