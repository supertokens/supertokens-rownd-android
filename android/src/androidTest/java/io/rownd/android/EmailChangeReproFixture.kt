package io.rownd.android

import android.app.Application
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.auth0.android.jwt.JWT
import com.lyft.kronos.KronosClock
import com.lyft.kronos.KronosTime
import com.supertokens.session.FrontToken
import com.supertokens.session.SuperTokens
import io.rownd.android.di.component.DaggerRowndGraph
import io.rownd.android.models.Store
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.repos.GlobalState
import io.rownd.android.models.repos.GlobalStateSerializer
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.AppLifecycleListener
import io.rownd.android.util.NewIntentTestActivity
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndJavascriptInterface
import io.rownd.android.views.RowndWebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal const val REPRO_HUB_URL = "https://hub.example.com"

/**
 * Runs the real Rownd state, user and session code against the harness backend and the
 * SuperTokens SDK. An [AppProcess] is one app process: its own Rownd graph and state file.
 * Reopening copies the persisted file to a fresh process, as Android does after process death.
 */
internal class EmailChangeReproFixture {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext!!
    val clock = OffsetClock()
    lateinit var origin: String
        private set
    private lateinit var appKey: String
    private val http = OkHttpClient()
    private val processes = mutableListOf<AppProcess>()
    private val webViews = mutableListOf<RowndWebView>()
    private val stateFiles = mutableListOf<File>()
    private var previousStore: Store<GlobalState, StateAction>? = null
    private val previousClock = Rownd.rowndContext.kronosClock
    private val previousConfig = Rownd.config.copy()

    fun setUp() {
        HarnessClient.reset()
        val config = HarnessClient.getConfig()
        origin = config.androidUrl
        appKey = config.appKey
        startSessionSdk()
        SuperTokensSessionBridge.clearLocalSession(context)
        // Hub URL serialization reads display metrics through the default instance.
        Rownd._registerActivityLifecycle(context.applicationContext as Application)
        previousStore = runCatching { Rownd.store }.getOrNull()
        Rownd.rowndContext.kronosClock = clock
        Rownd.config.apiUrl = origin
        Rownd.config.apiBasePath = "/auth"
        Rownd.config.appKey = appKey
        Rownd.config.baseUrl = REPRO_HUB_URL
        Rownd.config.supertokens = SuperTokensConfig(SuperTokensAppInfo(origin, "/auth"))
    }

    fun tearDown() {
        runCatching { HarnessClient.releaseResponseGate() }
        if (webViews.isNotEmpty()) instrumentation.runOnMainSync { webViews.forEach(RowndWebView::destroy) }
        webViews.clear()
        processes.forEach { it.close() }
        processes.clear()
        SuperTokensSessionBridge.clearLocalSession(context)
        Rownd.rowndContext.kronosClock = previousClock
        Rownd.config.apiUrl = previousConfig.apiUrl
        Rownd.config.apiBasePath = previousConfig.apiBasePath
        Rownd.config.appKey = previousConfig.appKey
        Rownd.config.baseUrl = previousConfig.baseUrl
        Rownd.config.supertokens = previousConfig.supertokens
        Rownd.store = previousStore ?: Rownd.stateRepo.getStore()
        AppLifecycleListener.isAppInForeground = false
        stateFiles.forEach { it.delete() }
        http.dispatcher.executorService.shutdown()
    }

    fun uniqueUserId(label: String) = "email-change-3832-$label-${UUID.randomUUID().toString().take(8)}"

    fun launch(restoreGate: Deferred<Unit>? = null) = AppProcess(newStateFile(), restoreGate)

    fun reopen(previous: AppProcess, restoreGate: Deferred<Unit>? = null): AppProcess {
        val file = newStateFile()
        previous.stop().copyTo(file, overwrite = true)
        return AppProcess(file, restoreGate)
    }

    fun signInThroughHub(process: AppProcess, session: HarnessClient.STSessionResponse) {
        hubBridge(process, HubPageSelector.SignIn).postSecureMessage(authenticationMessage(session))
        waitUntil("Hub sign-in must adopt the session and load its profile", 10_000) {
            process.state.auth.accessToken == session.accessToken && process.state.user.data["user_id"] == session.userId
        }
    }

    fun hubBridge(process: AppProcess, targetPage: HubPageSelector): RowndJavascriptInterface {
        val view = AtomicReference<RowndWebView>()
        instrumentation.runOnMainSync {
            view.set(RowndWebView(context, null).apply {
                rowndClient = process.client
                this.targetPage = targetPage
                dismiss = {}
            })
        }
        webViews += view.get()
        return view.get().rowndJavascriptInterface
    }

    /** Status the plugin's profile endpoint (checkDatabase = true) returns for this access token. */
    fun profileStatus(accessToken: String): Int = http.newCall(
        Request.Builder().url("$origin/auth/plugin/rownd/user")
            .header("Authorization", "Bearer $accessToken")
            .header("rid", "session")
            .header("st-auth-mode", "header")
            .build()
    ).execute().use { it.code }

    fun rphInit(process: AppProcess): JSONObject? {
        val url = runBlocking { process.client.config.hubLoaderUrl() }
        val fragment = url.toUri().encodedFragment?.removePrefix("rph_init=") ?: return null
        return JSONObject(String(Base64.getDecoder().decode(fragment)))
    }

