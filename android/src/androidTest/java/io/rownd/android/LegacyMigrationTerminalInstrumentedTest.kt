package io.rownd.android

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.GlobalState
import io.rownd.android.models.repos.GlobalStateSerializer
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.LegacyMigrationApiClient
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class LegacyMigrationTerminalInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val jwt = JwtGenerator()
    private lateinit var rownd: RowndClient
    private var calls = 0
    private var status = HttpStatusCode.Gone
    private var body = """{"status":"ERROR","code":"LEGACY_USER_NOT_FOUND","message":"untrusted server text"}"""
    private var responseHeaders = headersOf()
    private var beforeResponse: suspend () -> Unit = {}
    private val userRequested = CompletableDeferred<Unit>()
    private val releaseUser = CompletableDeferred<Unit>()

    @Before
    fun setUp() {
        try {
            SuperTokens.Builder(context, "https://api.example.com")
                .apiBasePath("/auth").tokenTransferMethod("header").build()
        } catch (_: Exception) {
            // The SDK singleton may already be initialized by another instrumentation class.
        }
        SuperTokensSessionBridge.isInitialized.set(true)
        clearSession()
        val engine = MockEngineConfig().apply {
            addHandler { request ->
                if (request.url.encodedPath.endsWith("/user")) {
                    userRequested.complete(Unit)
                    releaseUser.await()
                    return@addHandler respond("""{"data":{"user_id":"stale-user"}}""", HttpStatusCode.OK,
                        headersOf("Content-Type", "application/json"))
                }
                calls++
                beforeResponse()
                respond(body, status, responseHeaders)
            }
        }
        rownd = RowndClient(DaggerTestRowndGraph.builder().fakeNetworkModule(FakeNetworkModule(engine)).build())
        rownd.config.apiUrl = "https://api.example.com"
        rownd.config.applicationContext = context
        rownd.authRepo.legacyMigrationApiClient = LegacyMigrationApiClient(rownd.rowndContext, MockEngine(engine))
        rownd.authRepo.legacyMigrationApiClient.client = rownd.authRepo.legacyMigrationApiClient.client.config {
            install(HttpRequestRetry) { maxRetries = 0 }
        }
        seedLegacySession()
    }

    private fun seedLegacySession() {
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            isLoading = true, accessToken = jwt.generateTestJwt(appUserId = "legacy-user"), refreshToken = "legacy-refresh",
        )))
        rownd.stateRepo.getStore().dispatch(StateAction.SetUser(User(data = mapOf("user_id" to "legacy-user"), isLoading = true)))
    }

    @After
    fun tearDown() {
        releaseUser.complete(Unit)
        rownd.authRepo.legacyMigrationApiClient.client.close()
        rownd.authenticatedApiClient.client.close()
        clearSession()
    }

    private fun clearSession() {
        SuperTokensSessionBridge.clearLocalSession(context)
        context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun terminalPublishesNormalSignedOutStateAndRelaunchDoesNotRetry() = runBlocking {
        val signedOut = async(Dispatchers.Unconfined) {
            rownd.state.first { it.auth == AuthState() && it.user == User() }
        }
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        withTimeout(5_000) { signedOut.await() }
        assertEquals(2, calls)
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
        assertFalse(rownd.state.value.auth.isAuthenticated)
        assertNull(rownd.state.value.auth.toRphInitHash(rownd.userRepo, context))

        val persisted = GlobalStateSerializer.json.encodeToString(GlobalState.serializer(), rownd.state.value)
        val relaunched = GlobalStateSerializer.json.decodeFromString(GlobalState.serializer(), persisted)
        rownd.stateRepo.getStore().dispatch(StateAction.SetGlobalState(relaunched))
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(2, calls)
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
    }

    @Test
    fun malformedAndUnrelatedGoneResponsesClearAttemptedSession() = runBlocking {
        for (response in listOf("not json", "{}", """{"status":"OK","code":"LEGACY_USER_NOT_FOUND"}""",
            """{"status":"ERROR","code":"OTHER"}""", """{"code":"LEGACY_USER_NOT_FOUND"}""")) {
            body = response
            assertAbandonedFailure()
        }
        assertEquals(10, calls)
    }

    @Test
    fun noLegacyAttemptDoesNotCompleteUnrelatedLoading() = runBlocking {
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(isLoading = true)))
        val original = rownd.state.value
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(original, rownd.state.value)
        assertEquals(0, calls)
    }

    @Test
    fun genericErrorsAndNetworkFailuresClearAttemptedSessionAndFinishOwnedLoading() = runBlocking {
        for (responseStatus in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            status = responseStatus
            assertAbandonedFailure()
        }
        beforeResponse = { throw IOException("offline") }
        assertAbandonedFailure()
        beforeResponse = { throw SocketTimeoutException("timed out") }
        assertAbandonedFailure()
        assertEquals(12, calls)
    }

    @Test
    fun incompleteSuccessAndSessionlessConflictClearAttemptedSession() = runBlocking {
        status = HttpStatusCode.OK
        assertAbandonedFailure()
        status = HttpStatusCode.Conflict
        assertAbandonedFailure()
        assertEquals(4, calls)
    }

    @Test
    fun unauthorizedMigrationClearsAttemptedSession() = runBlocking {
        status = HttpStatusCode.Unauthorized
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
        assertEquals(2, calls)
    }

    @Test
    fun concurrentNativeSessionWinsOverEveryMigrationFailure() = runBlocking {
        val accessToken = jwt.generateTestJwt(sessionHandle = "new-session")
        for (responseStatus in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound,
            HttpStatusCode.Conflict, HttpStatusCode.Gone, HttpStatusCode.InternalServerError, HttpStatusCode.OK, null)) {
            clearSession()
            seedLegacySession()
            status = responseStatus ?: HttpStatusCode.OK
            val user = User(data = mapOf("user_id" to "new-session"), isLoading = true)
            beforeResponse = {
                SuperTokensSessionBridge.bootstrapSession(context, accessToken, "new-refresh")
                rownd.stateRepo.getStore().dispatch(StateAction.SetUser(user))
                if (responseStatus == null) throw SocketTimeoutException("timed out after B was established")
            }
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertTrue("Existing native session must win over $status", rownd.state.value.auth.accessToken == accessToken &&
                SuperTokensSessionBridge.getAccessToken(context) == accessToken)
            assertEquals(user, rownd.state.value.user)
        }
    }

    @Test
    fun newerLegacyCredentialsWinOverFailedAndSuccessfulResponses() = runBlocking {
        val newer = AuthState(isLoading = true, accessToken = jwt.generateTestJwt(appUserId = "newer-user"), refreshToken = "newer-refresh")
        val newerUser = User(data = mapOf("user_id" to "newer-user"), isLoading = true)
        for (responseStatus in listOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden, HttpStatusCode.NotFound,
            HttpStatusCode.Conflict, HttpStatusCode.Gone, HttpStatusCode.InternalServerError, null)) {
            seedLegacySession()
            status = responseStatus ?: HttpStatusCode.OK
            beforeResponse = {
                rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(newer))
                rownd.stateRepo.getStore().dispatch(StateAction.SetUser(newerUser))
                if (responseStatus == null) throw IOException("offline after credentials changed")
            }
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertTrue("Newer credentials and loading must remain", rownd.state.value.auth == newer)
            assertEquals(newerUser, rownd.state.value.user)
        }

        status = HttpStatusCode.OK
        val accessToken = jwt.generateTestJwt(sessionHandle = "migration-session")
        responseHeaders = headersOf("st-access-token" to listOf(accessToken), "st-refresh-token" to listOf("st-refresh"),
            "front-token" to listOf(SuperTokensSessionBridge.buildFrontToken(accessToken)))
        val newest = newer.copy(refreshToken = "newest-refresh")
        beforeResponse = { rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(newest)) }
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertTrue("Latest credentials must win over successful response", rownd.state.value.auth == newest)
        assertFalse(SuperTokensSessionBridge.doesSessionExist(context))
    }

    @Test
    fun concurrentMigrationCallersShareOneAttempt() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        beforeResponse = { requested.complete(Unit); release.await() }
        val first = async(Dispatchers.IO) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
        requested.await()
        val second = async(Dispatchers.Unconfined) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, calls)
        assertEquals(AuthState(), rownd.state.value.auth)
    }

    @Test
    fun oldUserLoadCannotRestoreProfileAfterTerminalCleanup() = runBlocking {
        val userLoad = async(Dispatchers.IO) { rownd.userRepo.loadUserIfCurrent { true } }
        withTimeout(5_000) { userRequested.await() }
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        releaseUser.complete(Unit)
        withTimeout(5_000) { userLoad.await() }
        assertEquals(AuthState(), rownd.state.value.auth)
        assertEquals(User(), rownd.state.value.user)
    }

    @Test
    fun normalSignInAfterGenericFailureHasNoAuthInitAndFinishesHubLoading() = runBlocking {
        status = HttpStatusCode.InternalServerError
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        Rownd.store = Rownd.stateRepo.getStore()
        val originalHubUrl = Rownd.config.baseUrl
        Rownd.config.baseUrl = "https://migration.example"
        rownd.config.baseUrl = Rownd.config.baseUrl
        val viewRef = AtomicReference<RowndWebView>()
        val loaded = CountDownLatch(1)
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = RowndWebView(context, null)
                viewRef.set(view)
                view.rowndClient = rownd
                view.setIsLoading = { if (!it) loaded.countDown() }
                assertTrue("Production secure bridge must be available", view.secureHubMessagingAvailable)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) = WebResourceResponse(
                        "text/html", "UTF-8", """
                            <!doctype html><html><body><script>
                            window.rownd = { requestSignIn: options => { window.signInOptions = options; } };
                            rowndAndroidSDK.postMessage(JSON.stringify({type:'hub_loaded'}));
                            </script></body></html>
                        """.byteInputStream())
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        (view as RowndWebView).rowndWebViewClient.onPageStarted(view, url, favicon)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        (view as RowndWebView).rowndWebViewClient.onPageFinished(view, url)
                    }
                }
                view.loadNewPage(HubPageSelector.SignIn, "{\"title\":\"Normal sign in\"}")
            }
            assertTrue("Normal sign-in Hub must finish loading", loaded.await(8, TimeUnit.SECONDS))
            val checked = CountDownLatch(1)
            val result = AtomicReference<String>()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewRef.get().evaluateJavascript("window.signInOptions?.title === 'Normal sign in' && !location.hash.includes('rph_init')") {
                    result.set(it)
                    checked.countDown()
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            assertEquals("true", result.get())
            assertEquals(2, calls)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { viewRef.get()?.destroy() }
            Rownd.config.baseUrl = originalHubUrl
        }
    }

    @Test
    fun successfulBoundedRetryKeepsAssociatedProfileAndAdoptsSession() = runBlocking {
        val original = rownd.state.value
        val token = jwt.generateTestJwt(sessionHandle = "successful-retry")
        beforeResponse = {
            assertTrue("Credentials must remain available until the bounded attempt finishes",
                rownd.state.value.auth.accessToken == original.auth.accessToken)
            if (calls == 1) status = HttpStatusCode.InternalServerError
            else {
                status = HttpStatusCode.OK
                responseHeaders = headersOf("st-access-token" to listOf(token), "st-refresh-token" to listOf("retry-refresh"),
                    "front-token" to listOf(SuperTokensSessionBridge.buildFrontToken(token)))
            }
        }
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(2, calls)
        assertTrue("Successful retry must synchronize the native session", rownd.state.value.auth.accessToken == token)
        assertEquals(original.user, rownd.state.value.user)
        assertTrue(SuperTokensSessionBridge.doesSessionExist(context))
    }

    @Test
    fun failedNativeAdoptionClearsAttemptWithoutSdkArtifacts() = runBlocking {
        status = HttpStatusCode.OK
        val token = jwt.generateTestJwt(sessionHandle = "uninstalled-session")
        responseHeaders = headersOf("st-access-token" to listOf(token), "st-refresh-token" to listOf("st-refresh"),
            "front-token" to listOf(SuperTokensSessionBridge.buildFrontToken(token)))
        val originalWrite = SuperTokensSessionBridge.writeSession
        SuperTokensSessionBridge.writeSession = {}
        try {
            assertAbandonedFailure()
        } finally {
            SuperTokensSessionBridge.writeSession = originalWrite
        }
    }

    private suspend fun assertAbandonedFailure() {
        seedLegacySession()
        val callsBefore = calls
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertTrue("Exhausted migration must clear attempted credentials", rownd.state.value.auth == AuthState())
        assertEquals("Associated profile and loading must be cleared", User(), rownd.state.value.user)
        assertNull(rownd.state.value.auth.toRphInitHash(rownd.userRepo, context))
        assertEquals(callsBefore + 2, calls)
        assertFalse(SuperTokensSessionBridge.doesSessionExist(context))
        assertNull(SuperTokensSessionBridge.getRefreshToken(context))
        assertNull(SuperTokensSessionBridge.getFrontToken(context))
        val persisted = GlobalStateSerializer.json.encodeToString(GlobalState.serializer(), rownd.state.value)
        rownd.stateRepo.getStore().dispatch(StateAction.SetGlobalState(
            GlobalStateSerializer.json.decodeFromString(GlobalState.serializer(), persisted)))
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals("Relaunch must not retry abandoned credentials", callsBefore + 2, calls)
    }
}
