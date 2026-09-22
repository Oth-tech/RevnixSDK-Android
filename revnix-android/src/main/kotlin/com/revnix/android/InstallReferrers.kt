package com.revnix.android

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.revnix.RevnixClient
import com.revnix.RevnixStorage

/**
 * Reads whichever install referrer this device can answer with and reports the
 * first one that is not blank through [RevnixClient.handleInstallReferrer].
 *
 * Call it once at launch instead of [PlayInstallReferrer.collect]:
 *
 * ```kotlin
 * InstallReferrers.collect(app, client, facebookAppId = "1234567890")
 * ```
 *
 * Order is Meta, then the store (Huawei AppGallery or Google Play), then a
 * preinstall `<meta-data>`. Meta comes first because its provider answers only
 * for an install its own ad actually drove, while Play answers
 * `utm_source=google-play&utm_medium=organic` for every plain store install —
 * store-first would score every Meta-driven install as organic.
 *
 * Samsung, Xiaomi, Vivo and Huawei Ads each need a proprietary AAR from that
 * vendor's own Maven repo, which would break the build of every Play-only
 * host, so they are not read here. Read the string with the vendor's SDK and
 * call `client.handleInstallReferrer(referrer, source = "samsung")` yourself.
 *
 * Never throws: a missing provider, a denied query or a malformed cursor is
 * "no answer", never a crash in the host app.
 */
public object InstallReferrers {

    // ponytail: every authority, path and column position below is a
    // vendor-published constant that could not be checked against a device in
    // the environment this was written in. They sit in one block so a wrong
    // one is a one-line fix; only a vendor changing its provider shape would
    // force the heavier vendor-SDK read.
    private const val FACEBOOK_PROVIDER =
        "content://com.facebook.katana.provider.InstallReferrerProvider"
    private const val INSTAGRAM_PROVIDER =
        "content://com.instagram.contentprovider.InstallReferrerProvider"
    private const val META_COLUMN = "install_referrer"
    private const val HUAWEI_PROVIDER = "content://com.huawei.appmarket.commondata/item/5"
    private const val HUAWEI_COLUMN = 1
    private const val HUAWEI_APP_GALLERY = "com.huawei.appmarket"
    private const val PREINSTALL_META_DATA = "revnix_preinstall_referrer"

    public fun collect(
        context: Context,
        client: RevnixClient,
        platform: String? = null,
        facebookAppId: String? = null,
    ) {
        val storage = AndroidStorage(context)
        val key = PlayInstallReferrer.collectedKey(client.customerId())
        if (storage.get(key) != null) return
        val appVersion = AndroidDeviceFacts.detect(context).appVersion

        val meta = facebookAppId?.let { readMeta(context, it) }
        if (!meta.isNullOrBlank()) {
            report(listOf("meta" to meta), client, storage, key, platform, appVersion)
            return
        }

        if (installerPackage(context) == HUAWEI_APP_GALLERY) {
            report(
                listOf("huawei" to readHuawei(context), "preinstall" to readPreinstall(context)),
                client,
                storage,
                key,
                platform,
                appVersion,
            )
            return
        }

        // Play binds a service and answers on a callback, so it cannot join
        // the list above; it latches the shared key itself and hands the
        // preinstall fallback back here only when it had nothing to post.
        PlayInstallReferrer.collect(context, client, platform) {
            report(
                listOf("preinstall" to readPreinstall(context)),
                client,
                storage,
                key,
                platform,
                appVersion,
            )
        }
    }

    internal fun report(
        candidates: List<Pair<String, String?>>,
        client: RevnixClient,
        storage: RevnixStorage,
        key: String,
        platform: String?,
        appVersion: String?,
    ) {
        candidates.firstOrNull { !it.second.isNullOrBlank() }?.let { (source, referrer) ->
            client.handleInstallReferrer(referrer!!, platform, appVersion, source)
        }
        // Latched even when nothing answered: the sources read here are fixed
        // for the life of the install, so a second launch would ask the same
        // providers the same question.
        storage.set(key, "1")
    }

    private fun readMeta(context: Context, facebookAppId: String): String? =
        queryColumn(context, "$FACEBOOK_PROVIDER/$facebookAppId", META_COLUMN)
            ?: queryColumn(context, "$INSTAGRAM_PROVIDER/$facebookAppId", META_COLUMN)

    private fun readHuawei(context: Context): String? = runCatching {
        context.applicationContext.contentResolver.query(
            Uri.parse(HUAWEI_PROVIDER),
            null,
            null,
            arrayOf(context.packageName),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > HUAWEI_COLUMN) {
                cursor.getString(HUAWEI_COLUMN)
            } else {
                null
            }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readPreinstall(context: Context): String? = runCatching {
        val app = context.applicationContext
        app.packageManager
            .getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(PREINSTALL_META_DATA)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun installerPackage(context: Context): String? = runCatching {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName
        } else {
            app.packageManager.getInstallerPackageName(app.packageName)
        }
    }.getOrNull()

    private fun queryColumn(context: Context, uri: String, column: String): String? = runCatching {
        context.applicationContext.contentResolver
            .query(Uri.parse(uri), null, null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
            }
    }.getOrNull()
}