    /** Lists what makes an rph_init payload unusable for the Hub; empty when it is coherent. */
    fun rphInitProblems(rphInit: JSONObject, expectedUserId: String): List<String> {
        val problems = mutableListOf<String>()
        val accessToken = rphInit.optString("access_token")
        val status = profileStatus(accessToken)
        if (status != 200) problems += "server rejects its access token (HTTP $status)"
        val frontUid = runCatching {
            JSONObject(String(Base64.getDecoder().decode(rphInit.getString("front_token")))).getString("uid")
        }.getOrNull()
        if (frontUid != JWT(accessToken).subject) problems += "front token belongs to another session"
        val appUserId = rphInit.optString("app_user_id").takeIf { !rphInit.isNull("app_user_id") }
        if (appUserId != expectedUserId) problems += "app_user_id is '$appUserId', expected '$expectedUserId'"
        return problems
    }

    fun authenticationMessage(session: HarnessClient.STSessionResponse): String = JSONObject()
        .put("type", "authentication")
        .put("payload", JSONObject()
            .put("access_token", session.accessToken)
            .put("refresh_token", session.refreshToken)
            .put("front_token", SuperTokensSessionBridge.buildFrontToken(session.accessToken)))
        .toString()

    fun userDataUpdateMessage(data: Map<String, Any>): String = JSONObject()
        .put("type", "user_data_update")
        .put("payload", JSONObject().put("data", JSONObject(data)))
        .toString()

    fun handle(accessToken: String): String = requireNotNull(JWT(accessToken).getClaim("sessionHandle").asString())

    /**
     * Lets device time pass beyond the cached access token's expiry. Rownd reads [clock]; the
     * SuperTokens SDK decides expiry from the front token, and the server reissues access tokens
     * with its default lifetime, so a short-lived token cannot be used for this.
     */
    fun advancePastAccessTokenExpiry() {
        clock.offsetMs = 2 * 60 * 60 * 1000L
        val frontToken = SuperTokensSessionBridge.getFrontToken(context) ?: return
        val payload = JSONObject(String(Base64.getDecoder().decode(frontToken)))
            .put("ate", System.currentTimeMillis() - 1_000)
        FrontToken.setToken(context, Base64.getEncoder().withoutPadding().encodeToString(payload.toString().toByteArray()))
    }

    fun nativeSessionPresent() = !SuperTokensSessionBridge.getRefreshToken(context).isNullOrBlank()

    fun waitUntil(description: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue(description, condition())
    }

    /** Like [waitUntil] but reports instead of failing, for observations that build a timeline. */
    fun eventually(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun startSessionSdk() {
        SuperTokens.resetForTests()
        SuperTokens.Builder(context, origin).apiBasePath("/auth").tokenTransferMethod("header").build()
        SuperTokensSessionBridge.isInitialized.set(true)
    }

    private fun newStateFile() = context.dataStoreFile("email-change-3832-${UUID.randomUUID()}.json").also { stateFiles += it }

    inner class AppProcess(private val stateFile: File, restoreGate: Deferred<Unit>?) {
        val client = RowndClient(DaggerRowndGraph.create())
        private val persisted = DataStoreFactory.create(GlobalStateSerializer) { stateFile }
        private val dataStore = ObservedDataStore(persisted, restoreGate)
        private var activity: ActivityScenario<NewIntentTestActivity>? = null

        val state: GlobalState get() = client.state.value

        init {
            // Rownd initializes from Application.onCreate, before any activity resumes.
            AppLifecycleListener.isAppInForeground = false
            startSessionSdk()
            client._registerActivityLifecycle(context.applicationContext as Application)
            client.config.apiUrl = origin
            client.config.apiBasePath = "/auth"
            client.config.appKey = appKey
            client.config.baseUrl = REPRO_HUB_URL
            client.config.applicationContext = context
            client.config.supertokens = SuperTokensConfig(SuperTokensAppInfo(origin, "/auth"))
            client.rowndContext.kronosClock = clock
            client.store = client.stateRepo.setup(dataStore)
            Rownd.store = client.store
            processes += this
        }

        /** Completes when startup reaches the persist collector, after app config, refresh and profile load. */
        fun awaitStartup() = runBlocking { withTimeout(20_000) { dataStore.firstWrite.await() } }

        fun resume() {
            activity = ActivityScenario.launch(NewIntentTestActivity::class.java)
        }

        internal fun stop(): File {
            waitUntil("state must reach disk before the process dies") {
                val disk = runBlocking { persisted.data.first() }
                disk.auth == state.auth && disk.user.data == state.user.data
            }
            close()
            return stateFile
        }

        internal fun close() {
            activity?.close()
            activity = null
            client.appHandleWrapper?.unregister()
            client.authenticatedApiClient.client.close()
        }
    }

    private class ObservedDataStore(
        private val delegate: DataStore<GlobalState>,
        private val restoreGate: Deferred<Unit>?,
    ) : DataStore<GlobalState> {
        val firstWrite = CompletableDeferred<Unit>()

        override val data: Flow<GlobalState> = flow {
            restoreGate?.await()
            emitAll(delegate.data)
        }

        override suspend fun updateData(transform: suspend (t: GlobalState) -> GlobalState): GlobalState =
            delegate.updateData(transform).also { firstWrite.complete(Unit) }
    }

    /** Rownd's JWT expiry check reads this clock; the SuperTokens SDK keeps using the real one. */
    class OffsetClock : KronosClock {
        @Volatile
        var offsetMs = 0L

        override fun getCurrentTimeMs() = System.currentTimeMillis() + offsetMs
        override fun getCurrentNtpTimeMs(): Long = getCurrentTimeMs()
        override fun getCurrentTime() = KronosTime(getCurrentTimeMs(), 0L)
        override fun getElapsedTimeMs() = SystemClock.elapsedRealtime()
        override fun sync() = true
        override fun syncInBackground() {}
        override fun shutdown() {}
    }
}
