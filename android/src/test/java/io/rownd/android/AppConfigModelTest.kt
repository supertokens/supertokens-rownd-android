package io.rownd.android

import io.rownd.android.models.network.AppConfig
import io.rownd.android.models.network.AppConfigConfig
import io.rownd.android.models.network.AppConfigResponse
import io.rownd.android.models.network.AppVariant
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.RowndConfig
import io.rownd.android.models.network.SignInLinkApi
import io.rownd.android.models.repos.AppConfigRepo
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
    fun `apple sign in method decodes client type fields`() {
        val payload = baseAppConfigJson.format(
            """
            {
              "hub": {
                "auth": {
                  "sign_in_methods": {
                    "apple": {
                      "enabled": true,
                      "client_id": "com.example.web",
                      "web_client_type": "web",
                      "ios_client_type": "ios",
                      "android_client_type": "android"
                    }
                  }
                }
              },
              "customizations": {}
            }
            """.trimIndent()
        )

        val response = json.decodeFromString(AppConfigResponse.serializer(), payload)
        val apple = response.asDomainModel().config.hub.auth.signInMethods.apple

        assertEquals(true, apple.enabled)
        assertEquals("com.example.web", apple.clientId)
        assertEquals("web", apple.webClientType)
        assertEquals("ios", apple.iosClientType)
        assertEquals("android", apple.androidClientType)
    }

    @Test
    fun `plugin schema fields can omit optional required flag`() {
        val payload = """
            {
              "app": {
                "id": "app_test123",
                "icon": "",
                "user_verification_fields": [],
                "schema": {
                  "zip_code": {
                    "display_name": "Zip code",
                    "type": "string",
                    "owned_by": "user",
                    "user_visible": true,
                    "read_only": false,
                    "show_empty": false
                  }
                },
                "config": {
                  "hub": { "auth": { "sign_in_methods": {} } },
                  "customizations": {}
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString(AppConfigResponse.serializer(), payload)
        val field = response.app.schema["zip_code"]

        assertNotNull(field)
        assertNull(field?.required)
        assertNull(field?.encryption)
    }

    @Test
    fun `hub script query params use supertokens config`() {
        val params = RowndConfig.buildHubScriptQueryParams(
            supertokens = SuperTokensConfig(
                appInfo = SuperTokensAppInfo(
                    apiDomain = "https://api.example.com",
                    apiBasePath = "/auth"
                )
            )
        )

        assertEquals(
            listOf(
                "apiDomain" to "https://api.example.com",
                "apiBasePath" to "/auth"
            ),
            params
        )
    }

    @Test
    fun `hub script query params fall back to rownd API config`() {
        val params = RowndConfig.buildHubScriptQueryParams(
            supertokens = SuperTokensConfig(),
            fallbackApiDomain = "http://10.0.2.2:3137",
            fallbackApiBasePath = "/auth"
        )

        assertEquals(
            listOf(
                "apiDomain" to "http://10.0.2.2:3137",
                "apiBasePath" to "/auth"
            ),
            params
        )
    }

    @Test
    fun `app config repo keeps configured supertokens when backend omits it`() {
        val configured = SuperTokensConfig(
            appInfo = SuperTokensAppInfo(
                apiDomain = "https://api.example.com",
                apiBasePath = "/auth",
            )
        )

        val resolved = AppConfigRepo.resolveSuperTokensConfig(
            backendConfig = SuperTokensConfig(),
            configuredConfig = configured,
        )

        assertEquals("https://api.example.com", resolved.appInfo.apiDomain)
        assertEquals("/auth", resolved.appInfo.apiBasePath)
    }

    @Test
    fun `app config repo prefers backend supertokens when present`() {
        val configured = SuperTokensConfig(
            appInfo = SuperTokensAppInfo(apiDomain = "https://configured.example.com")
        )
        val backend = SuperTokensConfig(
            appInfo = SuperTokensAppInfo(apiDomain = "https://backend.example.com")
        )

        val resolved = AppConfigRepo.resolveSuperTokensConfig(
            backendConfig = backend,
            configuredConfig = configured,
        )

        assertEquals("https://backend.example.com", resolved.appInfo.apiDomain)
    }

    @Test
    fun `deep link helper rewrites custom scheme to configured hub`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "rowndsupertokens://account/login?token=abc#frag",
            deepLinkScheme = "rowndsupertokens",
            hubBaseUrl = "https://app.rownd-hub.supertokens.com",
        )

        assertEquals("https://app.rownd-hub.supertokens.com/account/login?token=abc#frag", hubUrl)
    }

    @Test
    fun `deep link helper preserves encoded callback query params`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "sandboxx://account/login?apiBasePath=%2Fauth&apiDomain=https%3A%2F%2Fapi-stage.sandboxx.us#frag",
            deepLinkScheme = "sandboxx",
            hubBaseUrl = "https://rownd-hub.supertokens.com",
        )

        assertEquals(
            "https://rownd-hub.supertokens.com/account/login?apiBasePath=%2Fauth&apiDomain=https%3A%2F%2Fapi-stage.sandboxx.us#frag",
            hubUrl,
        )
    }

    @Test
    fun `deep link helper accepts production hub subdomains`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "https://tenant.rownd-hub.supertokens.com/account/verify-email?code=abc",
            deepLinkScheme = "rowndsupertokens",
            hubBaseUrl = "https://app.rownd-hub.supertokens.com",
        )

        assertEquals("https://app.rownd-hub.supertokens.com/account/verify-email?code=abc", hubUrl)
    }

    @Test
    fun `deep link helper accepts staging hub urls`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "https://staging.supertokens-rownd-hub.pages.dev/account/login?link=abc",
            deepLinkScheme = "rowndsupertokens",
            hubBaseUrl = "https://staging.supertokens-rownd-hub.pages.dev",
        )

        assertEquals("https://staging.supertokens-rownd-hub.pages.dev/account/login?link=abc", hubUrl)
    }

    @Test
    fun `deep link helper rejects unsupported hosts`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "https://evil.example.com/account/login?link=abc",
            deepLinkScheme = "rowndsupertokens",
            hubBaseUrl = "https://app.rownd-hub.supertokens.com",
        )

        assertNull(hubUrl)
    }

    @Test
    fun `deep link helper rejects unsupported paths`() {
        val hubUrl = SignInLinkApi.toHubUrl(
            rawUrl = "https://tenant.rownd-hub.supertokens.com/admin?link=abc",
            deepLinkScheme = "rowndsupertokens",
            hubBaseUrl = "https://app.rownd-hub.supertokens.com",
        )

        assertNull(hubUrl)
    }
}
