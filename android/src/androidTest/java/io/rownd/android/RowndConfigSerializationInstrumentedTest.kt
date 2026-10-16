package io.rownd.android

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.rownd.android.models.RowndConfig
import io.rownd.android.models.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RowndConfigSerializationInstrumentedTest {
    @Before
    fun setUp() {
        Rownd._registerActivityLifecycle(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        )
    }

    @Test
    fun hubConfigOmitsDefaultAppleCallbackButPreservesExplicitOverride() {
        val defaultConfig = Json.parseToJsonElement(
            json.encodeToString(RowndConfig.serializer(), RowndConfig())
        ).jsonObject
        assertFalse(defaultConfig.containsKey("appleIdCallbackUrl"))

        val callbackUrl = "https://api.example.com/auth/callback/apple"
        val overriddenConfig = Json.parseToJsonElement(
            json.encodeToString(RowndConfig.serializer(), RowndConfig(appleIdCallbackUrl = callbackUrl))
        ).jsonObject
        assertEquals(callbackUrl, overriddenConfig["appleIdCallbackUrl"]?.jsonPrimitive?.content)
    }
}
