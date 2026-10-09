# Changelog

## Unreleased

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
