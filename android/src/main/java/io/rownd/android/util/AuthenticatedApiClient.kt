package io.rownd.android.util
import io.ktor.client.engine.HttpClientEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthenticatedApiClient @Inject constructor(
    engine: HttpClientEngine,
    rowndContext: RowndContext
): KtorApiClient(engine, rowndContext)
