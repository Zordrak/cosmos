# Cosmos

A native Android TV / Google TV screensaver for space imagery from NASA and ESA.

Cosmos is designed to run entirely on the user's television. It has no backend, hosted catalogue, CDN, analytics, accounts, advertising, subscriptions, or recurring infrastructure costs. The app discovers and retrieves approved imagery directly from source providers and keeps its cache and metadata locally on the device.

## Project principles

- Android TV and Google TV first; native Kotlin implementation.
- Register as a standard Android screensaver using `DreamService`, selectable through the TV's normal settings UI.
- NASA and ESA sources only.
- Per-asset rights validation, with a fail-closed policy. An asset with unclear rights is excluded.
- Direct source-to-device downloads; no Cosmos image hosting or redistribution.
- Local image cache and provenance records.
- No telemetry, tracking, accounts, advertising, or paid features.

## Current milestone

**Milestone 001 — native screensaver proof of concept.**

Before implementing image discovery, prove that an ordinary installable APK can be selected as the screensaver on a physical Google TV, starts on idle, displays a synthetic test image correctly, and exits cleanly on user input.

See [the architecture](docs/architecture.md), [image licensing policy](docs/image-licensing.md), [testing plan](docs/testing.md), and [ADR 0001](docs/decisions/0001-native-dreamservice.md).

## Image rights

The MIT licence in this repository applies to Cosmos source code only. It does not apply to NASA or ESA imagery. No third-party image assets are included in the repository. Every image must carry a recorded, verifiable rights basis before it can be admitted to the catalogue.

## Development

The initial implementation is intentionally a single Android application with clear package boundaries. Avoid introducing a backend or unnecessary modules.

### Current proof of concept

The M001 branch contains:
- A native `DreamService` registration and minimal TV launcher activity.
- Original procedural synthetic artwork rendered in a fixed 16:9 viewport.
- Unit tests for 4K, 4:3, and ultrawide viewport fitting.
- A GitHub Actions build that produces a debug APK artifact.

### Build

Requirements: JDK 17, Android SDK Platform 36, Android Build Tools 36.0.0, and Gradle 9.6.0.

From the repository root:

```sh
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The debug application ID is `com.cosmos.screensaver.debug`.

The GitHub Actions workflow also uploads the debug APK as a 14-day artifact for each successful build. It is a build artifact only; it is not yet a release.

See [platform compatibility](docs/platform-compatibility.md), [AGENTS.md](AGENTS.md), and [.github/copilot-instructions.md](.github/copilot-instructions.md) for toolchain decisions and engineering constraints.
