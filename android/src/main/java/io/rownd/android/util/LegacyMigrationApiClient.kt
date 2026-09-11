package io.rownd.android.util

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Inject
import javax.inject.Singleton

// SuperTokensInterceptor saves response headers before AuthRepo can validate them or reject a
// stale attempt. Migration must use an isolated transport and commit the session explicitly.
// Revocation of an already signed-out session shares this transport to avoid clearing a newer login.
@Singleton
class LegacyMigrationApiClient internal constructor(
    rowndContext: RowndContext,
    engine: HttpClientEngine,
) : KtorApiClient(engine, rowndContext) {
    @Inject constructor(rowndContext: RowndContext) : this(rowndContext, OkHttp.create())
}
