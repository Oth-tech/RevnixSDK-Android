package com.revnix.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import com.revnix.DeviceFacts
import java.security.MessageDigest

/**
 * REV-268: the device facts an Android app can report with a `Context` in
 * hand — everything [DeviceFacts.detect] already finds, plus the app's own
 * version name and whether this is a debuggable build. Pass the result as
 * `RevnixConfig(device = AndroidDeviceFacts.detect(context))`.
 *
 * The Play storefront is deliberately NOT read here: it needs a billing
 * connection (`BillingClient.getBillingConfigAsync`) and this function is
 * synchronous. [PlayBillingConnector] has the connection; wiring the country
 * through it is a follow-up, and until then the server simply stores no
 * storefront for Android customers rather than a guessed one.
 */
public object AndroidDeviceFacts {
    public fun detect(context: Context): DeviceFacts {
        val app = context.applicationContext
        val versionName = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrNull()
        val debuggable =
            (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return DeviceFacts.detect().overriddenBy(
            DeviceFacts(
                platform = "android",
                osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
                appVersion = versionName?.takeIf { it.isNotBlank() },
                model = Build.MODEL?.takeIf { it.isNotBlank() },
                sandbox = debuggable,
                deviceKey = deviceKey(app),
            )
        )
    }

    private fun deviceKey(context: Context): String? = runCatching {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        // 9774d56d682e549c: Android's documented fallback ANDROID_ID on broken/emulator devices.
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return@runCatching null
        val digest = MessageDigest.getInstance("SHA-256").digest("revnix:$androidId".toByteArray(Charsets.UTF_8))
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
