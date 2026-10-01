package com.revnix

import java.util.Base64
import java.util.Currency
import java.util.Locale
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
 * [detect] fills what a JVM can answer on its own, and reads the Android
 * build facts reflectively when it finds itself on Android — so the
 * README's plain `RevnixConfig(apiKey, baseUrl, storage = AndroidStorage(…))`
 * already reports platform, OS version and model. The app version, the
 * sandbox flag and the Play storefront need a `Context` or a billing
 * connection, which `revnix-android`'s `AndroidDeviceFacts.detect(context)`
 * adds on top. The SDK owns `sdkVersion`, `installedAt` and `firstOpen`
 * itself. Every field is optional: the server treats a missing fact as
 * missing, never as an error.
 */
public data class DeviceFacts(
    /** "android", "jvm", … — lowercase family name. */
    val platform: String? = null,
    /** e.g. "15". */
    val osVersion: String? = null,
    /** The host app's version name, e.g. "1.2.10". The server derives the
     *  zero-padded sortable form (`device.appVersionPadded`) from it. */
    val appVersion: String? = null,
    /** BCP-47 ("en-US") or "en_US" — either separator. */
    val locale: String? = null,
    /** ISO 4217, e.g. "USD". */
    val currency: String? = null,
    /** Play storefront country, alpha-2 ("US"). */
    val storefront: String? = null,
    /** Hardware model, e.g. "Pixel 8". */
    val model: String? = null,
    /** True for a debuggable build. */
    val sandbox: Boolean? = null,
    /** Opaque hash that survives an uninstall/reinstall, for reinstall detection.
     *  Sent on `/v1/installs` only — never in the per-resolve device header. */
    val deviceKey: String? = null,
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
            deviceKey = over.deviceKey ?: deviceKey,
        )
    }

    /**
     * The wire payload for one resolve: these facts plus the SDK-owned ones,
     * base64url of the UTF-8 JSON. Null fields are left out — the server would
     * drop them, and bytes on every resolve are bytes on every resolve.
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
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    public companion object {
        /** What this process can say about itself without a `Context`. */
        public fun detect(): DeviceFacts {
            val locale = Locale.getDefault()
            val currency = runCatching { Currency.getInstance(locale).currencyCode }.getOrNull()
            val build = androidBuild()
            return DeviceFacts(
                platform = if (build != null) "android" else null,
                osVersion = build?.first,
                model = build?.second,
                locale = locale.toLanguageTag(),
                currency = currency,
            )
        }

        /**
         * (`Build.VERSION.RELEASE`, `Build.MODEL`) when running on Android,
         * read reflectively so this module keeps no Android dependency. On a
         * plain JVM the class is absent and this is null.
         */
        private fun androidBuild(): Pair<String?, String?>? = runCatching {
            val build = Class.forName("android.os.Build")
            val version = Class.forName("android.os.Build\$VERSION")
            val release = version.getField("RELEASE").get(null) as? String
            val model = build.getField("MODEL").get(null) as? String
            // An unset Build (unit tests with the Android stubs jar) reports
            // nulls or "unknown"; that is no fact at all.
            fun clean(value: String?) = value?.takeIf { it.isNotBlank() && it != "unknown" }
            clean(release) to clean(model)
        }.getOrNull()
    }
}
