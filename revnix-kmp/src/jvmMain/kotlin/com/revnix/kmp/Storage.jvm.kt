package com.revnix.kmp

import java.util.prefs.Preferences

public actual fun defaultStorage(namespace: String): RevnixStorage =
    PreferencesStorage(namespace)

public actual fun currentTimeMs(): Long = System.currentTimeMillis()

/** JVM durable storage. Android uses SharedPreferences instead. */
public class PreferencesStorage(namespace: String) : RevnixStorage {
    private val prefs: Preferences = Preferences.userRoot().node(namespace.replace('.', '/'))

    override fun get(key: String): String? = prefs.get(key, null)

    override fun set(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }

    override fun remove(key: String) {
        prefs.remove(key)
        prefs.flush()
    }
}
