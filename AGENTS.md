# Agent instructions

## Non-negotiable product constraints

- Target Android TV and Google TV only for the initial release.
- Implement a native Kotlin Android application and register the screensaver through Android `DreamService`.
- Cosmos must have zero recurring hosting or infrastructure costs. Do not add a backend, hosted catalogue, CDN, object storage, proxy, or remote image-processing service.
- Discover and download images directly from explicitly approved public source endpoints to the device (currently ESA/Hubble, ESA/Webb, ESO and NOIRLab; see `ApprovedEndpoints`).
- Cache images and provenance metadata locally.
- Do not add accounts, analytics, telemetry, tracking, advertising, subscriptions, or premium features.
- Cosmos is currently for the owner's personal use on their own TV. Licence checking is an optional setting, **off by default**, so any publicly available image from an approved source may be shown ([ADR 0004](docs/decisions/0004-personal-use-defaults.md)).
- When licence checking is on, validate rights per asset and fail closed: reject an asset whenever its rights basis or permission is unclear. API access or a download URL is never itself a licence.
- Before any distribution to other users, revisit ADR 0004: licence checking must become the default and attribution must be shown.
- Do not commit third-party image files. Use synthetic or project-created test fixtures only.
- The MIT licence covers this repository's software, not external imagery.

## Engineering approach

- Prefer a small, understandable single Android app with package boundaries. Do not prematurely split it into Gradle modules or services.
- Keep provider-specific retrieval separate from licence validation and catalogue admission.
- Preserve provenance for every admitted image: source page, original asset URL, creator/credit, exact rights basis, evidence URL, retrieval and validation timestamps, policy version, checksum, and alteration status.
- Do not assume background work executes while the TV is asleep or in screensaver mode. Validate lifecycle behaviour on real hardware.
- Avoid unnecessary permissions and network activity.
- Never distort aspect ratio unless the user explicitly chooses the Stretch display mode. The default (Fill) covers the screen by cropping edges.
- Show attribution with the displayed image by default; the user may turn credits off.
- With licence checking on, no image may be shown until it passes the rights validator.

## Delivery discipline

- Work in small, testable milestones.
- Milestone 001 is the native DreamService proof of concept using a synthetic test image. Do not build image providers or catalogue ingestion before this milestone passes on a physical TV.
- Verify Android TV compatibility and current Play requirements rather than assuming phone behaviour applies.
- Add tests for licence decisions, image selection, cache integrity, and lifecycle behaviour.
- Document material architectural decisions as ADRs.
