package com.revnix.kmp

import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.Currency
import java.util.Locale

/**
 * Build facts always; app version and the debuggable flag when
 * [RevnixAndroid.init] handed over a context (without one there is no package
 * to ask, and the two are simply left out). The Play storefront needs a
 * billing connection and is not read here.
 */
public actual fun detectDeviceFacts(): DeviceFacts {
    val locale = Locale.getDefault()
    val context = RevnixAndroid.applicationContext
    val versionName = context?.let {
        runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.getOrNull()
    }
    val debuggable = context?.let {
        (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    return DeviceFacts(
        platform = "android",
        osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
        appVersion = versionName?.takeIf { it.isNotBlank() },
        locale = locale.toLanguageTag(),
        currency = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull(),
        model = Build.MODEL?.takeIf { it.isNotBlank() },
        sandbox = debuggable,
    )
}
