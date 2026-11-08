# Development notes

## Toolchain

The current M001 branch uses:
- JDK 17
- Gradle 9.6.0
- Android Gradle Plugin 9.5.0
- AGP built-in Kotlin support (do not apply `org.jetbrains.kotlin.android`)
- Android SDK Platform 36 / Build Tools 36.0.0
- minSdk 23 (provisional), targetSdk 36

See `platform-compatibility.md` for the research basis and release caveats.

## Build and test

```sh
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

Debug APK:
`app/build/outputs/apk/debug/app-debug.apk`

Debug application ID:
`com.cosmos.screensaver.debug`

The repository currently uses a GitHub Actions Gradle installation rather than a checked-in Gradle Wrapper. Local builds therefore require Gradle 9.6.0 installed and available as `gradle`. The workflow uploads successful debug APKs as 14-day artifacts.

## Device verification

The first physical target is Sony BRAVIA XR-65X90L. Record the installed Android/Google TV version and build number before testing. Install the debug APK through the chosen normal installation path, then check system screensaver discovery/selection, idle activation, remote dismissal, repeated activation, reboot, and update behaviour.

Do not mark physical acceptance criteria complete from CI or emulator results alone.
