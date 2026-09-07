package com.revnix.kmp

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSProcessInfo
import platform.Foundation.currencyCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

/**
 * Darwin facts from UIDevice, the main bundle and the current locale. The
 * storefront is StoreKit's and asynchronous; it is left for the app to set
 * (`RevnixConfig(device = detectDeviceFacts().copy(storefront = …))`).
 */
@OptIn(ExperimentalForeignApi::class)
public actual fun detectDeviceFacts(): DeviceFacts {
    val device = UIDevice.currentDevice
    val bundle = NSBundle.mainBundle
    val locale = NSLocale.currentLocale
    val simulated = NSProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] as? String
    val model = simulated ?: memScoped {
        val system = alloc<utsname>()
        uname(system.ptr)
        system.machine.toKString().takeIf { it.isNotBlank() }
    }
    val receipt = bundle.appStoreReceiptURL?.lastPathComponent
    return DeviceFacts(
        platform = "ios",
        osVersion = device.systemVersion,
        appVersion = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String,
        locale = locale.localeIdentifier,
        currency = locale.currencyCode,
        model = model,
        sandbox = receipt == "sandboxReceipt",
    )
}
