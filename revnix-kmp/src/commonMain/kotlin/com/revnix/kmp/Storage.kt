package com.revnix.kmp

import kotlin.random.Random

/**
 * Small synchronous key-value store — synchronous because a gate check must be
 * able to answer without suspending. The offline entitlement cache and the
 * purchase retry queue both live here, so an implementation that does not
 * survive relaunch silently disables both.
 */
public interface RevnixStorage {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}

/** In-memory. Tests and previews only — nothing survives process death. */
public class MemoryStorage : RevnixStorage {
    private val values = mutableMapOf<String, String>()

    override fun get(key: String): String? = values[key]

    override fun set(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

/** Platform-durable storage: NSUserDefaults on Darwin, Preferences on JVM. */
public expect fun defaultStorage(namespace: String = "com.revnix"): RevnixStorage

/** Monotonic-ish wall clock in unix ms, injectable for tests. */
public expect fun currentTimeMs(): Long

internal fun generateAnonymousId(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder("rvx_anon_")
    repeat(32) { sb.append(hex[Random.nextInt(16)]) }
    return sb.toString()
}
