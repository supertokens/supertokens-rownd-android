package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import io.rownd.android.views.HubPageSelector
import io.rownd.android.views.RowndJavascriptInterface
import io.rownd.android.views.RowndWebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RowndWebViewEventInstrumentedTest {

    @Test
    fun hubSignInCompletedEventWithEmptyDataIsForwarded() {
        val interop = """
            {
              "type": "event",
              "payload": {
                "event": "sign_in_completed",
                "data": {}
              }
            }
        """.trimIndent()
        val events = mutableListOf<RowndEvent>()
        val listener: (RowndEvent) -> Unit = { events.add(it) }

        Rownd.addEventListener(listener)
        try {
            val bridge = createJavascriptInterface(HubPageSelector.SignIn)
            bridge.postSecureMessage(interop)

            assertEquals(listOf(RowndEventType.SignInCompleted), events.map { it.event })
            assertTrue(events.single().data.isEmpty())
        } finally {
            Rownd.removeEventListener(listener)
        }
    }

    private fun createJavascriptInterface(targetPage: HubPageSelector): RowndJavascriptInterface {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val webViewRef = AtomicReference<RowndWebView>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webViewRef.set(RowndWebView(context, null).apply {
                rowndClient = Rownd
                this.targetPage = targetPage
                dismiss = {}
            })
        }

        return RowndJavascriptInterface(webViewRef.get(), {}, {})
    }
}
