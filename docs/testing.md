# Testing strategy and milestone plan

## Milestone 001 — native screensaver proof of concept

Do not implement image-provider discovery or catalogue ingestion until this milestone passes on the target physical television.

### Acceptance criteria

1. Build and install an ordinary APK on the target Google TV.
2. Cosmos appears in the system screensaver selector without requiring ADB configuration.
3. Select Cosmos through the normal TV settings UI.
4. Confirm the system launches it automatically after the configured idle period.
5. Display a synthetic test image fullscreen, preserving aspect ratio and without distortion.
6. Confirm remote input or wake interaction dismisses the dream and returns to normal TV operation.
7. Confirm repeated activation and deactivation work without leaked windows, stuck state, or crashes.
8. Check behaviour after app update and TV reboot.
9. Verify that the proof of concept declares no network permission. Defer physical offline testing until network-dependent functionality is introduced.

Target device: Sony BRAVIA XR-65X90L (2023 Google TV).

### Test fixture

Use a synthetic, project-created test image with a known aspect ratio and visible edge markers. Do not use NASA, ESA, or other third-party imagery as a fixture.

## Unit tests

- Licence validator: positive and negative cases for every allowed rights category.
- Fail-closed behaviour for missing, contradictory, malformed, or unsupported rights evidence.
- Provider metadata mapping.
- Image selection randomisation and no-immediate-repeat behaviour.
- Aspect-ratio and dimension validation.
- Checksum and cache integrity.
- Cache eviction safety.
- Attribution formatting and persistence.

The standalone rights validator is tested with synthetic, explicitly injected evidence. There is no production evidence verifier yet, and those tests do not establish the rights of any real asset.

## Integration tests

- NASA candidate discovery and metadata parsing using recorded synthetic/mock responses.
- ESA/Hubble metadata parsing using synthetic/mock responses.
- Download failure, timeout, invalid MIME type, truncated file, and checksum mismatch.
- Rights rejection before catalogue admission.
- Offline screensaver startup and playback from cache.
- Database migration and preference persistence.

Tests must not require live external APIs or real third-party image files. Any fixtures containing real external metadata must be reviewed for licensing and should be avoided where synthetic data is sufficient.

## Physical-device validation

Test on the Sony XR-65X90L and, where possible, a second Android TV / Google TV device. Validate screensaver selection, idle activation, remote dismissal, wake behaviour, orientation/aspect ratio, memory pressure, repeated lifecycle transitions, app updates, and reboot. Test offline playback once network-dependent downloading or caching exists.

Do not infer TV behaviour from emulator or phone testing alone.

### Sony XR-65X90L — 2026-11-08

The debug APK from [CI run 42495932115](https://github.com/Zordrak/cosmos/actions/runs/42495932115), built from commit `84ad3c6fd6728f295631021415435e8ce5e4cea4`, was installed on the target TV. Its package was `com.cosmos.screensaver.debug`, version `0.1.0-debug` (versionCode 1), with SHA-256 `7274B90E4A51309979A1D666ACB63BD9688D8327B0AB87648CFA523C8DAED27E`. The device reported Android TV OS 12 (API 31), build `STT2.230505.001.S136/682241`, a 3840×2160 physical display, and a 1920×1080 logical display.

- The user selected Cosmos through the system screensaver UI, saw the synthetic artwork, and dismissed it with the remote.
- After the TV was left idle, Cosmos started automatically approximately ten minutes after dismissal. A second remote dismissal was confirmed.
- The selection survived a TV restart. Cosmos subsequently started again after that restart and a same-version APK reinstall.
- The reinstall preserved the selection, but it did not test an upgrade to a distinct app version.
- The installed package did not request the Internet permission. A physical offline test is deferred until network-dependent functionality exists.
- The tested APK predates the corner markers; physical verification of the revised fixture and a distinct-version upgrade remain outstanding.

A locally built revised debug APK had a different signing certificate from the installed CI APK, so Android cannot install it as an in-place update. Verify package names and signing certificates before an update test; do not uninstall the existing app or clear its data without the user's approval.

For a future side-by-side update check, two test APKs were prepared as session artifacts, not committed to the repository. They use the separate package `com.cosmos.screensaver.debug.m001test`, the same signing certificate, and versionCodes 1 and 2. Selecting the test screensaver temporarily changes the system selection, but does not replace the original app. Both APKs use the same source, so the check can verify platform upgrade and selection behaviour, not future data migrations.

## Later milestones

- M002: local catalogue, provenance schema, and fail-closed rights validator.
- M003: NASA discovery and approved-asset ingestion.
- M004: narrowly scoped ESA/Hubble CC BY 4.0 provider.
- M005: slideshow renderer, transitions, attribution, and cache management.
- M006: settings, quality controls, category selection, and robust offline behaviour.
- M007: release hardening, current Play requirements, CI, and publication.
