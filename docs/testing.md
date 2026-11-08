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
9. Confirm no network access is required for the proof of concept.

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

## Integration tests

- NASA candidate discovery and metadata parsing using recorded synthetic/mock responses.
- ESA/Hubble metadata parsing using synthetic/mock responses.
- Download failure, timeout, invalid MIME type, truncated file, and checksum mismatch.
- Rights rejection before catalogue admission.
- Offline screensaver startup and playback from cache.
- Database migration and preference persistence.

Tests must not require live external APIs or real third-party image files. Any fixtures containing real external metadata must be reviewed for licensing and should be avoided where synthetic data is sufficient.

## Physical-device validation

Test on the Sony XR-65X90L and, where possible, a second Android TV / Google TV device. Validate screensaver selection, idle activation, remote dismissal, wake behaviour, orientation/aspect ratio, memory pressure, repeated lifecycle transitions, app updates, reboot, and offline playback.

Do not infer TV behaviour from emulator or phone testing alone.

## Later milestones

- M002: local catalogue, provenance schema, and fail-closed rights validator.
- M003: NASA discovery and approved-asset ingestion.
- M004: narrowly scoped ESA/Hubble CC BY 4.0 provider.
- M005: slideshow renderer, transitions, attribution, and cache management.
- M006: settings, quality controls, category selection, and robust offline behaviour.
- M007: release hardening, current Play requirements, CI, and publication.
