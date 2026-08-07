package com.revnix.android

import android.content.Context
import android.content.SharedPreferences
import com.revnix.RevnixStorage

/**
 * Durable storage for the offline entitlement cache and the purchase retry
 * queue.
 *
 * The design doc proposed DataStore; `SharedPreferences` is used instead
 * because [RevnixStorage] is a synchronous contract (a gate check must be able
 * to answer without suspending) and DataStore is async-first — bridging it
 * would mean `runBlocking` on every read, which is worse than the thing
 * DataStore exists to avoid. Values here are small: ids, one entitlement
 * snapshot per recent customer, and the pending-purchase queue.
 */
public class AndroidStorage(context: Context) : RevnixStorage {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("com.revnix.storage", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
