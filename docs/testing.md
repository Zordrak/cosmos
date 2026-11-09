# Testing strategy and milestone plan

## Milestone 001 — native screensaver proof of concept

**Status: accepted.** The user confirmed the proof of concept on the Sony XR-65X90L (see the physical-device record below). Discovery and ingestion are now enabled ([ADR 0003](decisions/0003-esa-sources-and-nasa-exclusion.md)).

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

Run `gradle --no-daemon :app:testDebugUnitTest`. All fixtures are synthetic: hand-written HTML and RSS modelled on the page structure, and byte arrays standing in for JPEGs. No real images or copied page content are used.

| Test | Covers |
| --- | --- |
| `LicenceValidatorTest` | Fail-closed rights gate: every rejection reason, evidence binding, and provenance preservation |
| `DjangoplicityParserTest` | Hubble-, Webb-, ESO- and NOIRLab-style asset pages, optional credit, Observation-only types, renditions restricted to the site CDN, exception wording, missing notice or policy link, feed and listing discovery, policy statement changes, and entity decoding |
| `ApprovedEndpointsTest` | HTTPS-only host allow-list, including lookalike hosts, user info, ports, and NASA hosts |
| `CatalogueRefresherTest` | End-to-end admission with full provenance, ESO and NOIRLab admission, personal-use admission without rights checks, 4K rendition selection (skip before download, fall through to full resolution, reuse cached files), the 4K-else-1080p fallback, the exact-4K floor and 8K cap, retrying settings-dependent rejections, and every rejection path: unsuitable type, exception wording, missing notice, portrait, low resolution, dimension mismatch, no rendition, not an image, too large, and unknown territory. Also covers the rejection retry window, policy-change withdrawal, offline retention, enabled sources, capacity limits, the storage budget and low-space state, filling everything available in one pass, sources taking turns for a fair share, replacing only pictures already seen (least recently shown, from the source holding the most), retired pictures returning into spare room but never displacing new ones, no evict-and-return within a pass, rejections saved as a long pass goes, NASA admission in both modes, and concurrent removal |
| `DeepCrawlTest` | Listing-object and pagination parsing (`page` and ESO `list` styles), category crawl skipping accounted-for pictures, retired pictures first, impossible pictures skipped, cached pages replayed offline, stale head pages refetched, missing categories tolerated, damaged cache file ignored |
| `NasaSourcesTest` | NASA Image Library and APOD parsing and discovery |
| `ImageHeaderTest` | JPEG and PNG dimension probing, including malformed input |
| `OptionsTest` | Source presets, defaults and settings fingerprints |
| `CatalogueStoreTest` | JSON round trip including the settings fingerprint and personal-use entries; rejection of unsafe file names and entries without provenance; persistence, removal and clearing; corrupt-file recovery |
| `SlideshowTest` | No-repeat shuffle order and attribution text |
| `PlacementTest` | Fill, Fit, Auto, Original and Stretch placement, aspect-ratio preservation, and decode sample size |
| `ImageSelectionTest` | Personal-use and 4K defaults, exact-4K floor, quality tiers, openly-licensed filtering, 4K-else-1080p fallback, and settings fingerprints |
| `RefreshCoordinatorTest` | Refresh staleness rules, including settings changes |
| `ArtworkViewportTest`, `SyntheticArtworkGeometryTest`, `DreamSettingsLauncherTest` | Aspect-ratio fitting, the synthetic fixture, and the settings shortcut fallback |
| `ImagePickerTest`, `StreamQueueTest` | Fair, random and in-order selection: persistence across restarts, weights, starvation bounds, new-arrival priority, tiny pools, streaming leads and failure cooldowns |
| `UserStateStoreTest`, `LibraryMigrationTest`, `LibraryMaintenanceTest`, `LibraryExplicitCommandsTest`, `BulkCommitTest`, `CacheCompositionTest` | Unreadable-is-not-empty, the cache-to-files migration, favourites protected on every removal path, blocks, aliases, explicit library commands and the cache composition summary |
| `TrashBinTest`, `CacheTrimmerTest`, `CacheBudgetTest`, `SizeSessionTest`, `SizeCommitTest`, `ByteSizesTest` | Undoable Tidy, Clear and shrink with failures injected at each step, restore rules, eviction order, replacement order (seen pictures only, fair share by source), budget and favourite saturation, preview-only size proposals and byte formatting |
| `SavingPolicyTest`, `RefreshGateTest`, `DownloadModeSettingsTest`, `RefreshProgressTest`, `SetupHealthTest`, `SetupNoticesTest`, `ScreensaverSelectionTest`, `DreamFreshnessTest`, `IdleTimeTest` | The single saving policy across every trigger, capability states, setup notices, screensaver selection and idle-time read/write outcomes |
| `CosmosSettingsTest`, `SettingsShellTest`, `PickerModelTest`, `HomeSummariesTest`, `PreviewPlacementTest`, `SelectionSnapshotTest`, `StartupStatusTest`, `LibraryModelTest`, `StorageBarModelTest`, `AsyncImageCacheTest` | Preferences and reset coverage, settings shell and focus, pickers, summary wording, preview mapping, calm-start timing, the library list model, the storage bar and thumbnail caching |

