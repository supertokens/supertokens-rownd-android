package io.rownd.android

import io.rownd.android.models.MessageType
import io.rownd.android.views.isAllowedOverLegacyBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBridgeAuthorizationTest {
    @Test
    fun `legacy bridge rejects state mutations and native triggers`() {
        val sensitiveTypes = listOf(
            MessageType.authentication,
            MessageType.signOut,
            MessageType.AuthChallengeInitiated,
            MessageType.AuthChallengeCleared,
            MessageType.UserDataUpdate,
            MessageType.triggerSignInWithGoogle,
            MessageType.VerifyEmail,
            MessageType.Event,
        )

        sensitiveTypes.forEach { type ->
            assertFalse("$type must require secure WebMessage transport", type.isAllowedOverLegacyBridge())
        }
    }

    @Test
    fun `legacy bridge retains only non-sensitive compatibility messages`() {
        val compatibilityTypes = listOf(
            MessageType.tryAgain,
            MessageType.CloseHubView,
            MessageType.HubLoaded,
            MessageType.HubResize,
            MessageType.CanTouchBackgroundToDismiss,
            MessageType.OpenEmailApp,
        )

        compatibilityTypes.forEach { type ->
            assertTrue("$type should remain available over the legacy bridge", type.isAllowedOverLegacyBridge())
        }
    }
}
