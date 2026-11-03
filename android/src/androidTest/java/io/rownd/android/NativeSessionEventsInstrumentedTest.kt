package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.supertokens.session.EventHandler
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Requests made outside Rownd, such as the host app's own API calls, change the session through SDK events. */
@RunWith(AndroidJUnit4::class)
class NativeSessionEventsInstrumentedTest {
    private val fixture = EmailChangeReproFixture()
    private val listeners = mutableListOf<(EventHandler.EventType) -> Unit>()

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() {
        listeners.forEach(SuperTokensSessionBridge::removeSessionEventListener)
        fixture.tearDown()
    }

    @Test
    fun hostRequestRejectedForRevokedSessionSignsOut() {
        val session = HarnessClient.createSTSession(fixture.uniqueUserId("host-401"))
        val process = fixture.launch()
        process.awaitStartup()
        fixture.signInThroughHub(process, session)
        followNativeSessionEvents(process)
        HarnessClient.revokeSession(fixture.handle(session.accessToken))

        hostRequest().use { assertEquals(401, it.code) }

        fixture.waitUntil("Rownd must follow the SDK's UNAUTHORISED event", 5_000) {
            !process.state.auth.isAuthenticated && process.state.user.data.isEmpty()
        }
    }

    @Test
    fun nativeRefreshUpdatesStoreAccessToken() {
        val userId = fixture.uniqueUserId("refresh")
        val session = HarnessClient.createSTSession(userId)
        val process = fixture.launch()
        process.awaitStartup()
        fixture.signInThroughHub(process, session)
        followNativeSessionEvents(process)

        assertTrue(SuperTokens.attemptRefreshingSession(fixture.context))
        val refreshed = runBlocking { SuperTokensSessionBridge.getAccessToken(fixture.context) }
        assertNotEquals(session.accessToken, refreshed)

        fixture.waitUntil("Rownd must follow the SDK's REFRESH_SESSION event", 5_000) {
            process.state.auth.accessToken == refreshed
        }
        assertEquals(userId, process.state.user.data["user_id"])
    }

    private fun followNativeSessionEvents(process: EmailChangeReproFixture.AppProcess) {
        SuperTokens.resetForTests()
        SuperTokens.Builder(fixture.context, fixture.origin)
            .apiBasePath("/auth")
            .tokenTransferMethod("header")
            .eventHandler(SuperTokensSessionBridge.sessionEventHandler)
            .build()
        SuperTokensSessionBridge.addSessionEventListener(process.client.nativeSessionEventListener)
        listeners += process.client.nativeSessionEventListener
    }

    private fun hostRequest() = OkHttpClient.Builder()
        .addInterceptor(SuperTokensInterceptor())
        .build()
        .newCall(Request.Builder().url("${fixture.origin}/auth/plugin/rownd/user").header("rid", "session").build())
        .execute()
}
