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
import io.rownd.android.models.VerifyEmailMessage
import io.rownd.android.models.RowndHubInteropMessage
import io.rownd.android.models.TriggerSignInWithGoogleMessage
import io.rownd.android.models.UserDataUpdateMessage
import io.rownd.android.models.repos.StateAction
import io.rownd.android.util.Constants
import io.rownd.android.util.RowndEvent
import io.rownd.android.util.RowndEventType
import io.rownd.android.util.SignInCompletedEventDeduper
import io.rownd.android.util.SuperTokensSessionBridge
import io.rownd.android.util.nativeEmailVerificationRequest
import io.rownd.android.util.performNativeEmailVerification
import io.rownd.android.util.redactSensitiveKeys
import io.rownd.android.util.signInCompletedEventData
import io.rownd.android.views.html.noInternetHTML
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.URI


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
private const val NATIVE_EMAIL_VERIFICATION_EVENT = "rownd:native-email-verification"

internal fun urlForLogging(url: String?): String = runCatching {
    val parsed = URI(url ?: return@runCatching "[invalid URL]")
    URI(parsed.scheme, null, parsed.host, parsed.port, parsed.path, null, null).toString()
}.getOrDefault("[invalid URL]")

internal fun MessageType.isAllowedOverLegacyBridge(): Boolean = when (this) {
    MessageType.tryAgain,
    MessageType.CloseHubView,
    MessageType.HubLoaded,
    MessageType.HubResize,
    MessageType.CanTouchBackgroundToDismiss,
    MessageType.OpenEmailApp -> true
    else -> false
}

