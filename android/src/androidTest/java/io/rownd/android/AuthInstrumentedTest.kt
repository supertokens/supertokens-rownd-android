package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.Component
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.rownd.android.di.component.RowndGraph
import io.rownd.android.di.module.ApiModule
import io.rownd.android.di.module.AuthRepoModule
import io.rownd.android.di.module.FakeNetworkModule
import io.rownd.android.di.module.KtorMockEngineConfig
import io.rownd.android.di.module.RowndConfigProvider
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.JwtGenerator
import junit.framework.Assert.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.*
import javax.inject.Singleton

@Singleton
@Component(
    modules = [
        KtorMockEngineConfig::class,
        FakeNetworkModule::class,
        RowndConfigProvider::class,
        ApiModule::class,
        AuthRepoModule::class,
    ]
)
internal interface TestRowndGraph : RowndGraph {
    fun fakeNetworkModule(): FakeNetworkModule?
    fun inject(config: MockEngineConfig)
}

@RunWith(AndroidJUnit4::class)
class AuthInstrumentedTest {
    lateinit var rownd: RowndClient
    var jwtGenerator = JwtGenerator()
    var httpEngineConfig = MockEngineConfig()

    @Before
    @Throws(Exception::class)
    fun setUp() {
        // Have to have at least one request handler before instantiating engine
        httpEngineConfig.addHandler {  request ->
            respond(
                content = ByteReadChannel("""{"ip":"127.0.0.1"}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val graph = DaggerTestRowndGraph.builder()
            .fakeNetworkModule(FakeNetworkModule(httpEngineConfig))
            .build()


        rownd = RowndClient(graph)
        rownd.config.defaultRequestTimeout = 100L

        // Clear mock handlers before each test
        httpEngineConfig.requestHandlers.clear()
    }

    @Test
    fun detect_is_access_token_expired() = runTest {

        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            accessToken = jwtGenerator.generateTestJwt(
                expires = Date.from(Instant.now().plusSeconds(65)) // Just about to expire when 60sec margin include
            ),
            refreshToken = jwtGenerator.generateTestJwt()
        )))

        assertTrue(rownd.state.value.auth.isAccessTokenValid)

        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            accessToken = jwtGenerator.generateTestJwt(
                expires = Date.from(Instant.now().plusSeconds(55)) // Just expired when 60sec margin include
            ),
            refreshToken = jwtGenerator.generateTestJwt()
        )))

        assertFalse(rownd.state.value.auth.isAccessTokenValid)

        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            accessToken = jwtGenerator.generateTestJwt(
                expires = Date.from(Instant.now().plusSeconds(3600)) // Access Token is valid for another hour
            ),
            refreshToken = jwtGenerator.generateTestJwt()
        )))

        assertTrue(rownd.state.value.auth.isAccessTokenValid)

        rownd.stateRepo.getStore().dispatch(StateAction.SetAuth(AuthState(
            accessToken = jwtGenerator.generateTestJwt(
                expires = Date.from(Instant.now().minusSeconds(3600)) // Access Token expired an hour ago
            ),
            refreshToken = jwtGenerator.generateTestJwt()
        )))

        assertFalse(rownd.state.value.auth.isAccessTokenValid)
    }

}