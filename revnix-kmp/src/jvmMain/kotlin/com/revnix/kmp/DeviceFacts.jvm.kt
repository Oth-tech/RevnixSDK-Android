package com.revnix.kmp

import java.util.Currency
import java.util.Locale

/** A plain JVM has no store and no app bundle: locale and currency only. */
public actual fun detectDeviceFacts(): DeviceFacts {
    val locale = Locale.getDefault()
    return DeviceFacts(
        platform = "jvm",
        osVersion = System.getProperty("os.version")?.takeIf { it.isNotBlank() },
        locale = locale.toLanguageTag(),
        currency = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull(),
    )
}
