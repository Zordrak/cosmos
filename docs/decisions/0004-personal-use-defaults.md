# ADR 0004: Personal-use defaults, optional licence checking and quality tiers

- Status: Accepted
- Date: 2026-11-09

Partly supersedes [ADR 0002](0002-rights-evidence-gate.md) and [ADR 0003](0003-esa-sources-and-nasa-exclusion.md): their rights gate still exists, but it now runs only when the user turns licence checking on.

## Context

Cosmos is, for now, used only by its owner on their own television. It is not distributed. The owner asked for:

- the best available images rather than only those with a verified licence;
- 4K images by default;
- edge-to-edge pictures without distortion;
- subtler, optional credits;
- more public sources than ESA/Hubble and ESA/Webb.

The owner's position is that displaying publicly available images on a personal TV is no different from viewing them in a browser. The fail-closed rights gate rejected a large share of good images, and the 1080p-or-smaller publication renditions were visibly soft on a 4K panel.

## Decision

1. **Licence filter** setting with two modes:
   - **Any public image** (default): any image from an enabled source is eligible. Entries record the rights basis `NOT_CHECKED_PERSONAL_USE` with no evidence, and the source's usage policy page is not fetched.
   - **CC BY only**: the original verifier and `LicenceValidator` run unchanged and fail closed. Only `CC_BY_4_0` entries validated under the current policy version are shown.
2. **Picture quality** setting. Both image dimensions must reach the tier, within a 5% tolerance:
   - **4K only** (default): 3840×2160;
   - **4K, else 1080p**: 1080p images are also used while fewer than 20 4K images are available;
   - **1080p+**, **720p+**, **480p+**, **Any**.
   Djangoplicity publication JPEGs are capped at 4000 px wide, so when they cannot meet the tier, Cosmos downloads the full-resolution `large` rendition instead. Download size limits are 30 MB for publication renditions and 60 MB for full-resolution ones. Images above about 8K are therefore not fetched in practice. The library is capped at 80 images and 1.5 GB.
3. **Picture fit** setting. **Fill** (default) covers the screen and crops the overflowing edges. The other modes are **Fit**, **Auto** (fill unless that would crop more than 20%), **Original** (one image pixel per panel pixel) and **Stretch**. Stretch distorts and is never used unless chosen.
4. **Credits** setting: **Subtle** (default; small, translucent, no panel), **Detailed** (the earlier full caption), or **Off**.
5. **Sources:** ESO (`eso.org`) and NOIRLab (`noirlab.edu`) are added. Like ESA/Hubble and ESA/Webb, they run on Djangoplicity and publish CC BY 4.0 policies, so they reuse the parser and the verifier. Accepted types are Observations, plus Photographic images from curated picture-of-the-week series (ESO `potw`, NOIRLab `iotw`). NASA's image API remains unused because of its low resolution, not its rights.
6. Changing the licence or quality setting changes a selection fingerprint stored with the catalogue. This triggers a refresh and retries rejections that depended on the old settings. Images that no longer qualify are hidden but kept while space allows, so switching back does not download them again.

## Consequences

- With the defaults, the attribution and licence guarantees of ADR 0002 do not apply. This is acceptable only while Cosmos is not distributed.
- **Before any distribution to other users**, this ADR must be revisited: licence checking must become the default, and credits must not be switchable off when the licence requires them.
- The TV composites apps at 1920×1080 and upscales to the panel. Only video planes use 4K. 4K sources therefore give a sharper downscale and allow cropping in Fill mode without upscaling, but still images are not shown at native 4K.
- The host allow-list remains, and there is still no backend.