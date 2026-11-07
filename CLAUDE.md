# RevnixSDK-Android

Native Android / Kotlin Multiplatform SDK for Revnix: Play Billing purchases, entitlements, paywalls, attribution. Talks to the backend in `../revnix-app` (Convex HTTP routes under `/v1`, base URL `https://<deployment>.convex.site`). Repo `Oth-tech/RevnixSDK-Android`, default branch `main`, MIT.

## Published artifacts
- Maven Central group `io.revnix`: `revnix-android` (Android apps; pulls core via `api`) and `revnix-core` (pure JVM). `revnix-kmp` is NOT published (no maven-publish plugin).
- Kotlin packages are `com.revnix` / `com.revnix.android` / `com.revnix.kmp` (group differs from package on purpose).
- Version appears in THREE places that must agree: `version = "…"` in root `build.gradle.kts`, `SDK_VERSION` in `revnix-core/…/RevnixClient.kt` and in `revnix-kmp/…/commonMain/…/RevnixClient.kt`. `scripts/release.sh` bumps all three; never hand-edit.
- Toolchain: Kotlin 2.2.21, AGP 8.13.2, Gradle 8.14.3 (wrapper), vanniktech maven-publish 0.33.0, all pinned inline (no `libs.versions.toml`). minSdk 24, compileSdk 36, JVM 17, `explicitApi()` in every module (every public declaration needs `public`).
- Consumed as a git submodule (`android/revnix-kotlin`) by `../RevnixSDK-Flutter` and `../RevnixSDK-Capacitor`, which compile its sources directly. They re-pin at their next release; never rewrite history here.

## Modules
- `revnix-core` (`com.revnix`): `RevnixClient` (~1950 lines, the public entry: `RevnixClient(config)`), `RevnixConfig`, `Models`, `Storage`, `DeviceFacts`, `RevnixError`, paywall model/selection/localization/blocks/geometry/background, `ImplicitPlacements`. OkHttp 4.12 + kotlinx-serialization + coroutines. No Android dependency, so tests run on plain JVM.
- `revnix-android` (`com.revnix.android`): `PlayBillingConnector` (Billing 8.3.0), `PlayInstallReferrer` / `InstallReferrers` (installreferrer 2.2 + Meta/Instagram/Huawei providers, see `<queries>` in `AndroidManifest.xml`), `GoogleAdvertisingId` (`compileOnly` ads-identifier; opt-in via `RevnixConfig.advertisingId`), `PlayIntegrity` (token source for `RevnixConfig.deviceIntegrity`), `AndroidStorage`, `AndroidLifecycle`, `AndroidDeviceFacts`, `ui/` (`RevnixPaywallView`, `PaywallBlockRenderer`). ProGuard in `revnix-android/consumer-rules.pro`.
- `revnix-kmp` (`com.revnix.kmp`): separate re-implementation of the client on Ktor 3.0.3 (targets jvm, androidTarget, iosX64/Arm64/SimulatorArm64). Subset only: entitlements, purchases + retry queue, `resolvePlacement`, `registerInstall`, `logPaywallShown`, `resolveDeepLink`. README lists the gaps.
- Tests: `revnix-core/src/test/kotlin/com/revnix/` (MockWebServer), `revnix-android/src/test/kotlin/com/revnix/android/`, `revnix-kmp/src/commonTest/`. JUnit 5 / kotlin.test.

## Wire contract
- `RevnixConfig(apiKey, baseUrl, storage, …)`: apiKey is a PUBLISHABLE key (`rvx_pk_live_` / `rvx_pk_test_`); secret keys never ship (identify/alias go through the customer's backend).
- Every request (`RevnixClient.request`): `Authorization: Bearer <apiKey>`, `X-Revnix-SDK: revnix-kotlin/<SDK_VERSION>`, optional `X-Revnix-Bg-Failures`; 10 s timeout; errors map to `RevnixError` (`isRetryable`, `Retry-After`).
- Endpoints (all in `RevnixClient.kt`): GET `/v1/customers/{id}/entitlements` (ECDSA-signed, pinned keys, cached: 30 s soft TTL, 14 d offline max), POST `/v1/purchases`, GET `/v1/placements/{key}/offering`, POST `/v1/placements/triggered`, POST `/v1/installs`, POST `/v1/attribution`, GET `/v1/customers/{id}/attribution`, POST `/v1/customers/{id}/attributes`, POST `/v1/paywalls/viewed|closed|events`, POST `/v1/events`, POST `/v1/ad-revenue`, POST `/v1/push-token`, GET `/v1/links/resolve`, GET `/v1/paywalls/preview/{token}`, GET `/v1/config`.
- Event queue: persisted via `RevnixStorage`, cap 1000, flush at 20 queued or 10 s, 100 per batch, 30 s hold after a retryable failure, disk write debounced 1 s. Failed purchases go to a separate retry queue (immediate persist, 30 d max age).
- A wire-shape change here needs the matching change in `../revnix-app/convex/` HTTP routes; the SDK ships inside customer apps, so stay backward compatible.

## Commands
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17        # or openjdk@21; set it explicitly, java_home -v lookups were unreliable here
./gradlew :revnix-core:test :revnix-android:testReleaseUnitTest   # what CI runs
./gradlew :revnix-android:assembleRelease                           # CI also runs this
./gradlew :revnix-kmp:jvmTest                                       # KMP is not tested in CI (iOS targets need macOS)
```
- Publish is CI-only: `./gradlew publishAndReleaseToMavenCentral --no-configuration-cache` with `MAVEN_CENTRAL_*` + `SIGNING_KEY*` secrets.
- No lint task (no ktlint/detekt).
- `/usr/bin/git` is blocked by the Xcode license prompt on this Mac; use `/Applications/GitHub Desktop.app/Contents/Resources/app/git/bin/git`.

## Git, CI, release
- `.github/workflows/ci.yml`: PRs to `main` + manual; ubuntu, Temurin 17, 20 min; runs the test + assembleRelease lines above.
- `.github/workflows/release.yml`: manual dispatch (`bump` auto|major|minor|patch, `version`, `app_sha`, `run_key`), dispatched by `../revnix-app/.github/workflows/release-sdks.yml` (job `revnix-android`). Tests on main → `scripts/release.sh` bumps version + CHANGELOG → PR `release/vX.Y.Z` merged with `--merge` → Maven Central publish → tag `vX.Y.Z` + GitHub release. Publish runs before the tag, so a failed publish leaves no tag and a re-run releases the untagged version on main.
- Every user-visible change gets a bullet under `## Unreleased` in `CHANGELOG.md` (`### Breaking` → major, `### Added` → minor, else patch). Never hand-bump or tag.
- Merge commits only into `main` (ruleset; `gh pr merge --merge`, squash is refused). Opposite of revnix-app, which squash-merges into `dev`.

## Gotchas
- Core stays on OkHttp; KMP forks transport to Ktor. A behavior change usually needs porting into BOTH `revnix-core/…/RevnixClient.kt` and `revnix-kmp/…/RevnixClient.kt`, or a conscious note that KMP lacks it.
- `revnix-android` publishes an empty javadoc jar by design (AGP's Dokka cannot read Java 17 sealed classes; see its `build.gradle.kts`).
- GAID and device integrity are opt-in (Play policy); never make them default.
- Acknowledge/consume rules live in `PlayBillingConnector` + `RevnixConfig.consumableProductIds`; a wrong change there silently refunds customers after 3 days.
