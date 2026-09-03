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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL

internal object RevnixImageLoader {

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

    private fun fetch(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        val bytes = try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
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
