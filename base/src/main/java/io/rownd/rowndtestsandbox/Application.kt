package io.rownd.rowndtestsandbox

import android.app.Application
import android.util.Log
import io.rownd.android.Rownd
import io.rownd.android.RowndConfigureOptions

class RowndBaseApp: Application() {

    override fun onCreate() {
        super.onCreate()

        Rownd.configure(
            this,
            RowndConfigureOptions(
                appKey = "key_z53h41zi2e160d6gdv9j08vm",
                apiDomain = "https://api.dev.rownd.io",
                hubUrl = "https://staging.supertokens-rownd-hub.pages.dev",
                deepLinkScheme = "rowndsupertokens",
            )
        )
        Log.d("App.onCreate", "Rownd initialized: ${Rownd.state.value.isInitialized}")
    }
}
