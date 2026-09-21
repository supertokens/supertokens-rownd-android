package io.rownd.android.views

internal enum class HubLoadError(val code: String, val message: String) {
    PREPARATION("HUB_PREPARATION_FAILED", "We couldn't prepare this page. Please try again."),
    NAVIGATION_TIMEOUT("HUB_LOAD_TIMEOUT", "The page took too long to load. Check your connection and try again."),
    READINESS_TIMEOUT("HUB_INIT_TIMEOUT", "The page loaded but couldn't finish starting. Please try again."),
    REQUEST_MISMATCH("HUB_REQUEST_MISMATCH", "We couldn't open the requested page after a redirect. Please try again."),
    NETWORK("HUB_NETWORK_ERROR", "We couldn't connect to the sign-in service. Check your connection and try again."),
    HTTP("HUB_HTTP_ERROR", "The sign-in service couldn't load this page. Please try again in a moment."),
    TLS("HUB_TLS_ERROR", "We couldn't establish a secure connection. Check your device's date and time, then try again."),
    JAVASCRIPT("HUB_SCRIPT_ERROR", "The page couldn't start because of an application error. Please try again."),
}
