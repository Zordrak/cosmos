# ADR 0004: Personal-use defaults, optional licence checking, multiple sources and native 4K

- Status: Accepted (revised for 0.5.0)
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
6. **Credits.** **Subtle** is the default: boxless text that fades after 15 seconds and alternates between the lower corners. **Detailed** shows title, credit and source on a soft backing. **Off** shows nothing. As of 0.5.0 the default text sizes are larger and a Credit size setting scales both styles. After armchair testing on the 65-inch TV it has five steps roughly 30% apart (Extra small 0.75×, Small 0.95×, Medium 1.25× default, Large 1.6×, Extra large 2.1×); the earlier four-step choice is not carried over.
7. **Storage.** Superseded by [ADR 0005](0005-saved-pictures-user-data-and-ordering.md): there is no picture-count cap, the size limit is user-chosen (Automatic by default), the free-space reserve of at least the larger of 3 GB and 15% remains, and saved pictures live in the app data directory, not the cache. When there is not enough space to keep a library, the screensaver streams instead: each image is downloaded to a scratch directory on demand, shown, and deleted, at most once a minute, with no library retained. If fewer than 10 images are saved it alternates saved and streamed slides. Streaming needs about 150 MiB free; below that, settings and the screensaver say so.
8. **Refreshing.** On opening the app, on starting the screensaver when a refresh is due, when a viewing setting changes, on request, and through a daily job on an unmetered network. Android may defer that job while the TV is in standby. As of 0.5.0 a single "Download new pictures" setting (Automatically, Not on limited data, Paused) governs all automatic saving; only the explicit "Look for new pictures now" command overrides it.
9. **Native 4K without a 4K app layer.** The slideshow originally drew into a `SurfaceView` fixed to the panel's physical 3840×2160. On the Sony X90L (MediaTek) any app layer larger than the 1080p UI triggers SurfaceFlinger's "4K/2K UI coexist" mode, and afterwards every app's video (iPlayer, YouTube, home-screen previews) is drawn in a quarter of the screen until reboot, even though the framebuffer is restored (the display's framebuffer projection keeps 4K bounds with 1080p content). No app-level API restores it. Video layers do not trigger the mode, so each picture is now converted to BT.709 limited-range 4:2:0 and wrapped as one uncompressed (I_PCM) H.264 frame, which the hardware AVC decoder renders at full panel resolution on the video plane (`SharpLayer`). No encoder is needed (this TV's encoders stop at 1080p). A 1080p overlay (`SlideshowView`) above it carries the cross-fade and credits: it covers the screen whenever the picture underneath changes, then fades its own copy away to reveal the native picture. Devices whose apps already run at panel resolution, or that cannot decode the size in hardware, use the overlay alone. The 4K test-pattern activity was removed because it created the unsafe layer.
10. Changing the licence filter or quality changes a selection fingerprint stored with the catalogue. That retries rejections which depended on the old settings.

## Consequences

- With the licence filter off, none of the attribution or rights guarantees of ADR 0002 apply. That is acceptable while Cosmos is not distributed.
- **Before any distribution to other users**, this ADR must be revisited: licence checking would need to become the default and credits could no longer be switched off.
- The previous (0.3.x) image library, stored in app files, is deleted on first launch of 0.4.0.
- No backend is involved, and downloads go directly from each source to the TV.
