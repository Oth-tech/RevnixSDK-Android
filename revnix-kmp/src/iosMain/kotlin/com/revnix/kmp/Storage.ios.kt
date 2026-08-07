package com.revnix.kmp

import platform.Foundation.NSDate
import platform.Foundation.NSUserDefaults
import platform.Foundation.timeIntervalSince1970

public actual fun defaultStorage(namespace: String): RevnixStorage =
    UserDefaultsStorage(namespace)

public actual fun currentTimeMs(): Long =
    (NSDate().timeIntervalSince1970 * 1000).toLong()

/** Darwin durable storage, backed by NSUserDefaults. */
public class UserDefaultsStorage(namespace: String) : RevnixStorage {
    private val defaults = NSUserDefaults(suiteName = namespace)

    override fun get(key: String): String? = defaults.stringForKey(key)

    override fun set(key: String, value: String) {
        defaults.setObject(value, key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}
