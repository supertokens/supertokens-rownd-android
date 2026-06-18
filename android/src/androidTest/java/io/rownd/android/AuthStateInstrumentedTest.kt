package io.rownd.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.rownd.android.models.domain.AuthState
import io.rownd.android.models.domain.SuperTokensAppInfo
import io.rownd.android.models.domain.SuperTokensConfig
import io.rownd.android.util.JwtGenerator
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthStateInstrumentedTest {
    private val jwtGenerator = JwtGenerator()
    private lateinit var originalSuperTokensConfig: SuperTokensConfig

    @Before
    fun setUp() {
        originalSuperTokensConfig = Rownd.config.supertokens
    }

    @After
    fun tearDown() {
        Rownd.config.supertokens = originalSuperTokensConfig
    }

    @Test
    fun superTokensBackedAuthStateRejectsValidLegacyRowndAccessToken() {
        Rownd.config.supertokens = SuperTokensConfig(
            appInfo = SuperTokensAppInfo(
                apiDomain = "https://api.example.com",
                apiBasePath = "/auth",
            )
        )

        val legacyAccessToken = AuthState(
            accessToken = jwtGenerator.generateTestJwt(appUserId = "app-user-id"),
            refreshToken = "legacy-refresh-token",
        )
        val superTokensAccessToken = AuthState(
            accessToken = jwtGenerator.generateTestJwt(sessionHandle = "session-handle")
        )

        assertFalse("legacy Rownd access token should not be public-valid after SuperTokens config", legacyAccessToken.isAccessTokenValid)
        assertTrue("SuperTokens access token should remain public-valid", superTokensAccessToken.isAccessTokenValid)
    }
}
