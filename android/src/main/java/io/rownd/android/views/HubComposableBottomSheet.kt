package io.rownd.android.views

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidViewBinding
import androidx.lifecycle.ViewModelProvider
import com.composables.core.SheetDetent
import io.rownd.android.databinding.HubViewLayoutBinding
import kotlinx.coroutines.launch

enum class HubBottomSheetBundleKeys(val key: String) {
    TargetPage("target_page")
}

class HubComposableBottomSheet(
    activity: RowndBottomSheetActivity,
    override val onDismiss: () -> Unit = {},
    private val targetPage: HubPageSelector = HubPageSelector.Unknown,
    private val jsFnArgsAsJson: String? = null
) : ComposableBottomSheet(activity) {
    override val shouldDisplayLoader = true

    internal var existingWebView: RowndWebView? = null
    internal var isDismissing: Boolean = false
    private var viewModel: RowndWebViewModel? = null
    private var activeWebView: RowndWebView? = null
    private var isReusingWebView: Boolean = false

    init {
        viewModel = ViewModelProvider(this.activity)[RowndWebViewModel::class.java]
        viewModel?.webView()?.observe(this.activity) {
            existingWebView = it
        }
    }
        // Internal dismiss function to recycle web view
        override fun dismiss() {
            isDismissing = true
            viewModel?.webView()?.postValue(null)
            activeWebView?.let { webView ->
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.destroy()
            }
            activeWebView = null

            super.dismiss()
        }

        @ExperimentalMaterial3Api
        @Composable
        override fun Content(
            requestDetent: (detent: SheetDetent) -> Unit,
            setIsLoading: (isLoading: Boolean) -> Unit,
            setCanTouchBackgroundToDismiss: (canTouchBackgroundToDismiss: Boolean) -> Unit
        ) {
            val coroutineScope = rememberCoroutineScope()

            LaunchedEffect(this.jsFnArgsAsJson) {
                Log.d("HubComposableBottomSheet", "jsFnArgsAsJson: $jsFnArgsAsJson")
            }

            val (hasLoadedUrl, setHasLoadedUrl) = remember { mutableStateOf(false) }

            AndroidViewBinding(
                factory = { layoutInflater: LayoutInflater, viewGroup: ViewGroup, b: Boolean ->
                    val view = HubViewLayoutBinding.inflate(layoutInflater, viewGroup, b)
                    val rootViewGroup = view.root
                    val currentWebView = view.hubWebview

                    if (existingWebView != null && existingWebView?.parent == null) {
                        val oldWebViewIndex = viewGroup.indexOfChild(currentWebView)
                        rootViewGroup.removeView(currentWebView)
                        rootViewGroup.addView(existingWebView, oldWebViewIndex, currentWebView.layoutParams)
                        currentWebView.destroy()
                        activeWebView = existingWebView
                        isReusingWebView = true
                    } else {
                        activeWebView = currentWebView
                        isReusingWebView = false
                        viewModel?.webView()?.postValue(currentWebView)
                    }

                    return@AndroidViewBinding view
                },
                update = {
                    val hubWebView = requireNotNull(activeWebView)
                    hubWebView.progressBar = this.hubProgressBar
                    hubWebView.setIsLoading = setIsLoading

                    hubWebView.animateBottomSheet = {
                        requestDetent(it)
                    }
                    hubWebView.setCanTouchBackgroundToDismiss = {
                        coroutineScope.launch {
                            setCanTouchBackgroundToDismiss(it)
                        }
                    }
                    hubWebView.dismiss = {
                        hubWebView.rowndJavascriptInterface.invalidateEmailVerificationRequests()
                        this@HubComposableBottomSheet.dismiss()
                    }
                    if (!hasLoadedUrl) {
                        if (!isReusingWebView) {
                            hubWebView.loadNewPage(
                                this@HubComposableBottomSheet.targetPage,
                                this@HubComposableBottomSheet.jsFnArgsAsJson,
                            )
                        }
                        setHasLoadedUrl(true)
                    }
                }
            )
        }
    }
