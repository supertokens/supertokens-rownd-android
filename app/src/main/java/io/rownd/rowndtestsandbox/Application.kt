package io.rownd.rowndtestsandbox

import android.app.Application
import android.util.Log
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.rownd.android.Rownd
import io.rownd.android.RowndConfigureOptions
import io.rownd.android.models.RowndCustomizations
import io.rownd.android.util.RowndEventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


class AppCustomizations : RowndCustomizations() {
    override var sheetCornerBorderRadius: Dp = 25.dp
    //override var loadingAnimation: Int? = R.raw.loading_indicator_small
}

data class SandboxEventObservation(
    val signInCompletedCount: Int = 0,
    val signOutCount: Int = 0,
    val latestEvent: String = "none",
)

object SandboxObservability {
    private val mutableEvents = MutableStateFlow(SandboxEventObservation())
    val events = mutableEvents.asStateFlow()

    @Synchronized
    fun record(event: RowndEventType) {
        val current = mutableEvents.value
        mutableEvents.value = current.copy(
            signInCompletedCount = current.signInCompletedCount + if (event == RowndEventType.SignInCompleted) 1 else 0,
            signOutCount = current.signOutCount + if (event == RowndEventType.SignOut) 1 else 0,
            latestEvent = event.name,
        )
    }

    fun reset() {
        mutableEvents.value = SandboxEventObservation()
    }
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
            SandboxObservability.record(it.event)
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
