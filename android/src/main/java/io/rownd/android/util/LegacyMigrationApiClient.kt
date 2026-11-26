package io.rownd.android.util

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Inject
import javax.inject.Singleton

// Requests that use captured credentials must not let SuperTokensInterceptor substitute tokens
// or install response tokens before Rownd checks ownership.
@Singleton
class LegacyMigrationApiClient internal constructor(
    rowndContext: RowndContext,
    engine: HttpClientEngine,
) : KtorApiClient(engine, rowndContext, followRedirects = false) {
    @Inject constructor(rowndContext: RowndContext) : this(rowndContext, createEngine())

    companion object {
        internal fun createEngine(): HttpClientEngine = OkHttp.create {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }
}
