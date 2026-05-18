package io.rownd.android

import io.rownd.android.models.network.AppConfig
import io.rownd.android.models.network.AppConfigConfig
import io.rownd.android.models.network.AppConfigResponse
import io.rownd.android.models.network.AppVariant
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.RowndConfig
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private val json = Json { ignoreUnknownKeys = true }

class AppConfigModelTest {

    private val baseAppConfigJson = """
        {
          "app": {
            "id": "app_test123",
            "icon": "",
            "user_verification_fields": [],
            "schema": {},
            "config": %s
          }
        }
    """.trimIndent()

    @Test
    fun `network AppConfigConfig with supertokens decodes apiDomain and apiBasePath`() {
        val configJson = """
            {
              "hub": { "auth": { "sign_in_methods": {} } },
              "customizations": {},
              "supertokens": {
                "appInfo": {
                  "apiDomain": "https://api.example.com",
                  "apiBasePath": "/auth"
                }
              }
            }
        """.trimIndent()

        val network = json.decodeFromString(AppConfigConfig.serializer(), configJson)
        assertEquals("https://api.example.com", network.supertokens.appInfo.apiDomain)
        assertEquals("/auth", network.supertokens.appInfo.apiBasePath)
    }

    @Test
    fun `network AppConfigConfig without supertokens field deserializes with empty defaults`() {
        val configJson = """
            {
              "hub": { "auth": { "sign_in_methods": {} } },
              "customizations": {}
            }
        """.trimIndent()

        val network = json.decodeFromString(AppConfigConfig.serializer(), configJson)
        assertNotNull(network.supertokens)
        assertEquals("", network.supertokens.appInfo.apiDomain)
        assertNull(network.supertokens.appInfo.apiBasePath)
    }

    @Test
    fun `asDomainModel passes supertokens through unchanged`() {
        val stConfig = SuperTokensConfig(
            appInfo = SuperTokensAppInfo(
                apiDomain = "https://api.example.com",
                apiBasePath = "/auth"
            )
        )
        val network = AppConfigConfig(supertokens = stConfig)
        val domain = network.asDomainModel()

        assertEquals(stConfig.appInfo.apiDomain, domain.supertokens.appInfo.apiDomain)
        assertEquals(stConfig.appInfo.apiBasePath, domain.supertokens.appInfo.apiBasePath)
    }

    @Test
    fun `full AppConfigResponse with supertokens deserializes correctly`() {
        val payload = baseAppConfigJson.format(
            """
            {
              "hub": { "auth": { "sign_in_methods": {} } },
              "customizations": {},
              "supertokens": {
                "appInfo": {
                  "apiDomain": "https://api.example.com"
                }
              }
            }
            """.trimIndent()
        )

        val response = json.decodeFromString(AppConfigResponse.serializer(), payload)
        val domain = response.asDomainModel()

        assertEquals("https://api.example.com", domain.config.supertokens.appInfo.apiDomain)
        assertNull(domain.config.supertokens.appInfo.apiBasePath)
    }

    @Test
    fun `hub script query params use supertokens config`() {
        val params = RowndConfig.buildHubScriptQueryParams(
            appKey = "app_key_test",
            supertokens = SuperTokensConfig(
                appInfo = SuperTokensAppInfo(
                    apiDomain = "https://api.example.com",
                    apiBasePath = "/auth"
                )
            )
        )

        assertEquals(
            listOf(
                "appKey" to "app_key_test",
                "apiDomain" to "https://api.example.com",
                "apiBasePath" to "/auth"
            ),
            params
        )
    }
}
