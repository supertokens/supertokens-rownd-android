package io.rownd.android

import android.app.Application
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.rownd.android.di.component.DaggerRowndGraph
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.User
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.LegacyMigrationApiClient
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class LegacyMigrationProductionEngineInstrumentedTest {
    companion object {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var server: LocalMigrationServer
        private val jwt = JwtGenerator()

        @BeforeClass @JvmStatic
        fun startServer() {
            server = LocalMigrationServer()
            SuperTokens.Builder(context, server.origin).apiBasePath("/auth").tokenTransferMethod("header").build()
            SuperTokensSessionBridge.isInitialized.set(true)
        }

        @AfterClass @JvmStatic
        fun stopServer() = server.close()
    }

    private lateinit var rownd: RowndClient
    private lateinit var legacy: AuthState
    private val originalWriteSession = SuperTokensSessionBridge.writeSession

    @Before
    fun setUp() {
        clearSession()
        server.reset()
        // Use the production Dagger graph: NetworkModule supplies OkHttp + the pinned SDK interceptor.
        rownd = RowndClient(DaggerRowndGraph.create())
        rownd.store = rownd.stateRepo.getStore()
        rownd._registerActivityLifecycle(context.applicationContext as Application)
        rownd.config.applicationContext = context
        val appConfig = rownd.state.value.appConfig
        rownd.stateRepo.getStore().dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
            supertokens = SuperTokensConfig(SuperTokensAppInfo(server.origin, "/auth")),
        ))))
        legacy = AuthState(accessToken = jwt.generateTestJwt(appUserId = "legacy-engine-user"), refreshToken = "legacy-engine-refresh")
        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(legacy))
    }

    @After
    fun tearDown() {
        SuperTokensSessionBridge.writeSession = originalWriteSession
        rownd.authenticatedApiClient.client.close()
        rownd.authRepo.legacyMigrationApiClient.client.close()
        clearSession()
    }

    private fun clearSession() {
        SuperTokensSessionBridge.clearLocalSession(context)
        context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun malformedEndpointRetainsLegacyStateWithoutTransportDispatch() = runBlocking {
        val requests = observeMigrationTransport()
        val user = User(data = mapOf("user_id" to "legacy-engine-user"), isLoading = true)
        rownd.store.dispatch(StateAction.SetUser(user))
        val appConfig = rownd.state.value.appConfig
        for (domain in listOf("http://127.0.0.1:not-a-port", "http://[invalid", "ftp://127.0.0.1")) {
            rownd.store.dispatch(StateAction.SetAuth(legacy.copy(isLoading = true)))
            rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
                supertokens = SuperTokensConfig(SuperTokensAppInfo(domain, "/auth")),
            ))))
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertEquals("Invalid endpoint must not reach OkHttp Call.start", 0, requests.size)
            assertEquals("Invalid endpoint must not reach the HTTP server", 0, server.requestCount.get())
            assertTrue("Preparation failure must retain credentials and finish owned auth loading",
                rownd.state.value.auth == legacy.copy(isLoading = false))
            assertEquals("Preparation failure must preserve profile and its loading", user, rownd.state.value.user)
        }

        // The same observer must see a real request once configuration is corrected.
        rownd.store.dispatch(StateAction.SetAppConfig(appConfig))
        val valid = session("prepared-after-config-fix")
        server.enqueue(valid.reply())
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals(1, requests.size)
        assertMigrationRequest(server.takeRequest())
        assertSession(valid, "Corrected configuration must migrate the retained credentials")
    }

    @Test
    fun migrationRetriesReuseTheCapturedEndpoint() = runBlocking {
        for (useDefaultDomain in listOf(false, true)) {
            clearSession()
            server.reset()
            rownd.store.dispatch(StateAction.SetAuth(legacy))
            rownd.config.apiUrl = server.origin
            val appConfig = rownd.state.value.appConfig
            rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
                supertokens = SuperTokensConfig(SuperTokensAppInfo(if (useDefaultDomain) "" else server.origin, "/auth")),
            ))))
            val requests = observeMigrationTransport()
            val release = CountDownLatch(1)
            server.enqueue(Reply(emptyMap(), release, status = 400))
            val valid = session("captured-endpoint-retry")
            server.enqueue(valid.reply())
            val migration = async(Dispatchers.IO) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
            try {
                assertMigrationRequest(server.takeRequest())
                rownd.config.apiUrl = server.origin.replace("127.0.0.1", "localhost")
                rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
                    supertokens = SuperTokensConfig(SuperTokensAppInfo(if (useDefaultDomain) "" else rownd.config.apiUrl, "/replacement")),
                ))))
            } finally {
                release.countDown()
            }
            withTimeout(10_000) { migration.await() }
            assertEquals(2, requests.size)
            assertTrue("Both sends must use the captured endpoint",
                requests.all { it.url.toString() == "${server.origin}/auth/plugin/rownd/migrate" })
            assertMigrationRequest(server.takeRequest())
            assertSession(valid, "A config update must not redirect an owned migration retry")
        }
    }

    @Test
    fun dnsFailureAfterTransportHandoffAbandonsAttemptedSession() = runBlocking {
        val requests = observeMigrationTransport {
            dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("Deterministic migration DNS failure")
            })
        }
        val appConfig = rownd.state.value.appConfig
        rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
            supertokens = SuperTokensConfig(SuperTokensAppInfo("http://migration.invalid", "/auth")),
        ))))
        rownd.store.dispatch(StateAction.SetUser(User(data = mapOf("user_id" to "legacy-engine-user"), isLoading = true)))
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals("Both bounded attempts must reach the real OkHttp engine", 2, requests.size)
        assertTrue("DNS failure after handoff must abandon credentials", rownd.state.value.auth == AuthState())
        assertEquals(User(), rownd.state.value.user)
        assertEquals(0, server.requestCount.get())
        assertFalse(storedAccessToken() != null)
    }

    private fun observeMigrationTransport(configure: OkHttpClient.Builder.() -> Unit = {}): ConcurrentLinkedQueue<Request> {
        val requests = ConcurrentLinkedQueue<Request>()
        rownd.authRepo.legacyMigrationApiClient.client.close()
        rownd.config.defaultNumApiRetries = 0
        rownd.authRepo.legacyMigrationApiClient = LegacyMigrationApiClient(rownd.rowndContext, OkHttp.create {
            config {
                configure()
                eventListener(object : EventListener() {
                    override fun callStart(call: Call) { requests.add(call.request()) }
                })
            }
        })
        return requests
    }

    @Test
    fun isolatedRevocationRefreshUsesCapturedCredentialsAndCustomBasePath() = runBlocking {
        val old = session("captured-signout-A")
        val newer = session("current-signout-B")
        val rotated = session("request-local-rotated-A")
        val appConfig = rownd.state.value.appConfig
        rownd.store.dispatch(StateAction.SetAppConfig(appConfig.copy(config = appConfig.config.copy(
            supertokens = SuperTokensConfig(SuperTokensAppInfo(server.origin, "/custom/session-api")),
        ))))
        val url = rownd.authRepo.sessionRevocationUrl()
        val captured = captureOldSessionAndInstallNew(old, newer)
        server.enqueue(Reply(emptyMap(), status = 401))
        server.enqueue(rotated.reply())
        server.enqueue(Reply(mapOf("front-token" to "remove")))

        rownd.authRepo.revokeSignedOutSession(captured, url)

        assertRevocationRequest(server.takeRequest(), "/custom/session-api/signout", old.access, old.csrf)
        assertRevocationRequest(server.takeRequest(), "/custom/session-api/session/refresh", old.refresh, old.csrf)
        assertRevocationRequest(server.takeRequest(), "/custom/session-api/signout", rotated.access, rotated.csrf)
        assertEquals(3, server.requestCount.get())
        assertSession(newer, "Request-local rotated credentials and removal headers must not affect B")
        assertTrue(rownd.state.value.auth.accessToken == newer.access)
    }

    @Test
    fun terminalCapturedRefresh401StopsWithoutClearingNewSession() = runBlocking {
        val newer = session("terminal-refresh-B")
        val captured = captureOldSessionAndInstallNew(session("terminal-refresh-A"), newer)
        server.enqueue(Reply(emptyMap(), status = 401))
        server.enqueue(Reply(mapOf("front-token" to "remove"), status = 401))
        rownd.authRepo.revokeSignedOutSession(captured, rownd.authRepo.sessionRevocationUrl())
        assertEquals(2, server.requestCount.get())
        assertSession(newer, "Terminal old refresh must preserve B")
    }

    @Test
    fun capturedRefreshFailuresRemainUnconfirmedAndAreNotRetried() = runBlocking {
        val newer = session("failed-refresh-B")
        val captured = captureOldSessionAndInstallNew(session("failed-refresh-A"), newer)
        for (reply in listOf(Reply(emptyMap(), status = 500), Reply(emptyMap(), status = 403), Reply(emptyMap()))) {
            server.reset()
            server.enqueue(Reply(emptyMap(), status = 401))
            server.enqueue(reply)
            // Extra replies let an accidental HTTP retry complete so the count assertion catches it.
            repeat(4) { server.enqueue(reply) }
            val failure = runCatching {
                rownd.authRepo.revokeSignedOutSession(captured, rownd.authRepo.sessionRevocationUrl())
            }.exceptionOrNull()
            assertTrue("Refresh failure must report unconfirmed remote revocation", failure?.message?.contains("remote revocation unconfirmed") == true)
            assertEquals("Only one refresh attempt is allowed", 2, server.requestCount.get())
            assertSession(newer, "Failed old refresh must preserve B")
        }
    }

    @Test
    fun unauthorizedRevokeRetryRemainsUnconfirmedWithoutAnotherRefresh() = runBlocking {
        val newer = session("retry-401-B")
        val captured = captureOldSessionAndInstallNew(session("retry-401-A"), newer)
        server.enqueue(Reply(emptyMap(), status = 401))
        server.enqueue(session("retry-401-rotated-A").reply())
        server.enqueue(Reply(emptyMap(), status = 401))
        val failure = runCatching {
            rownd.authRepo.revokeSignedOutSession(captured, rownd.authRepo.sessionRevocationUrl())
        }.exceptionOrNull()
        assertTrue("A second revoke 401 must not be reported as success", failure?.message?.contains("HTTP 401; remote revocation unconfirmed") == true)
        assertEquals(3, server.requestCount.get())
        assertSession(newer, "Unconfirmed old revocation must preserve B")
    }

    private suspend fun captureOldSessionAndInstallNew(old: SessionHeaders, newer: SessionHeaders): SuperTokensSessionBridge.SignedOutSession {
        SuperTokensSessionBridge.bootstrapSession(context, old.access, old.refresh, old.front, old.csrf)
        val captured = checkNotNull(SuperTokensSessionBridge.beginSignOut(context) {})
        SuperTokensSessionBridge.bootstrapSession(context, newer.access, newer.refresh, newer.front, newer.csrf)
        SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.store)
        return captured
    }

    private fun assertRevocationRequest(request: CapturedRequest, path: String, token: String, antiCSRF: String) {
        assertEquals("POST $path HTTP/1.1", request.line)
        assertTrue("Exactly one captured bearer is required", request.headers["authorization"] == listOf("Bearer $token"))
        assertTrue("Anti-CSRF must belong to the captured session", request.headers["anti-csrf"] == listOf(antiCSRF))
        assertEquals(listOf("session"), request.headers["rid"])
        assertEquals(listOf("1.18"), request.headers["fdi-version"])
        assertEquals(listOf("header"), request.headers["st-auth-mode"])
        for (name in listOf("x-rownd-app-key", "cookie", "st-access-token", "st-refresh-token", "front-token")) {
            assertFalse("Captured-session requests must not attach $name", request.headers.containsKey(name))
        }
    }

    @Test
    fun delayedOrdinarySignOutResponseCannotClearNewSession() = runBlocking {
        val old = session("signout-C")
        SuperTokensSessionBridge.bootstrapSession(context, old.access, old.refresh, old.front, old.csrf)
        SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.stateRepo.getStore())
        val release = CountDownLatch(1)
        server.enqueue(Reply(mapOf("front-token" to "remove"), release))
        val signOut = async(Dispatchers.IO) { rownd.signOut() }
        val newer = session("during-signout-B")
        try {
            val request = server.takeRequest()
            assertEquals("POST /auth/signout HTTP/1.1", request.line)
            assertTrue("Revocation must target the captured old session", request.headers["authorization"] == listOf("Bearer ${old.access}"))
            SuperTokensSessionBridge.bootstrapSession(context, newer.access, newer.refresh, newer.front, newer.csrf, replaceExisting = true)
            SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.stateRepo.getStore())
            assertSession(newer, "The newer login must complete before the old logout response")
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { signOut.await() }
        assertSession(newer, "Late logout response and cleanup must preserve session B")
        assertTrue("Late logout cleanup must preserve compatibility state B", rownd.state.value.auth.accessToken == newer.access)
    }

    @Test
    fun ordinarySignOutDuringInstallationCannotResurrectMigrationSession() = runBlocking {
        pauseInstallationAcrossSignOut(installNewSession = false)
    }

    @Test
    fun sessionCreatedAfterOrdinarySignOutSurvivesResumedOldInstallation() = runBlocking {
        pauseInstallationAcrossSignOut(installNewSession = true)
    }

    private suspend fun pauseInstallationAcrossSignOut(installNewSession: Boolean) {
        val reachedWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val pauseOnce = AtomicBoolean(true)
        SuperTokensSessionBridge.writeSession = { write ->
            if (pauseOnce.compareAndSet(true, false)) {
                reachedWrite.countDown()
                check(releaseWrite.await(10, TimeUnit.SECONDS)) { "Installation write gate timed out" }
            }
            originalWriteSession(write)
        }
        server.enqueue(session("approved-migration-A").reply())
        val migration = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).async {
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
        }
        val newer = session("after-signout-B")
        try {
            assertTrue("Migration must reach the actual native write boundary", reachedWrite.await(10, TimeUnit.SECONDS))
            assertMigrationRequest(server.takeRequest())
            // This is the ordinary native API, off-main, so its cleanup has completed on return.
            rownd.signOut()
            assertEquals(AuthState(), rownd.state.value.auth)
            assertFalse(SuperTokensSessionBridge.doesSessionExist(context))
            if (installNewSession) {
                SuperTokensSessionBridge.bootstrapSession(context, newer.access, newer.refresh, newer.front, newer.csrf)
                SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.stateRepo.getStore())
            }
        } finally {
            releaseWrite.countDown()
        }
        withTimeout(10_000) { migration.await() }
        if (installNewSession) {
            assertSession(newer, "Old installation must not overwrite session B created after sign-out")
            assertTrue("Compatibility state must retain B", rownd.state.value.auth.accessToken == newer.access)
        } else {
            assertFalse("Approved migration must not resurrect a signed-out SDK session", SuperTokensSessionBridge.doesSessionExist(context))
            assertFalse("Approved migration must not persist an access token after sign-out", storedAccessToken() != null)
            assertEquals(AuthState(), rownd.state.value.auth)
        }
    }

    @Test
    fun productionAuthenticatedEngineReallyCapturesSessionHeaders() = runBlocking {
        val session = session("interceptor-probe")
        server.enqueue(session.reply())
        rownd.authenticatedApiClient.client.get("${server.origin}/auth/interceptor-probe")
        assertSession(session, "Production NetworkModule must exercise the real SDK interceptor")
    }

    @Test
    fun delayedMigrationCannotOverwriteConcurrentNativeSession() = runBlocking {
        val stale = session("migration-A")
        val current = session("native-B")
        val release = CountDownLatch(1)
        server.enqueue(stale.reply(release))
        val migration = async(Dispatchers.IO) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
        try {
            assertMigrationRequest(server.takeRequest())
            SuperTokensSessionBridge.sessionMutationMutex.withLock {
                SuperTokensSessionBridge.bootstrapSession(context, current.access, current.refresh, current.front, current.csrf)
                SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(context, rownd.stateRepo.getStore())
            }
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { migration.await() }
        assertSession(current, "Late migration response must not overwrite native session B")
        assertTrue("Compatibility state must still reference B", rownd.state.value.auth.accessToken == current.access)
    }

    @Test
    fun sessionCapturedByOrdinaryProductionRequestWinsBeforeMigrationCommit() = runBlocking {
        val current = session("http-session-B")
        val release = CountDownLatch(1)
        server.enqueue(session("migration-A").reply(release))
        val migration = async(Dispatchers.IO) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
        try {
            assertMigrationRequest(server.takeRequest())
            server.enqueue(current.reply())
            // Ordinary SDK interception does not acquire the Hub mutex. Its completed session
            // must still win when migration later acquires the mutex and rechecks native state.
            SuperTokensSessionBridge.sessionMutationMutex.withLock {
                rownd.authenticatedApiClient.client.get("${server.origin}/auth/native-sign-in")
                assertSession(current, "The ordinary production request must establish B")
                assertTrue("Compatibility state has not yet synced B", rownd.state.value.auth.accessToken == legacy.accessToken)
            }
            assertEquals("GET /auth/native-sign-in HTTP/1.1", server.takeRequest().line)
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { migration.await() }
        assertSession(current, "The migration commit must preserve the already captured session B")
        assertTrue("Migration must synchronize B instead of adopting A", rownd.state.value.auth.accessToken == current.access)
    }

    @Test
    fun partialHeadersAbandonLegacySessionAndFreshCredentialsCanMigrate() = runBlocking {
        val incomplete = session("partial")
        val partial = Reply(headers = mapOf("st-access-token" to incomplete.access, "front-token" to incomplete.front))
        server.enqueue(partial)
        server.enqueue(partial)
        rownd.authRepo.migrateLegacySessionIfNeeded(context)

        assertFalse("Partial migration response must not write SDK access storage", storedAccessToken() != null)
        assertFalse("Partial migration response must not write SDK refresh storage", SuperTokensSessionBridge.getRefreshToken(context) != null)
        assertFalse("Partial migration response must not write SDK front storage", SuperTokensSessionBridge.getFrontToken(context) != null)
        assertFalse("Partial migration response must not establish an SDK session", SuperTokensSessionBridge.doesSessionExist(context))
        assertTrue("Partial response must abandon attempted credentials", rownd.state.value.auth == AuthState())
        assertFalse(rownd.state.value.auth.isLoading)
        repeat(2) { assertMigrationRequest(server.takeRequest()) }

        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertEquals("Abandoned credentials must not trigger another request", 2, server.requestCount.get())
        legacy = AuthState(accessToken = jwt.generateTestJwt(appUserId = "fresh-legacy-user"), refreshToken = "fresh-legacy-refresh")
        rownd.store.dispatch(StateAction.SetAuth(legacy))

        val valid = session("valid-retry")
        server.enqueue(valid.reply())
        rownd.authRepo.migrateLegacySessionIfNeeded(context)
        assertMigrationRequest(server.takeRequest())
        assertSession(valid, "A complete retry must adopt the complete session")
        assertTrue("Compatibility state must adopt the retry", rownd.state.value.auth.accessToken == valid.access)
    }

    @Test
    fun malformedSuccessHeadersAreRejectedBeforeNativeStorageWrites() = runBlocking {
        val valid = session("malformed-success")
        for (headers in listOf(
            mapOf("st-access-token" to valid.access, "st-refresh-token" to valid.refresh, "front-token" to "not-base64-json"),
            mapOf("st-access-token" to "not-a-jwt", "st-refresh-token" to valid.refresh, "front-token" to valid.front),
        )) {
            rownd.store.dispatch(StateAction.SetAuth(legacy))
            repeat(2) { server.enqueue(Reply(headers)) }
            rownd.authRepo.migrateLegacySessionIfNeeded(context)
            assertTrue("Malformed response must abandon attempted credentials", rownd.state.value.auth == AuthState())
            assertFalse("Malformed success must not install access credentials", storedAccessToken() != null)
            assertFalse("Malformed success must not install refresh credentials", SuperTokensSessionBridge.getRefreshToken(context) != null)
            assertFalse("Malformed success must not install front token", SuperTokensSessionBridge.getFrontToken(context) != null)
        }
    }

    @Test
    fun delayedMigrationCannotInstallSessionAfterNewerLegacyCredentials() = runBlocking {
        val release = CountDownLatch(1)
        server.enqueue(session("stale-migration").reply(release))
        val newer = AuthState(accessToken = jwt.generateTestJwt(appUserId = "newer-legacy"), refreshToken = "newer-legacy-refresh")
        val migration = async(Dispatchers.IO) { rownd.authRepo.migrateLegacySessionIfNeeded(context) }
        try {
            assertMigrationRequest(server.takeRequest())
            rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(newer))
        } finally {
            release.countDown()
        }
        withTimeout(10_000) { migration.await() }
        assertFalse("Stale response must not write SDK access storage", storedAccessToken() != null)
        assertFalse("Stale response must not write SDK refresh storage", SuperTokensSessionBridge.getRefreshToken(context) != null)
        assertFalse("Stale response must not write SDK front storage", SuperTokensSessionBridge.getFrontToken(context) != null)
        assertTrue("Newer legacy credentials must win", rownd.state.value.auth == newer)
    }

    private fun assertMigrationRequest(request: CapturedRequest) {
        assertEquals("POST /auth/plugin/rownd/migrate HTTP/1.1", request.line)
        assertTrue("Exactly one explicit legacy bearer is required", request.headers["authorization"] == listOf("Bearer ${legacy.accessToken}"))
        assertEquals(listOf("session"), request.headers["rid"])
        assertEquals(listOf("1.18"), request.headers["fdi-version"])
        assertEquals(listOf("header"), request.headers["st-auth-mode"])
        for (name in listOf("x-rownd-app-key", "cookie", "anti-csrf", "st-access-token", "st-refresh-token")) {
            assertFalse("Migration must not attach $name", request.headers.containsKey(name))
        }
    }

    private fun storedAccessToken() = context.getSharedPreferences("supertokens-android-shared-preferences", Context.MODE_PRIVATE)
        .getString("st-storage-item-st-access-token", null)

    private suspend fun assertSession(expected: SessionHeaders, message: String) {
        assertTrue(message, storedAccessToken() == expected.access &&
            SuperTokensSessionBridge.getAccessToken(context) == expected.access &&
            SuperTokensSessionBridge.getRefreshToken(context) == expected.refresh &&
            SuperTokensSessionBridge.getFrontToken(context) == expected.front &&
            SuperTokensSessionBridge.getAntiCSRF(context) == expected.csrf)
    }

    private fun session(name: String): SessionHeaders {
        val access = jwt.generateTestJwt(sessionHandle = name)
        return SessionHeaders(access, "$name-refresh", SuperTokensSessionBridge.buildFrontToken(access), "$name-csrf")
    }

    private class SessionHeaders(val access: String, val refresh: String, val front: String, val csrf: String) {
        fun reply(release: CountDownLatch = CountDownLatch(0)) = Reply(headers = mapOf(
            "st-access-token" to access, "st-refresh-token" to refresh, "front-token" to front, "anti-csrf" to csrf,
        ), release = release)
    }

    private class Reply(val headers: Map<String, String>, val release: CountDownLatch = CountDownLatch(0), val body: String = """{"status":"OK"}""", val status: Int = 200)
    private class CapturedRequest(val line: String, val headers: Map<String, List<String>>)

    private class LocalMigrationServer : AutoCloseable {
        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool()
        private val replies = LinkedBlockingQueue<Reply>()
        private val requests = LinkedBlockingQueue<CapturedRequest>()
        val requestCount = AtomicInteger()
        val origin = "http://127.0.0.1:${socket.localPort}"

        init {
            workers.execute {
                try {
                    while (!socket.isClosed) {
                        val connection = socket.accept()
                        workers.execute {
                            connection.use {
                                it.soTimeout = 10_000
                                val reader = it.getInputStream().bufferedReader()
                                val line = reader.readLine()
                                val headers = mutableMapOf<String, MutableList<String>>()
                                while (true) {
                                    val header = reader.readLine()?.takeIf { value -> value.isNotEmpty() } ?: break
                                    headers.getOrPut(header.substringBefore(':').lowercase()) { mutableListOf() }
                                        .add(header.substringAfter(':').trim())
                                }
                                requestCount.incrementAndGet()
                                val reply = checkNotNull(replies.poll(10, TimeUnit.SECONDS)) { "Missing local response" }
                                requests.put(CapturedRequest(line, headers))
                                check(reply.release.await(10, TimeUnit.SECONDS)) { "Local response gate timed out" }
                                val response = buildString {
                                    append("HTTP/1.1 ${reply.status} Response\r\nContent-Type: application/json\r\nContent-Length: ${reply.body.toByteArray().size}\r\nConnection: close\r\n")
                                    reply.headers.forEach { (name, value) -> append("$name: $value\r\n") }
                                    append("\r\n${reply.body}")
                                }
                                it.getOutputStream().write(response.toByteArray())
                            }
                        }
                    }
                } catch (ex: SocketException) {
                    if (!socket.isClosed) throw ex
                }
            }
        }

        fun enqueue(reply: Reply) { replies.put(reply) }
        fun takeRequest(): CapturedRequest = checkNotNull(requests.poll(10, TimeUnit.SECONDS)) { "No local migration request received" }
        fun reset() { replies.clear(); requests.clear(); requestCount.set(0) }
        override fun close() { socket.close(); workers.shutdownNow() }
    }
}
