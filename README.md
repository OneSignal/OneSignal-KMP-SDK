# OneSignal-KMP-SDK

Internal Kotlin Multiplatform code shared between the OneSignal Android and iOS SDKs.
This repository is the single source of truth for shared logic; each platform SDK
consumes it as a git submodule pinned to a tag so both release in parity.

This is **not** published to any public artifact repository. It is consumed only by
the internal OneSignal Android and iOS SDK teams.

## Modules

- `:kmp` — the single umbrella module for all shared multiplatform code, published as
  `com.onesignal:kmp`. New shared features land as sibling packages inside this one
  module (so iOS links a single framework) rather than as new Gradle modules. Today it
  contains:
  - `com.onesignal.logger` — platform-agnostic logging/telemetry pipeline
    (OTLP/protobuf encoder, batch processor, crash capture + upload) with no platform,
    networking, or storage coupling. All logic lives in `commonMain`; platform values
    are injected via interfaces (`ILoggerPlatformProvider`, `ILogHttpSender`,
    `ILogFileStore`, `ILogger`) that each SDK implements in its own repo.

## Targets

`androidTarget`, `iosX64`, `iosArm64`, `iosSimulatorArm64`.

The iOS targets are packaged as one static `OneSignalKMP.xcframework`. It contains
an `ios-arm64` device slice and an `ios-arm64_x86_64-simulator` slice.

## Build & test

```bash
./gradlew :kmp:testDebugUnitTest        # JVM/Android unit tests
./gradlew :kmp:iosSimulatorArm64Test    # iOS simulator (Kotlin/Native) tests
./gradlew :kmp:verifyOneSignalKMPXCFramework # release XCFramework + API smoke check
./gradlew spotlessCheck                 # formatting
```

CI (`.github/workflows/ci.yml`) runs all of the above on every push and PR using a
macOS runner (required for the iOS simulator tests).

## How the SDKs consume this repo

Each SDK pins this repo as a git submodule. Android includes `:kmp` as a Gradle source
project. iOS builds the submodule into `OneSignalKMP.xcframework` and links that binary
through Swift Package Manager or CocoaPods.

### Swift Package Manager

Build the release XCFramework before resolving the local package:

```bash
./gradlew :kmp:assembleOneSignalKMPReleaseXCFramework
```

Then add this checkout as a local package in Xcode, or reference it from the host
package:

```swift
.package(path: "../OneSignal-KMP-SDK")
```

The package product and Swift module are both named `OneSignalKMP`. `Package.swift`
points to the generated framework under `kmp/build/XCFrameworks/release`.

### CocoaPods

Build the release XCFramework, then reference the checkout from the host Podfile:

```ruby
pod 'OneSignalKMP', path: '../OneSignal-KMP-SDK'
```

`OneSignalKMP.podspec` vendors the generated framework into the host target. Its
`prepare_command` builds the artifact for downloaded/tagged pods; CocoaPods does not
guarantee that command runs for a local `path` pod, so local consumers must run the
Gradle assembly command first.

To release Android and iOS in lockstep: tag this repo, then bump the submodule pointer
to that tag in both SDK repos.

## Releasing

Releases are cut from the **Release** workflow (`.github/workflows/release.yml`) — no
manual tagging or version bookkeeping required.

1. Go to **Actions → Release → Run workflow**.
2. Pick a **bump** type (`patch` / `minor` / `major`) and whether to open the Android
   bump PR.
3. The workflow then:
   - verifies the code (spotless + JVM/Android + iOS simulator tests),
   - computes the next `vX.Y.Z` from the latest tag (e.g. latest `v0.1.0` + `minor`
     → `v0.2.0`; first release starts from `v0.0.0`),
   - creates the tag and a GitHub Release with auto-generated notes,
   - attaches `OneSignalKMP.xcframework.zip` and its SwiftPM checksum, and
   - (if selected) opens a PR in `OneSignal-Android-SDK` that re-points the submodule
     gitlink to the new tag.

Review and merge the Android PR to complete that side of the release.

### iOS

The release XCFramework is available on each GitHub Release for a host repository to
vendor as a remote SwiftPM binary target. The checked-in `Package.swift` and podspec
support submodule/path-based consumption while the iOS SDK integration and automated
`bump-ios` release job are implemented separately.

### Required configuration (one-time)

The Android bump PR is opened by a **reusable workflow in the Android repo**
(`.github/workflows/bump-kmp-submodule.yml`), which this workflow calls and passes the
shared org push token to — the same `GH_PUSH_TOKEN` used across the other OneSignal SDK
repos (`sdk-shared`, the wrapper SDKs, etc.). No dedicated GitHub App or new secret is
created; the default `GITHUB_TOKEN` cannot write to another repo, but `GH_PUSH_TOKEN`
can.

To enable it, an org admin grants this repo access to the existing org secret:
**Org → Settings → Secrets and variables → Actions → `GH_PUSH_TOKEN` → Repository
access → add `OneSignal-KMP-SDK`.**

Without that access, run the workflow with **open_android_pr** unchecked to tag +
release only, then bump the Android submodule manually.
