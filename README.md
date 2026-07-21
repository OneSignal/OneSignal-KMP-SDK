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
