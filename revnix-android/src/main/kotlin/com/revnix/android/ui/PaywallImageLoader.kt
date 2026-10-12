// The one image fetch a designed paywall uses, with a bitmap cache.
//
// Image loading is otherwise the host app's job in this SDK — it ships no
// image library — but a photo the designer placed is not optional decoration,
// so a plain HttpURLConnection + BitmapFactory fetch serves both the screen
// background and `image` blocks. Decoded bitmaps are kept by URL because
// selection on a designed paywall is a STRUCTURAL rebuild (a badge appears, a
// `selectedStyle` merges in), and a rebuild that re-downloaded every photo
// made each tap on a plan card flash the screen.

package com.revnix.android.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.revnix.RevnixClient
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves [placementKeys] into the cache, then downloads their paywalls'
 * images to disk, so the first display renders without waiting on the network.
 */
public suspend fun RevnixClient.preload(context: Context, placementKeys: List<String>) {
    RevnixImageLoader.init(context)
    val urls = mutableSetOf<String>()
    preload(placementKeys).forEach { collectImageUrls(it.paywallJson, urls) }
    coroutineScope {
        urls.map { url -> async(Dispatchers.IO) { RevnixImageLoader.prefetch(url) } }.awaitAll()
    }
}

private fun collectImageUrls(element: JsonElement?, into: MutableSet<String>) {
    fun JsonElement?.url() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    when (element) {
        is JsonArray -> element.forEach { collectImageUrls(it, into) }
        is JsonObject -> {
            element["heroImageUrl"].url()?.let(into::add)
            if (element["type"].url() == "image") element["url"].url()?.let(into::add)
            (element["image"] as? JsonObject)?.get("url").url()?.let(into::add)
            element.values.forEach { collectImageUrls(it, into) }
        }
        else -> Unit
    }
}

internal object RevnixImageLoader {

    private const val DISK_MAX_AGE_MS = 7L * 24 * 3600 * 1000

    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** A slice of the heap, capped: a paywall carries a handful of photos, not a gallery. */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(32L * 1024 * 1024).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Listeners waiting on a URL whose fetch is in flight, so a rebuild mid-download never fetches twice. */
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    private val main = Handler(Looper.getMainLooper())

    /** The decoded bitmap for [url] if it has been loaded already, without fetching. */
    fun cached(url: String): Bitmap? = synchronized(cache) { cache.get(url) }

    /**
     * Loads [url], decoded no larger than it needs to be to cover a
     * [targetWidth]×[targetHeight] px box, and hands the bitmap to [onLoaded]
     * on the main thread. A cached bitmap is delivered synchronously. A failed
     * fetch delivers nothing — the caller's placeholder stays.
     */
    fun load(url: String, targetWidth: Int, targetHeight: Int, onLoaded: (Bitmap) -> Unit) {
        cached(url)?.let {
            onLoaded(it)
            return
        }
        val start = synchronized(pending) {
            val waiting = pending[url]
            if (waiting != null) {
                waiting.add(onLoaded)
                false
            } else {
                pending[url] = mutableListOf(onLoaded)
                true
            }
        }
        if (!start) return
        Thread {
            val bitmap = runCatching { fetch(url, targetWidth, targetHeight) }.getOrNull()
            if (bitmap != null) synchronized(cache) { cache.put(url, bitmap) }
            val listeners = synchronized(pending) { pending.remove(url) }.orEmpty()
            if (bitmap != null) main.post { for (listener in listeners) listener(bitmap) }
        }.also { it.isDaemon = true; it.start() }
    }

    fun prefetch(url: String) {
        runCatching { bytes(url) }
    }

    fun fetch(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bytes = bytes(url) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun bytes(url: String): ByteArray? {
        val file = diskFile(url)
        if (file?.isFile == true && System.currentTimeMillis() - file.lastModified() < DISK_MAX_AGE_MS) return file.readBytes()
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val bytes = try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        // ponytail: unbounded disk cache, the OS reclaims cacheDir; add an LRU size cap if paywalls carry many large photos
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (file != null && bounds.outWidth > 0) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
        return bytes
    }

    private fun diskFile(url: String): File? {
        val dir = appContext?.cacheDir ?: return null
        val name = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(dir, "revnix-images"), name)
    }

    /**
     * The largest power-of-two reduction that keeps the decoded image at
     * least the size of the box on BOTH axes — enough for a cover crop at
     * device resolution and never more than 2× it on the limiting axis. A
     * 4000px photo in a 160dp slot used to decode at full size, per slot.
     */
    internal fun sampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2
        }
        return sample
    }
}
