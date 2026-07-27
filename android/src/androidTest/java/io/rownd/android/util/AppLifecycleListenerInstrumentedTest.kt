package io.rownd.android.util

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NewIntentTestActivity : ComponentActivity()

@RunWith(AndroidJUnit4::class)
class AppLifecycleListenerInstrumentedTest {
    @Test
    fun forwardsIntentDeliveredToWarmSingleTopActivity() {
        val expectedUri = Uri.parse("rowndsupertokens://account/login?token=warm")
        val receivedIntent = arrayOfNulls<Intent>(1)
        val intentReceived = CountDownLatch(1)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val lifecycleListener = AppLifecycleListener(application)
        lifecycleListener.registerNewIntentListener {
            receivedIntent[0] = it
            intentReceived.countDown()
        }

        try {
            ActivityScenario.launch(NewIntentTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, expectedUri, activity, NewIntentTestActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                }

                check(intentReceived.await(5, TimeUnit.SECONDS)) { "The warm activity did not receive the new intent" }
                assertEquals(expectedUri, receivedIntent[0]?.data)
            }
        } finally {
            lifecycleListener.unregister()
        }
    }

    @Test
    fun stopsForwardingIntentsAfterUnregister() {
        val firstIntentReceived = CountDownLatch(1)
        val receivedIntentCount = AtomicInteger()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val lifecycleListener = AppLifecycleListener(application)
        lifecycleListener.registerNewIntentListener {
            receivedIntentCount.incrementAndGet()
            firstIntentReceived.countDown()
        }

        ActivityScenario.launch(NewIntentTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, NewIntentTestActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }
            check(firstIntentReceived.await(5, TimeUnit.SECONDS)) { "The listener was not registered" }

            instrumentation.runOnMainSync { lifecycleListener.unregister() }
            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, NewIntentTestActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            }
            instrumentation.waitForIdleSync()

            assertEquals(1, receivedIntentCount.get())
        }
    }
}
