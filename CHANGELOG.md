# Changelog

## Unreleased

### Changed

- Entitlement cache is signed. The server signs each `GET /v1/customers/{customerId}/entitlements` 200 (ECDSA P-256 / SHA-256, `Revnix-Signed-At` and `Revnix-Signature` headers, bound to the API key and customer id). The SDK pins Revnix's public keys and only trusts a cached snapshot whose signature verifies for its own API key and customer; a tampered, legacy or foreign snapshot is treated as no cache. Cache age comes from the signed server time, so editing the stored time cannot extend the 30 s soft TTL or the 14-day offline window. A 200 without signature headers is returned but not cached, and drops an older cached entry. After upgrading, the first offline launch has no cached entitlements until one online read, and an offline cache needs the server to sign. This stops edits to the cache file, not a user who patches the app binary on a rooted or jailbroken device.
- Queued analytics events are kept in memory and written to disk at most once a second, immediately when the app goes to the background or `close()` is called and after each flush, instead of rewriting the whole storage file on every event. A crash within that second can lose those events. The purchase retry queue is still written immediately.

## 1.9.0 (2026-10-22)

### Added

- `RevnixClient.flags()`: the feature flags set in Settings → Flags in the dashboard, read once per launch from the same `/v1/config` request as implicit placements. Empty when none are set or the request fails.
- `RevnixClient.identify(userId)`: make the device act as an identified customer. Your backend still merges history with `POST /v1/identify` and a secret key. Blank or over-200-character ids are ignored.
- `PlacementResolution.decisionId`, and optional `decisionId`, `priceShown`, `screenIndex`, `blockId`, `durationMs` on `logPaywallDisplay`, `logPaywallClosed` and `logPaywallEvent`; `RegisterPurchaseInput.decisionId`. `RevnixPaywallView.bind` takes `decisionId` and reports it with dwell time on close. `PlayBillingConnector.purchase` takes an optional `decisionId` and sends it with the purchase.
- `RevnixConfig.consumableProductIds`: Play purchases of these products are consumed after Revnix records them, so they can be bought again. If the server is unreachable the purchase is acknowledged and queued, and consumed on a later restore. Purchases acknowledged by older versions are consumed on the next restore once listed. If consume fails, the SDK acknowledges instead and reports a diagnostic.
- Buying a listed consumable that is still owned throws `BillingException` with `ITEM_ALREADY_OWNED` (retryable) after restoring it.

### Changed

- A server 401 or 404 (endpoint not found, e.g. wrong baseUrl) on a purchase is now handled like a network failure: acknowledged, queued and retried, so Google does not refund a real purchase over a developer config mistake. 403, 409 and other 4xx stay unacknowledged.
- Queued purchases are retried as the customer who bought them, even after logout.
- `handleDeepLink` reports only scheme, host, path and known attribution query params (utm_*, campaign_id, pid, gclid, gbraid, wbraid, ttclid, sccid, twclid, revnix_link, web_id and similar). Other query params, the fragment and credentials are dropped before upload. `resolveDeepLink` still sends the full URL.

### Fixed

- Minting the anonymous customer id is thread-safe; a logout during an in-flight purchase is no longer overwritten.

## 1.8.0 (2026-10-14)

### Added

- Paywall blocks: onboarding `screens` shown before the paywall, with `next` / `back` actions on text, image and button blocks and progress dots while an onboarding screen is showing.

## 1.7.0 (2026-10-13)
## 1.7.0 (2026-10-13)

### Added

- `advertisingId = googleAdvertisingId(context)` stores the Google Advertising ID on the customer as `gps_adid` after `registerInstall`, resent only when it changes and deleted after an opt-out; needs `com.google.android.gms:play-services-ads-identifier` and the `com.google.android.gms.permission.AD_ID` permission in the app; without them, or after an opt-out, nothing is stored.
- Paywall blocks: `timer` (countdown to a date or from when the paywall opens), `video` (muted looping clip with a poster), `toggle` (free-trial switch that flips the selected package) and the card `carousel` layout (swipeable pages with dots and optional auto-advance).

