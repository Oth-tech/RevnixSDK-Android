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
 *
 * Android's Auto Backup can restore this file onto a fresh install.
 */
public class AndroidStorage(context: Context) : RevnixStorage {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("com.revnix.storage", Context.MODE_PRIVATE)

    init {
        val app = context.applicationContext
        val firstInstallTime = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).firstInstallTime
        }.getOrNull()
        if (firstInstallTime != null) {
            val stored = prefs.getLong(KEY_FIRST_INSTALL_TIME, -1L)
            if (stored != -1L && stored != firstInstallTime) {
                prefs.edit().clear().commit()
            }
            prefs.edit().putLong(KEY_FIRST_INSTALL_TIME, firstInstallTime).apply()
        }
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val KEY_FIRST_INSTALL_TIME = "revnix.firstInstallTime"
    }
}
