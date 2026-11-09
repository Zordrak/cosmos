# Technical architecture

## Goals

Cosmos is a native Android TV / Google TV screensaver that shows space imagery downloaded directly from approved sources. Retrieval, optional rights validation, storage, selection and rendering all happen on the television.

There is no Cosmos-operated backend, hosted catalogue, CDN, object store, proxy, analytics service or account system, and no recurring infrastructure cost.

## Application structure

One Android application module with package boundaries:

| Package | Responsibility |
| --- | --- |
| `screensaver` | `CosmosDreamService`, `PreviewActivity`, `TestPatternActivity` (4K pixel check), `SlideshowController` (selection, integrity check, region decoding, freshness tick), `ImagePicker` (pure Fair/Random/In-order engine), `Placement` and `PreviewPlacement` (display modes and the settings preview mapping), `SlideshowView` (4K `SurfaceView`, cross-fade), `CaptionRenderer` (credits, shared with the preview), `StartupStatus` (calm black start), `DreamFreshness` |
| `sync` (streaming) | `ImageStreamer` and `StreamQueue`: the no-room fallback that fetches, shows and deletes one image at a time via `CatalogueRefresher.streamNext`, never touching the library. It uses the same ordering engine as saved pictures |
| `source` | `ApprovedEndpoints` host allow-list, `HttpsFetcher` (HTTPS only, every redirect hop re-checked, size limits, streamed SHA-256), `Sources` and `SourceAdapter` (one adapter per source), `DjangoplicityAdapter` (feed/top-100 leads, then a cursor-free category-archive crawl backed by `ListingCache`; see ADR 0005 decision 11), `DjangoplicitySite` and `DjangoplicityParser` for ESA/Hubble, ESA/Webb, ESO and NOIRLab, `NasaSources` (NASA Image Library and APOD), `ContentFilter` (photograph-only content policy), `ImageHeader` (JPEG/PNG dimension probing) |
| `rights` | `LicenceValidator` (fail-closed gate, [ADR 0002](decisions/0002-rights-evidence-gate.md)) and `DjangoplicityEvidenceVerifier` ([ADR 0003](decisions/0003-esa-sources-and-nasa-exclusion.md)) |
| `catalogue` | `CatalogueEntry` provenance model, `CatalogueStore` (atomic JSON plus app-private image files, one library lock), `UserState` and `UserStateStore` (favourites, blocks, history, order state), `LibraryMaintenance` (every mutation of saved pictures), `AtomicJsonFile` (synced write, last-good backup) |
| `sync` | `CatalogueRefresher` (discovery, admission pipeline, capacity), `RefreshCoordinator` (when to refresh, storage budget, one run at a time), `SavingPolicy`/`RefreshGate`/`NetworkProbe` (the single saving policy), `CacheBudget` and `CacheTrimmer` (size and eviction order), `TrashBin` (durable, undoable bulk removals), `LibraryStorage`/`LibraryMigration` (storage executor and the one-time move from the cache directory), `RemovalFence`, `RefreshJobService` (daily `JobScheduler` job) |
| `settings` | `CosmosSettings` (`SharedPreferences`), `Options`, `SelectionSnapshot` (the one definition of "which pictures are eligible"), `HomeSummaries`, `SetupHealth`, `ScreensaverSelection`, `IdleTime` (read-back-verified TV idle timeout) |
| `ui` | `SettingsScreen`, `SettingsPages` and `SettingsUi` (the Home plus short pages, stable row ids, focus by id), `OptionPicker`, `ConfirmDialog`, `SpacePage` with `StorageBarView`, `LibraryScreen` (browse, favourite, remove, block), `UndoNotice`, `SetupNotice` |

The only dependencies are the Android platform APIs. Room, DataStore, Compose and image-loading libraries were deliberately not added: the catalogue is a few hundred records, and the platform APIs cover the needs without increasing APK size or dependencies.

## Saved pictures are user data

The library (`images/`, `catalogue.json`), `userstate.json` (favourites, blocks, history, order state) and the Undo area (`trash/`) live in `filesDir`. Only streaming scratch files live in `cacheDir/stream`, so Android's **Clear cache** and system cache reclaim cannot remove a picture, favourite or block. **Clear data** and uninstalling remove everything, as for any app. Because Android no longer reclaims the library, Cosmos enforces its own limits ([ADR 0005](decisions/0005-saved-pictures-user-data-and-ordering.md)).

All JSON is written atomically with a last-good backup. A missing file is an empty library on first run; an unreadable, corrupt or unsupported file is never treated as empty, and nothing is cleaned up or overwritten after a failed read.

## Admission pipeline

For each enabled site, `CatalogueRefresher`:

