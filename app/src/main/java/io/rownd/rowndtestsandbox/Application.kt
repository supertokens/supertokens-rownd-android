package io.rownd.rowndtestsandbox

import android.app.Application
import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.rownd.android.Rownd
import io.rownd.android.RowndConfigureOptions
import io.rownd.android.models.RowndCustomizations
import io.rownd.android.util.RowndEventType


class AppCustomizations : RowndCustomizations() {
    override var sheetCornerBorderRadius: Dp = 25.dp
    //override var loadingAnimation: Int? = R.raw.loading_indicator_small
}


class RowndTestSandbox: Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        Rownd.config.enableDebugMode = true
        Rownd.configure(
            this,
            RowndConfigureOptions(
                appKey = BuildConfig.APP_KEY,
                apiDomain = BuildConfig.API_URL,
                apiBasePath = "/auth",
                hubUrl = BuildConfig.HUB_URL,
                deepLinkScheme = BuildConfig.DEEP_LINK_SCHEME,
            )
        )
        Log.d("App.onCreate", "Rownd initialized: ${Rownd.state.value.isInitialized}")

        Rownd.addEventListener {
            when (it.event) {
                RowndEventType.SignInStarted -> {
                    // Do stuff
                }
                RowndEventType.SignInCompleted -> {
                    it.data?.get("user_type")?.let { it1 -> Log.d("App", it1.toString()) }
                }

                else -> {
                    // no-op
                }
            }
        }
    }

    companion object {
        lateinit var instance: RowndTestSandbox
            private set
    }
}
