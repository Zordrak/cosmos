# Technical architecture

## Goals

Cosmos is a native Android TV / Google TV screensaver that shows space imagery downloaded directly from approved sources. Retrieval, optional rights validation, storage, selection and rendering all happen on the television.

There is no Cosmos-operated backend, hosted catalogue, CDN, object store, proxy, analytics service or account system, and no recurring infrastructure cost.

## Application structure

One Android application module with package boundaries:

| Package | Responsibility |
| --- | --- |
| `screensaver` | `CosmosDreamService`, `PreviewActivity`, `TestPatternActivity` (4K pixel check), `SlideshowController` (selection, integrity check, region decoding), `Placement` (display modes), `SlideshowView` (4K `SurfaceView`, cross-fade, captions), `SyntheticArtworkRenderer` (empty state) |
| `sync` (streaming) | `ImageStreamer` and `StreamQueue`: the low-space fallback that fetches, shows and deletes one image at a time via `CatalogueRefresher.streamNext`, never touching the library |
| `source` | `ApprovedEndpoints` host allow-list, `HttpsFetcher` (HTTPS only, every redirect hop re-checked, size limits, streamed SHA-256), `Sources` and `SourceAdapter` (one adapter per source), `DjangoplicityAdapter`, `DjangoplicitySite` and `DjangoplicityParser` for ESA/Hubble, ESA/Webb, ESO and NOIRLab, `NasaSources` (NASA Image Library and APOD), `ImageHeader` (JPEG/PNG dimension probing) |
| `rights` | `LicenceValidator` (fail-closed gate, [ADR 0002](decisions/0002-rights-evidence-gate.md)) and `DjangoplicityEvidenceVerifier` ([ADR 0003](decisions/0003-esa-sources-and-nasa-exclusion.md)) |
| `catalogue` | `CatalogueEntry` provenance model and `CatalogueStore` (atomic JSON file plus app-private image files) |
| `sync` | `CatalogueRefresher` (discovery, admission pipeline and capacity), `RefreshCoordinator` (when to refresh, storage budget, one run at a time) and `RefreshJobService` (daily `JobScheduler` job) |
| `settings` | Local `SharedPreferences` (`CosmosSettings`) and the pure `ImageSelection` rules for licence mode and quality tiers (`Options.kt`) |

The only dependencies are the Android platform APIs. Room, DataStore, Compose and image-loading libraries were deliberately not added: the catalogue is a few dozen records, and the platform APIs cover the needs without increasing APK size or dependencies.

## Admission pipeline

For each enabled site, `CatalogueRefresher`:

1. In **Only openly licensed images** mode, fetches the usage-policy page. If it cannot be fetched, existing images are kept and nothing new is admitted for that site. If the licence sentence has changed, the site's licence-checked images are withdrawn. In the default **Any public image** mode, this step is skipped.
2. Discovers candidate IDs from the site's RSS feed, Top 100 listing and picture-of-the-week listings. It skips IDs that already qualify under the current settings, and IDs rejected in the last 30 days. If the settings fingerprint has changed, rejections that depended on the old settings are forgotten.
3. For a bounded, shuffled set of candidates (40 pages and 8 admissions per site per refresh):
   1. Fetches and parses the asset page.
   2. Requires a suitable type (Observation, or a curated picture-of-the-week photograph) and landscape proportions.
   3. Chooses a rendition. When the page's declared size cannot meet the quality tier, the image is rejected as `LOW_RESOLUTION` without downloading it. The 4000-px publication JPEG is used when it meets the tier; otherwise the full-resolution `large` JPEG is used.
   4. In CC BY only mode, runs the rights verifier and `LicenceValidator`.
   5. Reuses the existing file if the same asset URL is already cached; otherwise downloads it (30 MB cap for publication renditions, 60 MB for full resolution).
   6. Checks the JPEG signature, the decoded dimensions, and that the proportions match the page. If a downloaded publication file falls short of the tier, the next rendition is tried.
   7. Stores the file as `<sha256>.jpg` and records provenance. Unchecked entries have the rights basis `NOT_CHECKED_PERSONAL_USE`.
4. Enforces capacity: up to 150 images and 2 GB, always leaving free space of at least the larger of 3 GB and 15% of the storage. It keeps images eligible under the current settings first, then the newest. When the library is full, a run admits at most 15 new images. Below a 50 MiB budget nothing is downloaded and the status says there is not enough free space. It saves atomically, preserving removals made concurrently by the slideshow, and deletes unreferenced files. The library lives in the app cache directory.

Sources other than Djangoplicity sites (the NASA Image Library and APOD) are handled by their own `SourceAdapter`. They declare no image size, so dimensions are read from the first 256 KB of the file before the full download.

Every rejection is recorded with its reason. A failed or interrupted download never produces a catalogue entry.
## Refresh scheduling

Cosmos does not assume any background scheduler runs while the TV is idle or dreaming. A refresh starts only:

- when the app is opened, if one is due;
- when the screensaver starts, if one is due;
- when the user selects **Refresh images now**;
- by a daily job on an unmetered network, which Android may defer while the TV is in standby.

A refresh is also due when the licence or quality setting changes the selection fingerprint. Otherwise a refresh is due 20 hours after the last attempt, or 10 minutes after it if no image in the library can be shown with the current settings and sources. A forced refresh requested while one is running queues one more run. Runs are serialised on one low-priority background thread. The screensaver always plays from the local cache and never waits for the network.

## Rendering

- `Placement` maps the image to the screen for the chosen display mode:
  - **Fill** (default): covers the screen and crops the overflowing edges equally.
  - **Fit:** shows the whole image with black bars.
  - **Auto:** fills when at least 85% of the image area stays visible, and fits otherwise.
  - **Original:** one image pixel per physical panel pixel.
  - **Stretch:** distorts the image to cover the screen.
  Only Stretch changes the aspect ratio.
- Only the visible region is decoded, using `BitmapRegionDecoder` with a power-of-two sample size that never drops below the destination size. The result is then scaled exactly to the destination. This keeps memory bounded for full-resolution files.
- The TV composites normal views at 1920×1080 and upscales to the 4K panel. To reach the panel's real pixels, `SlideshowView` draws into a `SurfaceView` whose buffer is fixed to the physical display size (up to 3840×2160), falling back to 1080p if that fails. `TestPatternActivity` lets the owner verify the result by eye.
- Before display, the file's SHA-256 is checked against the catalogue. If it does not match, the entry is removed.
- Images cross-fade over 1.5 seconds. The interval ranges from 15 seconds to 30 minutes, with a default of 60 seconds.
- Selection shuffles through every eligible image before repeating and never shows the same image twice in a row.
- Captions:
  - **Subtle** (default): the title and credit in small translucent text with a soft shadow and no panel.
  - **Detailed:** title, credit, source and licence on a faint panel.
  - **Off:** no caption.
  Captions alternate corners to reduce burn-in risk.
- If no images are available yet, the synthetic starfield is shown with a status message.
## Privacy and network behaviour

The app has no telemetry, analytics, tracking, accounts or advertising. The only permission is `INTERNET`, and cleartext traffic is disabled. Requests go only to the allow-listed ESA/Hubble, ESA/Webb, ESO and NOIRLab hosts and identify the app with a plain `Cosmos/<version>` User-Agent. All data stays in app-private storage. `allowBackup` is false.
