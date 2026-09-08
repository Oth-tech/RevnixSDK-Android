package com.revnix.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.revnix.RevnixAppState
import com.revnix.RevnixLifecycle

/**
 * REV-272: foreground detection for implicit placements, built on
 * [Application.ActivityLifecycleCallbacks].
 *
 * Deliberately NOT `androidx.lifecycle.ProcessLifecycleOwner`: that would drag
 * a new dependency into every host for one callback, and `revnix-core` is
 * plain Kotlin precisely so the SDK stays cheap to adopt. Counting started
 * activities gives the same answer — the process is in the foreground exactly
 * while at least one activity is started — and a rotation, which stops the old
 * activity only after starting the new one, never dips the count to zero.
 *
 * ```kotlin
 * RevnixConfig(
 *     apiKey = "rvx_pk_live_…",
 *     baseUrl = "https://…convex.site",
 *     lifecycle = AndroidLifecycle(application),
 *     onImplicitPaywall = { trigger -> present(trigger.resolution) },
 * )
 * ```
 */
public class AndroidLifecycle(private val application: Application) : RevnixLifecycle {

    override fun onStateChange(handler: (RevnixAppState) -> Unit): () -> Unit {
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            /** Started activities. 0 → 1 is the process coming forward, 1 → 0
             *  is it going to the background. */
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                started += 1
                if (started == 1) handler(RevnixAppState.FOREGROUND)
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0) started -= 1
                if (started == 0) handler(RevnixAppState.BACKGROUND)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        return { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
