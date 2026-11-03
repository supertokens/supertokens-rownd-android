package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.rownd.android.views.HubPageSelector
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pylon #3832: a profile written by an older request or an older session must not replace newer
 * profile data. The harness holds a profile response after the server computed it, so the stale
 * body arrives deterministically after the newer write.
 */
@RunWith(AndroidJUnit4::class)
class ProfileWriteOrdering3832ReproInstrumentedTest {
    private val fixture = EmailChangeReproFixture()

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() = fixture.tearDown()

    @Test
    fun lateProfileLoadCannotOverwriteHubUserDataUpdate() = runBlocking {
        val userId = fixture.uniqueUserId("load-vs-hub")
        val newEmail = "changed-$userId@example.com"
        val process = signedInProcess(userId)
        val hub = fixture.hubBridge(process, HubPageSelector.ManageAccount)

        HarnessClient.armResponseGate("GET", PROFILE_PATH)
        val load = process.client.userRepo.refresh()
        fixture.waitUntil("profile load must reach the server") { HarnessClient.responseGateReached() }
        HarnessClient.changeUserEmail(userId, newEmail)
        hub.postSecureMessage(fixture.userDataUpdateMessage(mapOf("user_id" to userId, "email" to newEmail)))
        assertEquals(newEmail, process.state.user.data["email"])

        HarnessClient.releaseResponseGate()
        withTimeout(10_000) { load.await() }
        assertEquals("Load started before the Hub update overwrote it", newEmail, process.state.user.data["email"])
    }

    @Test
    fun lateProfileLoadCannotOverwriteSavedField() = runBlocking {
        val userId = fixture.uniqueUserId("load-vs-save")
        val process = signedInProcess(userId)

        HarnessClient.armResponseGate("GET", PROFILE_PATH)
        val load = process.client.userRepo.refresh()
        fixture.waitUntil("profile load must reach the server") { HarnessClient.responseGateReached() }
        withTimeout(10_000) { process.client.user.set("nickname", "saved").await() }
        assertEquals("saved", process.state.user.data["nickname"])

        HarnessClient.releaseResponseGate()
        withTimeout(10_000) { load.await() }
        assertEquals("Load started before the save overwrote it", "saved", process.state.user.data["nickname"])
    }

    @Test
    fun lateSaveResponseFromReplacedSessionCannotOverwriteNewerProfile() = runBlocking {
        val userId = fixture.uniqueUserId("save-vs-session")
        val newEmail = "changed-$userId@example.com"
        val process = signedInProcess(userId)
        val oldSession = process.state.auth.accessToken!!

        HarnessClient.armResponseGate("PUT", PROFILE_PATH)
        val save = process.client.user.set("nickname", "from-old-session")
        fixture.waitUntil("save must be answered for the old session") { HarnessClient.responseGateReached() }
        HarnessClient.changeUserEmail(userId, newEmail)
        val replacement = HarnessClient.createSTSession(userId)
        fixture.signInThroughHub(process, replacement)
        HarnessClient.revokeSession(fixture.handle(oldSession))
        assertEquals(newEmail, process.state.user.data["email"])

        HarnessClient.releaseResponseGate()
        runCatching { withTimeout(10_000) { save.await() } }
        assertEquals(replacement.accessToken, process.state.auth.accessToken)
        assertEquals("Old session's save response overwrote the newer profile", newEmail, process.state.user.data["email"])
    }

    @Test
    fun hubUserDataUpdateFromReplacedSessionIsIgnored() {
        val userId = fixture.uniqueUserId("hub-old-session")
        val oldEmail = "$userId@example.com"
        val newEmail = "changed-$userId@example.com"
        val process = signedInProcess(userId)
        val oldSession = process.state.auth.accessToken!!
        val oldManageAccount = fixture.hubBridge(process, HubPageSelector.ManageAccount)

        HarnessClient.changeUserEmail(userId, newEmail)
        fixture.signInThroughHub(process, HarnessClient.createSTSession(userId))
        HarnessClient.revokeSession(fixture.handle(oldSession))
        assertEquals(newEmail, process.state.user.data["email"])

        oldManageAccount.postSecureMessage(fixture.userDataUpdateMessage(mapOf("user_id" to userId, "email" to oldEmail)))
        assertEquals("Hub document from the replaced session wrote its profile", newEmail, process.state.user.data["email"])
    }

    private fun signedInProcess(userId: String): EmailChangeReproFixture.AppProcess {
        val process = fixture.launch()
        process.awaitStartup()
        fixture.signInThroughHub(process, HarnessClient.createSTSession(userId))
        assertEquals("$userId@example.com", process.state.user.data["email"])
        return process
    }

    private companion object {
        const val PROFILE_PATH = "/auth/plugin/rownd/user"
    }
}