Tests do not contact live services. During development, the parser and the complete admission pipeline were also exercised once against the live ESA/Hubble and ESA/Webb sites in a temporary, uncommitted test (2026-11-09). Both policies matched. The pipeline admitted 4 Observation images with full credits and correctly rejected a Collage.
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
- The original CI-signed package remains installed and selected. A local debug APK with a different signing certificate was not installed over it.

#### Revised fixture and side-by-side upgrade check

To avoid replacing or uninstalling the original installation, a pair of test APKs using package `com.cosmos.screensaver.debug.m001test`, signed with a local debug key, was installed alongside it. On version 1 (versionCode 1), the user selected and opened **Cosmos M001 test** through the TV's screensaver UI. The user confirmed that all four white corner markers were visible and noted a small margin of background around each.

The user then restored the original Cosmos selection and returned to Home. While the test app was no longer selected or running, version 2 (versionCode 2) was installed over version 1 using the same package and signing certificate. Android accepted the update and reports version 2; the original Cosmos package remains installed and selected. The test APK requested no Android permissions and its DreamService registration remained present.

This verifies the separate test package's versioned install/update path, but **does not verify that Android retains the selection when the selected DreamService app itself is upgraded**, because the user restored the original selection before the update. No application-data migration was exercised. The test packages are session artifacts, not repository files. A physical offline test remains deferred until the app has network-dependent functionality.

## Full application — physical checks

Verified remotely over ADB on the Sony XR-65X90L on 2026-11-08:

- The first refresh on the TV took about 5 minutes. It admitted 11 images and excluded 17.
- After the policy 3 update, the outdated entries were re-validated automatically. The result was 12 images, all with verbatim credits.
- The dream started through Somnambulator and showed fitted images with the full caption.
- The home screen status is correct.

Still to check by hand, with the remote:

1. Install version 0.2.0 and open Cosmos. Confirm the status shows images downloading and then becoming ready.
2. **Preview** shows images fitted without cropping. Each image has a readable title, full credit, source, and licence, and the caption alternates corners. Any remote key exits.
3. Interval, source on/off, **Look for new pictures now**, and **Clear** all work with the D-pad.
4. Select Cosmos as the screensaver. When idle, the slideshow starts and cross-fades at the chosen interval. The remote dismisses it.
5. With the network disconnected, the screensaver still plays cached images.
6. Leave it running for several hours. Check memory and stability and confirm there are no crashes (`adb logcat -s CosmosDream CosmosSlideshow CosmosRefresh`).
7. Upgrade from 0.1.0 to 0.2.0 using the same signing key, and confirm the screensaver selection is retained.

### Version 0.5.0 — hand and device checks

Unit tests: 487 pass (`gradle --no-daemon :app:testDebugUnitTest`); lint 0 errors.

Checked over ADB on the Sony TV (D-pad key counts and screenshots; see the plan log for evidence): Home and every topic page; Download new pictures (Automatically, Not on limited data, Paused, and the explicit "Look for new pictures now" override); the Sources page (Off, Less, Normal, More); idle time on "When it starts"; the Saved pictures browser, favourites and blocks; Size, Tidy up and Clear confirmations (Cancel-first); caption sizes; and stream-only.

By eye, on the TV:

1. **Calm start.** Start the screensaver from idle, warm and cold. There is no starfield, wireframe or logo flash; the screen is black, then the first picture fades in.
2. **Credit size.** From the armchair, Subtle and Detailed credits are readable but not large; Credit size changes them noticeably.
3. **Focus and readability.** The focused row is obvious, text is readable, the storage bar is understandable, and fast D-pad scrolling in Saved pictures keeps up.
4. **Android "Clear cache"** (Settings → Apps → Cosmos → Clear cache) leaves saved pictures, favourites and blocks untouched. "Clear data" removes everything and is expected to.

### Version 0.4.0 — superseded by the 0.5.0 list above

1. Video after the screensaver: let the screensaver run, dismiss it (and separately let the TV sleep from it), then play video in iPlayer or YouTube and check the home-screen previews. Video must fill its normal area. `adb logcat | grep "4K/2K UI Coexist"` must never show "Launch 4K UI" for a Cosmos layer.
2. Open Cosmos. The settings screen is navigable with the D-pad, and the status pane updates as images arrive from the six sources.
3. Preview with the defaults. Images fill the screen without distortion, are sharp, and credits are faint and fade after about 15 seconds. Try each Picture fit and Credits option.
4. Change the Picture quality, licence filter and source preset, and confirm that a refresh starts.
5. Leave the TV in standby for a day and check whether the daily refresh job ran (Android may defer it).

### Version 0.3.0 — checked or superseded by the 0.4.0 list above

1. Open Cosmos. The settings screen is navigable with the D-pad: up and down move between rows, OK and left/right change values, and the status pane updates as images arrive.
2. Preview with the defaults. Images fill the screen without distortion, and credits are small and translucent.
3. Try each Picture fit and Credits option in Preview.
4. Change the Picture quality and Licence filter settings, and confirm that a refresh starts.

## Remaining work

- Release signing and Play publication (current Android TV requirements, data safety form, and store listing).
- Optional: more ESA/Hubble or ESA/Webb discovery listings, and a manual image browser.