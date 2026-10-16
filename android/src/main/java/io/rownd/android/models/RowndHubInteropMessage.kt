package io.rownd.android.models

import io.rownd.android.RowndSignInIntent
import io.rownd.android.RowndSignInUserType
import io.rownd.android.models.network.User
import io.rownd.android.util.RowndEvent
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

//val json = Json { encodeDefaults = true }

@Serializable(with = RowndHubInteropMessageSerializer::class)
sealed class RowndHubInteropMessage {
    abstract var type: MessageType
}

@Serializable
enum class MessageType {
    @SerialName("authentication")
    authentication,

    @SerialName("sign_out")
    signOut,

    @SerialName("sign_in")
    SignIn,

    @SerialName("try_again")
    tryAgain,

    @SerialName("trigger_sign_in_with_google")
    triggerSignInWithGoogle,

    @SerialName("user_data_update")
    UserDataUpdate,

    @SerialName("close_hub_view_controller")
    CloseHubView,

    @SerialName("hub_loaded")
    HubLoaded,

    @SerialName("hub_resize")
    HubResize,

    @SerialName("can_touch_background_to_dismiss")
    CanTouchBackgroundToDismiss,

    @SerialName("event")
    Event,

    @SerialName("auth_challenge_initiated")
    AuthChallengeInitiated,

    @SerialName("auth_challenge_cleared")
    AuthChallengeCleared,

    @SerialName("open_email_app")
    OpenEmailApp,

    @SerialName("verify_email")
    VerifyEmail,

    Unknown
}

@Serializable
data class AuthenticationMessage(
    override var type: MessageType = MessageType.authentication,
    var payload: AuthenticationPayload
) : RowndHubInteropMessage()

@Serializable
data class AuthenticationPayload(
    @SerialName("access_token")
    var accessToken: String,

    @SerialName("refresh_token")
    var refreshToken: String? = null,

    @SerialName("front_token")
    var frontToken: String? = null,

    @SerialName("anti_csrf")
    var antiCsrf: String? = null,

    @SerialName("user_type")
    var userType: RowndSignInUserType? = null,

    @SerialName("app_variant_user_type")
    var appVariantUserType: RowndSignInUserType? = null,

    var method: String? = null,
)

@Serializable
data class SignInMessage(
    override var type: MessageType = MessageType.SignIn,
    var payload: SignInPayload? = null,
) : RowndHubInteropMessage()

@Serializable
data class SignInPayload(
    @SerialName("was_user_initiated")
    var wasUserInitiated: Boolean? = null,
    @SerialName("email_change")
    var emailChange: EmailChangeDescriptor? = null,
)

@Serializable
data class EmailChangeDescriptor(
    val version: Int,
    @SerialName("request_id") val requestId: String,
    @SerialName("local_draft") val localDraft: LocalDraft,
    @SerialName("expires_at") val expiresAt: Long,
) {
    @Serializable
    data class LocalDraft(
        @SerialName("owner_id") val ownerId: String,
        @SerialName("tenant_id") val tenantId: String,
        val configuration: String,
        val changes: Changes,
    )
    @Serializable
    data class Changes(val email: String)
}

@Serializable
data class SignOutMessage(
    override var type: MessageType = MessageType.signOut,
    var payload: SignOutPayload? = null,
) : RowndHubInteropMessage()

@Serializable
data class SignOutPayload(
    @SerialName("was_user_initiated")
    var wasUserInitiated: Boolean? = null,
)

@Serializable
data class TryAgainMessage(
    override var type: MessageType = MessageType.tryAgain
) : RowndHubInteropMessage()


@Serializable
data class TriggerSignInWithGoogleMessage(
    override var type: MessageType = MessageType.triggerSignInWithGoogle,
    var payload: TriggerSignInWithGooglePayload? = null
) : RowndHubInteropMessage()

@Serializable
data class TriggerSignInWithGooglePayload(
    @SerialName("intent")
    var intent: RowndSignInIntent? = null,
    var hint: String? = null,
)

@Serializable
data class UserDataUpdateMessage(
    override var type: MessageType = MessageType.UserDataUpdate,
    var payload: User
) : RowndHubInteropMessage()

@Serializable
data class CloseHubViewMessage(
    override var type: MessageType = MessageType.CloseHubView
) : RowndHubInteropMessage()

@Serializable
data class HubResizePayload(
    @SerialName("height")
    var height: String? = null,
)
@Serializable
data class HubLoaded(
    override var type: MessageType = MessageType.HubLoaded
) : RowndHubInteropMessage()

@Serializable
data class HubResizeMessage(
    override var type: MessageType = MessageType.HubResize,
    var payload: HubResizePayload
) : RowndHubInteropMessage()

