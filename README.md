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
Gradle assembly command first. The checked-in podspec defaults to `0.1.1` solely for
local validation and is not version-synchronized by the Release workflow. Publishing
versioned podspecs backed by each GitHub Release XCFramework is deferred.

To release Android and iOS in lockstep: tag this repo, then bump the submodule pointer
to that tag in both SDK repos.

## Releasing

Releases are cut from the **Release** workflow (`.github/workflows/release.yml`) — no
manual tagging or version bookkeeping required.

1. Go to **Actions → Release → Run workflow**.
2. Pick a **bump** type (`patch` / `minor` / `major`) and whether to open the Android
   and iOS bump PRs.
3. The workflow then:
   - verifies the code (spotless + JVM/Android + iOS simulator tests),
   - computes the next `vX.Y.Z` from the latest tag (e.g. latest `v0.1.0` + `minor`
     → `v0.2.0`; first release starts from `v0.0.0`),
   - creates the tag and a GitHub Release with auto-generated notes,
   - attaches `OneSignalKMP.xcframework.zip` and its SwiftPM checksum, and
   - (if selected) opens PRs in `OneSignal-Android-SDK` and `OneSignal-iOS-SDK` that
     re-point their submodule gitlinks to the new tag.

Review and merge both host SDK PRs to keep Android and iOS on the same KMP release.

### iOS

The iOS SDK pins this repository as a submodule, builds the static XCFramework from
that checkout, and links it internally into `OneSignalCore`. The optional `bump-ios`
release job opens a PR that advances the gitlink to the new tag. Each GitHub Release
also carries the packaged XCFramework and SwiftPM checksum.

### Required configuration (one-time)

The host bump PRs are opened by reusable workflows in the Android and iOS repositories
(`.github/workflows/bump-kmp-submodule.yml`). This repository calls them with the shared
org `GH_PUSH_TOKEN` used across the OneSignal SDK repos. The default `GITHUB_TOKEN`
cannot write to another repository.

To enable it, an org admin grants this repo access to the existing org secret:
**Org → Settings → Secrets and variables → Actions → `GH_PUSH_TOKEN` → Repository
access → add `OneSignal-KMP-SDK`.**

Without that access, run the workflow with **open_android_pr** and **open_ios_pr**
unchecked, then bump the host submodules manually.
