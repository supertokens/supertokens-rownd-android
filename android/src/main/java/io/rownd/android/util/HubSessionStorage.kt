package io.rownd.android.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import io.rownd.android.models.RowndConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "Rownd.HubStorage"

internal object HubSessionStorage {
    @Volatile private var cleanupBarrier: CompletableDeferred<Unit>? = null
    private var failedCleanupConfig: RowndConfig? = null
    val hasPendingCleanup: Boolean get() = cleanupBarrier?.isCompleted == false
    suspend fun awaitCleanup(): Unit = withContext(Dispatchers.Main) {
        val barrier = cleanupBarrier
        try {
            barrier?.await()
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (cleanupBarrier !== barrier) {
                awaitCleanup()
            } else {
                // Retry a completed failed attempt, never bypass a still-pending cookie removal.
                clearAndAwait(failedCleanupConfig ?: throw error)
            }
        }
    }
    internal var removeCookies: ((Boolean) -> Unit) -> Unit = { callback ->
        CookieManager.getInstance().removeAllCookies { callback(it) }
    }

    suspend fun clearAndAwait(config: RowndConfig, webView: WebView? = null) = withContext(Dispatchers.Main) {
        if (hasPendingCleanup) awaitCleanup()
        val barrier = CompletableDeferred<Unit>()
        cleanupBarrier = barrier
        failedCleanupConfig = null
        var cookiesRequested = false
        try { withTimeout(5_000) {
            if (webView != null) suspendCancellableCoroutine<Unit> { continuation ->
                webView.evaluateJavascript("localStorage.clear(); sessionStorage.clear(); true") {
                    if (continuation.isActive) {
                        if (it == "true") continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("Hub DOM cleanup failed"))
                    }
                }
            }
            val storage = WebStorage.getInstance()
            storage.deleteOrigin(config.baseUrl.trimEnd('/'))
            storage.deleteOrigin(config.supertokens.appInfo.apiDomain.ifBlank { config.apiUrl }.trimEnd('/'))
            storage.deleteAllData()
            suspendCancellableCoroutine<Unit> { continuation ->
                storage.getOrigins { origins ->
                    if (continuation.isActive) {
                        if (origins.isNullOrEmpty()) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("Hub storage cleanup failed"))
                    }
                }
            }
            suspendCancellableCoroutine<Unit> { continuation ->
                cookiesRequested = true
                try { removeCookies {
                    // false means there were no cookies to remove, not that removal failed.
                    val error = runCatching { CookieManager.getInstance().flush() }.exceptionOrNull()
                    if (error == null) {
                        barrier.complete(Unit)
                        if (continuation.isActive) continuation.resume(Unit)
                    } else {
                        failedCleanupConfig = config
                        barrier.completeExceptionally(error)
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                } } catch (error: Exception) {
                    cookiesRequested = false
                    throw error
                }
            }
        } } catch (error: Exception) {
            // A late cookie callback may still erase cookies. Keep new presentations behind
            // its barrier even if this handoff timed out or its Activity was destroyed.
            if (!cookiesRequested) {
                failedCleanupConfig = config
                barrier.completeExceptionally(error)
            }
            throw error
        }
    }

    fun clear(config: RowndConfig, webView: WebView? = null) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clearOnMainThread(config, webView)
        } else {
            Handler(Looper.getMainLooper()).post { clearOnMainThread(config, webView) }
        }
    }

    private fun clearOnMainThread(config: RowndConfig, webView: WebView?) {
        runCatching {
            webView?.evaluateJavascript(
                "localStorage.clear(); sessionStorage.clear();",
                null,
            )
        }.onFailure { Log.w(TAG, "Failed to clear active Hub WebView storage", it) }

        runCatching {
            val storage = WebStorage.getInstance()
            storage.deleteOrigin(config.baseUrl.trimEnd('/'))
            if (config.apiUrl.isNotBlank()) {
                storage.deleteOrigin(config.apiUrl.trimEnd('/'))
            }

            // Hub may store auth state under related origins during redirects. Clearing DOM storage
            // prevents the next sign-in request from silently refreshing the previous Hub session.
            storage.deleteAllData()
        }.onFailure { Log.w(TAG, "Failed to clear Hub WebStorage", it) }

        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }.onFailure { Log.w(TAG, "Failed to clear Hub cookies", it) }
    }
}
