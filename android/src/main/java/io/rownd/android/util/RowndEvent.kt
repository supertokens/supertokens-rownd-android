package io.rownd.android.util

import io.rownd.android.Rownd
import io.rownd.android.RowndSignInType
import io.rownd.android.RowndSignInUserType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@Serializable(with = RowndEventTypeSerializer::class)
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
    Auth,

    Unknown
}

@Serializable(with = RowndEventSerializer::class)
data class RowndEvent (
    var event: RowndEventType,
    var data: Map<String, String?> = emptyMap()
)

object RowndEventTypeSerializer : KSerializer<RowndEventType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RowndEventType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RowndEventType {
        return rowndEventTypeFromName(decoder.decodeString())
    }

    override fun serialize(encoder: Encoder, value: RowndEventType) {
        val eventName = when (value) {
            RowndEventType.SignInStarted -> "sign_in_started"
            RowndEventType.SignInCompleted -> "sign_in_completed"
            RowndEventType.SignInFailed -> "sign_in_failed"
            RowndEventType.UserUpdated -> "user_updated"
            RowndEventType.SignOut -> "sign_out"
            RowndEventType.UserData -> "user_data"
            RowndEventType.UserDataSaved -> "user_data_saved"
            RowndEventType.VerificationStarted -> "verification_started"
            RowndEventType.VerificationCompleted -> "verification_completed"
            RowndEventType.Auth -> "auth"
            RowndEventType.Unknown -> "unknown"
        }
        encoder.encodeString(eventName)
    }
}

object RowndEventSerializer : KSerializer<RowndEvent> {
    private val dataSerializer = MapSerializer(String.serializer(), String.serializer().nullable)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("RowndEvent") {
        element<RowndEventType>("event")
        element<Map<String, String?>>("data")
    }

    override fun deserialize(decoder: Decoder): RowndEvent {
        val jsonDecoder = decoder as? JsonDecoder ?: return decodeStructured(decoder)
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        val event = rowndEventTypeFromName(obj["event"]?.jsonPrimitive?.contentOrNull)
        val data = (obj["data"] as? JsonObject)?.mapValues { (_, value) -> value.asEventDataString() } ?: emptyMap()
        return RowndEvent(event, data)
    }

    override fun serialize(encoder: Encoder, value: RowndEvent) {
        encoder.encodeStructure(descriptor) {
            encodeSerializableElement(descriptor, 0, RowndEventTypeSerializer, value.event)
            encodeSerializableElement(descriptor, 1, dataSerializer, value.data)
        }
    }

    private fun decodeStructured(decoder: Decoder): RowndEvent {
        var event = RowndEventType.Unknown
        var data = emptyMap<String, String?>()
        decoder.decodeStructure(descriptor) {
            loop@ while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> event = decodeSerializableElement(descriptor, 0, RowndEventTypeSerializer)
                    1 -> data = decodeSerializableElement(
                        descriptor,
                        1,
                        dataSerializer,
                    )
                    CompositeDecoder.DECODE_DONE -> break@loop
                    else -> throw kotlinx.serialization.SerializationException("Unknown index $index")
                }
            }
        }
        return RowndEvent(event, data)
    }
}

private fun rowndEventTypeFromName(name: String?): RowndEventType {
    return when (name) {
        "sign_in_started" -> RowndEventType.SignInStarted
        "sign_in_completed" -> RowndEventType.SignInCompleted
        "sign_in_failed" -> RowndEventType.SignInFailed
        "user_updated" -> RowndEventType.UserUpdated
        "sign_out" -> RowndEventType.SignOut
        "user_data" -> RowndEventType.UserData
        "user_data_saved" -> RowndEventType.UserDataSaved
        "verification_started" -> RowndEventType.VerificationStarted
        "verification_completed" -> RowndEventType.VerificationCompleted
        "auth" -> RowndEventType.Auth
        else -> RowndEventType.Unknown
    }
}

private fun JsonElement.asEventDataString(): String? {
    return if (this is JsonPrimitive) {
        contentOrNull
    } else {
        toString()
    }
}

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
