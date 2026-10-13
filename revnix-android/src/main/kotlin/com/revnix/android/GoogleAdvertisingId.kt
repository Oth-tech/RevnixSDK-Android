package com.revnix.android

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ZERO_ID = "00000000-0000-0000-0000-000000000000"

/**
 * Google Advertising ID source for [com.revnix.RevnixConfig.advertisingId].
 * Needs `com.google.android.gms:play-services-ads-identifier` and the
 * `com.google.android.gms.permission.AD_ID` permission in the app. Answers
 * null when the user opted out; throws when the id cannot be read.
 */
public fun googleAdvertisingId(context: Context): suspend () -> String? {
    val app = context.applicationContext
    return {
        withContext(Dispatchers.IO) {
            val info = AdvertisingIdClient.getAdvertisingIdInfo(app)
            info.id.takeUnless { info.isLimitAdTrackingEnabled || it == ZERO_ID }
        }
    }
}
