package io.rownd.android

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewFeature
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewCapabilityInstrumentedTest {
    @Test
    fun productionBridgeCapabilitiesAreAvailable() {
        val provider = WebView.getCurrentWebViewPackage()
        val listener = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        println("WebView provider=${provider?.packageName} version=${provider?.versionName} WEB_MESSAGE_LISTENER=$listener DOCUMENT_START_SCRIPT=$documentStart")
        assertTrue("Production secure bridge requires WEB_MESSAGE_LISTENER and DOCUMENT_START_SCRIPT", listener && documentStart)
    }
}
