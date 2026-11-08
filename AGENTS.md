# Agent instructions

## Non-negotiable product constraints

- Target Android TV and Google TV only for the initial release.
- Implement a native Kotlin Android application and register the screensaver through Android `DreamService`.
- Cosmos must have zero recurring hosting or infrastructure costs. Do not add a backend, hosted catalogue, CDN, object storage, proxy, or remote image-processing service.
- Discover and download images directly from NASA and explicitly approved ESA endpoints to the device.
- Cache images and provenance metadata locally.
- Do not add accounts, analytics, telemetry, tracking, advertising, subscriptions, or premium features.
- NASA API access is not an image licence. Validate rights per asset.
- Fail closed: reject an asset whenever its rights basis or permission is unclear.
- Do not commit third-party image files. Use synthetic or project-created test fixtures only.
- The MIT licence covers this repository's software, not external imagery.

## Engineering approach

- Prefer a small, understandable single Android app with package boundaries. Do not prematurely split it into Gradle modules or services.
- Keep provider-specific retrieval separate from licence validation and catalogue admission.
- Preserve provenance for every admitted image: source page, original asset URL, creator/credit, exact rights basis, evidence URL, retrieval and validation timestamps, policy version, checksum, and alteration status.
- Do not assume background work executes while the TV is asleep or in screensaver mode. Validate lifecycle behaviour on real hardware.
- Avoid unnecessary permissions and network activity.
- Preserve aspect ratio; never stretch images.
- Keep attribution visible with the displayed image.
- No image may enter the display catalogue until it passes the rights validator.

## Delivery discipline

- Work in small, testable milestones.
- Milestone 001 is the native DreamService proof of concept using a synthetic test image. Do not build image providers or catalogue ingestion before this milestone passes on a physical TV.
- Verify Android TV compatibility and current Play requirements rather than assuming phone behaviour applies.
- Add tests for licence decisions, image selection, cache integrity, and lifecycle behaviour.
- Document material architectural decisions as ADRs.
