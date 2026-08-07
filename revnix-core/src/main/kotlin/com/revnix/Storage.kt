package com.revnix

import java.io.File
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Small synchronous key-value store. The offline entitlement cache and the
 * purchase retry queue must survive relaunch, so the default implementation is
 * durable; [MemoryStorage] is for tests. Android ships a DataStore-backed
 * implementation in the `revnix-android` module.
 */
public interface RevnixStorage {
    public fun get(key: String): String?
    public fun set(key: String, value: String)
    public fun remove(key: String)
}

public class MemoryStorage : RevnixStorage {
    private val values = mutableMapOf<String, String>()
    private val lock = Any()

    override fun get(key: String): String? = synchronized(lock) { values[key] }

    override fun set(key: String, value: String) {
        synchronized(lock) { values[key] = value }
    }

    override fun remove(key: String) {
        synchronized(lock) { values.remove(key) }
    }
}

/** One JSON file. The JVM default; values are small. */
public class FileStorage(private val file: File) : RevnixStorage {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val values: MutableMap<String, String> = runCatching {
        json.decodeFromString(
            MapSerializer(String.serializer(), String.serializer()),
            file.readText(),
        ).toMutableMap()
    }.getOrElse { mutableMapOf() }

    override fun get(key: String): String? = synchronized(lock) { values[key] }

    override fun set(key: String, value: String) {
        synchronized(lock) {
            values[key] = value
            persist()
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            values.remove(key)
            persist()
        }
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    MapSerializer(String.serializer(), String.serializer()),
                    values,
                )
            )
        }
    }
}

internal fun generateAnonymousId(): String =
    "rvx_anon_" + UUID.randomUUID().toString().replace("-", "").lowercase()
