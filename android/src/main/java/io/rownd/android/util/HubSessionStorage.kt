package io.rownd.android.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import io.rownd.android.models.RowndConfig

private const val TAG = "Rownd.HubStorage"

internal object HubSessionStorage {
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
