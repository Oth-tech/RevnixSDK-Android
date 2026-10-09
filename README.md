<p align="center">
  <a href="https://www.revnix.io"><img src="https://www.revnix.io/sdk/logo.png" width="360" alt="Revnix"></a>
</p>

<h1 align="center">Subscriptions, Paywalls and Attribution<br>for Your Android App</h1>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.revnix/revnix-android"><img src="https://img.shields.io/maven-central/v/io.revnix/revnix-android?color=2f6fe0" alt="Maven Central"></a>
  <a href="https://github.com/Oth-tech/RevnixSDK-Android/blob/main/LICENSE"><img src="https://img.shields.io/github/license/Oth-tech/RevnixSDK-Android?color=2f6fe0" alt="license"></a>
</p>

<p align="center">
  <a href="https://www.revnix.io"><b>Website</b></a> •
  <a href="https://www.revnix.io/docs/android"><b>Docs</b></a> •
  <a href="https://www.revnix.io/docs/android/setup"><b>API Reference</b></a>
</p>

![Revnix: subscriptions, paywalls and attribution for mobile apps](https://www.revnix.io/sdk/hero.png)

Revnix SDK makes in-app subscriptions, paywalls and attribution for Android fast and easy. One Gradle dependency buys through Play Billing 8, validates the purchase on the server and unlocks the entitlement, offline-correct.

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
| `revnix-android` | Play Billing 8 glue (`PlayBillingConnector`), install referrer readers (`InstallReferrers`, `PlayInstallReferrer`), `AndroidStorage`, `AndroidLifecycle`, `AndroidDeviceFacts` (app version, debuggable flag, reinstall `deviceKey`), and the `RevnixPaywallView` renderer. |
| `revnix-kmp` | Kotlin Multiplatform build of the same client: identical resilience policy, but only entitlements, purchases and the retry queue, `resolvePlacement`, `registerInstall`, `logPaywallShown` and `resolveDeepLink`. No deep links (`handleDeepLink`, `getLastDeepLink`, deferred links), no install referrer, no attribution read-back, no ad revenue, no `setAttributes`, no paywall close or interaction events, no implicit placements and no paywall view. Ktor transport instead of OkHttp. Targets `jvm`, `androidTarget`, `iosX64`, `iosArm64`, `iosSimulatorArm64`. Use it from a shared KMP module; use `revnix-core` + `revnix-android` from an Android-only app. |

Also public, for less common cases:

- `client.reportRenderDiagnostic(message)`: report a render fallback to `onDiagnostic` when a host renders a paywall design itself instead of using `RevnixPaywallView`.
- `RevnixLifecycleDisabled`: explicit off switch for foreground/background detection; launch-time moments still fire.
- `RevnixImplicitPlacement.fromKey(key)`: look up one of the six implicit placements by its server key.
- `REVNIX_DEFAULT_SESSION_TIMEOUT_MS`: the backgrounded duration that counts a return as a new session.
- `FileStorage` (`revnix-core`): a JSON-file `RevnixStorage` implementation for non-Android JVM hosts.
- `revnixMinorUnits(currency)`: minor units per major unit for a currency (100 for USD, 1 for JPY); divide `amountMinor` by it to get the display price.

## Install

```kotlin
dependencies {
    implementation("io.revnix:revnix-android:1.5.0")
}
```

A non-Android JVM host depends on `io.revnix:revnix-core` alone.

## Quick start

```kotlin
val client = RevnixClient(
    RevnixConfig(
        apiKey = "rvx_pk_live_…",
        baseUrl = "https://your-deployment.convex.site",
        storage = AndroidStorage(context),
        // Device facts sent with every placement resolve (REV-268): platform,
        // OS version and model are detected by default; this adds the app
        // version, debuggable flag and the deviceKey reinstall detection
        // needs. Pass null to send nothing.
        device = AndroidDeviceFacts.detect(context),
    )
)

// Any app-lifetime scope works; Revnix calls are suspend functions.
val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

// At app launch: connect to Play, replay owned purchases, drain the queue.
val billing = PlayBillingConnector.start(context, client)
appScope.launch { client.registerInstall(platform = "android") }

// Buy. The connector registers the purchase and acknowledges it.
billing.launchPurchase(activity, productDetails)

// Gate. Never throws; unknown/unreachable = locked.
appScope.launch { if (client.isEntitled("pro")) { /* … */ } }
```

Use the **publishable** key (`rvx_pk_…`) only. Secret keys must never ship in a
binary, so `identify`/`alias` are deliberately not SDK methods; proxy them from
your server (see the docs recipe).

## Paywalls and A/B tests

![Revnix paywall builder with a live device preview](https://www.revnix.io/sdk/react-native/paywalls.png)

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
appScope.launch {
    val resolution = client.resolvePlacement("paywall_main")
    resolution.experiment?.let { experiment ->
        analytics.log("paywall_shown", mapOf(
            "experiment" to experiment.key,
            "variant" to experiment.variantId,
        ))
    }
}
```

Assignment is sticky per customer and survives identity merges.

![Revnix A/B test results with a winner and credible intervals](https://www.revnix.io/sdk/react-native/ab-test.png)

### Targeting: `setAttributes`

A test can be narrowed to an audience: conditions over customer attributes.
`setAttributes` supplies the facts those conditions read:

```kotlin
appScope.launch {
    client.setAttributes(mapOf(
        "country" to "US",
        "app_version" to "4.2.0",
        "lifetime_orders" to 3,
        "stale_key" to null,   // null deletes the key
    ))
}
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
(default: the device's); `RevnixClient.setLocale(tag)` (`null` clears it)
forces that language for every paywall rendered after the call app-wide, but
the view's own `locale` still wins for that view.

### Reporting a display you render yourself

`RevnixPaywallView` reports all of the below for you. Rendering your own
paywall, the three calls are yours:

| Call | What it does |
|---|---|
| `logPaywallDisplay(placementKey, paywallId): String` | The impression beacon, returning the `viewId` it minted. Prefer it over `logPaywallShown` whenever you intend to report the close or an interaction: that id is what pairs the halves of one display. |
| `logPaywallClosed(viewId, placementKey, paywallId)` | Ends that display. Idempotent per view id, so a retry, a rotation or a double-dismiss cannot count two. Without it a funnel knows how many saw the paywall, not how many left without buying. |
| `logPaywallEvent(event, viewId, …)` | One of six interactions (`Selected`, `PurchaseStarted`, `PurchaseAbandoned`, `PurchaseFailed`, `Restore`, `Error`), i.e. what happened BETWEEN the display and the close. |

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
call site, and all are pure ledger history: over-reporting can skew a report,
never grant or revoke access.

### Ad revenue

Call `logAdRevenue` from AdMob's `OnPaidEventListener` or AppLovin MAX's
`onAdRevenuePaid`:

```kotlin
client.logAdRevenue(
    revenue = adValue.valueMicros / 1_000_000.0,
    currency = adValue.currencyCode,
    network = "admob",
    format = "banner",
)
```

It is fire-and-forget. `revenue` must be finite and > 0, otherwise nothing is
sent. Pass `eventId` (the mediation SDK's impression id) to make retries
idempotent. It appends `ad.revenue`, which feeds only the ROAS table on the
Links page.

### Custom events

![Revnix overview dashboard with revenue and MRR](https://www.revnix.io/sdk/react-native/analytics.png)

Call `track` for in-app events you want on the customer's ledger (level up,
tutorial complete, …). It is not for purchases: those stay on
`registerPurchase`:

```kotlin
client.track("level_up", properties = mapOf("level" to 5, "source" to "menu"))
```

It is fire-and-forget, like `logAdRevenue`. `event` must match
`^[a-z0-9_]{1,64}$`, otherwise nothing is sent. Property values may be
strings, numbers or booleans; anything else is dropped. Pass `eventId` to
make retries idempotent; otherwise the SDK generates one per call.

### MMP attribution

If you already run an MMP (Adjust, AppsFlyer, Singular, Branch, Kochava,
Tenjin, Airbridge), call `setAttribution` from its attribution callback so
Revnix credits revenue to the right network/campaign:

```kotlin
// Adjust's attribution callback
override fun onAttributionChanged(attribution: AdjustAttribution) {
    appScope.launch {
        client.setAttribution(
            provider = "adjust",
            network = attribution.network,
            campaign = attribution.campaign,
            adGroup = attribution.adgroup,
            creative = attribution.creative,
        )
    }
}

// AppsFlyer's onConversionDataSuccess
override fun onConversionDataSuccess(data: Map<String, Any>) {
    if (data["af_status"] == "Organic") return
    appScope.launch {
        client.setAttribution(
            provider = "appsflyer",
            network = data["media_source"] as String,
            campaign = data["campaign"] as? String,
            adGroup = data["af_adset"] as? String,
            creative = data["af_ad"] as? String,
        )
    }
}
```

It is fire-and-forget, like `logAdRevenue`.

### Uninstall measurement

Revnix measures uninstalls the way Adjust/AppsFlyer do: register the
device's push token, and once a day a silent push probes it; when FCM
reports the token dead, the customer gets an `app.uninstalled` event. Call
it from Firebase's token callbacks:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        appScope.launch { client.setPushToken(token) }
    }
}

// once at startup too, in case onNewToken doesn't fire this launch
appScope.launch {
    client.setPushToken(FirebaseMessaging.getInstance().token.await())
}
```

Fire-and-forget, like `setAttribution`: never throws, dedupes per
customer+token. Requires Firebase Cloud Messaging set up in the app; no
notification permission needed, the probe is a silent data-only message.
See [Uninstall measurement](https://revnix.io/docs/uninstall-measurement).

### Implicit placements

Six placements resolve without a `resolvePlacement` call: `app_install`,
`app_launch`, `session_start`, `deeplink_open`, `paywall_decline` and
`transaction_abandon`. Passing `onImplicitPaywall` to `RevnixConfig` turns
them on (off by default: no handler, no extra requests for the other five
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
    // session_start is built on comes from revnix-android, no androidx.lifecycle.
    lifecycle = AndroidLifecycle(app),
    onImplicitPaywall = { trigger ->
        // May run on any thread: post before touching views.
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

`onCreate` is the cold start (the link launched the closed app); `onNewIntent`
is a link arriving while the activity is already running (`singleTop` /
`singleTask`). A rotation or process restore recreates the activity with the
same intent, and reopening from Recents replays it; the `relaunch` check
keeps either from counting as a second open. (`intent.data` is deliberately
left set, not nulled out: the app's own router may still need to read it.)

A dashboard QR/link preview (`<scheme>://revnix-preview?revnix_preview=<token>`)
goes through the same `handleDeepLink` call (recognised by its own URL shape,
not by `deeplink_open` being configured) and is always handed to
`onImplicitPaywall`. Tell it apart from a real trigger with
`trigger.resolution.placementKey == REVNIX_PREVIEW_PLACEMENT_KEY` /
`trigger.resolution.preview`: `RevnixPaywallView` already refuses to invoke
`onPurchase` for one, showing "Purchases are disabled in preview." instead,
and no paywall analytics are sent for it.

When you bind it, pass `placementKey = trigger.resolution.placementKey`;
that marks the display as implicit and is what stops a `paywall_decline`
paywall from firing `paywall_decline` again. A close is a decline: never
report one for a display that ended in a purchase. `close()` retires the
foreground listener; `sessionTimeoutMs` (default 30 min) is the session
boundary.

### Deferred deep links

A click on a Revnix link sends Android to the store with the query string as
the install referrer. Reading that referrer is how the match is made, and the
SDK does it for you: call `InstallReferrers.collect(context, client)` once at
launch, next to `PlayBillingConnector.start`. It needs no Gradle dependency or
ProGuard rule from the host, and reports through
`client.handleInstallReferrer(referrer, source = …)`. That report matches
exactly, since the link is read straight from the referrer, and delivers to
`onDeferredDeepLink` at most once per install, off the main thread. Only a
link whose scheme matches the app's configured URL scheme is ever returned.
A plain `registerInstall` call carries no referrer and returns no deferred
link on Android. Wire it up:

```kotlin
val client = RevnixClient(RevnixConfig(
    apiKey = "rvx_pk_live_…", baseUrl = "https://….convex.site",
    storage = AndroidStorage(app),
    onDeferredDeepLink = { url, match ->
        mainHandler.post { router.open(url) }
    },
))

InstallReferrers.collect(app, client, facebookAppId = "1234567890")
```

`facebookAppId` is optional; pass it and Meta's own referrer provider
(Facebook, then Instagram) is read first. It answers only for an install a
Meta ad actually drove, while Play answers
`utm_source=google-play&utm_medium=organic` for every plain store install, so
store-first would score every Meta-driven install as organic. After Meta comes
the store (Huawei AppGallery when it installed the app, otherwise Google Play)
and last a preinstall referrer baked into the app manifest:

```xml
<meta-data android:name="revnix_preinstall_referrer"
    android:value="utm_source=oem&amp;utm_medium=preinstall" />
```

The first non-blank source wins, exactly one referrer is posted, and the
result is latched per customer so later launches cost one preference read.
`PlayInstallReferrer.collect(context, client)` is still there if you want
Google Play and nothing else.

Samsung, Xiaomi, Vivo and Huawei Ads are not read for you: each needs a
proprietary AAR from that vendor's own Maven repo, which would break the
Gradle build of every Play-only app. Read the string with the vendor's own SDK
and hand it over yourself. `source` accepts `play`, `huawei`, `samsung`,
`xiaomi`, `vivo`, `meta` and `preinstall`, and the server refuses anything
else:

```kotlin
client.handleInstallReferrer(referrerFromSamsungSdk, source = "samsung")
```

Route the URL yourself; optionally also pass it to `handleDeepLink` for
`deeplink_open` paywall rules.

An email service provider (Mailchimp, SendGrid) often rewrites a link
through its own click-tracking domain before the customer ever taps it. Call
`client.resolveDeepLink(url)` first to unwrap it. It returns the input
unchanged if the server cannot resolve it, or if the input is not itself an
http(s) URL, so the result can still be an http(s) URL when the chain could
not be unwrapped; check its scheme before routing. Then route the result and
hand it to `handleDeepLink`.

`client.getLastDeepLink()` returns the most recent link `handleDeepLink` was
given, or a delivered deferred link, as `LastDeepLink(url, receivedAt)`; null
if none has landed yet. Useful after a flow (login, onboarding) that swallowed
the original delivery and needs the link back. A dashboard preview link is
never recorded.

### Install attribution

![Revnix ROAS by channel report](https://www.revnix.io/sdk/react-native/attribution.png)

`client.getAttribution()` answers how this install was attributed:
`RevnixAttribution(installMatch, attributedAt, …)`, where `installMatch` is
`referrer`, `click`, `impression` or `organic`, alongside the campaign fields
(`source`, `medium`, `campaign`, `term`, `content`), the matched
`referrerSource` and `linkToken`. It returns null when the server has not
recorded an install yet (a normal cold-start race) or when the read fails,
and never throws.

Set `onAttribution` to be told instead of asking. It fires when the verdict
CHANGES, so a late Play referrer or a re-attribution can fire it more than
once; an unchanged verdict is not re-delivered. It is called off the main
thread, and only if you set it does the SDK fetch the verdict on its own
(after the install report and after `handleInstallReferrer`):

```kotlin
val client = RevnixClient(RevnixConfig(
    apiKey = "rvx_pk_live_…", baseUrl = "https://….convex.site",
    storage = AndroidStorage(app),
    onAttribution = { attribution ->
        analytics.setCampaign(attribution.campaign, attribution.installMatch)
    },
))
```

### Device integrity

Pass `deviceIntegrity = playIntegrity(context)` to have every install-related
call (`registerInstall`, `handleInstallReferrer`, `setAttribution`) carry a
Play Integrity classic token, so the server can verify the install came from
the genuine app on a genuine device:

```kotlin
val client = RevnixClient(RevnixConfig(
    apiKey = "rvx_pk_live_…", baseUrl = "https://….convex.site",
    storage = AndroidStorage(app),
    deviceIntegrity = playIntegrity(app),
))
```

The classic request is made at most once per customer id per process. The
first call to wait on it pays the cost (up to 10s on a cold first launch,
while Play services warm up), the rest share that same token, and the
server dedupes verification across the calls it rides on. Requires linking a
Cloud project to your app in Play Console → App integrity (the same project
as the service account given to Revnix), and raising the classic-request
quota if your install volume exceeds the 10k/day default. Turn on "Require
device integrity" in Revnix Settings → Fraud prevention to enforce it
server-side. A failed or timed-out check never blocks the install report; it
just omits the token.

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

When Google Play is connected, the server checks the `purchaseToken` with
Play during registration, so the claim is store-verified and `provisional`
stays `false`. Only if Play cannot be reached at that moment is the claim
recorded `provisional = true` until the server corroborates it via RTDN /
`subscriptionsv2.get`. A token Play rejects, or one for a different product,
is refused with a 403 and left unacknowledged. `provisional` is surfaced on
`RegisterPurchaseResult`.

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

`waitForEntitlements(seq)` reads entitlements (the first read may come from
the soft TTL snapshot); the polls after that first read bypass the soft TTL
(the point of a poll is a fresh ledger cursor), and resolve with the last
read rather than throwing if the ledger never catches up.

`isEntitled` never throws: on a transient failure `entitlements()` serves the
offline-policy cache (flagged `stale`); a deliberate rejection
(401/403/404/409), an unknown id, or a failed read with no cache answers
`false`.

## Tests

```sh
./gradlew :revnix-core:test     # the resilience matrix plus the paywall model
./gradlew :revnix-kmp:allTests  # the same matrix, Ktor transport
```

`revnix-core` runs against OkHttp's `MockWebServer`; `revnix-kmp` runs the
ported matrix on every configured target.

The Play Billing glue needs a real Play connection and is exercised with test
purchases in a sandbox app, not by either suite.

## Not in v1

- `identify` / `alias`: server-proxied by design.
- Amazon and other stores.
