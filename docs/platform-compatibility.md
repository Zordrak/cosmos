# Android TV platform compatibility research

- Research date: 2026-11-08
- Target hardware: Sony BRAVIA XR-65X90L (2023 Google TV)
- Scope: initial native screensaver proof of concept and future Google Play TV distribution

## Decisions

| Setting | Decision | Rationale |
|---|---|---|
| Application form factor | Android TV / Google TV only | Product scope |
| Screensaver API | Native `DreamService` | Platform screensaver registration and lifecycle |
| `minSdk` | 23 (provisional) | Below the Android TV quality maximum of API 31; broad legacy compatibility. Confirm target device OS before final release. |
| `compileSdk` | 36 | Stable Android 16 SDK; supported by current AGP 9.5 |
| `targetSdk` | 36 | Meets current Android TV Play minimum (34) and opts into Android 16 behaviour. Re-test target-SDK behaviour on device. |
| Android Gradle Plugin | 9.5.0 | Current stable release found during research; supports API 37 |
| Gradle | 9.6.0 | Minimum required by AGP 9.5 |
| JDK | 17 | AGP 9.5 supported JDK |
| Kotlin | 2.4.21 | Current stable Kotlin release found during research |
| TV manifest | Leanback feature and Leanback launcher activity; touchscreen not required | Required for Android TV identification/discovery |
| Native ABIs | Keep default supported Android ABIs; no NDK/native dependencies in POC | Avoid unnecessary native code; Play TV 32-/64-bit rule applies at publication |

These are initial project defaults. The tested Sony reported Android TV OS 12 (API 31); see the [physical-device evidence](testing.md#sony-xr-65x90l--2026-11-08) for the installed build and observed behaviour.

## DreamService registration

The Android `DreamService` reference specifies:
- A service declaration with `android:exported="true"`.
- `android:permission="android.permission.BIND_DREAM_SERVICE"`.
- An intent filter for action `android.service.dreams.DreamService` and category `android.intent.category.DEFAULT`.
- Optional metadata named `android.service.dream` referencing an XML resource whose root element is `<dream>`.

The platform documentation says the service permission is required when targeting API 21 or later. The content view should be set during `onAttachedToWindow()`, not `onCreate()`. A non-interactive dream (the default) dismisses on the first input event. Fullscreen mode hides system UI.

The proof of concept sets a custom synthetic artwork view in `onAttachedToWindow()`, marks the service non-interactive, and uses fullscreen mode. No network, storage, image library, or runtime permission is needed.

## Android TV manifest requirements

Current Android TV documentation and quality criteria specify:
- `android.software.leanback` feature declaration.
- `android.hardware.touchscreen` declared with `android:required="false"`.
- A TV launcher activity using `ACTION_MAIN` and `CATEGORY_LEANBACK_LAUNCHER`.
- A TV app launcher banner and icon.
- `minSdkVersion` 31 or lower to meet the TV-PS broad-device-compatibility criterion.

Cosmos is TV-only, so the Leanback feature can be marked required. Include no unnecessary hardware features or permissions.

## Current Google Play TV requirements

As of the research date:
- New Android TV apps must target API 34 or higher for Google Play submission.
- Android TV quality criterion TV-G6, effective 1 August 2026, requires both 32-bit and 64-bit support and 16 KB page-size compliance.
- Android TV apps must use Android App Bundles for Play distribution (TV-G1).
- The TV quality checklist also expects a launcher icon and banner and requires TV usability/compatibility criteria.

The POC builds a debug APK for direct installation. Play publication packaging (AAB), store listing assets, policy checks, and final ABI/page-size verification are later release tasks. No NDK dependency is planned, which avoids introducing native page-size compatibility concerns through Cosmos-owned native code, but transitive native libraries must still be checked if introduced later.

## Device-specific evidence

Sony's official UK specification page identifies the XR-65X90L as a 2023 Google TV / Android TV model with a 3840x2160 LCD panel and Google Play Store. It does not publish the installed Android OS/API level; the tested TV's reported version and build are recorded in the physical-device evidence.

The user also confirmed that Retro Screensaver Collection appears in the Sony's system screensaver selector after ordinary Play Store installation, without ADB. Cosmos was tested independently as documented in the physical-device evidence.

## Authoritative sources

1. Android Developers, [DreamService API reference](https://developer.android.com/reference/android/service/dreams/DreamService) — manifest service permission, action/category, metadata, lifecycle, content view, fullscreen and input behaviour. Accessed 2026-11-08.
2. Android Developers, [Android TV app quality](https://developer.android.com/docs/quality-guidelines/tv-app-quality) — TV quality criteria, launcher/manifest, min SDK, App Bundle, ABI and 16 KB requirements. Accessed 2026-11-08.
3. Android Developers, [Create and run a TV app](https://developer.android.com/training/tv/get-started/create) — Leanback launcher, Leanback feature, touchscreen declaration, banner. Accessed 2026-11-08.
4. Android Developers, [Handle TV hardware](https://developer.android.com/training/tv/get-started/hardware) — TV hardware feature declarations and touchscreen requirements. Accessed 2026-11-08.
5. Google Play Console Help, [Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878) — current Android TV target API submission threshold. Accessed 2026-11-08.
6. Android Developers, [Android Gradle Plugin 9.5.0 release notes](https://developer.android.com/build/releases/agp-9-5-0-release-notes) — API support and Gradle/JDK compatibility. Accessed 2026-11-08.
7. Android Developers, [About Android Gradle Plugin](https://developer.android.com/build/releases/about-agp) — Gradle compatibility table. Accessed 2026-11-08.
8. Android Developers, [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) — AGP 9.0+ enables built-in Kotlin and derives the JVM target from Java compile compatibility. Accessed 2026-11-08.
9. Sony UK, [XR-65X90L specifications](https://www.sony.co.uk/electronics/support/televisions-projectors-lcd-tvs-android-/xr-65x90l/specifications) — model year, display resolution/type, Google TV and Android TV. Accessed 2026-11-08.

## Remaining verification

- Physically verify the revised synthetic fixture's edge markers and a distinct-version app upgrade.
- Test other devices and lifecycle cases listed in the physical-device validation plan where available.
- Revisit minSdk and targetSdk if device evidence or current Play policy changes.
- Before Play release, verify current AAB, 32-/64-bit, 16 KB page-size and listing requirements, including any new policy changes.