@Serializable
data class CanTouchBackgroundToDismissMessage(
    override var type: MessageType = MessageType.CanTouchBackgroundToDismiss,
    var payload: CanTouchBackgroundToDismissPayload
) : RowndHubInteropMessage()

@Serializable
data class CanTouchBackgroundToDismissPayload(
    @SerialName("enable")
    var enable: String
)

@Serializable
data class EventMessage(
    override var type: MessageType = MessageType.Event,

    @SerialName("payload")
    var payload: RowndEvent
) : RowndHubInteropMessage()

@Serializable
data class OpenEmailAppMessage(
    override var type: MessageType = MessageType.OpenEmailApp
) : RowndHubInteropMessage()

@Serializable
data class VerifyEmailMessage(
    override var type: MessageType = MessageType.VerifyEmail,
    var payload: VerifyEmailPayload,
) : RowndHubInteropMessage()

@Serializable
data class VerifyEmailPayload(
    @SerialName("request_id")
    var requestId: String,
)

@Serializable
data class AuthChallengeInitiatedMessage(
    override var type: MessageType = MessageType.AuthChallengeInitiated,

    @SerialName("payload")
    var payload: AuthChallengeInitiatedPayload
) : RowndHubInteropMessage()

@Serializable
data class AuthChallengeInitiatedPayload(
    @SerialName("challenge_id")
    var challengeId: String,

    @SerialName("user_identifier")
    var userIdentifier: String
)

@Serializable
data class AuthChallengeClearedMessage(
    override var type: MessageType = MessageType.AuthChallengeCleared
) : RowndHubInteropMessage()

object RowndHubInteropMessageSerializer : JsonContentPolymorphicSerializer<RowndHubInteropMessage>(RowndHubInteropMessage::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<out RowndHubInteropMessage> {
        return when (val messageType = element.jsonObject["type"]?.jsonPrimitive?.content) {
            "authentication" -> AuthenticationMessage.serializer()
            "sign_out" -> SignOutMessage.serializer()
            "sign_in" -> {
                val payload = element.jsonObject["payload"] as? JsonObject
                if (payload != null && !setOf("was_user_initiated", "email_change").containsAll(payload.keys)) {
                    throw SerializationException("Invalid sign_in fields")
                }
                val descriptor = (element.jsonObject["payload"] as? JsonObject)?.get("email_change")
                if (descriptor != null && descriptor !is JsonObject) throw SerializationException("Invalid email_change")
                if (descriptor is JsonObject && (descriptor.keys != setOf("version", "request_id", "local_draft", "expires_at") ||
                    descriptor.toString().toByteArray(Charsets.UTF_8).size > 32 * 1024)) {
                    throw SerializationException("Invalid email_change fields")
                }
                if (descriptor is JsonObject && (listOf("version", "expires_at").any {
                    (descriptor[it] as? JsonPrimitive)?.isString != false
                } || (descriptor["request_id"] as? JsonPrimitive)?.isString != true || descriptor["local_draft"] !is JsonObject)) {
                    throw SerializationException("Invalid email_change field types")
                }
                if (descriptor is JsonObject) {
                    val draft = descriptor["local_draft"] as JsonObject
                    val changes = draft["changes"] as? JsonObject
                    if (draft.keys != setOf("owner_id", "tenant_id", "configuration", "changes") ||
                        listOf("owner_id", "tenant_id", "configuration").any { (draft[it] as? JsonPrimitive)?.isString != true } ||
                        changes?.keys != setOf("email") || (changes["email"] as? JsonPrimitive)?.isString != true) {
                        throw SerializationException("Invalid local email form")
                    }
                }
                val userInitiated = (element.jsonObject["payload"] as? JsonObject)?.get("was_user_initiated")
                if (userInitiated is JsonPrimitive && userInitiated.isString) {
                    throw SerializationException("was_user_initiated must be a boolean")
                }
                SignInMessage.serializer()
            }
            "try_again" -> TryAgainMessage.serializer()
            "trigger_sign_in_with_google" -> TriggerSignInWithGoogleMessage.serializer()
            "user_data_update" -> UserDataUpdateMessage.serializer()
            "close_hub_view_controller" -> CloseHubViewMessage.serializer()
            "hub_loaded" -> HubLoaded.serializer()
            "hub_resize" -> HubResizeMessage.serializer()
            "can_touch_background_to_dismiss" -> CanTouchBackgroundToDismissMessage.serializer()
            "event" -> EventMessage.serializer()
            "open_email_app" -> OpenEmailAppMessage.serializer()
            "verify_email" -> VerifyEmailMessage.serializer()
            "auth_challenge_initiated" -> AuthChallengeInitiatedMessage.serializer()
            "auth_challenge_cleared" -> AuthChallengeClearedMessage.serializer()
            else -> throw Error("Key '$messageType' did not match a known serializer.")
        }
    }
}