private fun trustedOriginRule(baseUrl: String): String? = runCatching {
    val uri = URI(baseUrl)
    if (uri.scheme == null || uri.host == null) {
        return@runCatching null
    }
    "${uri.scheme.lowercase()}://${uri.host.lowercase()}${if (uri.port >= 0) ":${uri.port}" else ""}"
}.getOrNull()

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
    internal val rowndJavascriptInterface: RowndJavascriptInterface
    internal val rowndWebViewClient: RowndWebViewClient

    private val lifecycleJob = SupervisorJob()
    internal val lifecycleScope = CoroutineScope(Dispatchers.Main.immediate + lifecycleJob)
    private val secureHubMessagingAvailable: Boolean
    private var targetPageRequestId: Long = 0
    private var pendingTargetPageRequest: PendingTargetPageRequest? = null

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

        rowndJavascriptInterface = RowndJavascriptInterface(this, ::dynamicBottomSheet, ::setCanTouchBackground)

        val trustedOrigin = trustedOriginRule(Rownd.config.baseUrl)
        secureHubMessagingAvailable =
            trustedOrigin != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (secureHubMessagingAvailable) {
            val allowedOrigins = setOf(trustedOrigin)
            WebViewCompat.addWebMessageListener(
                this,
                "rowndAndroidSDK",
                allowedOrigins,
            ) { _, message, _, isMainFrame, _ ->
                if (isMainFrame && message.data != null) {
                    rowndJavascriptInterface.postSecureMessage(message.data!!)
                }
            }
            WebViewCompat.addDocumentStartJavaScript(
                this,
                "window.__rowndNativeEmailVerificationBridge = true;",
                allowedOrigins,
            )
        } else {
            Log.w("Rownd.hub", "Secure Hub messaging is unavailable in this WebView version")
            this.addJavascriptInterface(rowndJavascriptInterface, "rowndAndroidSDK")
        }
        rowndWebViewClient = RowndWebViewClient(this, context)
        this.webViewClient = rowndWebViewClient

        val appFlags = Rownd.appHandleWrapper?.app?.get()?.applicationInfo?.flags ?: 0
        if (0 != appFlags.and(ApplicationInfo.FLAG_DEBUGGABLE)) {
            setWebContentsDebuggingEnabled(true)
        }
    }

    override fun destroy() {
        rowndJavascriptInterface.dispose()
        lifecycleJob.cancel()
        super.destroy()
    }

    override fun onDetachedFromWindow() {
        rowndJavascriptInterface.invalidateEmailVerificationRequests()
        super.onDetachedFromWindow()
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
        val targetPageRequestId = beginTargetPageRequest(targetPage, jsFnOptionsAsJson)

        this.let {
            lifecycleScope.launch {
                val targetUrl = rowndClient.config.hubLoaderUrl().toUri().buildUpon()
                    .appendQueryParameter(TARGET_PAGE_REQUEST_ID_PARAM, targetPageRequestId.toString())
                    .build()
                    .toString()
                if (!it.setTargetPageRequestUrl(targetPageRequestId, targetUrl)) {
                    return@launch
                }
                it.loadUrl(targetUrl)
            }
        }
    }

    internal fun loadNewPage(targetPage: HubPageSelector = HubPageSelector.SignIn, jsFnOptions: RowndSignInOptionsBase) {
        loadNewPage(targetPage, jsFnOptions.toJsonString())
    }

    internal fun prepareTargetPageRequest(
        targetPage: HubPageSelector,
        jsFnOptionsAsJson: String?,
        targetUrl: String,
    ) {
        val requestId = beginTargetPageRequest(targetPage, jsFnOptionsAsJson)
        setTargetPageRequestUrl(requestId, targetUrl)
    }

    @Synchronized
    private fun beginTargetPageRequest(targetPage: HubPageSelector, jsFnOptionsAsJson: String?): Long {
        this.targetPage = targetPage
        this.jsFunctionArgsAsJson = jsFnOptionsAsJson ?: DEFAULT_JS_FN_ARGS
        targetPageRequestId += 1
        pendingTargetPageRequest = PendingTargetPageRequest(
            id = targetPageRequestId,
            targetPage = targetPage,
            jsFunctionArgsAsJson = this.jsFunctionArgsAsJson,
        )
        return targetPageRequestId
    }

    @Synchronized
    private fun setTargetPageRequestUrl(requestId: Long, targetUrl: String): Boolean {
        val request = pendingTargetPageRequest?.takeIf { it.id == requestId } ?: return false
        pendingTargetPageRequest = request.copy(targetUrl = targetUrl)
        return true
    }

    @Synchronized
    internal fun pendingTargetPageRequest(url: String): PendingTargetPageRequest? {
        val uri = url.toUri()
        // WebView uses opaque URLs such as about:blank for locally loaded error pages.
        if (!uri.isHierarchical) {
            return null
        }

        val requestId = uri.getQueryParameter(TARGET_PAGE_REQUEST_ID_PARAM)?.toLongOrNull()
        return pendingTargetPageRequest?.takeIf { it.id == requestId }
    }

    @Synchronized
    internal fun hasPendingTargetPageRequest(): Boolean = pendingTargetPageRequest != null

    @Synchronized
    internal fun consumeTargetPageRequest(requestId: Long): PendingTargetPageRequest? {
        val request = pendingTargetPageRequest?.takeIf { it.id == requestId } ?: return null
        pendingTargetPageRequest = null
        return request
    }

    companion object {
        const val DEFAULT_JS_FN_ARGS = "{}"
        private const val TARGET_PAGE_REQUEST_ID_PARAM = "rph_sdk_request_id"
    }

    internal data class PendingTargetPageRequest(
        val id: Long,
        val targetPage: HubPageSelector,
        val jsFunctionArgsAsJson: String,
        val targetUrl: String? = null,
    )
}

class RowndWebViewClient(private val webView: RowndWebView, private val context: Context) : WebViewClientCompat() {
    private var timeout: Boolean = true
    private var pageLoadId: Long = 0
    private var finishedHubPage: Pair<Long, String>? = null

