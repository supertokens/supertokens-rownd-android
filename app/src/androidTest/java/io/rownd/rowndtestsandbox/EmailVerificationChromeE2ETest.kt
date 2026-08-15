package io.rownd.rowndtestsandbox

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EmailVerificationChromeE2ETest {
    @Test
    fun chromeCustomSchemeLinkOpensApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val chromePackage = "com.android.chrome"

        val launcherUrl = Uri.parse(
            InstrumentationRegistry.getArguments().getString("harnessUrl")
                ?: "http://10.0.2.2:3137",
        ).buildUpon()
            .appendPath("test")
            .appendPath("email-verification-launcher")
            .appendQueryParameter("token", "opaque-token")
            .appendQueryParameter("rowndPendingVerificationId", "pending-123")
            .appendQueryParameter("apiDomain", "https://api.example.com")
            .appendQueryParameter("apiBasePath", "/auth")
            .build()

        context.startActivity(Intent(Intent.ACTION_VIEW, launcherUrl).apply {
            setPackage(chromePackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })

        val device = UiDevice.getInstance(instrumentation)
        assertNotNull("Chrome did not open", device.wait(Until.findObject(By.pkg(chromePackage)), 10_000))
        dismissChromeFirstRun(device)

        val link = device.wait(Until.findObject(By.desc("Open email verification")), 10_000)
        assertNotNull("Verification launcher link did not load", link)
        link.click()
        handleExternalAppPrompt(device, context.getString(R.string.app_name))

        assertNotNull(
            "Android did not dispatch the custom scheme to the app",
            device.wait(Until.findObject(By.pkg(context.packageName)), 10_000),
        )

    }

    private fun dismissChromeFirstRun(device: UiDevice) {
        val dismissButtons = listOf("Accept & continue", "Use without an account", "No thanks")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (device.hasObject(By.desc("Open email verification"))) return
            val dismissButton = dismissButtons.firstNotNullOfOrNull { text -> device.findObject(By.text(text)) }
            if (dismissButton != null) {
                dismissButton.click()
                device.waitForIdle()
                continue
            }
            Thread.sleep(200)
        }
    }

    private fun handleExternalAppPrompt(device: UiDevice, appName: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.text("Open"))?.let {
                it.click()
                return
            }
            device.findObject(By.textContains(appName))?.let {
                it.click()
                device.findObject(By.text("Just once"))?.click()
                return
            }
            if (device.hasObject(By.pkg(InstrumentationRegistry.getInstrumentation().targetContext.packageName))) return
            Thread.sleep(200)
        }
    }
}
