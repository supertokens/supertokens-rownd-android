package io.rownd.android.util

import io.rownd.android.Rownd
import io.rownd.android.RowndSignInType
import io.rownd.android.RowndSignInUserType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject

@Serializable
enum class RowndEventType {
    @SerialName("sign_in_started")
    SignInStarted,

    @SerialName("sign_in_completed")
    SignInCompleted,

    @SerialName("sign_in_failed")
    SignInFailed,

    @SerialName("user_updated")
    UserUpdated,

    @SerialName("sign_out")
    SignOut,

    @SerialName("user_data")
    UserData,

    @SerialName("user_data_saved")
    UserDataSaved,

    @SerialName("verification_started")
    VerificationStarted,

    @SerialName("verification_completed")
    VerificationCompleted,

    @SerialName("auth")
    Auth
}

@Serializable
data class RowndEvent (
    var event: RowndEventType,
    var data: Map<String, String?> = emptyMap()
)

internal fun signInCompletedEventData(
    method: RowndSignInType? = null,
    userType: RowndSignInUserType? = null,
    appVariantUserType: RowndSignInUserType? = userType,
): Map<String, String?> {
    val data = mutableMapOf<String, String?>()
    method?.let { data["method"] = it.value }
    userType?.let { data["user_type"] = it.value }
    appVariantUserType?.let { data["app_variant_user_type"] = it.value }
    return data
}

class RowndEventEmitter<T> @Inject constructor() {
    private val observers = mutableSetOf<(T) -> Unit>()
    private var signInCompletedAccessToken: String? = null

    fun addListener(observer: (T) -> Unit) {
        observers.add(observer)
    }

    fun removeListener(observer: (T) -> Unit) {
        observers.remove(observer)
    }

    internal fun emit(value: T) {
        if (value is RowndEvent) {
            if (value.event == RowndEventType.SignOut) {
                signInCompletedAccessToken = null
            }

            if (value.event == RowndEventType.SignInCompleted) {
                val accessToken = runCatching { Rownd.store.currentState.auth.accessToken }.getOrNull()
                if (accessToken != null) {
                    if (signInCompletedAccessToken == accessToken) return
                    signInCompletedAccessToken = accessToken
                }
            }
        }

        for (observer in observers)
            observer(value)
    }
}