    init {
        webView.lifecycleScope.launch(Dispatchers.IO) {
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
        webView.rowndJavascriptInterface.invalidateEmailVerificationRequests()
        timeout = false
        pageLoadId += 1
        finishedHubPage = null
        super.onPageStarted(webView, url, favicon)
        Log.d("Rownd.hub", "Started loading ${urlForLogging(url)}")
        setIsLoading(true)
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
        finishedHubPage = pageLoadId to url

        if (!webView.hasPendingTargetPageRequest()) {
            setIsLoading(false)
        }
    }

    internal fun onHubLoaded() {
        val (finishedPageLoadId, finishedUrl) = finishedHubPage ?: return
        if (pageLoadId != finishedPageLoadId) {
            return
        }

        val targetPageRequest = webView.pendingTargetPageRequest(finishedUrl) ?: return
        displayTargetPage(webView, targetPageRequest, finishedPageLoadId)
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

    private fun displayTargetPage(
        view: WebView,
        targetPageRequest: RowndWebView.PendingTargetPageRequest,
        finishedPageLoadId: Long,
    ) {
        if (pageLoadId != finishedPageLoadId) {
            return
        }

        val request = webView.consumeTargetPageRequest(targetPageRequest.id) ?: return

        when (request.targetPage) {
            HubPageSelector.SignIn, HubPageSelector.Unknown -> evaluateJavascript("rownd.requestSignIn(${request.jsFunctionArgsAsJson})")
            HubPageSelector.SignOut -> evaluateJavascript("rownd.signOut({\"show_success\":true})")
            HubPageSelector.QrCode -> evaluateJavascript("rownd.generateQrCode(${request.jsFunctionArgsAsJson})")
            HubPageSelector.ManageAccount -> evaluateJavascript("rownd.user.manageAccount()")
            HubPageSelector.ConnectAuthenticator -> evaluateJavascript("rownd.connectAuthenticator(${request.jsFunctionArgsAsJson})")
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
    private val bridgeLifecycle = SupervisorJob()
    private val bridgeScope = CoroutineScope(Dispatchers.IO + bridgeLifecycle)
    private var signInCompletedFallbackJob: Job? = null
    private var emailVerificationJob: Job? = null
    @Volatile
    private var emailVerificationRequestId: String? = null
    @Volatile
    private var emailVerificationNavigationGeneration = 0
    @Volatile
    private var disposed = false

    private fun scheduleSignInCompletedAuthenticationFallback(authenticationMessage: AuthenticationMessage) {
        if (disposed || !signInCompletedDeduper.shouldScheduleAuthenticationFallback() || signInCompletedFallbackJob?.isActive == true) {
            Log.d("Rownd.hub", "Skipping duplicate sign_in_completed event")
            return
        }

        signInCompletedFallbackJob = bridgeScope.launch {
            delay(SIGN_IN_COMPLETED_AUTHENTICATION_FALLBACK_DELAY_MILLISECONDS)
            if (disposed || !signInCompletedDeduper.shouldEmitForAuthenticationFallback()) {
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

    internal fun dispose() {
        disposed = true
        bridgeLifecycle.cancel()
        signInCompletedFallbackJob = null
        invalidateEmailVerificationRequests()
    }

    internal fun invalidateEmailVerificationRequests() {
        emailVerificationNavigationGeneration += 1
        emailVerificationJob?.cancel()
        emailVerificationJob = null
        emailVerificationRequestId = null
    }

    private fun verifyEmail(requestId: String) {
        val requestedUrl = parentWebView.url
        val verificationRequest = nativeEmailVerificationRequest(
            targetPage = parentWebView.targetPage,
            currentUrl = requestedUrl,
            trustedBaseUrl = Rownd.config.baseUrl,
            trustedApiDomain = Rownd.config.supertokens.appInfo.apiDomain,
            trustedApiBasePath = Rownd.config.supertokens.appInfo.apiBasePath ?: Rownd.config.apiBasePath,
        )
        if (
            disposed ||
            requestId.isEmpty() ||
            requestId.length > 256 ||
            verificationRequest == null
        ) {
            Log.w("Rownd.hub", "Ignoring unauthorized native email-verification request")
            return
        }

        emailVerificationJob?.cancel()
        emailVerificationRequestId = requestId
        val requestedNavigationGeneration = emailVerificationNavigationGeneration
        emailVerificationJob = bridgeScope.launch {
            val result = runCatching {
                val appContext = parentWebView.context.applicationContext
                val previousAccessToken = SuperTokensSessionBridge.getAccessToken(appContext)
                    ?: error("Native email verification requires an existing session")
                val status = performNativeEmailVerification(parentWebView.rowndClient.authenticatedApiClient.client, verificationRequest)
                val replacementAccessToken = SuperTokensSessionBridge.getAccessToken(appContext)
                check(
                    replacementAccessToken != null &&
                        replacementAccessToken != previousAccessToken &&
                        !SuperTokensSessionBridge.getRefreshToken(appContext).isNullOrEmpty() &&
                        !SuperTokensSessionBridge.getFrontToken(appContext).isNullOrEmpty()
                ) { "Native replacement session was not adopted" }
                check(SuperTokensSessionBridge.syncRowndAuthStateFromSuperTokens(
                    context = appContext,
                    store = parentWebView.rowndClient.stateRepo.getStore(),
                )) { "Native session was not adopted" }
                status
            }
            if (
                disposed ||
                emailVerificationRequestId != requestId ||
                emailVerificationNavigationGeneration != requestedNavigationGeneration
            ) {
                return@launch
            }

            val detail = JSONObject().put("request_id", requestId)
            if (result.isSuccess) {
                detail.put("status", result.getOrThrow())
            } else {
                Log.w("Rownd.hub", "Native email verification failed", result.exceptionOrNull())
                detail.put("error", "Email verification failed")
            }
            val script = """
                if (window.location.href === ${JSONObject.quote(requestedUrl)}) {
                    window.dispatchEvent(new CustomEvent('$NATIVE_EMAIL_VERIFICATION_EVENT', { detail: $detail }));
                }
            """.trimIndent()

            parentWebView.post {
                if (
                    !disposed &&
                    emailVerificationRequestId == requestId &&
                    emailVerificationNavigationGeneration == requestedNavigationGeneration &&
                    parentWebView.url == requestedUrl &&
                    nativeEmailVerificationRequest(
                        targetPage = parentWebView.targetPage,
                        currentUrl = parentWebView.url,
                        trustedBaseUrl = Rownd.config.baseUrl,
                        trustedApiDomain = Rownd.config.supertokens.appInfo.apiDomain,
                        trustedApiBasePath = Rownd.config.supertokens.appInfo.apiBasePath ?: Rownd.config.apiBasePath,
                    ) != null
                ) {
                    parentWebView.evaluateJavascript(script, null)
                    emailVerificationRequestId = null
                    emailVerificationJob = null
                }
            }
        }
    }

    private fun resetSignInCompletedDeduper() {
        signInCompletedFallbackJob?.cancel()
        signInCompletedFallbackJob = null
        signInCompletedDeduper.reset()
    }

    @JavascriptInterface
    fun postMessage(message: String) {
        handleMessage(message, allowSensitiveMessages = false)
    }

    internal fun postSecureMessage(message: String) {
        handleMessage(message, allowSensitiveMessages = true)
    }

    private fun handleMessage(message: String, allowSensitiveMessages: Boolean) {
        if (disposed) {
            return
        }

        Log.d("Rownd.hub", "postMessage: " + redactSensitiveKeys(message))
        try {
            val interopMessage = json.decodeFromString(RowndHubInteropMessage.serializer(), message)
            Log.d("Rownd.hub", redactSensitiveKeys(interopMessage.toString()))

            if (!allowSensitiveMessages && !interopMessage.type.isAllowedOverLegacyBridge()) {
                Log.w("Rownd.hub", "Ignoring '${interopMessage.type}' over the legacy bridge")
                return
            }

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

                    fun launchPostBootstrap(includeBootstrap: Boolean) = bridgeScope.launch {
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

                            parentWebView.lifecycleScope.launch {
                                delay(HUB_CLOSE_AFTER_MILLISECONDS)
                                parentWebView.dismiss?.invoke()
                            }
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
                    parentWebView.lifecycleScope.launch {
                        delay(HUB_CLOSE_AFTER_MILLISECONDS)
                        parentWebView.dismiss?.invoke()
                    }

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
                    parentWebView.loadNewPage(parentWebView.targetPage, parentWebView.jsFunctionArgsAsJson)
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

                MessageType.VerifyEmail -> {
                    val request = interopMessage as VerifyEmailMessage
                    parentWebView.post { verifyEmail(request.payload.requestId) }
                }

                MessageType.HubLoaded -> {
                    parentWebView.lifecycleScope.launch {
                        parentWebView.rowndWebViewClient.onHubLoaded()
                    }
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
