package io.rownd.android

import io.rownd.android.util.signInCompletedEventData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RowndEventTest {

    @Test
    fun `sign in completed event data includes app variant user type fallback`() {
        val data = signInCompletedEventData(
            method = RowndSignInType.SignInLink,
            userType = RowndSignInUserType.ExistingUser,
        )

        assertEquals(RowndSignInType.SignInLink.value, data["method"])
        assertEquals(RowndSignInUserType.ExistingUser.value, data["user_type"])
        assertEquals(RowndSignInUserType.ExistingUser.value, data["app_variant_user_type"])
    }

    @Test
    fun `sign in completed event data omits user type fields when absent`() {
        val data = signInCompletedEventData(method = RowndSignInType.Google)

        assertEquals(RowndSignInType.Google.value, data["method"])
        assertFalse(data.containsKey("user_type"))
        assertFalse(data.containsKey("app_variant_user_type"))
    }
}
