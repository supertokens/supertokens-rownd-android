package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.supertokens.session.SuperTokens
import com.supertokens.session.SuperTokensInterceptor
import io.rownd.android.models.RowndConfig
import io.rownd.android.models.repos.AuthRepo
import io.rownd.android.util.JwtGenerator
import io.rownd.android.util.RowndContext
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class AuthSessionInstrumentedTest {

    companion object {
        private lateinit var harnessConfig: HarnessClient.HarnessConfig
        private val jwtGenerator = JwtGenerator()
        private lateinit var stClient: OkHttpClient

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            harnessConfig = HarnessClient.getConfig()
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            try {
                SuperTokens.Builder(context, harnessConfig.androidUrl)
                    .apiBasePath("/auth")
                    .tokenTransferMethod("header")
                    .build()
            } catch (_: Exception) {
                // Already initialized by another test class in the same run
            }
            SuperTokensSessionBridge.isInitialized.set(true)

            stClient = OkHttpClient.Builder()
                .addInterceptor(SuperTokensInterceptor())
                .build()
        }
    }

    @Before
    fun resetHarnessAndSession() {
        HarnessClient.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { SuperTokensSessionBridge.signOut(context) }
    }

    @Test
    fun getLatestAuthStateReturnsNullWhenContextIsUnavailable() {
        val repo = AuthRepo()
        repo.rowndContext = RowndContext(RowndConfig())

        val result = runBlocking { repo.getLatestAuthState() }
        assertNull("getLatestAuthState must return null when rowndContext.client is null", result)
    }

    @Test
    fun getAccessTokenReturnsNullWhenContextIsUnavailable() {
        val repo = AuthRepo()
        repo.rowndContext = RowndContext(RowndConfig())

        val result = runBlocking { repo.getAccessToken() }
        assertNull("getAccessToken must return null when rowndContext.client is null", result)
    }

    @Test
    fun bootstrappedSessionTokenIsReturnedByGetAccessToken() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val accessToken = jwtGenerator.generateTestJwt()
        val refreshToken = jwtGenerator.generateTestJwt()

        SuperTokensSessionBridge.bootstrapSession(context, accessToken, refreshToken)

        val retrieved = runBlocking { SuperTokensSessionBridge.getAccessToken(context) }
        assertNotNull("getAccessToken must return a non-null token after bootstrapSession", retrieved)
    }

    @Test
    fun expiredSessionRefreshesViaSuperTokensNotLegacy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("expired-refresh-user")

        val expiredToken = jwtGenerator.generateTestJwt(
            expires = Date.from(Instant.now().minusSeconds(3600))
        )
        SuperTokensSessionBridge.bootstrapSession(context, expiredToken, stSession.refreshToken)

        val request = Request.Builder()
            .url("${harnessConfig.androidUrl}/health")
            .build()
        stClient.newCall(request).execute().close()

        val counters = HarnessClient.getCounters()
        assertEquals("stRefresh must be exactly 1 after one expired-token request", 1, counters.stRefresh)
        assertEquals("legacyRefresh must remain 0", 0, counters.legacyRefresh)
    }

    @Test
    fun concurrentRequestsProduceAtMostOneSuperTokensRefresh() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stSession = HarnessClient.createSTSession("concurrent-refresh-user")

        val expiredToken = jwtGenerator.generateTestJwt(
            expires = Date.from(Instant.now().minusSeconds(3600))
        )
        SuperTokensSessionBridge.bootstrapSession(context, expiredToken, stSession.refreshToken)

        val latch = CountDownLatch(3)
        val executor = Executors.newFixedThreadPool(3)
        repeat(3) {
            executor.execute {
                try {
                    val request = Request.Builder()
                        .url("${harnessConfig.androidUrl}/health")
                        .build()
                    stClient.newCall(request).execute().close()
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        executor.shutdown()

        val counters = HarnessClient.getCounters()
        assertTrue("stRefresh must be at most 1 for concurrent requests", counters.stRefresh <= 1)
        assertEquals("legacyRefresh must remain 0", 0, counters.legacyRefresh)
    }
}
