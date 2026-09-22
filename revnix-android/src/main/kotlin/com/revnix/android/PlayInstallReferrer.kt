package com.revnix.android

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.revnix.RevnixClient
import com.revnix.RevnixStorage

/**
 * Reads the Google Play install referrer and reports it through
 * [RevnixClient.handleInstallReferrer].
 *
 * Call it once at launch, next to [PlayBillingConnector.start]:
 *
 * ```kotlin
 * PlayInstallReferrer.collect(context, client)
 * ```
 *
 * Play returns the same referrer for the life of the install, so the result is
 * latched in [AndroidStorage] and every later launch costs one preference read
 * with no service binding at all.
 *
 * Never throws: a referrer that could not be read is worth another launch,
 * never a crash in the host app.
 */
public object PlayInstallReferrer {

    // Keyed by customer the way registerInstall keys its own beacon: logout()
    // mints a new id and reports a second install, and that one has to be able
    // to carry the referrer too or it lands as organic.
    private fun collectedKey(customerId: String) = "revnix.installReferrerCollected.$customerId"

    public fun collect(context: Context, client: RevnixClient, platform: String? = null) {
        val storage = AndroidStorage(context)
        val key = collectedKey(client.customerId())
        if (storage.get(key) != null) return

        // The server keeps the payload of whichever install report lands
        // first, and on a host that never calls registerInstall that is this
        // one.
        val appVersion = AndroidDeviceFacts.detect(context).appVersion

        // ponytail: no setup timeout — if Play accepts the bind but never
        // calls back, that one binding lives as long as the process. Add a
        // timeout if it ever shows up in an ANR trace.
        runCatching {
            val referrer = InstallReferrerClient.newBuilder(context.applicationContext).build()
            referrer.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    runCatching {
                        report(
                            responseCode,
                            { referrer.installReferrer.installReferrer },
                            client,
                            storage,
                            key,
                            platform,
                            appVersion,
                        )
                    }
                    runCatching { referrer.endConnection() }
                }

                // Play's own sample reconnects here; the next cold start is
                // the retry, and a reconnect loop would outlive the single
                // string we came for.
                override fun onInstallReferrerServiceDisconnected() {}
            })
        }
    }

    internal fun report(
        responseCode: Int,
        readReferrer: () -> String?,
        client: RevnixClient,
        storage: RevnixStorage,
        key: String,
        platform: String?,
        appVersion: String?,
    ) {
        when (responseCode) {
            InstallReferrerClient.InstallReferrerResponse.OK -> {
                // The details getter itself throws RemoteException on some
                // devices. That is a dead binder, not an answer, so the key
                // stays unset and the next cold start asks again. A referrer
                // that comes back blank IS an answer — an organic install —
                // and latches like any other.
                val details = runCatching(readReferrer)
                if (details.isFailure) return
                details.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { client.handleInstallReferrer(it, platform, appVersion) }
                // ponytail: latched on the attempt, not on delivery. A first
                // launch with no network — or a client the host closed before
                // this callback arrived, which drops the send silently — loses
                // the referrer for good, and the next-cold-start retry only
                // helps inside the server's 24h re-attribution window. Upgrade
                // when handleInstallReferrer can report delivery, the way
                // registerInstall already keys off a successful POST.
                storage.set(key, "1")
            }

            // No Play Store that can answer: either none is installed, or the
            // one that is predates the referrer API. Latched anyway — a Play
            // Store that later updates itself is never re-asked, which is the
            // price of not binding a service on every launch of a device that
            // will never have one.
            InstallReferrerClient.InstallReferrerResponse.FEATURE_NOT_SUPPORTED ->
                storage.set(key, "1")

            // SERVICE_DISCONNECTED, SERVICE_UNAVAILABLE, DEVELOPER_ERROR and
            // PERMISSION_ERROR: leave the key unset so the next cold start
            // tries again.
            else -> Unit
        }
    }
}
