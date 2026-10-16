package io.rownd.android.views

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import io.rownd.android.R
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInOptions
import io.rownd.android.RowndSignInJsOptions
import io.rownd.android.RowndSignInIntent
import io.rownd.android.RowndSignInLoginStep
import io.rownd.android.models.EmailChangeDescriptor
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.util.HubSessionStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import java.util.UUID

class RowndBottomSheetActivity : ComponentActivity() {
    private var bottomSheetHolder by mutableStateOf<HubComposableBottomSheet?>(null)
    private var pendingSheetRequest: SheetRequest? = null
    private var pendingSignInHandoff: SignInHandoff? = null
    private var emailChangeOwner: String? = null
    private var emailChangeRevision: Long? = null
    private var clearingEmailChange = false
    private var presentationRevision = Rownd.signInHandoffRevision.get()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }

        setTheme(R.style.Theme_Rownd_Transparent)
        super.onCreate(savedInstanceState)

        Rownd.signInWithGoogle.registerIntentLauncher(this)

        savedInstanceState?.getBundle(STATE_PRESENTATION)?.let { intent.replaceExtras(it) }
        emailChangeOwner = activeEmailChangeOwner(intent)
        emailChangeRevision = emailChangeOwner?.let { Rownd.signInHandoffRevision.get() }
        val handoff = intent.getBundleExtra(EXTRA_HANDOFF)?.let {
            if (it.getString("process") != processId) {
                finish()
                return
            }
            SignInHandoff(it.getLong("revision"), it.getLong("generation"))
        }
        if (handoff != null && !handoff.isCurrent()) {
            // Configuration changes can retain a replacement that was created after state saving.
            val retainedWebView = ViewModelProvider(this)[RowndWebViewModel::class.java].webView()
            retainedWebView.value?.destroy()
            retainedWebView.value = null
            finish()
            return
        }
        val phase = Rownd.emailChangeContinuation.read()?.phase
        val recoveringCleanup = emailChangeOwner != null && phase == "cleanup"
        val resumingEmailChange = emailChangeOwner != null && phase == "resume"
        val targetPage = if (resumingEmailChange) HubPageSelector.ManageAccount
            else intent.getSerializableExtra(EXTRA_TARGET_PAGE) as? HubPageSelector ?: HubPageSelector.Unknown
        val jsFnOptions = if (resumingEmailChange) null
            else if (emailChangeOwner != null && targetPage == HubPageSelector.SignIn) emailChangeSignInOptions()
            else intent.getStringExtra(EXTRA_JS_FN_OPTIONS)

        run {
            val revision = Rownd.signInHandoffRevision.get()
            val requestId = emailChangeOwner
            if (recoveringCleanup) {
                // S0 was already captured. Retire its disposed document; only finish the existing barrier.
                val retained = ViewModelProvider(this)[RowndWebViewModel::class.java].webView()
                retained.value?.destroy()
                retained.value = null
            }
            lifecycleScope.launch {
                try {
                    HubSessionStorage.awaitCleanup()
                    if (revision != Rownd.signInHandoffRevision.get()) { dismiss(); return@launch }
                    if (recoveringCleanup) {
                        check(requestId != null && Rownd.emailChangeContinuation.readyForSignIn(requestId))
                    }
                    showSheet(targetPage, jsFnOptions, handoff)
                } catch (error: Exception) {
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    dismiss()
                }
            }
        }
        // A retained replacement may not have received its initial navigation yet.
        if (handoff != null) pendingSheetRequest = SheetRequest(targetPage, jsFnOptions)

        setContent {
            val sheet = bottomSheetHolder
            key(sheet) { sheet?.BottomSheet() }
        }
    }

    override fun onDestroy() {
        if (isFinishing) emailChangeOwner?.let { Rownd.emailChangeContinuation.clear(it) }
        bottomSheetHolder?.detach()
        if (!isChangingConfigurations) {
            bottomSheetHolder?.dispose()
        }
        bottomSheetHolder = null
        Rownd.signInWithGoogle.deRegisterIntentLauncher(this.localClassName)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle(STATE_PRESENTATION, intent.extras)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSheetRequest(intent)
    }

    private fun handleSheetRequest(intent: Intent) {
        if (HubSessionStorage.hasPendingCleanup && !clearingEmailChange) {
            val revision = Rownd.signInHandoffRevision.incrementAndGet()
            lifecycleScope.launch {
                try {
                    HubSessionStorage.awaitCleanup()
                    if (revision == Rownd.signInHandoffRevision.get()) handleSheetRequest(intent)
                } catch (error: Exception) { dismiss() }
            }
            return
        }
        presentationRevision = Rownd.signInHandoffRevision.incrementAndGet()
        val previousEmailChangeOwner = emailChangeOwner
        emailChangeOwner = activeEmailChangeOwner(intent)
        emailChangeRevision = emailChangeOwner?.let { Rownd.signInHandoffRevision.get() }
        val targetPage = intent.getSerializableExtra(EXTRA_TARGET_PAGE) as? HubPageSelector ?: HubPageSelector.Unknown
        val jsFnOptions = intent.getStringExtra(EXTRA_JS_FN_OPTIONS)
        persistPresentation(targetPage, jsFnOptions, null)

        val request = SheetRequest(targetPage, jsFnOptions)
        if (clearingEmailChange) {
            pendingSheetRequest = request
            return
        }
        if (previousEmailChangeOwner != null || emailChangeOwner != null ||
            bottomSheetHolder?.existingWebView?.isEmailChangePresentation == true || targetPage == HubPageSelector.ManageAccount) {
            bottomSheetHolder?.dispose()
            val revision = Rownd.signInHandoffRevision.get()
            lifecycleScope.launch {
                try {
                    HubSessionStorage.awaitCleanup()
                    if (revision == Rownd.signInHandoffRevision.get()) showSheet(targetPage, jsFnOptions)
                } catch (error: Exception) { dismiss() }
            }
            return
        }
        if (bottomSheetHolder?.isDismissing == true || pendingSignInHandoff != null) {
            bottomSheetHolder?.dispose()
            showSheet(targetPage, jsFnOptions)
            return
        }
        val webView = bottomSheetHolder?.existingWebView
        if (webView == null) {
            pendingSheetRequest = request
        } else {
            webView.loadNewPage(request.targetPage, request.jsFnOptions)
        }
    }

    private fun applyPendingSheetRequest(webView: RowndWebView): Boolean {
        if (clearingEmailChange) return true
        val handoff = pendingSignInHandoff
        pendingSignInHandoff = null
        if (handoff != null && (!handoff.isCurrent() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) {
            bottomSheetHolder?.dismiss()
            return true
        }
        intent.removeExtra(EXTRA_HANDOFF)
        val sourceRevision = presentationRevision
        webView.nativeSignInHandoff = { replaceManageAccountWithSignIn(webView) }
        webView.nativeEmailChangeHandoff = { descriptor -> beginEmailChange(webView, descriptor, sourceRevision) }
        webView.nativeEmailChangeAuthenticated = { completeEmailChangeSignIn(webView) }
        webView.emailChangeIsCurrent = { ownsEmailChangeView(webView) }
        webView.nativeEmailChangeDelivered = {
            emailChangeOwner = null
            emailChangeRevision = null
            intent.removeExtra(EXTRA_EMAIL_CHANGE)
        }
        Rownd.emailChangeContinuation.read()?.descriptor?.takeIf { it.requestId == emailChangeOwner }?.let { descriptor ->
            val phase = Rownd.emailChangeContinuation.read()?.phase
            webView.configureEmailChange(descriptor, phase == "resume")
        }
        val request = pendingSheetRequest ?: return false
        pendingSheetRequest = null
        webView.loadNewPage(request.targetPage, request.jsFnOptions)
        return true
    }

    private fun showSheet(targetPage: HubPageSelector, jsFnOptions: String?, handoff: SignInHandoff? = null) {
        presentationRevision = Rownd.signInHandoffRevision.get()
        persistPresentation(targetPage, jsFnOptions, handoff)
        pendingSheetRequest = null
        pendingSignInHandoff = handoff
        bottomSheetHolder = HubComposableBottomSheet(
            this,
            onDismiss = { dismiss() },
            targetPage = targetPage,
            jsFnArgsAsJson = jsFnOptions,
            onWebViewReady = ::applyPendingSheetRequest,
        )
    }

    private fun persistPresentation(targetPage: HubPageSelector, jsFnOptions: String?, handoff: SignInHandoff?) {
        intent.putExtra(EXTRA_EMAIL_CHANGE, emailChangeOwner)
        intent.putExtra(EXTRA_TARGET_PAGE, targetPage)
        intent.putExtra(EXTRA_JS_FN_OPTIONS, jsFnOptions)
        intent.removeExtra(EXTRA_HANDOFF)
        handoff?.let {
            intent.putExtra(EXTRA_HANDOFF, Bundle().apply {
                // Generation counters reset after process death; never revive an uncommitted handoff then.
                putString("process", processId)
                putLong("revision", it.presentationRevision)
                putLong("generation", it.signOutGeneration)
            })
        }
    }

    internal fun replaceManageAccountWithSignIn(source: RowndWebView) {
        val sheet = bottomSheetHolder ?: return
        if (isFinishing || isDestroyed || sheet.isDismissing ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            sheet.existingWebView !== source || !source.isAttachedToWindow ||
            source.targetPage != HubPageSelector.ManageAccount
        ) return

        emailChangeOwner?.let { Rownd.emailChangeContinuation.clear(it) }
        emailChangeOwner = null
        emailChangeRevision = null

        val handoff = SignInHandoff(
            Rownd.signInHandoffRevision.incrementAndGet(),
            SuperTokensSessionBridge.currentSignOutGeneration(),
        )
        val options = RowndSignInOptions().toJsonString()
        pendingSignInHandoff = handoff
        persistPresentation(HubPageSelector.SignIn, options, handoff)
        // Retire the bridge and its document before scheduling any replacement. A new target
        // on this WebView would let delayed Profile messages authenticate or close SignIn.
        sheet.dispose()
        Handler(Looper.getMainLooper()).post {
            if (isFinishing || isDestroyed || bottomSheetHolder !== sheet) return@post
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                !handoff.isCurrent()
            ) {
                dismiss()
                return@post
            }

            showSheet(HubPageSelector.SignIn, options, handoff)
        }
    }

    private fun ownsEmailChangeView(source: RowndWebView): Boolean = !isFinishing && !isDestroyed &&
        !source.isDestroyed && bottomSheetHolder?.existingWebView === source &&
        emailChangeRevision == Rownd.signInHandoffRevision.get() &&
        emailChangeOwner?.let { Rownd.emailChangeContinuation.read()?.descriptor?.requestId == it } == true

    private fun activeEmailChangeOwner(intent: Intent): String? {
        val storage = Rownd.emailChangeContinuation
        val record = storage.read() ?: return null
        if (record.descriptor.requestId != intent.getStringExtra(EXTRA_EMAIL_CHANGE)) {
            storage.clear(record.descriptor.requestId)
            return null
        }
        return record.descriptor.requestId
    }

    internal fun beginEmailChange(source: RowndWebView, descriptor: EmailChangeDescriptor, sourceRevision: Long): Boolean {
        val sheet = bottomSheetHolder ?: return false
        if (clearingEmailChange || isFinishing || isDestroyed || sheet.isDismissing ||
            sourceRevision != Rownd.signInHandoffRevision.get() ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            sheet.existingWebView !== source || !source.isAttachedToWindow ||
            source.targetPage != HubPageSelector.ManageAccount ||
            !Rownd.state.value.auth.isAuthenticated ||
            !Rownd.emailChangeContinuation.begin(descriptor)) return false
        val admittedRevision = sourceRevision + 1
        // Capture/clear S0 synchronously at admission, under the existing native commit lock.
        // The later cleanup coroutine never reads or clears whichever session replaced it.
        if (!Rownd.beginSignOutForEmailChange(source.context.applicationContext) {
                Rownd.signInHandoffRevision.compareAndSet(sourceRevision, admittedRevision)
            }) {
            Rownd.emailChangeContinuation.clear(descriptor.requestId)
            return false
        }
        emailChangeOwner = descriptor.requestId
        emailChangeRevision = admittedRevision
        persistPresentation(HubPageSelector.SignIn, emailChangeSignInOptions(), null)
        source.stopLoading()
        source.rowndJavascriptInterface.dispose()
        continueEmailChangeCleanup(source)
        return true
    }

    private fun emailChangeSignInOptions() = RowndSignInJsOptions(
        intent = RowndSignInIntent.SignIn, loginStep = RowndSignInLoginStep.Init,
    ).toJsonString()

    private fun continueEmailChangeCleanup(source: RowndWebView) {
        val requestId = emailChangeOwner ?: return
        val revision = emailChangeRevision
        clearingEmailChange = true
        lifecycleScope.launch {
            try {
                HubSessionStorage.clearAndAwait(Rownd.config, source)
                if (revision != Rownd.signInHandoffRevision.get() ||
                    Rownd.emailChangeContinuation.read()?.descriptor?.requestId != requestId) return@launch
                check(Rownd.emailChangeContinuation.readyForSignIn(requestId))
                bottomSheetHolder?.dispose()
                showSheet(HubPageSelector.SignIn, emailChangeSignInOptions())
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                android.util.Log.w("Rownd", "Email-change sign-in cleanup failed", error)
                Rownd.emailChangeContinuation.clear(requestId)
                if (revision == Rownd.signInHandoffRevision.get()) {
                    android.widget.Toast.makeText(this@RowndBottomSheetActivity,
                        "Unable to start sign-in. Please try again.", android.widget.Toast.LENGTH_LONG).show()
                    if (pendingSheetRequest == null) dismiss()
                }
            } finally {
                clearingEmailChange = false
                val pending = pendingSheetRequest
                pendingSheetRequest = null
                if (pending != null) lifecycleScope.launch {
                    try {
                        HubSessionStorage.awaitCleanup()
                        if (!isFinishing && !isDestroyed && pending.revision == Rownd.signInHandoffRevision.get()) {
                            bottomSheetHolder?.dispose()
                            showSheet(pending.targetPage, pending.jsFnOptions)
                        }
                    } catch (error: Exception) {
                        if (pending.revision == Rownd.signInHandoffRevision.get()) dismiss()
                    }
                }
            }
        }
    }

    private fun completeEmailChangeSignIn(source: RowndWebView): Boolean {
        if (source.emailChangeRequestId == null) return false
        Handler(Looper.getMainLooper()).post {
            val requestId = emailChangeOwner ?: return@post
            if (!ownsEmailChangeView(source) || source.targetPage !in setOf(HubPageSelector.SignIn, HubPageSelector.DeepLink) ||
                !Rownd.emailChangeContinuation.authenticated(requestId)) return@post
            bottomSheetHolder?.dispose()
            showSheet(HubPageSelector.ManageAccount, null)
        }
        return true
    }

    private data class SignInHandoff(val presentationRevision: Long, val signOutGeneration: Long) {
        fun isCurrent() = presentationRevision == Rownd.signInHandoffRevision.get() &&
            signOutGeneration == SuperTokensSessionBridge.currentSignOutGeneration()
    }

    private data class SheetRequest(
        val targetPage: HubPageSelector,
        val jsFnOptions: String?,
        val revision: Long = Rownd.signInHandoffRevision.get(),
    )

    private fun dismiss() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val EXTRA_TARGET_PAGE = "extra_target_page"
        private const val EXTRA_JS_FN_OPTIONS = "extra_js_fn_options"
        private const val EXTRA_HANDOFF = "extra_sign_in_handoff"
        private const val EXTRA_EMAIL_CHANGE = "extra_email_change_request"
        private const val STATE_PRESENTATION = "rownd_presentation"
        private val processId = UUID.randomUUID().toString()

        val json = Json { encodeDefaults = true }

        fun launch(context: Context, targetPage: HubPageSelector, jsFnOptions: String? = null, emailChangeRequestId: String? = null) {
            Rownd.signInHandoffRevision.incrementAndGet()
            val intent = Intent(context, RowndBottomSheetActivity::class.java).apply {
                putExtra(EXTRA_TARGET_PAGE, targetPage) // Must be Serializable or Parcelable
                putExtra(EXTRA_EMAIL_CHANGE, emailChangeRequestId)
                jsFnOptions?.let { putExtra(EXTRA_JS_FN_OPTIONS, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Required if launching from non-activity context
            }

            context.startActivity(intent)

            if (context is Activity) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    context.overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    context.overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                } else {
                    @Suppress("DEPRECATION")
                    context.overridePendingTransition(0, 0)
                }
            }
        }
    }
}
