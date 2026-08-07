package com.revnix.kmp

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.SharedPreferences

public actual fun defaultStorage(namespace: String): RevnixStorage {
    val context = RevnixAndroid.applicationContext
        ?: error(
            "Call RevnixAndroid.init(context) before defaultStorage(), or pass " +
                "an explicit AndroidStorage — Android has no ambient context."
        )
    return AndroidStorage(context, namespace)
}

public actual fun currentTimeMs(): Long = System.currentTimeMillis()

/** Android durable storage, backed by SharedPreferences. */
public class AndroidStorage(
    context: Context,
    namespace: String = "com.revnix",
) : RevnixStorage {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** Optional one-time context handoff so [defaultStorage] can work. */
public object RevnixAndroid {
    @SuppressLint("StaticFieldLeak")
    internal var applicationContext: Context? = null
        private set

    public fun init(context: Context) {
        applicationContext = context.applicationContext
    }
}
