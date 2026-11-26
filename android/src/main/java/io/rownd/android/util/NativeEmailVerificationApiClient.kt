package io.rownd.android.util

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal class NativeEmailVerificationApiClient(
    private val engine: HttpClientEngine,
    private val ownedClient: HttpClient,
) : AutoCloseable {
    constructor(rowndContext: RowndContext, engine: HttpClientEngine = createEngine()) :
        this(engine, KtorApiClient(engine, rowndContext, followRedirects = false).client)

    internal var client = ownedClient

    override fun close() {
        client.close()
        if (client !== ownedClient) ownedClient.close()
        // Ktor doesn't close an explicitly supplied engine with the HttpClient.
        engine.close()
    }

    companion object {
        internal fun createEngine(client: OkHttpClient = createOkHttpClient()): HttpClientEngine =
            OkHttp.create { preconfigured = client }

        // An idle socket may have been closed by the peer. Replaying a verification whose
        // response was lost is unsafe, so discard idle sockets rather than retry the POST.
        internal fun createOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
