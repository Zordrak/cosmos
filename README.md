# Cosmos

A native Android TV / Google TV screensaver showing space imagery from ESA/Hubble, ESA/Webb, ESO and NOIRLab.

Cosmos is designed to run entirely on the user's television. It has no backend, hosted catalogue, CDN, analytics, accounts, advertising, subscriptions, or recurring infrastructure costs. The television downloads images directly from the source sites and keeps the image cache and provenance metadata locally.

## Project principles

- Android TV and Google TV first; native Kotlin implementation.
- Register as a standard Android screensaver using `DreamService`, selectable through the TV's normal settings UI.
- Approved sources only: currently ESA/Hubble, ESA/Webb, ESO and NOIRLab, through an HTTPS host allow-list.
- Personal use for now: licence checking is optional and off by default. When it is on, rights are validated per asset and fail closed ([ADR 0004](docs/decisions/0004-personal-use-defaults.md)).
- Direct source-to-device downloads; no Cosmos image hosting or redistribution.
- Local image cache and provenance records.
- No telemetry, tracking, accounts, advertising, or paid features.

## Current state — version 0.3.0

Milestone 001 (the DreamService proof of concept) was accepted on a Sony XR-65X90L. The full application is implemented, and its physical-device checks are listed in [testing](docs/testing.md).

- **Screensaver:** a cross-fading slideshow of cached images. By default the images fill the screen without distortion, and a small translucent credit appears in alternating corners.
- **Settings (D-pad):** a two-pane screen. The left pane shows the library status. The right pane holds the settings: press OK to change a value, or left/right to step through values.
  - **Screensaver:** Preview; set as screensaver.
  - **Display:** picture fit (Fill, Fit, Auto, Original, Stretch); interval (15 s–30 min); credits (Subtle, Detailed, Off).
  - **Images:** picture quality (4K only, 4K else 1080p, 1080p+, 720p+, 480p+, Any); licence filter (Any public image, or CC BY only).
  - **Sources:** each source on or off.
  - **Library:** refresh now; clear downloaded images.
- **Downloads:** run when the app is opened, when the screensaver starts and a refresh is due, when a setting changes, or on request. The full-resolution rendition is fetched when that is needed for 4K. The cache is capped at 80 images and 1.5 GB.
- **Permissions:** `INTERNET` only, with HTTPS restricted to an allow-list of hosts.

See [the architecture](docs/architecture.md), [image licensing policy](docs/image-licensing.md), [testing plan](docs/testing.md), and ADRs [0001](docs/decisions/0001-native-dreamservice.md), [0002](docs/decisions/0002-rights-evidence-gate.md), [0003](docs/decisions/0003-esa-sources-and-nasa-exclusion.md) and [0004](docs/decisions/0004-personal-use-defaults.md).
## Image rights

The MIT licence in this repository applies to Cosmos source code only. It does not apply to ESA, NASA or any other imagery; displayed images remain under their own licences and credits. No third-party image assets are included in the repository. Cosmos is currently for personal use only. Before any distribution, licence checking must become the default ([ADR 0004](docs/decisions/0004-personal-use-defaults.md)).

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
