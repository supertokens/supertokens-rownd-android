package io.rownd.android.util

import io.ktor.client.engine.okhttp.OkHttp
import javax.inject.Inject
import javax.inject.Singleton

// Used only for POST /hub/auth/token on api.rownd.io during startup legacy session migration.
// Must not have SuperTokensInterceptor registered — it sends a legacy Rownd access token, not ST headers.
// Remove this class once the migration window closes.
@Singleton
class LegacyTokenApiClient @Inject constructor(
    rowndContext: RowndContext
) : KtorApiClient(OkHttp.create(), rowndContext)
