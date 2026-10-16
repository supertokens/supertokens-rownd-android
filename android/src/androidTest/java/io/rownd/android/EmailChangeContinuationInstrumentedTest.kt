package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.rownd.android.models.EmailChangeDescriptor
import io.rownd.android.models.RowndConfig
import io.rownd.android.util.EmailChangeContinuation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmailChangeContinuationInstrumentedTest {
    private val config = RowndConfig(baseUrl = "https://hub.example", apiUrl = "https://api.example", appKey = "app")
    private val storage = EmailChangeContinuation { config }
    private val descriptor get() = EmailChangeDescriptor(2, "request-123456789", EmailChangeDescriptor.LocalDraft(
        "owner", "public", "config", EmailChangeDescriptor.Changes("destination@example.test")),
        System.currentTimeMillis() + 60_000)

    @Test fun invalidDescriptorsNeverEnterMemory() {
        val valid = descriptor
        for (invalid in listOf(valid.copy(version = 1), valid.copy(requestId = "short"), valid.copy(expiresAt = 0),
            valid.copy(expiresAt = System.currentTimeMillis() + 16 * 60_000),
            valid.copy(localDraft = valid.localDraft.copy(changes = EmailChangeDescriptor.Changes(""))),
            valid.copy(localDraft = valid.localDraft.copy(changes = EmailChangeDescriptor.Changes("a".repeat(255)))),
            valid.copy(localDraft = valid.localDraft.copy(configuration = "a".repeat(33 * 1024))))) {
            assertFalse(storage.begin(invalid))
            assertNull(storage.read())
        }
    }

    @Test fun warmCallbackRequiresOwnedChallengeAndCannotSurviveNewInstance() {
        val owned = descriptor
        assertTrue(storage.begin(owned))
        assertTrue(storage.readyForSignIn(owned.requestId))
        val url = "https://hub.example/account/login?preAuthSessionId=challenge"
        assertNull(storage.claimCallback(url))
        assertTrue(storage.challenge(owned.requestId, "challenge"))
        assertNull(EmailChangeContinuation { config }.claimCallback(url))
        assertNull(storage.claimCallback("https://untrusted.example/account/login?preAuthSessionId=challenge"))
        assertNull(storage.claimCallback("$url&preAuthSessionId=challenge"))
        assertNull(storage.claimCallback("$url&apiDomain=https://foreign.example"))
        assertEquals(owned, storage.claimCallback(url))
        assertNull(storage.claimCallback(url))
    }

    @Test fun configChangeExpiryAndCancellationAreFailClosed() {
        val owned = descriptor
        storage.begin(owned)
        assertFalse(storage.clear("foreign"))
        assertEquals(owned, storage.read()?.descriptor)
        config.appKey = "another-app"
        assertNull(storage.read())
        storage.begin(owned)
        assertNull(storage.read(owned.expiresAt))
        storage.begin(owned)
        storage.readyForSignIn(owned.requestId)
        storage.clear()
        assertFalse(storage.authenticated(owned.requestId))
    }

    @Test fun oneShotDeliveryConsumesRoutingBeforeDispatchAndNeverRestores() {
        val owned = descriptor
        storage.begin(owned)
        assertFalse(storage.authenticated(owned.requestId))
        storage.readyForSignIn(owned.requestId)
        assertFalse(storage.authenticated("other-request"))
        assertTrue(storage.authenticated(owned.requestId))
        assertNull(storage.takeResume(owned.copy(requestId = "other-request-1234")))
        assertEquals(owned, storage.takeResume(owned))
        assertNull(storage.read())
        assertNull(storage.takeResume(owned))
        assertNull(EmailChangeContinuation { config }.read())
    }
}
