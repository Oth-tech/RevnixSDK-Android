# Revnix Kotlin SDK

Native Android SDK for [Revnix](https://revnix.com) — Play Billing 8 purchase
glue plus the same resilience policy as `revnix-react` and `revnix-swift`.

- **Play Billing 8 native**, with auto-reconnect and the acknowledgement rule
  handled correctly (see below — this is the one most integrations get wrong).
- **Offline-correct by design.** A network blip keeps paying customers
  unlocked; a revoked key still locks them out.
- **One fetch per screen.** Soft TTL plus in-flight coalescing.

Requires Android minSdk 24 and JDK 17.

## Modules

| Module | What it is |
|---|---|
| `revnix-core` | Pure JVM client — entitlements, cache policy, purchases, retry queue. No Android dependency, so the resilience matrix runs as a plain JVM test task. |
| `revnix-android` | Play Billing 8 glue (`PlayBillingConnector`) and `AndroidStorage`. |

## Install

```kotlin
dependencies {
    implementation("com.revnix:revnix-android:0.1.0")
}
```

## Quick start

```kotlin
val client = RevnixClient(
    RevnixConfig(
        apiKey = "rvx_pk_live_…",
        baseUrl = "https://your-deployment.convex.site",
        storage = AndroidStorage(context),
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
binary, so `identify`/`alias` are deliberately not SDK methods — proxy them from
your server (see the docs recipe).

## Two Android-specific rules

**Acknowledgement happens after the claim is recorded.** The backend never
acknowledges; the SDK does. Google refunds any purchase not acknowledged within
3 days, so acknowledging *before* Revnix has the claim would trade a refund
window for a lost entitlement. `PlayBillingConnector` acknowledges once
`registerPurchase` has either succeeded or been durably queued. A *deliberate*
server refusal is not queued and not acknowledged — better that Google refunds
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
| Deliberate rejection (401/403/404/409) | **Always throw** — a cache must never defeat a kill-switch |
| Cached entitlement past `expiresAt` | Grace 3 days, then inactive |
| Cache age ceiling | 14 days → all inactive |
| Clock rolled back > 5 min | All inactive |
| Repeat reads | 30 s soft TTL + in-flight coalescing |
| Failed purchase registration | Persistent queue keyed `source:token:transactionId`, retryable failures only |
| Retry / poll delays | ±20% jitter; `Retry-After` honored |
| Swallowed background failures | `onDiagnostic` callback; count rides `X-Revnix-Bg-Failures` |

`waitForEntitlements(seq)` bypasses the soft TTL — the point of that poll is a
fresh ledger cursor — and resolves with the last read rather than throwing if
the ledger never catches up.

## Tests

```sh
./gradlew :revnix-core:test
```

26 tests cover the full resilience matrix against OkHttp's `MockWebServer`.

The Play Billing glue needs a real Play connection and is exercised with test
purchases in a sandbox app, not by this suite.

## Not in v1

- Paywall UI rendering — `resolvePlacement` ships the config, your app renders it.
- `identify` / `alias` — server-proxied by design.
- Amazon and other stores.
