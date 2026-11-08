# Technical architecture

## Goals

Cosmos is a native Android TV / Google TV screensaver that displays a curated stream of space imagery sourced directly from NASA and explicitly approved ESA collections. All retrieval, storage, selection, and rendering happen on the television.

The design has no Cosmos-operated backend, hosted catalogue, CDN, object store, proxy, analytics service, account system, or recurring infrastructure dependency.

## Application structure

Start with one Android application module and package-level boundaries:

- `screensaver`: `DreamService`, lifecycle coordination, and display surface.
- `catalogue`: local catalogue models and image selection.
- `providers.nasa`: NASA Images and Video Library discovery and metadata mapping.
- `providers.esa`: narrowly scoped provider for explicitly licensed ESA/Hubble assets.
- `rights`: per-asset rights evidence and fail-closed validation.
- `download`: direct downloads, integrity checks, and safe file handling.
- `cache`: local image files and Room persistence.
- `settings`: user preferences using DataStore.
- `ui`: ordinary app/settings UI, using Compose where appropriate.

Avoid separate Gradle modules until there is a demonstrated build or ownership reason.

## Runtime and screensaver lifecycle

Use Android's native `DreamService` integration and the platform screensaver selection flow. The app must be installed as a normal APK and selectable through the TV settings UI; ADB is a development aid, not a user-facing requirement.

The screensaver runtime should use only already-cached, approved assets. Do not make network access a prerequisite for starting or continuing the screensaver. Handle interruption, user input, wake, stop, and repeated activation cleanly.

## Image providers

### NASA

Use the NASA Images and Video Library endpoints at `images.nasa.gov` for discovery and metadata. Treat API results as candidate records only. API availability does not establish copyright status. Follow source-page and asset-level rights evidence before admission.

### ESA

Limit the initial ESA integration to individually verified ESA/Hubble assets carrying an explicitly applicable licence such as CC BY 4.0. Do not treat the general ESA multimedia archive as uniformly reusable. Any additional ESA collection or licence requires a documented policy decision before implementation.

## Rights validation and catalogue admission

Provider adapters return candidate metadata. A separate `LicenceValidator` decides whether an asset can enter the local approved catalogue. Unknown, missing, contradictory, or ambiguous rights evidence means rejection.

Persist the source page, original asset URL, creator/credit, exact licence or public-domain basis, evidence URL, retrieval and validation timestamps, policy version, checksum, and alteration status. Keep attribution data available to the renderer.

## Local persistence and downloads

Use Room for catalogue records and provenance, DataStore for preferences, and app-private storage for downloaded image files. Downloads go directly from the TV to the source provider. Validate response type, dimensions, file size, checksum, and rights before marking an asset displayable.

The screensaver must work offline from the local cache. Cache eviction must never remove the only usable image before a replacement is safely downloaded and validated.

## Scheduling

WorkManager may be evaluated for deferrable discovery and download work, but do not assume it runs while the TV is in standby or during screensaver operation. Confirm actual scheduling and lifecycle behaviour on the target device. The screensaver itself should not depend on background work.

## Rendering

- Preserve source aspect ratio; never stretch.
- Prefer appropriately sized source assets for 4K output.
- Do not upscale small images merely to fill the screen.
- Use a restrained neutral background for non-16:9 images.
- Provide a configurable slideshow interval (30 seconds initially).
- Randomise selection without immediate repeats.
- Use a restrained crossfade; optional motion must be subtle and disableable.
- Keep attribution visible and legible without dominating the image.

## Privacy and network behaviour

No telemetry, analytics, tracking, accounts, or advertising. Network requests are limited to direct source discovery and asset downloads, initiated according to user settings and platform lifecycle constraints. No image or usage data is sent to Cosmos-operated infrastructure because none exists.

## Initial dependencies

- Kotlin and Android Gradle Plugin.
- AndroidX / native Android APIs, including `DreamService`.
- Compose for regular settings UI.
- Room for local catalogue metadata.
- DataStore for preferences.
- Coil or an equivalent maintained image-loading library, subject to TV/lifecycle suitability.
- JUnit and Android instrumented tests.

Confirm current Android SDK, target API, TV compatibility, Play policy, and device minimum API requirements before fixing project values.
