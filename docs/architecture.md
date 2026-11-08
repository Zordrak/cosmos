# Technical architecture

## Goals

Cosmos is a native Android TV / Google TV screensaver that shows space imagery downloaded directly from approved sources. Retrieval, rights validation, storage, selection and rendering all happen on the television.

There is no Cosmos-operated backend, hosted catalogue, CDN, object store, proxy, analytics service or account system, and no recurring infrastructure cost.

## Application structure

One Android application module with package boundaries:

| Package | Responsibility |
| --- | --- |
| `screensaver` | `CosmosDreamService`, `PreviewActivity`, `SlideshowController` (selection, integrity check, decoding), `SlideshowView` (fit, cross-fade, attribution), `SyntheticArtworkRenderer` (empty state) |
| `source` | `ApprovedEndpoints` host allow-list, `HttpsFetcher` (HTTPS only, every redirect hop re-checked, size limits, streamed SHA-256), `DjangoplicitySite` and `DjangoplicityParser` for ESA/Hubble and ESA/Webb pages |
| `rights` | `LicenceValidator` (fail-closed gate, [ADR 0002](decisions/0002-rights-evidence-gate.md)) and `DjangoplicityEvidenceVerifier` ([ADR 0003](decisions/0003-esa-sources-and-nasa-exclusion.md)) |
| `catalogue` | `CatalogueEntry` provenance model and `CatalogueStore` (atomic JSON file plus app-private image files) |
| `sync` | `CatalogueRefresher` (discovery, admission pipeline and capacity) and `RefreshCoordinator` (when to refresh, one run at a time) |
| `settings` | Local `SharedPreferences`: slideshow interval and enabled sources |

The only dependencies are the Android platform APIs. Room, DataStore, Compose and image-loading libraries were deliberately not added: the catalogue is a few dozen records, and the platform APIs cover the needs without increasing APK size or dependencies.

## Admission pipeline

For each enabled site, `CatalogueRefresher`:

1. Fetches the usage-policy page. If it cannot be fetched, existing images are kept and nothing new is admitted. If the licence sentence has changed, all of that site's images are withdrawn.
2. Discovers candidate IDs from the site's image RSS feed and Top 100 listing. It skips IDs already in the catalogue and IDs rejected in the last 30 days.
3. For a bounded, shuffled set of candidates (16 pages and 6 admissions per site per refresh):
   1. Fetches the asset page and parses it.
   2. Requires Type `Observation` and landscape proportions.
   3. Runs the rights verifier and `LicenceValidator`.
   4. Downloads the preferred CDN rendition to a temporary file.
   5. Checks the JPEG signature, decoded dimensions, minimum size, and that the proportions match the page.
   6. Stores the file as `<sha256>.jpg`.
   7. Records full provenance.
4. Enforces capacity: 60 images and 400 MB, keeping the newest. It saves atomically, preserving removals made concurrently by the slideshow, and deletes unreferenced files.

Every rejection is recorded with its reason. A failed or interrupted download never produces a catalogue entry.

## Refresh scheduling

Cosmos does not assume any background scheduler runs while the TV is idle or dreaming. A refresh starts only:

- when the app is opened, if one is due;
- when the screensaver starts, if one is due;
- when the user selects **Refresh images now**.

A refresh is due 20 hours after the last attempt, or 10 minutes after it if no images are available. Runs are serialised on one low-priority background thread. The screensaver always plays from the local cache and never waits for the network.

## Rendering

- Images are scaled to fit (`ArtworkViewport.fit`) and never cropped, stretched or upscaled beyond fit. The background is black.
- Before display, the file's SHA-256 is checked against the catalogue. If it does not match, the entry is removed.
- Decoding uses a power-of-two sample size bounded to 2560×1440, which limits memory on 4K panels.
- Images cross-fade over 1.5 seconds. The interval is user-selectable from 30 seconds to 10 minutes, with a default of 60 seconds.
- Selection shuffles through every available image before repeating and never shows the same image twice in a row.
- Each image shows its title, the full unaltered credit line, the source, the licence name and the source page. The caption is on a translucent panel that alternates corners to reduce burn-in risk.
- If no images are available yet, the original synthetic starfield is shown with a status message.

## Privacy and network behaviour

The app has no telemetry, analytics, tracking, accounts or advertising. The only permission is `INTERNET`, and cleartext traffic is disabled. Requests go only to the allow-listed ESA/Hubble and ESA/Webb hosts and identify the app with a plain `Cosmos/<version>` User-Agent. All data stays in app-private storage. `allowBackup` is false.
