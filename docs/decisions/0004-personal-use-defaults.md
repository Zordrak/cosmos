# ADR 0004: Personal-use defaults, optional licence checking, multiple sources and native 4K

- Status: Accepted (revised for 0.4.0)
- Date: 2026-11-09

Supersedes the default behaviour of [ADR 0002](0002-rights-evidence-gate.md) and [ADR 0003](0003-esa-sources-and-nasa-exclusion.md). Their rights gate still exists, but it now runs only when the user turns on "Only openly licensed images". The owner's instructions override any repository guideline that conflicts with this ADR.

## Context

Cosmos is, for now, used only by its owner on their own television and is not distributed. The owner's position is that showing a publicly available image on a personal TV is no different from viewing it in a browser, so availability, not licence, is the default test. The owner wants the best possible pictures, filling the whole screen, at 4K.

## Decision

1. **Licence filter** ("Only openly licensed images"), **off by default**.
   - Off: any image from an enabled source is eligible. Entries record the rights basis `NOT_CHECKED_PERSONAL_USE`. No policy page is fetched and no evidence is stored.
   - On: the original fail-closed verification runs. ESA/Hubble, ESA/Webb, ESO and NOIRLab images must pass `LicenceValidator` against the site's CC BY 4.0 policy. NASA and APOD images are accepted as NASA public-domain works unless their page marks them as copyrighted. Anything else is rejected.
2. **Sources.** All are enabled by default and can be switched on and off individually or through a collection preset (Everything, Deep space, Picture of the day, NASA only, Custom): ESA/Hubble, ESA/Webb, ESO, NOIRLab, the NASA Image and Video Library, and NASA's Astronomy Picture of the Day. Requests are restricted to a host allow-list. Further public sources can be added with a `SourceAdapter` and the allow-list.
3. **Content.** Real observations only (no art, charts, people or hardware), landscape from 4:3 to 21:9.
4. **Picture quality.** The default is exactly 3840×2160 or larger, with no tolerance on either dimension. Images with a long edge above 7680 px are never kept, because no commercial TV exceeds 8K. Other tiers remain (4K else 1080p, 1080p+, 720p+, 480p+, Any). Download limits are 30 MiB for publication renditions and 60 MiB for full-resolution ones.
5. **Picture fit.** **Fill** is the default: it covers the screen without distortion and crops the overflow. The other modes are Fit, Stretch (the only mode that distorts), Original (one image pixel per panel pixel) and Auto (fills when the crop is at most 15% of the image area).
6. **Credits.** **Subtle** is the default: faint boxless text that fades after 15 seconds and alternates between the lower corners. **Detailed** shows title, credit and source on a soft backing. **Off** shows nothing.
7. **Storage.** Up to 150 images and 2 GB, and always leaving free space of at least the larger of 3 GB and 15% of the device's storage. The library lives in the app cache directory, so the system may reclaim it; it is rebuilt by the next refresh. When the library is full, a refresh swaps in at most 15 new images, evicting images that no longer qualify first and then the oldest. When there is not enough space, nothing is downloaded and settings say so. Streaming without caching is not implemented.
8. **Refreshing.** On opening the app, on starting the screensaver when a refresh is due, and through a daily job on an unmetered network. Android may defer that job while the TV is in standby.
9. **Native 4K.** The slideshow draws into a `SurfaceView` whose buffer is fixed to the panel's physical size (up to 3840×2160), because a normal view is composited at the logical 1920×1080. It falls back to a 1080p buffer if 4K cannot be allocated. A test pattern activity (`am start -n com.cosmos.screensaver.debug/com.cosmos.screensaver.screensaver.TestPatternActivity`) lets the owner check that the panel is receiving true 4K.
10. Changing the licence filter or quality changes a selection fingerprint stored with the catalogue. That retries rejections which depended on the old settings.

## Consequences

- With the licence filter off, none of the attribution or rights guarantees of ADR 0002 apply. That is acceptable while Cosmos is not distributed.
- **Before any distribution to other users**, this ADR must be revisited: licence checking would need to become the default and credits could no longer be switched off.
- The previous (0.3.x) image library, stored in app files, is deleted on first launch of 0.4.0.
- No backend is involved, and downloads go directly from each source to the TV.
