# OneSignal-KMP-SDK

Internal Kotlin Multiplatform code shared between the OneSignal Android and iOS SDKs.
This repository is the single source of truth for shared logic; each platform SDK
consumes it as a git submodule pinned to a tag so both release in parity.

This is **not** published to any public artifact repository. It is consumed only by
the internal OneSignal Android and iOS SDK teams.

## Modules

- `:logger` — platform-agnostic logging/telemetry pipeline (OTLP/protobuf encoder,
  batch processor, crash capture + upload) with no platform, networking, or storage
  coupling. All logic lives in `commonMain`; platform values are injected via
  interfaces (`ILoggerPlatformProvider`, `ILogHttpSender`, `ILogFileStore`,
  `ILogger`) that each SDK implements in its own repo.

## Targets

`androidTarget`, `iosX64`, `iosArm64`, `iosSimulatorArm64`.

## Build & test

```bash
./gradlew :logger:testDebugUnitTest        # JVM/Android unit tests
./gradlew :logger:iosSimulatorArm64Test    # iOS simulator (Kotlin/Native) tests
./gradlew spotlessCheck                     # formatting
```

CI (`.github/workflows/ci.yml`) runs all of the above on every push and PR using a
macOS runner (required for the iOS simulator tests).

## How the SDKs consume this repo

Each SDK adds this repo as a git submodule and includes `:logger` as a Gradle source
project (no binary artifact). The module's `build.gradle` is written to resolve under
both this repo's root and the host SDK root, so a single source file works in both
contexts.

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
   - creates the tag and a GitHub Release with auto-generated notes, and
   - (if selected) opens a PR in `OneSignal-Android-SDK` that re-points the submodule
     gitlink to the new tag.

Review and merge the Android PR to complete that side of the release.

### iOS

iOS bumping is deferred until this repo builds and publishes an XCFramework. Once the
packaging block + publish step exist, a `bump-ios` job mirroring `bump-android` will
open a version-bump PR against the iOS SDK's SPM/CocoaPods manifest.

### Required configuration (one-time)

The Android bump PR is opened by a **GitHub App** (the default `GITHUB_TOKEN` cannot
write to other repositories). Create an app in the OneSignal org with **Contents:
write** and **Pull requests: write**, install it on `OneSignal-Android-SDK`, and add
these secrets to this repo:

| Secret | Value |
| --- | --- |
| `KMP_RELEASE_APP_ID` | The GitHub App's App ID |
| `KMP_RELEASE_APP_PRIVATE_KEY` | The App's generated private key (PEM) |

Without these, run the workflow with **open_android_pr** unchecked to tag + release
only, then bump the Android submodule manually.
