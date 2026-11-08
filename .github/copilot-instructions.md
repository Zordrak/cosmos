# Cosmos — Copilot instructions

Follow the repository-wide constraints in `/AGENTS.md`.

## Project shape

- Native Kotlin Android TV / Google TV application.
- Native Android `DreamService` is the screensaver integration.
- One app module initially; use package boundaries rather than premature modularisation.
- No backend or hosted image service of any kind.
- Device connects directly to specifically approved public source endpoints (currently ESA/Hubble, ESA/Webb, ESO and NOIRLab).
- Local-only image cache, metadata, and preferences.

## Rights and content

- Personal-use phase: licence checking is an optional setting, off by default; any publicly available image from an approved source may be shown (`docs/decisions/0004-personal-use-defaults.md`).
- When licence checking is on, rights validation is per asset and fail-closed; never infer licensing from the provider, API, page branding, or availability of a download URL, and exclude unclear, third-party, partner, contractor, or permission-restricted content.
- Preserve provenance. Credits are shown by default and can be turned off by the user.
- Do not add external image files to the repository; use synthetic fixtures.

## Implementation discipline

- Do not implement image discovery before the physical-device DreamService proof of concept is accepted.
- Do not assume WorkManager runs during TV idle or screensaver operation.
- Avoid analytics, telemetry, tracking, accounts, ads, subscriptions, and unnecessary permissions.
- The default display mode (Fill) covers the screen without distortion by cropping edges; Fit, Auto and Original preserve aspect ratio; only the user-selected Stretch mode distorts.
- Add tests and document non-obvious decisions.
