package io.rownd.android.views

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.net.toUri
import androidx.fragment.app.DialogFragment
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.composables.core.SheetDetent
import io.rownd.android.Rownd
import io.rownd.android.RowndClient
import io.rownd.android.RowndSignInHint
import io.rownd.android.RowndSignInOptions
import io.rownd.android.RowndSignInOptionsBase
import io.rownd.android.models.AuthChallengeInitiatedMessage
import io.rownd.android.models.AuthenticationMessage
import io.rownd.android.models.CanTouchBackgroundToDismissMessage
import io.rownd.android.models.EventMessage
import io.rownd.android.models.HubResizeMessage
import io.rownd.android.models.MessageType
import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.models.TriggerSignInWithGoogleMessage
import io.rownd.android.models.UserDataUpdateMessage
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.Constants
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SignInCompletedEventDeduper
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.util.redactSensitiveKeys
import io.rownd.android.util.signInCompletedEventData
import io.rownd.android.views.html.noInternetHTML
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


val json = Json { ignoreUnknownKeys = true }

@Serializable
enum class HubPageSelector {
    SignIn,
    SignOut,
    QrCode,
    ManageAccount,
    ConnectAuthenticator,
    DeepLink,
    Unknown
}

private const val HUB_CLOSE_AFTER_MILLISECONDS: Long = 1500
private const val SIGN_IN_COMPLETED_AUTHENTICATION_FALLBACK_DELAY_MILLISECONDS: Long = 500

@SuppressLint("SetJavaScriptEnabled")
class RowndWebView(context: Context, attrs: AttributeSet?) : WebView(context, attrs), DialogChild {
    override var dialog: DialogFragment? = null
    internal var dismiss: (() -> Unit)? = null
    internal var targetPage: HubPageSelector = HubPageSelector.Unknown
    internal var jsFunctionArgsAsJson: String = DEFAULT_JS_FN_ARGS
    internal var progressBar: ProgressBar? = null
    internal var setIsLoading: ((isLoading: Boolean) -> Unit)? = null
    internal var animateBottomSheet: ((to: SheetDetent) -> Unit)? = null
    internal var setCanTouchBackgroundToDismiss: ((to: Boolean) -> Unit)? = null

    internal lateinit var rowndClient: RowndClient

