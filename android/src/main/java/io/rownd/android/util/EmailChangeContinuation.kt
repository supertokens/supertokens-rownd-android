package io.rownd.android.util

import android.net.Uri
import io.rownd.android.models.EmailChangeDescriptor
import io.rownd.android.models.RowndConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Form input and routing permission live only in this process. */
internal class EmailChangeContinuation(private val config: () -> RowndConfig) {
    internal data class Record(
        val descriptor: EmailChangeDescriptor,
        val configuration: List<String?>,
        val phase: String = "cleanup",
        val challengeId: String? = null,
    )

    private var record: Record? = null
    private fun configuration() = config().let {
        listOf(it.baseUrl, it.apiUrl, it.apiBasePath, it.supertokens.appInfo.apiDomain,
            it.supertokens.appInfo.apiBasePath, it.appKey, it.appVariantId, it.deepLinkScheme)
    }

    @Synchronized fun read(now: Long = System.currentTimeMillis()): Record? {
        if (record?.let { it.configuration != configuration() || !live(it.descriptor, now) } == true) clear()
        return record
    }

    @Synchronized fun begin(descriptor: EmailChangeDescriptor): Boolean {
        if (!valid(descriptor)) return false
        record = Record(descriptor, configuration())
        return true
    }

    @Synchronized fun readyForSignIn(requestId: String): Boolean =
        read()?.phase == "cleanup" && update(requestId) { it.copy(phase = "sign_in") }

    @Synchronized fun challenge(requestId: String, challengeId: String): Boolean =
        challengeId.matches(Regex("[A-Za-z0-9_-]{1,512}")) && update(requestId) {
            if (it.phase == "sign_in") it.copy(challengeId = challengeId) else it
        }

    @Synchronized fun clearChallenge(requestId: String) { update(requestId) { it.copy(challengeId = null) } }

    @Synchronized fun claimCallback(url: String): EmailChangeDescriptor? {
        val current = read() ?: return null
        val uri = Uri.parse(url)
        val hub = Uri.parse(config().baseUrl)
        if (uri.scheme != hub.scheme || uri.encodedAuthority != hub.encodedAuthority ||
            uri.path != "/account/login" || current.phase != "sign_in" || current.challengeId == null ||
            uri.getQueryParameters("preAuthSessionId") != listOf(current.challengeId)) return null
        for ((key, expected) in listOf("apiDomain" to config().supertokens.appInfo.apiDomain,
            "apiBasePath" to config().supertokens.appInfo.apiBasePath)) {
            val values = uri.getQueryParameters(key)
            if (values.isNotEmpty() && values != listOf(expected)) return null
        }
        record = current.copy(challengeId = null)
        return current.descriptor
    }

    @Synchronized fun authenticated(requestId: String): Boolean =
        read()?.takeIf { it.phase == "sign_in" }?.let { update(requestId) { it.copy(phase = "resume", challengeId = null) } } == true

    @Synchronized fun takeResume(descriptor: EmailChangeDescriptor): EmailChangeDescriptor? {
        if (read()?.let { it.phase == "resume" && it.descriptor == descriptor } != true) return null
        clear() // One shot: revoke routing before asynchronous JavaScript dispatch.
        return descriptor
    }

    @Synchronized fun clear(requestId: String? = null): Boolean {
        if (requestId != null && record?.descriptor?.requestId != requestId) return false
        record = null
        return true
    }

    private fun update(requestId: String, change: (Record) -> Record): Boolean {
        val current = read() ?: return false
        if (current.descriptor.requestId != requestId) return false
        record = change(current)
        return true
    }

    companion object {
        fun valid(descriptor: EmailChangeDescriptor, now: Long = System.currentTimeMillis()): Boolean {
            val draft = descriptor.localDraft
            return descriptor.version == 2 && descriptor.requestId.matches(Regex("[A-Za-z0-9_-]{16,128}")) &&
                live(descriptor, now) &&
                Json.encodeToString(descriptor).toByteArray(Charsets.UTF_8).size <= 32 * 1024 &&
                listOf(draft.ownerId, draft.tenantId).all { it.isNotEmpty() && it.length <= 256 } &&
                draft.configuration.isNotEmpty() && draft.configuration.length <= 4096 &&
                draft.changes.email.isNotEmpty() && draft.changes.email.length <= 254
        }

        private fun live(descriptor: EmailChangeDescriptor, now: Long): Boolean =
            descriptor.expiresAt > now && descriptor.expiresAt <= now + 15 * 60_000
    }
}
