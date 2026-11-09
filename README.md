# Cosmos

A native Android TV / Google TV screensaver showing space imagery from ESA/Hubble, ESA/Webb, ESO, NOIRLab, the NASA Image Library and NASA APOD.

Cosmos is designed to run entirely on the user's television. It has no backend, hosted catalogue, CDN, analytics, accounts, advertising, subscriptions, or recurring infrastructure costs. The television downloads images directly from the source sites and keeps the image cache and provenance metadata locally.

## Project principles

- Android TV and Google TV first; native Kotlin implementation.
- Register as a standard Android screensaver using `DreamService`, selectable through the TV's normal settings UI.
- Public sources only: currently ESA/Hubble, ESA/Webb, ESO, NOIRLab, the NASA Image Library and NASA APOD, through an HTTPS host allow-list.
- Personal use for now: licence checking is optional and off by default. When it is on, rights are validated per asset and fail closed ([ADR 0004](docs/decisions/0004-personal-use-defaults.md)).
- Direct source-to-device downloads; no Cosmos image hosting or redistribution.
- Local image cache and provenance records.
- No telemetry, tracking, accounts, advertising, or paid features.

## Current state — version 0.5.0

Milestone 001 (the DreamService proof of concept) was accepted on a Sony XR-65X90L. The full application is implemented, and its physical-device checks are listed in [testing](docs/testing.md).

- **Screensaver:** a cross-fading slideshow of saved pictures, drawn at the panel's native 4K through a `SurfaceView`. It starts from plain black and fades the first real picture in; there is no placeholder artwork. By default pictures fill the screen without distortion, and a credit appears in alternating corners and fades after 15 seconds. Credits have a size setting (Small to Extra large).
- **Picture order:** Fair shuffle (default: recently shown pictures wait, new pictures arrive within a few slides, favourites are shown a little more), Pure random, or In order. Each source can be Off, Less, Normal or More.
- **Settings (D-pad):** one Home with six topics (Preview, What to show, Slideshow, Saved pictures, When it starts, More). Each topic opens a short page; a live preview, plain wording and a "Needs attention" row replace long lists of options.
  - **What to show:** collection, sources, picture quality, free-to-reuse only, and **Tidy up**.
  - **Slideshow:** time per picture, order, picture fit, credits and credit size, with a live preview.
  - **Saved pictures:** a live storage bar, **Space for pictures** (Automatic or a chosen limit), **Download new pictures** (Automatically, Not on limited data, Paused), **Look for new pictures now**, an image browser (favourite, remove, block), Undo, and Tidy up.
  - **When it starts:** the TV's idle timeout (set in-app after one grant of the "Modify system settings" toggle) and a shortcut to the TV's screensaver selection.
  - **More:** blocked pictures, reset viewing choices, clear saved pictures, and About.
- **Saved pictures are your data, not cache:** the library, favourites, blocked pictures and Undo files live in the app's private files area. Android's **Clear cache** only removes temporary streaming files; **Clear data** or uninstalling removes everything. Favourites are never removed automatically. The only size limits are the one you choose (or Automatic) and a free-space reserve of at least the larger of 3 GB and 15% of storage.
- **Downloads:** run when the app is opened, when the screensaver starts and a refresh is due, when a setting changes, on request, and through a daily job on an unmetered network (Android may defer it in standby). **Paused** stops all automatic saving; the explicit **Look for new pictures now** is the only command that overrides it. If the TV cannot report limited-data connections, that choice is greyed out with the reason and the app keeps working. When there is no room to save, the screensaver streams pictures online one at a time without keeping them.
- **Known bad pictures:** a built-in block list and a content filter keep out multi-epoch composites and charts.
- **Display safety and native 4K:** the slideshow never draws an app layer larger than the TV's app resolution. On MediaTek TVs (including the Sony X90L) a 4K app layer puts the compositor into a "4K/2K UI coexist" mode that leaves other apps' video in a quarter of the screen until reboot. Pictures still reach the panel at full 4K, through the hardware video decoder on the video plane.
- **Permissions:** `INTERNET`; `ACCESS_NETWORK_STATE` (only to honour "Not on limited data"); `WRITE_SETTINGS` (only to change the TV's idle timeout when you choose to). HTTPS is restricted to an allow-list of hosts.

See [the architecture](docs/architecture.md), [image licensing policy](docs/image-licensing.md), [testing plan](docs/testing.md), and ADRs [0001](docs/decisions/0001-native-dreamservice.md), [0002](docs/decisions/0002-rights-evidence-gate.md), [0003](docs/decisions/0003-esa-sources-and-nasa-exclusion.md), [0004](docs/decisions/0004-personal-use-defaults.md), [0005](docs/decisions/0005-saved-pictures-user-data-and-ordering.md) and [0006](docs/decisions/0006-settings-experience.md).

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
