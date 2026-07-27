package io.rownd.android

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class SignInLinksInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.rownd.android.test", appContext.packageName)
    }

    @Test
    fun parseVariousUris() {
        val passwordlessUri = Uri.parse("rowndsupertokens://account/login?preAuthSessionId=pid#abc")
        val emailVerificationUri = Uri.parse("rowndsupertokens://account/verify-email?token=token_123")

        assertEquals("rowndsupertokens", passwordlessUri.scheme)
        assertEquals("account", passwordlessUri.host)
        assertEquals("/login", passwordlessUri.path)
        assertEquals("abc", passwordlessUri.fragment)
        assertEquals("rowndsupertokens", emailVerificationUri.scheme)
        assertEquals("account", emailVerificationUri.host)
        assertEquals("/verify-email", emailVerificationUri.path)
    }

    @Test
    fun ignoresIntentWithoutDeepLink() {
        assertFalse(Rownd.handleIntent(Intent()))
    }
}
