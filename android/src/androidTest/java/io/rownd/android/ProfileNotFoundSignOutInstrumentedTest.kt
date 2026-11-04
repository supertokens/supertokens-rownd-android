package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.LegacyMigrationApiClient
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A 404 for the profile signs the user out, but only the session whose request got it. The
 * decision and the sign-out are separate steps, so these tests change the world in between:
 * the sign-out is held at the lock that serializes it with session commits, the way a session
 * commit that is already in progress would hold it.
 */
@RunWith(AndroidJUnit4::class)
class ProfileNotFoundSignOutInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jwt = JwtGenerator()
    private val original = jwt.generateTestJwt(sessionHandle = "not-found-original")
    private val replacement = jwt.generateTestJwt(sessionHandle = "not-found-replacement")
    private val requested = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private lateinit var rownd: RowndClient
    private var commitHolder: Thread? = null
    private val commitCanFinish = CountDownLatch(1)
    private val revocations = AtomicInteger()

    @Before
    fun setUp() {
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.Builder(context, "https://api.example.com").apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
        SuperTokensSessionBridge.bootstrapSession(context, original, "original-refresh", replaceExisting = true)
        val engine = MockEngineConfig().apply {
            addHandler { request ->
                val json = headersOf("Content-Type", "application/json")
                when {
                    request.url.encodedPath.endsWith("/user") && request.method == HttpMethod.Get -> {
                        requested.complete(Unit)
                        release.await()
                        respond("""{"message":"user not found"}""", HttpStatusCode.NotFound, json)
                    }
                    request.url.encodedPath.endsWith("/user") ->
                        respond("""{"data":{"email":"saved@example.com"}}""", HttpStatusCode.OK, json)
                    else -> respond("""{"status":"OK"}""", HttpStatusCode.OK, json)
                }
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder().fakeNetworkModule(FakeNetworkModule(engine)).build())
        // Revocation of a signed-out session goes through its own transport.
        rownd.authRepo.legacyMigrationApiClient = LegacyMigrationApiClient(rownd.rowndContext, MockEngine(MockEngineConfig().apply {
            addHandler { request ->
                if (request.url.encodedPath.endsWith("/signout")) revocations.incrementAndGet()
                respond("""{"status":"OK"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        }))
        rownd.config.apiUrl = "https://api.example.com"
        rownd.config.applicationContext = context
        rownd.store = rownd.stateRepo.getStore()
        rownd.authenticatedApiClient.client = rownd.authenticatedApiClient.client.config {
            install(HttpRequestRetry) { maxRetries = 0 }
        }
        rownd.store.dispatch(StateAction.SetAuth(AuthState(accessToken = original)))
        rownd.store.dispatch(StateAction.SetUser(User(data = mapOf("email" to "old@example.com"))))
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        commitCanFinish.countDown()
        commitHolder?.join(5_000)
        rownd.authenticatedApiClient.client.close()
        SuperTokensSessionBridge.clearLocalSession(context)
        SuperTokens.resetForTests()
        SuperTokensSessionBridge.isInitialized.set(false)
    }

    @Test
    fun notFoundForTheCurrentSessionSignsOut() = runBlocking {
        val load = rownd.userRepo.loadUserAsync()
        withTimeout(5_000) { requested.await() }
        release.complete(Unit)

        assertNull(withTimeout(5_000) { load.await() })

        assertFalse(rownd.state.value.auth.isAuthenticated)
        assertEquals(User(), rownd.state.value.user)
        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
        assertEquals(1, revocations.get())
    }

    @Test
    fun sessionReplacedInTheSdkBeforeTheResponseIsNotSignedOut() = runBlocking {
        val load = rownd.userRepo.loadUserAsync()
        withTimeout(5_000) { requested.await() }
        // The store learns of a session the SDK already holds only when it next reads the SDK.
        SuperTokensSessionBridge.bootstrapSession(context, replacement, "replacement-refresh", replaceExisting = true)
        release.complete(Unit)

        assertNull(withTimeout(5_000) { load.await() })

        assertEquals(replacement, SuperTokensSessionBridge.getAccessToken(context))
        assertEquals("replacement-refresh", SuperTokensSessionBridge.getRefreshToken(context))
        assertTrue(rownd.state.value.auth.isAuthenticated)
        assertEquals(0, revocations.get())
    }

    @Test
    fun sessionCommittedAfterTheDecisionIsNotSignedOut() = runBlocking {
        val load = rownd.userRepo.loadUserAsync()
        withTimeout(5_000) { requested.await() }
        holdSessionCommits(thenInstall = replacement)
        release.complete(Unit)
        awaitSignOutWaitingForSessionCommit()

        commitCanFinish.countDown()
        assertNull(withTimeout(5_000) { load.await() })

        assertEquals("Sign-out for the old session cleared the session that replaced it",
            replacement, SuperTokensSessionBridge.getAccessToken(context))
        assertEquals("replacement-refresh", SuperTokensSessionBridge.getRefreshToken(context))
        assertTrue(rownd.state.value.auth.isAuthenticated)
        assertEquals(0, revocations.get())
    }

    @Test
    fun requestStartedAfterTheDecisionKeepsTheUserSignedIn() = runBlocking {
        val load = rownd.userRepo.loadUserAsync()
        withTimeout(5_000) { requested.await() }
        holdSessionCommits(thenInstall = null)
        release.complete(Unit)
        awaitSignOutWaitingForSessionCommit()

        // The 404 was accepted as the latest request; now a newer one starts before the sign-out commits.
        val save = rownd.userRepo.set("nickname", "newer")
        commitCanFinish.countDown()
        assertNull(withTimeout(5_000) { load.await() })
        assertEquals("saved@example.com", withTimeout(5_000) { save.await() }?.data?.get("email"))

        assertTrue("A 404 superseded after its decision signed the user out", rownd.state.value.auth.isAuthenticated)
        assertEquals("saved@example.com", rownd.state.value.user.data["email"])
        assertEquals("original-refresh", SuperTokensSessionBridge.getRefreshToken(context))
        assertEquals(0, revocations.get())
    }

    /**
     * Holds the lock that serializes session commits and sign-outs until [commitCanFinish]. With
     * [thenInstall] the held commit then installs that session; without it the commit is abandoned.
     */
    private fun holdSessionCommits(thenInstall: String?) {
        val holding = CountDownLatch(1)
        val checks = AtomicInteger()
        commitHolder = thread {
            SuperTokensSessionBridge.bootstrapSessionIfCurrent(
                context = context,
                accessToken = thenInstall ?: original,
                refreshToken = "replacement-refresh",
                frontToken = null,
                antiCSRF = null,
                replaceExisting = true,
                expectedSignOutGeneration = SuperTokensSessionBridge.currentSignOutGeneration(),
                // Checked once before the lock is taken and once inside it.
                isCurrent = {
                    if (checks.incrementAndGet() < 2) true else {
                        holding.countDown()
                        commitCanFinish.await()
                        thenInstall != null
                    }
                },
            )
        }
        assertTrue(holding.await(5, TimeUnit.SECONDS))
    }

    private fun awaitSignOutWaitingForSessionCommit() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val waiting = Thread.getAllStackTraces().any { (thread, stack) ->
                thread.state == Thread.State.BLOCKED && stack.any { it.methodName.startsWith("beginSignOut") }
            }
            if (waiting) return
            Thread.sleep(20)
        }
        throw AssertionError("The 404 was never decided and handed to the sign-out")
    }
}