    init {
        this.setLayerType(LAYER_TYPE_HARDWARE, null)
        this.setBackgroundColor(0x00000000)
        this.isHorizontalScrollBarEnabled = false
//        this.isVerticalScrollBarEnabled = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = Constants.DEFAULT_WEB_USER_AGENT

        fun dynamicBottomSheet(height: String) {
            val deviceMetrics = Rownd.getDeviceSize(context)
            val viewportPixelHeight = deviceMetrics.heightPixels / deviceMetrics.density
            val deviceHeight = deviceMetrics.heightPixels
            height.toIntOrNull()?.let {
                val ratio = it / viewportPixelHeight
                val targetOffset = deviceHeight.toFloat() - deviceHeight.toFloat() * ratio - 100F
                if (ratio >= .5) {
                    animateBottomSheet?.let { it(SheetDetent.FullyExpanded) }
                } else {
                    animateBottomSheet?.let { it(Peek) }
                }
            }
        }

        fun setCanTouchBackground(enable: Boolean) {
            setCanTouchBackgroundToDismiss?.let { it(enable) }
        }

        val rowndJavascriptInterface = RowndJavascriptInterface(this, ::dynamicBottomSheet, ::setCanTouchBackground)

        this.addJavascriptInterface(rowndJavascriptInterface, "rowndAndroidSDK")
        this.webViewClient = RowndWebViewClient(this, context)

        val appFlags = Rownd.appHandleWrapper?.app?.get()?.applicationInfo?.flags ?: 0
        if (0 != appFlags.and(ApplicationInfo.FLAG_DEBUGGABLE)) {
            setWebContentsDebuggingEnabled(true)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event!!.action == KeyEvent.ACTION_DOWN) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (this.canGoBack()) {
                        this.goBack()
                    } else {
                        dismiss?.invoke()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    internal fun loadNewPage(targetPage: HubPageSelector = HubPageSelector.SignIn, jsFnOptionsAsJson: String?) {
        this.jsFunctionArgsAsJson = jsFnOptionsAsJson ?: DEFAULT_JS_FN_ARGS
        this.targetPage = targetPage

        this.let {
            CoroutineScope(Dispatchers.Main).launch {
                val targetUrl = Rownd.config.hubLoaderUrl()
                it.loadUrl(targetUrl)
            }
        }

    }

    internal fun loadNewPage(targetPage: HubPageSelector = HubPageSelector.SignIn, jsFnOptions: RowndSignInOptionsBase) {
        loadNewPage(targetPage, jsFnOptions.toJsonString())
    }

    companion object {
        const val DEFAULT_JS_FN_ARGS = "{}"
    }
}

class RowndWebViewClient(private val webView: RowndWebView, private val context: Context) : WebViewClientCompat() {
    private var timeout: Boolean = true
    private var displayedTargetForUrl: String? = null

    init {
        CoroutineScope(Dispatchers.IO).launch {
            delay(20000)
            if (timeout) {
                loadNoInternetHTML()
            }
        }
    }

    private fun setIsLoading(isLoading: Boolean) {
        if (isLoading) {
            if (webView.setIsLoading == null) {
                webView.progressBar?.visibility = View.VISIBLE
            }

            webView.setIsLoading?.invoke(true)
        } else {
            if (webView.setIsLoading == null) {
                webView.progressBar?.visibility = View.INVISIBLE
            }

            webView.setIsLoading?.invoke(false)
        }
    }

    private fun loadNoInternetHTML() {
        webView.post {
            setIsLoading(false)
            webView.loadDataWithBaseURL(null, noInternetHTML(context), "text/html", "utf-8", null)
        }
    }

    private fun evaluateJavascript(code: String) {
        val wrappedJs = """
            if (typeof rownd !== 'undefined') {
                $code
            } else if (typeof _rphConfig !== 'undefined') {
                _rphConfig.push(['onLoaded', () => {
                    $code
                }]);
            }
        """

        Log.d("Rownd.hub", "Evaluating script: $code")

        webView.evaluateJavascript(wrappedJs) {
            Log.d("Rownd.hub", "Hub js evaluation response: $it")
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        val urlStr = request.url.toString()

        if (urlStr.startsWith("tel:")) {
            val telIntent = Intent(Intent.ACTION_DIAL, url).apply {
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(telIntent)
            return true
        }

        // Handle special case where we're just opening the default email app (triggered by app message)
        if (urlStr == "mailto:") {
            return true
        }

        // If it's mailto:foo@bar.com (or similar) then start composing
        if (urlStr.startsWith("mailto:")) {
            val emailIntent = Intent(Intent.ACTION_VIEW, url).apply {
                setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.context.startActivity(emailIntent)
        }

        return if (shouldOpenInSeparateActivity(url)) {
            view.context?.startActivity(
                Intent(Intent.ACTION_VIEW, urlStr.toUri())
            )
            true
        } else {
            false
        }
    }

    private fun shouldOpenInSeparateActivity(url: Uri?): Boolean {
        if (url == null || !URLUtil.isValidUrl(url.toString())) {
            return false
        }

        // The following urls should always open in the hub web view
        val urlStrings: List<String> = persistentListOf(
            "https://appleid.apple.com/auth/authorize",
            Rownd.config.baseUrl
        )
        val match = urlStrings.find {
            url.toString().startsWith(it)
        }

        return !(match != null && match != "")
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        timeout = false
        super.onPageStarted(webView, url, favicon)
        Log.d("Rownd.hub", "Started loading $url")
        setIsLoading(true)
        if (url?.startsWith(Rownd.config.baseUrl) == true) {
            displayedTargetForUrl = null
        }

        if (url?.startsWith(Rownd.config.baseUrl) == true) {
            view?.setBackgroundColor(0x00000000)
        } else {
            view?.setBackgroundColor(Color.WHITE)

        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)

        if (view.progress < 100) {
            return
        }

        if (!url.startsWith(Rownd.config.baseUrl) && url != "about:blank") {
            webView.animateBottomSheet?.invoke(SheetDetent.FullyExpanded)
            setIsLoading(false)
            return
        }

        setFeatureFlagJs()
        setDebugFlags()

        view.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)

        if (WebViewFeature.isFeatureSupported(WebViewFeature.VISUAL_STATE_CALLBACK)) {
            WebViewCompat.postVisualStateCallback(view, 1) {
                displayTargetPage(view)
            }
        } else {
            // If VISUAL_STATE_CALLBACK isn't supported on this platform, try to display the
            // appropriate content.
            displayTargetPage(view)
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceErrorCompat
    ) {
        super.onReceivedError(view, request, error)

        if (!request.isForMainFrame) {
            return
        }

        try {
            val targetUri = this.webView.url?.toUri()
            val currentUri = request.url

            if (
                targetUri?.host != currentUri.host &&
                targetUri?.path != currentUri?.path
                )
            {
                return
            }
        } catch (ex: Exception) {
            // No-op
        }

        if (
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_RESOURCE_ERROR_GET_DESCRIPTION) &&
            error.description.contains("net::ERR")
            )
        {
            loadNoInternetHTML()
        }
    }

    private fun displayTargetPage(view: WebView) {
        if (displayedTargetForUrl == view.url) {
            setIsLoading(false)
            return
        }
        displayedTargetForUrl = view.url

        when ((view as RowndWebView).targetPage) {
            HubPageSelector.SignIn, HubPageSelector.Unknown -> evaluateJavascript("rownd.requestSignIn(${webView.jsFunctionArgsAsJson})")
            HubPageSelector.SignOut -> evaluateJavascript("rownd.signOut({\"show_success\":true})")
            HubPageSelector.QrCode -> evaluateJavascript("rownd.generateQrCode(${webView.jsFunctionArgsAsJson})")
            HubPageSelector.ManageAccount -> evaluateJavascript("rownd.user.manageAccount()")
            HubPageSelector.ConnectAuthenticator -> evaluateJavascript("rownd.connectAuthenticator(${webView.jsFunctionArgsAsJson})")
            HubPageSelector.DeepLink -> Unit
        }

        setIsLoading(false)
    }

    private fun setFeatureFlagJs() {
        val supportedFeatureStr = Constants.getSupportedFeatures()
        val code = """
            if (rownd?.setSessionStorage) {
                rownd.setSessionStorage("rph_feature_flags",${JSONObject.quote(supportedFeatureStr)})
            }
        """
        evaluateJavascript(code)
    }

    private fun setDebugFlags() {
        if (Rownd.config.enableDebugMode) {
            evaluateJavascript("""
                if (rownd?.setLogLevel) {
                    rownd.setLogLevel('default', 'debug');
                }
            """.trimIndent())
        }
    }

    private fun handleScriptReturn(value: String) {
        Log.d("Rownd.hub", value)
    }
}

class RowndJavascriptInterface constructor(
    private val parentWebView: RowndWebView,
    private val dynamicBottomSheet: (to: String) -> Unit,
    private val setCanTouchBackground: (to: Boolean) -> Unit,
    ) {
    private val signInCompletedDeduper = SignInCompletedEventDeduper()
    private var signInCompletedFallbackJob: Job? = null

    private fun scheduleSignInCompletedAuthenticationFallback(authenticationMessage: AuthenticationMessage) {
        if (!signInCompletedDeduper.shouldScheduleAuthenticationFallback() || signInCompletedFallbackJob?.isActive == true) {
            Log.d("Rownd.hub", "Skipping duplicate sign_in_completed event")
            return
        }

        signInCompletedFallbackJob = CoroutineScope(Dispatchers.IO).launch {
            delay(SIGN_IN_COMPLETED_AUTHENTICATION_FALLBACK_DELAY_MILLISECONDS)
            if (!signInCompletedDeduper.shouldEmitForAuthenticationFallback()) {
                Log.d("Rownd.hub", "Skipping duplicate sign_in_completed event")
                return@launch
            }

            parentWebView.rowndClient.eventEmitter.emit(
                RowndEvent(
                    event = RowndEventType.SignInCompleted,
                    data = signInCompletedEventData(
                        userType = authenticationMessage.payload.userType,
                        appVariantUserType = authenticationMessage.payload.appVariantUserType
                            ?: authenticationMessage.payload.userType,
                    ),
                )
            )
        }
    }

    private fun resetSignInCompletedDeduper() {
        signInCompletedFallbackJob?.cancel()
        signInCompletedFallbackJob = null
        signInCompletedDeduper.reset()
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        Log.d("Rownd.hub", "postMessage: " + redactSensitiveKeys(message))
        try {
            val interopMessage = json.decodeFromString(RowndHubInteropMessage.serializer(), message)
            Log.d("Rownd.hub", redactSensitiveKeys(interopMessage.toString()))

            when (interopMessage.type) {
                MessageType.authentication -> {
                    if (
                        parentWebView.targetPage != HubPageSelector.SignIn &&
                        parentWebView.targetPage != HubPageSelector.DeepLink
                    ) {
                        return
                    }

                    val authenticationMessage = interopMessage as AuthenticationMessage
                    val refreshToken = authenticationMessage.payload.refreshToken
                    if (refreshToken.isNullOrEmpty()) {
                        Log.e("Rownd.hub", "Hub authentication message is missing refresh_token")
                        return
                    }
                    val appContext = parentWebView.context.applicationContext

                    fun launchPostBootstrap(includeBootstrap: Boolean) = CoroutineScope(Dispatchers.IO).launch {
                        try {
                            if (includeBootstrap) {
                                SuperTokensSessionBridge.bootstrapSession(
                                    context = appContext,
                                    accessToken = authenticationMessage.payload.accessToken,
                                    refreshToken = refreshToken,
                                    frontToken = authenticationMessage.payload.frontToken,
                                    antiCSRF = authenticationMessage.payload.antiCsrf,
                                    replaceExisting = true,
                                )
                            }

                            if (!SuperTokensSessionBridge.awaitInitialized()) {
                                Log.e("Rownd.hub", "Skipping post-authentication user load because SuperTokens is not initialized")
                                return@launch
                            }

                            SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(
                                context = appContext,
                                store = parentWebView.rowndClient.stateRepo.getStore(),
                            )

                            parentWebView.rowndClient.signInRepo.reset()
                            parentWebView.rowndClient.userRepo.loadUserAsync()
                            scheduleSignInCompletedAuthenticationFallback(authenticationMessage)

                            Executors.newSingleThreadScheduledExecutor().schedule({
                                parentWebView.dismiss?.invoke()
                            }, HUB_CLOSE_AFTER_MILLISECONDS, TimeUnit.MILLISECONDS)
                        } catch (e: Exception) {
                            Log.e("Rownd.hub", "Hub authentication bootstrap failed", e)
                        }
                    }

                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        launchPostBootstrap(includeBootstrap = true)
                    } else {
                        try {
                            SuperTokensSessionBridge.bootstrapSession(
                                context = appContext,
                                accessToken = authenticationMessage.payload.accessToken,
                                refreshToken = refreshToken,
                                frontToken = authenticationMessage.payload.frontToken,
                                antiCSRF = authenticationMessage.payload.antiCsrf,
                                replaceExisting = true,
                            )
                            launchPostBootstrap(includeBootstrap = false)
                        } catch (e: Exception) {
                            Log.e("Rownd.hub", "Hub authentication bootstrap failed", e)
                        }
                    }
                }

                MessageType.signOut -> {
                    resetSignInCompletedDeduper()
                    Executors.newSingleThreadScheduledExecutor().schedule({
                        parentWebView.dismiss?.invoke()
                    }, HUB_CLOSE_AFTER_MILLISECONDS, TimeUnit.MILLISECONDS)

                    Rownd.signOut()
                }

                MessageType.triggerSignInWithGoogle -> {
                    val signInWithGoogleMessage = (interopMessage as TriggerSignInWithGoogleMessage).payload
                    parentWebView.rowndClient.signInWithGoogle.signIn(intent = signInWithGoogleMessage?.intent, hint = signInWithGoogleMessage?.hint, wasUserInitiated = true)
                    parentWebView.dismiss?.invoke()
                }

                MessageType.UserDataUpdate -> {
                    Rownd.store.dispatch(
                        StateAction.SetUser(
                            (interopMessage as UserDataUpdateMessage).payload.asDomainModel(
                                parentWebView.rowndClient.stateRepo,
                                parentWebView.rowndClient.userRepo
                            )
                        )
                    )
                }

                MessageType.CloseHubView -> {
                    parentWebView.dismiss?.invoke()
                }

                MessageType.tryAgain -> {
                    CoroutineScope(Dispatchers.Main).launch {
                        parentWebView.loadUrl(Rownd.config.hubLoaderUrl())
                    }
                }

                MessageType.HubResize -> {
                    val height = (interopMessage as HubResizeMessage).payload.height
                    if (height != null) {
                        dynamicBottomSheet(height)
                    }
                }

                MessageType.CanTouchBackgroundToDismiss -> {
                    val enable = (interopMessage as CanTouchBackgroundToDismissMessage).payload.enable
                    setCanTouchBackground(enable != "false")
                }

                MessageType.Event -> {
                    val event = (interopMessage as EventMessage).payload
                    when (event.event) {
                        RowndEventType.SignInCompleted -> {
                            if (signInCompletedDeduper.shouldEmitForHubEvent()) {
                                signInCompletedFallbackJob?.cancel()
                                signInCompletedFallbackJob = null
                                parentWebView.rowndClient.eventEmitter.emit(event)
                            } else {
                                Log.d("Rownd.hub", "Skipping duplicate sign_in_completed event")
                            }
                        }

                        RowndEventType.SignInStarted, RowndEventType.SignOut -> {
                            resetSignInCompletedDeduper()
                            parentWebView.rowndClient.eventEmitter.emit(event)
                        }

                        else -> parentWebView.rowndClient.eventEmitter.emit(event)
                    }
                }

                MessageType.OpenEmailApp -> {
                    val emailIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_APP_EMAIL)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                    try {
                        parentWebView.rowndClient.appHandleWrapper?.activity?.get()?.startActivity(emailIntent)
                    } catch (ex: android.content.ActivityNotFoundException) {
                        Toast.makeText(parentWebView.rowndClient.appHandleWrapper?.activity?.get(), "No email clients installed.", Toast.LENGTH_SHORT).show()
                    }
                }

                MessageType.HubLoaded -> {
                    // The hub sends this as a readiness signal; no native action is required.
                }

                MessageType.AuthChallengeInitiated -> {
                    val authChallengeMessage = (interopMessage as AuthChallengeInitiatedMessage)
                    Rownd.store.dispatch(
                        StateAction.SetAuth(
                            parentWebView.rowndClient.stateRepo.state.value.auth.copy(
                                challengeId = authChallengeMessage.payload.challengeId,
                                userIdentifier = authChallengeMessage.payload.userIdentifier,
                            )
                        )
                    )
                }

                MessageType.AuthChallengeCleared -> {
                    parentWebView.rowndClient.store.currentState.auth.let {
                        Rownd.store.dispatch(
                            StateAction.SetAuth(
                                it.copy(
                                    challengeId = null,
                                    userIdentifier = null,
                                )
                            )
                        )
                    }
                }

                else -> {
                    Log.w("RowndHub", "An unhandled message '${interopMessage.type}' was received")
                }
            }
        } catch (e : Exception) {
            Log.d("Rownd.hub", "Unparseable message", e)
        } catch (e : Error) {
            Log.d("Rownd.hub", "Unparseable message", e)
        }
    }
}
