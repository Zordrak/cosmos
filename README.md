# Cosmos

A native Android TV / Google TV screensaver showing space imagery from ESA/Hubble and ESA/Webb.

Cosmos is designed to run entirely on the user's television. It has no backend, hosted catalogue, CDN, analytics, accounts, advertising, subscriptions, or recurring infrastructure costs. The television downloads rights-validated images directly from the source sites and keeps the image cache and provenance metadata locally.

## Project principles

- Android TV and Google TV first; native Kotlin implementation.
- Register as a standard Android screensaver using `DreamService`, selectable through the TV's normal settings UI.
- Approved sources only: currently ESA/Hubble and ESA/Webb (CC BY 4.0). NASA endpoints are excluded until per-asset rights can be established ([ADR 0003](docs/decisions/0003-esa-sources-and-nasa-exclusion.md)).
- Per-asset rights validation, with a fail-closed policy. An asset with unclear rights is excluded.
- Direct source-to-device downloads; no Cosmos image hosting or redistribution.
- Local image cache and provenance records.
- No telemetry, tracking, accounts, advertising, or paid features.

## Current state — version 0.2.0

Milestone 001 (the DreamService proof of concept) was accepted on a Sony XR-65X90L. The full application is implemented, and its physical-device checks are listed in [testing](docs/testing.md).

- **Screensaver:** a cross-fading slideshow of cached images, fitted without cropping. Each image always shows its title, full credit, source and licence.
- **Home screen (D-pad):** shows status, and lets you preview the slideshow, refresh images, open the system screensaver settings, choose the interval (30 s–10 min), switch Hubble and Webb on or off, and clear downloaded images.
- **Downloads:** run when the app is opened, when the screensaver starts and a refresh is due, or on request. Every asset must pass the rights verifier and the fail-closed `LicenceValidator` before admission. The cache is capped at 60 images and 400 MB.
- **Permissions:** `INTERNET` only, with HTTPS restricted to an allow-list of hosts.

See [the architecture](docs/architecture.md), [image licensing policy](docs/image-licensing.md), [testing plan](docs/testing.md), [ADR 0001](docs/decisions/0001-native-dreamservice.md), [ADR 0002](docs/decisions/0002-rights-evidence-gate.md), and [ADR 0003](docs/decisions/0003-esa-sources-and-nasa-exclusion.md).
## Image rights

The MIT licence in this repository applies to Cosmos source code only. It does not apply to ESA, NASA or any other imagery; displayed images remain under their own licences and credits. No third-party image assets are included in the repository. Every image must carry a recorded, verifiable rights basis before it can be admitted to the catalogue.

## Development

The initial implementation is intentionally a single Android application with clear package boundaries. Avoid introducing a backend or unnecessary modules.

### Build

Requirements: JDK 17, Android SDK Platform 36, Android Build Tools 36.0.0, and Gradle 9.6.0.

From the repository root:

```sh
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. The debug application ID is `com.cosmos.screensaver.debug`.

The GitHub Actions workflow also uploads the debug APK as a 14-day artifact for each successful build. It is a build artifact only; it is not yet a release.

See [platform compatibility](docs/platform-compatibility.md), [AGENTS.md](AGENTS.md), and [.github/copilot-instructions.md](.github/copilot-instructions.md) for toolchain decisions and engineering constraints.
