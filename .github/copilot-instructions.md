# Cosmos — Copilot instructions

Follow the repository-wide constraints in `/AGENTS.md`.

## Project shape

- Native Kotlin Android TV / Google TV application.
- Native Android `DreamService` is the screensaver integration.
- One app module initially; use package boundaries rather than premature modularisation.
- No backend or hosted image service of any kind.
- Device connects directly to NASA and specifically approved ESA source endpoints.
- Local-only image cache, metadata, and preferences.

## Rights and content

- Rights validation is per asset and fail-closed.
- Never infer image licensing from the provider, API, page branding, or availability of a download URL.
- Exclude unclear, third-party, partner, contractor, or permission-restricted content unless explicit applicable rights are documented.
- Preserve full provenance and visible attribution.
- Do not add external image files to the repository; use synthetic fixtures.

## Implementation discipline

- Do not implement image discovery before the physical-device DreamService proof of concept is accepted.
- Do not assume WorkManager runs during TV idle or screensaver operation.
- Avoid analytics, telemetry, tracking, accounts, ads, subscriptions, and unnecessary permissions.
- Keep images uncropped unless an explicit user-selected display mode permits it; never distort aspect ratio.
- Add tests and document non-obvious decisions.
