package io.rownd.android.util

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle.State
import kotlinx.collections.immutable.PersistentList
import java.lang.ref.WeakReference
import java.util.WeakHashMap

enum class ContextType {
    APP, ACTIVITY
}

data class Listener(
    val states: PersistentList<State>,
    val once: Boolean? = false,
    val callback: (activity: Activity) -> Unit
)

class AppLifecycleListener(parentApp: Application) : ActivityLifecycleCallbacks {
    var app: WeakReference<Application>
        private set
    var activity: WeakReference<Activity>? = null
        private set

    private val activityListeners: MutableList<Listener> = mutableListOf()
    private val newIntentListeners: MutableList<(intent: Intent) -> Unit> = mutableListOf()
    private val registeredNewIntentListeners =
        WeakHashMap<ComponentActivity, MutableMap<(intent: Intent) -> Unit, Consumer<Intent>>>()

    init {
        app = WeakReference(parentApp)
        parentApp.registerActivityLifecycleCallbacks(this)
    }

    constructor(currentActivity: Activity) : this(currentActivity.application) {
        activity = WeakReference(currentActivity)
    }

    override fun onActivityCreated(activity: Activity, bundle: Bundle?) {
        this.activity = WeakReference(activity)

        registerNewIntentListeners(activity)

        val listeners = activityListeners.filter() {
            it.states.contains(State.CREATED)
        }

        callListeners(listeners, activity)
    }

    override fun onActivityStarted(activity: Activity) {
        this.activity = WeakReference(activity)

        val listeners = activityListeners.filter() {
            it.states.contains(State.STARTED)
        }

        callListeners(listeners, activity)
    }

    override fun onActivityResumed(activity: Activity) {
        this.activity = WeakReference(activity)
        isAppInForeground = true
        registerNewIntentListeners(activity)

        // This is probably one of the better trigger points for listeners
        // unless there's a need for something earlier in the lifecycle
        val listeners = activityListeners.filter() {
            it.states.contains(State.RESUMED)
        }

        callListeners(listeners, activity)
    }

    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        super.onActivityPreCreated(activity, savedInstanceState)

        val listeners = activityListeners.filter() {
            it.states.contains(State.INITIALIZED)
        }

        callListeners(listeners, activity)
    }

    override fun onActivityPaused(activity: Activity) {
        isAppInForeground = false
    }
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (activity is ComponentActivity) {
            removeNewIntentListeners(activity)
        }

        val listeners = activityListeners.filter() {
            it.states.contains(State.DESTROYED)
        }

        callListeners(listeners, activity)
    }

    internal fun registerActivityListener(
        states: PersistentList<State>,
        immediate: Boolean = false,
        immediateIfBefore: State? = null,
        once: Boolean? = false,
        callback: (activity: Activity) -> Unit
    ) {
        val activity = this.activity?.get() as? ComponentActivity
        activityListeners.add(Listener(
            states,
            once,
            callback
        ))

        if (immediateIfBefore != null &&
            activity != null &&
            !activity.lifecycle.currentState.isAtLeast(immediateIfBefore)
        ) {
            callback.invoke(activity)
        } else if (immediate && activity != null) {
            callback.invoke(activity)
        }
    }

    internal fun registerNewIntentListener(callback: (intent: Intent) -> Unit) {
        newIntentListeners.add(callback)
        (activity?.get() as? ComponentActivity)?.let {
            registerNewIntentListener(it, callback)
        }
    }

    private fun registerNewIntentListeners(activity: Activity) {
        if (activity !is ComponentActivity) {
            return
        }

        newIntentListeners.forEach { registerNewIntentListener(activity, it) }
    }

    private fun registerNewIntentListener(
        activity: ComponentActivity,
        callback: (intent: Intent) -> Unit,
    ) {
        val registeredListeners = registeredNewIntentListeners.getOrPut(activity) { mutableMapOf() }
        if (callback !in registeredListeners) {
            val listener = Consumer<Intent> { callback(it) }
            registeredListeners[callback] = listener
            activity.addOnNewIntentListener(listener)
        }
    }

    private fun removeNewIntentListeners(activity: ComponentActivity) {
        registeredNewIntentListeners.remove(activity)?.values?.forEach {
            activity.removeOnNewIntentListener(it)
        }
    }

    internal fun unregister() {
        app.get()?.unregisterActivityLifecycleCallbacks(this)
        registeredNewIntentListeners.keys.toList().forEach(::removeNewIntentListeners)
        newIntentListeners.clear()
        activityListeners.clear()
        activity = null
    }

    private fun callListeners(listeners: List<Listener>, activity: Activity) {
        for (listener in listeners) {
            listener.callback.invoke(activity)
            if (listener.once == true) {
                activityListeners.remove(listener)
            }
        }
    }

    companion object {
        var isAppInForeground: Boolean = false
    }
}
