package io.rownd.android

import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.models.repos.StateRepo
import io.rownd.android.util.SuperTokensSessionBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

class ProfileRequestOwnershipTest {
    private val repo = StateRepo()
    private val store = repo.getStore()
    private val session = token("handle-a", revision = 1)

    init {
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = session)))
    }

    @Test
    fun olderRequestCannotOverwriteOrFinishNewerOne() {
        val load = begin()
        val save = begin { User(data = mapOf("nickname" to "saved")) }

        complete(load, User(data = mapOf("nickname" to "loaded-earlier")))
        assertEquals("saved", repo.state.value.user.data["nickname"])
        assertTrue(repo.state.value.user.isLoading)

        complete(save, User(data = mapOf("nickname" to "saved-on-server")))
        assertEquals("saved-on-server", repo.state.value.user.data["nickname"])
        assertFalse(repo.state.value.user.isLoading)
    }

    @Test
    fun responseForReplacedSessionIsDropped() {
        val load = begin()
        val replacement = User(data = mapOf("email" to "new@example.com"))
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = token("handle-b", revision = 1))))
        store.dispatch(StateAction.SetUser(replacement))

        complete(load, User(data = mapOf("email" to "old@example.com")))
        assertEquals(replacement, repo.state.value.user)
    }

    @Test
    fun refreshWithinTheSameSessionKeepsResponse() {
        val load = begin()
        store.dispatch(StateAction.SetAuth(AuthState(accessToken = token("handle-a", revision = 2))))

        complete(load, User(data = mapOf("email" to "current@example.com")))
        assertEquals("current@example.com", repo.state.value.user.data["email"])
        assertFalse(repo.state.value.user.isLoading)
    }

    @Test
    fun optimisticUpdateForReplacedSessionIsDropped() {
        val current = User(data = mapOf("nickname" to "current"))
        store.dispatch(StateAction.SetUser(current))
        val id = repo.nextProfileRequestId()
        store.dispatch(StateAction.BeginProfileRequest(id, token("handle-old", revision = 1)) {
            User(data = mapOf("nickname" to "stale"))
        })
        assertEquals(current, repo.state.value.user)
    }

    @Test
    fun supersededCallerFinishesLoadingWithoutWriting() {
        store.dispatch(StateAction.SetUser(User(data = mapOf("email" to "old@example.com"))))
        val load = begin()
        store.dispatch(StateAction.CompleteProfileRequest(load, session, User(data = mapOf("email" to "late@example.com"))) { false })
        assertEquals("old@example.com", repo.state.value.user.data["email"])
        assertFalse(repo.state.value.user.isLoading)
    }

    @Test
    fun completionReportsWhetherItsOwnResponseWasApplied() {
        val applied = AtomicBoolean()
        val load = begin()
        store.dispatch(StateAction.CompleteProfileRequest(load, session, User(data = mapOf("email" to "a@example.com")), applied))
        assertTrue(applied.get())

        // A request that starts after the response was applied must not retroactively un-apply it.
        begin()
        assertTrue(applied.get())
        assertEquals("a@example.com", repo.state.value.user.data["email"])
    }

    @Test
    fun completionOfASupersededRequestIsNotReportedAsApplied() {
        val applied = AtomicBoolean(true)
        val load = begin()
        begin()
        store.dispatch(StateAction.CompleteProfileRequest(load, session, User(data = mapOf("email" to "late@example.com")), applied))
        assertFalse(applied.get())
    }

    @Test
    fun completionRejectedByItsOwnerIsNotReportedAsApplied() {
        val applied = AtomicBoolean(true)
        val load = begin()
        store.dispatch(StateAction.CompleteProfileRequest(load, session, User(data = mapOf("email" to "late@example.com")), applied) { false })
        assertFalse(applied.get())
    }

    @Test
    fun tokensWithoutSessionHandleOnlyMatchThemselves() {
        assertTrue(SuperTokensSessionBridge.isSameSession("legacy-a", "legacy-a"))
        assertTrue(SuperTokensSessionBridge.isSameSession(null, null))
        assertFalse(SuperTokensSessionBridge.isSameSession("legacy-a", "legacy-b"))
        assertFalse(SuperTokensSessionBridge.isSameSession(session, null))
        assertFalse(SuperTokensSessionBridge.isSameSession(token("handle-a", 1, sub = "user-a"), token("handle-a", 1, sub = "user-b")))
    }

    private fun begin(update: (User) -> User = { it }): Long =
        repo.nextProfileRequestId().also { store.dispatch(StateAction.BeginProfileRequest(it, session, update)) }

    private fun complete(id: Long, user: User) = store.dispatch(StateAction.CompleteProfileRequest(id, session, user))

    private fun token(handle: String, revision: Int, sub: String = "user-a"): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"sessionHandle":"$handle","sub":"$sub","tId":"public","revision":$revision}""".toByteArray(),
        )
        return "header.$payload.signature"
    }
}