1. In **Only openly licensed images** mode, fetches the usage-policy page. If it cannot be fetched, existing images are kept and nothing new is admitted for that site. If the licence sentence has changed, the site's licence-checked images are withdrawn. In the default **Any public image** mode, this step is skipped.
2. Discovers candidate IDs from the site's RSS feed, Top 100 listing and picture-of-the-week listings, then walks its category archive pages (ADR 0005 decision 11). It skips IDs that already qualify under the current settings, IDs rejected in the last 30 days, blocked pictures, and anything already tried in this pass. If the settings fingerprint has changed, rejections that depended on the old settings are forgotten.
3. The sources take turns, one saved picture per turn, so each gets a fair share of the space. A pass carries on until the library is full or every source has nothing new; there is no per-source or per-refresh admission cap. A source sits out the rest of a pass after 150 consecutive leads that do not qualify, or 6 consecutive failed downloads. When a source's queue runs out it is discovered again, which continues from the first picture not yet accounted for. Rejections are saved every 20 as the pass goes, so a pass that is stopped part-way does not repeat them. For each lead:
   1. Fetches and parses the asset page.
   2. Requires a suitable type (Observation, or a curated picture-of-the-week photograph), landscape proportions, and a content check that rejects charts, composites and art.
   3. Chooses a rendition. When the page's declared size cannot meet the quality tier, the image is rejected as `LOW_RESOLUTION` without downloading it. The 4000-px publication JPEG is used when it meets the tier; otherwise the full-resolution `large` JPEG is used.
   4. In CC BY only mode, runs the rights verifier and `LicenceValidator`.
   5. Reuses the existing file if the same asset URL is already saved; otherwise downloads it (30 MB cap for publication renditions, 60 MB for full resolution).
   6. Checks the JPEG signature, the decoded dimensions, and that the proportions match the page. If a downloaded publication file falls short of the tier, the next rendition is tried.
   7. Stores the file as `<sha256>.jpg` and records provenance. Unchecked entries have the rights basis `NOT_CHECKED_PERSONAL_USE`. Identical bytes served by two sources share one file; each source keeps its own provenance entry as an alias.
4. Enforces capacity: there is no picture-count cap. The limit is the user's chosen size (Automatic by default), clamped to what is safely available, and free space must stay at least the larger of 3 GB and 15% of the storage; a write may never take free space below 90 MiB. When the library is full, a new picture may only replace one that is outside the current view, or one that has been **shown since it was saved**, taken from the source holding the most pictures, least recently shown first. A picture not yet seen is never replaced, so how fast the library changes follows how much it is watched. Favourites are **never evicted**; if favourites alone exceed the limit, saving stops and the settings say so. Below a 50 MiB budget nothing is downloaded and the status says there is not enough free space. It merges into the live catalogue, preserving removals made concurrently by the user, and deletes unreferenced files.

Sources other than Djangoplicity sites (the NASA Image Library and APOD) are handled by their own `SourceAdapter`. They declare no image size, so dimensions are read from the first 256 KB of the file before the full download.

Every rejection is recorded with its reason. A failed or interrupted download never produces a catalogue entry. A refresh cannot re-add a picture the user has just removed or that is sitting in Undo (`RemovalFence`, `TrashBin.isStashed`).

## Refresh scheduling and the saving policy

Cosmos does not assume any background scheduler runs while the TV is idle or dreaming. A refresh starts only:

- when the app is opened, if one is due;
- when the screensaver starts, if one is due;
- when the user changes a viewing setting;
- when the user selects **Look for new pictures now**;
- by a daily job on an unmetered network, which Android may defer while the TV is in standby.

One saving policy (`SavingPolicy`, a single `download_mode` preference) governs all of these:

| Mode | Behaviour |
| --- | --- |
| Automatically (default) | Saving proceeds. |
| Not on limited data | Saving stops only if the network is positively known to be limited (metered). If the TV cannot say (`ACCESS_NETWORK_STATE` unavailable or the state is unknown right now) saving continues, and the setting is greyed out with the reason when the capability is unavailable. |
| Paused | Automatic saving is held, including after settings changes, Tidy and size changes; the daily job is cancelled. |

Only the explicit **Look for new pictures now** command overrides Paused or limited-data. Urgency (`force`) is never permission. Local maintenance (Tidy, removing, reconciling) runs regardless, and streaming is independent of this setting. A refresh is due when the selection fingerprint changes, 20 hours after the last attempt, or 10 minutes after it if nothing can be shown. Runs are serialised on one low-priority background thread. The screensaver always plays from saved pictures and never waits for the network.

The TV's own inactivity timer decides when the screensaver starts. Cosmos can read and, with the user's one-time grant of "Modify system settings", set `Settings.System.SCREEN_OFF_TIMEOUT`, and always verifies the value by reading it back.

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
- **Order** (`ImagePicker`, pure and tested) is one of: *Fair shuffle* (default; lowest-score-first weighted service, new pictures served after every four ordinary picks, favourites cheaper, per-source Less/Normal/More weights), *Pure random*, or *In order* (by source, title, then hash). The state is persisted, and history advances only when a frame has actually been posted. Streaming uses the same engine, keyed by lead.
- Captions (shared by the slideshow and the settings preview through `CaptionRenderer`):
  - **Subtle** (default): the title and credit in translucent text with a soft shadow and no panel.
  - **Detailed:** title, credit, source and licence on a faint panel.
  - **Off:** no caption.
  Credit size is Small, Standard, Large or Extra large. Captions alternate corners to reduce burn-in risk.
- **Calm start:** the dream's window and the surface are black from the first frame and the first real picture fades in. There is no placeholder artwork. After 2.5 seconds of genuine waiting a small dim status line may appear.
## Privacy and network behaviour

The app has no telemetry, analytics, tracking, accounts or advertising. Permissions are `INTERNET`, `ACCESS_NETWORK_STATE` (to honour "Not on limited data") and `WRITE_SETTINGS` (only if the user chooses to change the TV's idle timeout), and cleartext traffic is disabled. Requests go only to the allow-listed ESA/Hubble, ESA/Webb, ESO, NOIRLab and NASA hosts and identify the app with a plain `Cosmos/<version>` User-Agent. All data stays in app-private storage. `allowBackup` is false.

