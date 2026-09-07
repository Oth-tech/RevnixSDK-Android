package com.revnix.kmp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * REV-268: the device attribute contract, SDK side. Every Revnix SDK sends the
 * same facts about the device on each placement resolve, in the
 * `X-Revnix-Device` header, and the server stores them as reserved `device.*`
 * customer attributes — what a targeting rule ("US storefront", "app version
 * at least 3", "first open") evaluates against on the very request that serves
 * the paywall.
 *
 * [detectDeviceFacts] is `expect`ed per target: Darwin reads UIDevice / the
 * main bundle, Android reads `Build` plus the application context handed to
 * [RevnixAndroid.init], the JVM reports its locale and currency. The SDK owns
 * `sdkVersion`, `installedAt` and `firstOpen` itself. Every field is
 * optional: the server treats a missing fact as missing, never as an error.
 */
public data class DeviceFacts(
    /** "ios", "android", "jvm", … — lowercase family name. */
    val platform: String? = null,
    /** e.g. "18.1". */
    val osVersion: String? = null,
    /** The host app's version, e.g. "1.2.10". The server derives the
     *  zero-padded sortable form (`device.appVersionPadded`) from it. */
    val appVersion: String? = null,
    /** BCP-47 ("en-US") or "en_US" — either separator. */
    val locale: String? = null,
    /** ISO 4217, e.g. "USD". */
    val currency: String? = null,
    /** Store country — alpha-2 ("US") or alpha-3 ("USA"), the server
     *  normalizes. */
    val storefront: String? = null,
    /** Hardware model, e.g. "iPhone15,3" / "Pixel 8". */
    val model: String? = null,
    /** True for a debug / sandbox build. */
    val sandbox: Boolean? = null,
) {
    /** These facts with every non-null field of [over] winning. */
    public fun overriddenBy(over: DeviceFacts?): DeviceFacts {
        if (over == null) return this
        return DeviceFacts(
            platform = over.platform ?: platform,
            osVersion = over.osVersion ?: osVersion,
            appVersion = over.appVersion ?: appVersion,
            locale = over.locale ?: locale,
            currency = over.currency ?: currency,
            storefront = over.storefront ?: storefront,
            model = over.model ?: model,
            sandbox = over.sandbox ?: sandbox,
        )
    }

    /**
     * The wire payload for one resolve: these facts plus the SDK-owned ones,
     * base64url of the UTF-8 JSON. Null fields are left out.
     */
    internal fun encodedHeader(sdkVersion: String, installedAt: Long?, firstOpen: Boolean?): String {
        val payload = buildJsonObject {
            put("sdkVersion", JsonPrimitive(sdkVersion))
            platform?.let { put("platform", JsonPrimitive(it)) }
            osVersion?.let { put("osVersion", JsonPrimitive(it)) }
            appVersion?.let { put("appVersion", JsonPrimitive(it)) }
            locale?.let { put("locale", JsonPrimitive(it)) }
            currency?.let { put("currency", JsonPrimitive(it)) }
            storefront?.let { put("storefront", JsonPrimitive(it)) }
            model?.let { put("model", JsonPrimitive(it)) }
            sandbox?.let { put("sandbox", JsonPrimitive(it)) }
            installedAt?.let { put("installedAt", JsonPrimitive(it)) }
            firstOpen?.let { put("firstOpen", JsonPrimitive(it)) }
        }
        return base64Url(payload.toString().encodeToByteArray())
    }
}

/** What this process can say about the device it runs on. */
public expect fun detectDeviceFacts(): DeviceFacts

private const val BASE64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/**
 * Unpadded base64url. Hand-rolled because `kotlin.io.encoding.Base64` is
 * not available on every target/toolchain this module builds for, and the
 * payload is a few hundred bytes.
 */
internal fun base64Url(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val a = bytes[i].toInt() and 0xff
        val hasB = i + 1 < bytes.size
        val hasC = i + 2 < bytes.size
        val b = if (hasB) bytes[i + 1].toInt() and 0xff else 0
        val c = if (hasC) bytes[i + 2].toInt() and 0xff else 0
        sb.append(BASE64_URL[a shr 2])
        sb.append(BASE64_URL[((a and 0x03) shl 4) or (b shr 4)])
        if (hasB) sb.append(BASE64_URL[((b and 0x0f) shl 2) or (c shr 6)])
        if (hasC) sb.append(BASE64_URL[c and 0x3f])
        i += 3
    }
    return sb.toString()
}
