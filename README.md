# Revnix Kotlin SDK

Native Android SDK for [Revnix](https://revnix.io), Play Billing 8 purchase
glue plus the same resilience policy as `revnix-react` and `revnix-swift`.

- **Play Billing 8 native**, with auto-reconnect and the acknowledgement rule
  handled correctly (see below; this is the one most integrations get wrong).
- **Offline-correct by design.** A network blip keeps paying customers
  unlocked; a revoked key still locks them out.
- **One fetch per screen.** Soft TTL plus in-flight coalescing.

Requires Android minSdk 24 and JDK 17.

## Modules

| Module | What it is |
|---|---|
| `revnix-core` | Pure JVM client: entitlements, cache policy, purchases, retry queue. No Android dependency, so the resilience matrix runs as a plain JVM test task. |
| `revnix-android` | Play Billing 8 glue (`PlayBillingConnector`), `AndroidStorage`, and the `RevnixPaywallView` paywall renderer. |
| `revnix-kmp` | Kotlin Multiplatform build of the same client: identical resilience policy (but no `setAttributes`, `logPaywallDisplay`/`logPaywallClosed`/`logPaywallEvent`, implicit placements or paywall view yet), Ktor transport instead of OkHttp. Targets `jvm`, `androidTarget`, `iosX64`, `iosArm64`, `iosSimulatorArm64`. Use it from a shared KMP module; use `revnix-core` + `revnix-android` from an Android-only app. |

## Install

> Not yet published to Maven Central; build from source for now (see
> [Distribution status](#distribution-status)).

```kotlin
dependencies {
    implementation("com.revnix:revnix-android:0.2.0")
}
```

## Quick start

```kotlin
val client = RevnixClient(
    RevnixConfig(
        apiKey = "rvx_pk_live_…",
        baseUrl = "https://your-deployment.convex.site",
        storage = AndroidStorage(context),
        // Device facts sent with every placement resolve (REV-268): platform,
        // OS version and model are detected by default; this adds the app
        // version and debuggable flag. Pass null to send nothing.
        device = AndroidDeviceFacts.detect(context),
    )
)

// At app launch: connect to Play, replay owned purchases, drain the queue.
val billing = PlayBillingConnector.start(context, client)
client.registerInstall(platform = "android")

// Buy. The connector registers the purchase and acknowledges it.
billing.launchPurchase(activity, productDetails)

// Gate. Never throws; unknown/unreachable = locked.
if (client.isEntitled("pro")) { /* … */ }
```

Use the **publishable** key (`rvx_pk_…`) only. Secret keys must never ship in a
binary, so `identify`/`alias` are deliberately not SDK methods; proxy them from
your server (see the docs recipe).

## Paywalls and A/B tests

`resolvePlacement` returns the published offering plus a typed `PaywallConfig`:
nine layouts in `template`: `focus`, `feature-list`, `minimal`, `hero`,
`timeline`, `plans`, `feature-grid`, `offer`, `reveal`, a light/dark `mode`,
and optional `review` (stars, quote, author, count) and `offer` (anchor price,
urgency line) blocks. `template` is a `String` on purpose so a config published
with a future layout still deserializes rather than failing the resolve.

The resolve sends the customer id, so a running A/B test serves that
customer's variant. The `offering` and `paywall` you get back are *already*
the variant's; render them as-is. `experiment` is attribution metadata, null
when no running test covers the placement:

```kotlin
val resolution = client.resolvePlacement("paywall_main")
resolution.experiment?.let { experiment ->
    analytics.log("paywall_shown", mapOf(
        "experiment" to experiment.key,
        "variant" to experiment.variantId,
    ))
}
```

Assignment is sticky per customer and survives identity merges.

### Targeting: `setAttributes`

A test can be narrowed to an audience: conditions over customer attributes.
`setAttributes` supplies the facts those conditions read:

```kotlin
client.setAttributes(mapOf(
    "country" to "US",
    "app_version" to "4.2.0",
    "lifetime_orders" to 3,
    "stale_key" to null,   // null deletes the key
))
```

Values must be `String`, `Number`, or `null`; anything else throws
`IllegalArgumentException` before a request is made. This suspends until the
write completes rather than firing and forgetting, because the next
`resolvePlacement` may depend on it. Set an audience's attributes *before* the
first resolve on a covered placement; eligibility is checked at that resolve.
`email` and `username` are reserved (secret key, from your server), and an
attribute your backend already set cannot be changed from a device; both
reject the whole batch rather than applying part of it.

### Paywall UI: `RevnixPaywallView`

`revnix-android` ships a ready renderer for that config, `com.revnix.android.ui.RevnixPaywallView`, a port of `revnix-react`'s
`RevnixPaywall` kept in visual lockstep with the dashboard's paywall-builder
preview. It is built from programmatic classic Views (zero added
dependencies), so it also works inside Compose via `AndroidView` interop.
Prices come from Play Billing, never from the config, so the display cannot
disagree with the charge:

```kotlin
val resolution = client.resolvePlacement("paywall_main")
val paywall = resolution.paywall ?: return

val view = RevnixPaywallView(context)
view.bind(
    config = paywall.config,
    packages = listOf(
        // priceLabel MUST be the store's localized price: a ProductDetails
        // pricing phase's (or one-time offer's) formattedPrice.
        RevnixPaywallPackage("monthly", "Monthly", monthlyPrice),
        RevnixPaywallPackage("annual", "Annual", annualPrice),
    ),
    onPurchase = { packageId -> billing.launchPurchase(activity, detailsFor(packageId)) },
    onRestore = { /* replay owned purchases */ },
    client = client,                  // reports one paywall.viewed per bind
    placementKey = "paywall_main",
    paywallId = paywall.paywallId,
)
container.addView(view)

view.loading = true                   // spinner in the CTA while purchasing
```

All nine `template` layouts render (unknown future layouts fall back to the
classic structure), `mode` picks the dark/light palette, and a
`RevnixPaywallThemeOverride` restyles individual colors on top. A config that
carries a designed paywall (`blocks`, from the dashboard's block builder)
renders that instead, with the template as the fallback. Footer
Terms/Privacy links prefer your `onTerms`/`onPrivacy` handlers and fall back
to opening the config's URLs. Pass `onClose` to draw a close button: the view
calls you, you perform the dismissal, and with `client` set it reports
`paywall.closed`. The `locale` property picks a designed paywall's language
(default: the device's).

### Reporting a display you render yourself

`RevnixPaywallView` reports all of the below for you. Rendering your own
paywall, the three calls are yours:

| Call | What it does |
|---|---|
| `logPaywallDisplay(placementKey, paywallId): String` | The impression beacon, returning the `viewId` it minted. Prefer it over `logPaywallShown` whenever you intend to report the close or an interaction — that id is what pairs the halves of one display. |
| `logPaywallClosed(viewId, placementKey, paywallId)` | Ends that display. Idempotent per view id, so a retry, a rotation or a double-dismiss cannot count two. Without it a funnel knows how many saw the paywall, not how many left without buying. |
| `logPaywallEvent(event, viewId, …)` | One of six interactions — `Selected`, `PurchaseStarted`, `PurchaseAbandoned`, `PurchaseFailed`, `Restore`, `Error` — i.e. what happened BETWEEN the display and the close. |

The purchase **outcome** is always yours, even with the built-in renderer:
your app makes the Play Billing call, so only your app sees whether the sheet
was cancelled or the card was declined. `PlayBillingConnector` keeps its
`PurchasesUpdatedListener` to itself, so this needs a flow launched by your
own `BillingClient`.

```kotlin
val viewId = client.logPaywallDisplay(placementKey, paywall.paywallId)

when (result.responseCode) {
    BillingResponseCode.USER_CANCELED ->
        client.logPaywallEvent(RevnixPaywallEvent.PurchaseAbandoned, viewId, productId = productId)
    BillingResponseCode.OK -> Unit
    else ->
        client.logPaywallEvent(RevnixPaywallEvent.PurchaseFailed, viewId, productId = productId)
}

client.logPaywallClosed(viewId, placementKey, paywall.paywallId)
```

All of these are fire-and-forget: failures go to `onDiagnostic`, never to your
call site, and all are pure ledger history — over-reporting can skew a report,
never grant or revoke access.

### Implicit placements

Six placements resolve without a `resolvePlacement` call: `app_install`,
`app_launch`, `session_start`, `deeplink_open`, `paywall_decline` and
`transaction_abandon`. Passing `onImplicitPaywall` to `RevnixConfig` turns
them on (off by default — no handler, no extra requests for the other five
moments, though `handleDeepLink` always reports the link it is handed); the
SDK then asks `GET /v1/config` once and fires only for the moments the
dashboard configured. `implicitPlacements = false` turns off implicit
paywalls even with a handler set, but does not stop `handleDeepLink`'s
report.

```kotlin
val client = RevnixClient(RevnixConfig(
    apiKey = "rvx_pk_live_…", baseUrl = "https://….convex.site",
    storage = AndroidStorage(app),
    // revnix-core has no Android dependency, so the foreground source that
    // session_start is built on comes from revnix-android — no androidx.lifecycle.
    lifecycle = AndroidLifecycle(app),
    onImplicitPaywall = { trigger ->
        // Off the main thread — post before touching views.
        mainHandler.post { showPaywall(trigger.resolution) }
    },
))

// deeplink_open is the one moment the SDK cannot see itself:
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val relaunch = savedInstanceState != null ||
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (!relaunch) reportDeepLink(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    reportDeepLink(intent)
}

private fun reportDeepLink(intent: Intent) {
    val url = intent.data ?: return
    lifecycleScope.launch { client.handleDeepLink(url.toString()) }
}
```

`onCreate` is the cold start — the link launched the closed app; `onNewIntent`
is a link arriving while the activity is already running (`singleTop` /
`singleTask`). A rotation or process restore recreates the activity with the
same intent, and reopening from Recents replays it — the `relaunch` check
keeps either from counting as a second open. (`intent.data` is deliberately
left set, not nulled out: the app's own router may still need to read it.)

A dashboard QR/link preview (`<scheme>://revnix-preview?revnix_preview=<token>`)
goes through the same `handleDeepLink` call — recognised by its own URL shape,
not by `deeplink_open` being configured — and is always handed to
`onImplicitPaywall`. Tell it apart from a real trigger with
`trigger.resolution.placementKey == REVNIX_PREVIEW_PLACEMENT_KEY` /
`trigger.resolution.preview`: `RevnixPaywallView` already refuses to invoke
`onPurchase` for one, showing "Purchases are disabled in preview." instead,
and no paywall analytics are sent for it.

When you bind it, pass `placementKey = trigger.resolution.placementKey` —
that marks the display as implicit and is what stops a `paywall_decline`
paywall from firing `paywall_decline` again. A close is a decline: never
report one for a display that ended in a purchase. `close()` retires the
foreground listener; `sessionTimeoutMs` (default 30 min) is the session
boundary.

### Deferred deep links

A click on a Revnix link sends Android to Google Play with the query string
as the install referrer, so `registerInstall` can come back with the link
that install matched — exact, since it's read straight from that referrer.
Only a link whose scheme matches the app's configured URL scheme is ever
returned. The same link can also arrive later through the Play Install
Referrer service: read it with
`com.android.installreferrer:installreferrer` and hand the raw string to
`client.handleInstallReferrer(referrer)`. Either path delivers to
`onDeferredDeepLink` at most once per install, off the main thread:

```kotlin
val client = RevnixClient(RevnixConfig(
    apiKey = "rvx_pk_live_…", baseUrl = "https://….convex.site",
    storage = AndroidStorage(app),
    onDeferredDeepLink = { url, match ->
        mainHandler.post { router.open(url) }
    },
))

val referrerClient = InstallReferrerClient.newBuilder(app).build()
referrerClient.startConnection(object : InstallReferrerStateListener {
    override fun onInstallReferrerSetupFinished(responseCode: Int) {
        if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
            client.handleInstallReferrer(referrerClient.installReferrer.installReferrer)
        }
        referrerClient.endConnection()
    }
    override fun onInstallReferrerServiceDisconnected() {}
})
```

Route the URL yourself; optionally also pass it to `handleDeepLink` for
`deeplink_open` paywall rules.

## Two Android-specific rules

**Acknowledgement happens after the claim is recorded.** The backend never
acknowledges; the SDK does. Google refunds any purchase not acknowledged within
3 days, so acknowledging *before* Revnix has the claim would trade a refund
window for a lost entitlement. `PlayBillingConnector` acknowledges once
`registerPurchase` has either succeeded or been durably queued. A *deliberate*
server refusal is not queued and not acknowledged; better that Google refunds
it than that the customer is stranded having paid.

**`transactionId` is the purchaseToken.** Google has no separate transaction
identifier; the token is the stable key the backend dedupes on.

`obfuscatedAccountId` is set to the Revnix customer id on the billing flow, so
server-to-server RTDN self-identifies.

Google device claims cannot carry store-side proof, so expect
`provisional = true` on store-connected tenants until the server corroborates
via RTDN / `subscriptionsv2.get`. It is surfaced on `RegisterPurchaseResult`.

## Resilience policy

A product contract, not an implementation detail. `revnix-react`'s
`resilience.test.ts` is the spec; every case is ported to
`revnix-core/src/test/kotlin/com/revnix/RevnixClientTest.kt` and must stay in
agreement with it and with the Swift port.

| Behavior | Rule |
|---|---|
| Entitlement reads | Network-first |
| Transient failure (offline, timeout, 429, 5xx, non-JSON 200) | Serve cache, `stale = true` |
| Deliberate rejection (401/403/404/409) | **Always throw**: a cache must never defeat a kill-switch |
| Cached entitlement past `expiresAt` | Grace 3 days, then inactive |
| Cache age ceiling | 14 days → all inactive |
| Clock rolled back > 5 min | All inactive |
| Repeat reads | 30 s soft TTL + in-flight coalescing |
| Failed purchase registration | Persistent queue keyed `source:token:transactionId`, retryable failures only |
| Retry / poll delays | ±20% jitter; `Retry-After` honored |
| Swallowed background failures | `onDiagnostic` callback; count rides `X-Revnix-Bg-Failures` |

`waitForEntitlements(seq)` bypasses the soft TTL (the point of that poll is a
fresh ledger cursor), and resolves with the last read rather than throwing if
the ledger never catches up.

`isEntitled` never throws: on a transient failure `entitlements()` serves the
offline-policy cache (flagged `stale`); a deliberate rejection
(401/403/404/409), an unknown id, or a failed read with no cache answers
`false`.

## Tests

```sh
./gradlew :revnix-core:test     # 163 tests, the resilience matrix plus the paywall model
./gradlew :revnix-kmp:allTests  # 39 tests, the same matrix, Ktor transport
```

`revnix-core` runs against OkHttp's `MockWebServer`; `revnix-kmp` runs the
ported matrix on every configured target.

The Play Billing glue needs a real Play connection and is exercised with test
purchases in a sandbox app, not by either suite.

## Not in v1

- `identify` / `alias`: server-proxied by design.
- Amazon and other stores.

## Distribution status

**Not yet published.** `com.revnix:revnix-android:0.2.0` is the intended
coordinate, but nothing is on Maven Central yet, so that dependency will not
resolve. Until it ships, apps integrate over the
[REST API](https://revnix.io/docs/rest-api), the same `/v1` contract this SDK
speaks, so migrating later does not change the backend integration.
