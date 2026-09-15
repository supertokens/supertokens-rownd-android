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
import io.rownd.android.R
import io.rownd.android.Rownd
import io.rownd.android.RowndSignInOptions
import io.rownd.android.util.SuperTokensSessionBridge
import kotlinx.serialization.json.Json

class RowndBottomSheetActivity : ComponentActivity() {
    private var bottomSheetHolder by mutableStateOf<HubComposableBottomSheet?>(null)
    private var pendingSheetRequest: SheetRequest? = null
    private var pendingSignInHandoff: SignInHandoff? = null

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

        val targetPage = intent.getSerializableExtra(EXTRA_TARGET_PAGE) as? HubPageSelector ?: HubPageSelector.Unknown
        val jsFnOptions = intent.getStringExtra(EXTRA_JS_FN_OPTIONS)

        showSheet(targetPage, jsFnOptions)

        setContent {
            val sheet = bottomSheetHolder
            key(sheet) { sheet?.BottomSheet() }
        }
    }

    override fun onDestroy() {
        bottomSheetHolder?.detach()
        if (!isChangingConfigurations) {
            bottomSheetHolder?.dispose()
        }
        bottomSheetHolder = null
        Rownd.signInWithGoogle.deRegisterIntentLauncher(this.localClassName)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSheetRequest(intent)
    }

    private fun handleSheetRequest(intent: Intent) {
        Rownd.signInHandoffRevision.incrementAndGet()
        val targetPage = intent.getSerializableExtra(EXTRA_TARGET_PAGE) as? HubPageSelector ?: HubPageSelector.Unknown
        val jsFnOptions = intent.getStringExtra(EXTRA_JS_FN_OPTIONS)

        val request = SheetRequest(targetPage, jsFnOptions)
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
        val handoff = pendingSignInHandoff
        pendingSignInHandoff = null
        if (handoff != null && (!handoff.isCurrent() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))) {
            bottomSheetHolder?.dismiss()
            return true
        }
        webView.nativeSignInHandoff = { replaceManageAccountWithSignIn(webView) }
        val request = pendingSheetRequest ?: return false
        pendingSheetRequest = null
        webView.loadNewPage(request.targetPage, request.jsFnOptions)
        return true
    }

    private fun showSheet(targetPage: HubPageSelector, jsFnOptions: String?, handoff: SignInHandoff? = null) {
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

    internal fun replaceManageAccountWithSignIn(source: RowndWebView) {
        val sheet = bottomSheetHolder ?: return
        if (isFinishing || isDestroyed || sheet.isDismissing ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            sheet.existingWebView !== source || !source.isAttachedToWindow ||
            source.targetPage != HubPageSelector.ManageAccount
        ) return

        val handoff = SignInHandoff(
            Rownd.signInHandoffRevision.incrementAndGet(),
            SuperTokensSessionBridge.currentSignOutGeneration(),
        )
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

            showSheet(HubPageSelector.SignIn, RowndSignInOptions().toJsonString(), handoff)
        }
    }

    private data class SignInHandoff(val presentationRevision: Long, val signOutGeneration: Long) {
        fun isCurrent() = presentationRevision == Rownd.signInHandoffRevision.get() &&
            signOutGeneration == SuperTokensSessionBridge.currentSignOutGeneration()
    }

    private data class SheetRequest(
        val targetPage: HubPageSelector,
        val jsFnOptions: String?,
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

        val json = Json { encodeDefaults = true }

        fun launch(context: Context, targetPage: HubPageSelector, jsFnOptions: String? = null) {
            Rownd.signInHandoffRevision.incrementAndGet()
            val intent = Intent(context, RowndBottomSheetActivity::class.java).apply {
                putExtra(EXTRA_TARGET_PAGE, targetPage) // Must be Serializable or Parcelable
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