## 1.6.0 (2026-10-12)
## 1.6.0 (2026-10-12)
## 1.6.0 (2026-10-12)
## 1.6.0 (2026-10-12)

### Added

- `flushEvents()` sends queued `track` and paywall interaction events now; `preload(placementKeys)` warms the placement cache, and `RevnixClient.preload(context, placementKeys)` in `revnix-android` also downloads the paywalls' images to disk.

### Changed

- `track` and `logPaywallEvent` queue events on device and send them in batches of up to 100 (at 20 queued, 10 s after an event, on app background, and after launch), so offline events are no longer lost. The queue holds at most 1000 events.
- `resolvePlacement` answers from a cache up to 24 h old for the same customer and refreshes it in the background.
- Paywall images are cached on disk under the app's cache directory.
- Entitlements with a cached snapshot wait at most 3 s for the network; HTTP connects time out after 5 s.
- `AndroidStorage` and `FileStorage` no longer read from disk in their constructors.
- The purchase retry queue holds at most 100 purchases and drops ones older than 30 days.

## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)
## 1.5.0 (2026-10-09)

### Added

- First Maven Central release under `io.revnix`: `io.revnix:revnix-android` and `io.revnix:revnix-core`. `revnix-kmp` is not published yet.
- `PlayBillingConnector` buys, registers and acknowledges Play Billing 8 purchases; `purchase(activity, productId)` resolves with the Revnix registration and throws `BillingException` / `ProductNotFoundException`.
- Offline-correct entitlements: `isEntitled` and `entitlements()` serve a cached answer through a network blip, while a revoked key still locks the customer out.
- `resolvePlacement` returns the published offering and typed paywall config, already the customer's A/B test variant, with `experiment` metadata.
- `RevnixPaywallView` renders all nine paywall templates and dashboard block designs natively, with prices from Play Billing and an optional close button.
- `RevnixClient.setLocale(tag)` forces the paywall language app-wide, including the localized Restore, Terms and Privacy footer labels.
- `setAttributes` supplies the customer attributes an A/B test audience targets.
- Six implicit placements (`app_install`, `app_launch`, `session_start`, `deeplink_open`, `paywall_decline`, `transaction_abandon`) through `onImplicitPaywall`.
- Paywall analytics: `logPaywallDisplay`, `logPaywallClosed` and `logPaywallEvent` report a self-rendered paywall's view, close and interactions.
- `handleDeepLink` reports every opened link for attribution and opens dashboard QR previews without charging.
- Deferred deep links: `InstallReferrers.collect` reads the Meta, Huawei AppGallery, Google Play and preinstall referrers and delivers the matched link to `onDeferredDeepLink` once per install.
- `resolveDeepLink` unwraps links rewritten by email click trackers, and `getLastDeepLink` returns the last link received.
- `getAttribution()` and `onAttribution` return the install-attribution verdict and campaign.
- `setAttribution` forwards Adjust, AppsFlyer, Singular, Branch, Kochava, Tenjin and Airbridge attribution callbacks.
- `logAdRevenue` reports impression-level ad revenue from AdMob or AppLovin MAX paid callbacks.
- `track` reports custom in-app events to the customer's ledger.
- `setPushToken` registers the FCM token for uninstall measurement.
- `AndroidDeviceFacts` sends app version, debuggable flag and a reinstall `deviceKey` with install reports, so Auto Backup reinstalls are counted.
- `session_start` carries `previousSessionMs`, the length of the previous session.
- `deviceIntegrity = playIntegrity(context)` attaches a Play Integrity token to install calls for the server's fraud prevention checks.

### Changed

- Versioning now follows the Revnix release train: this jumps from 0.3.0 to 1.5.0 to match the app and the sibling SDKs, no API changes.
