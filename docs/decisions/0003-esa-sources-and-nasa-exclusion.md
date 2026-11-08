# ADR 0003: ESA/Hubble and ESA/Webb as the initial sources; NASA endpoints excluded

- Status: Accepted
- Date: 2026-11-09

## Context

The user accepted the Milestone 001 proof of concept on the Sony XR-65X90L, so discovery and ingestion are now unblocked. A source can be used only if Cosmos can establish rights for each asset without a backend.

- **ESA/Hubble** (`esahubble.org`) and **ESA/Webb** (`esawebb.org`) run the same publishing platform. Each usage-policy page states that "[ESA/Hubble | ESA/Webb] images, videos and web texts are released under the Creative Commons Attribution 4.0 International license and may on a non-exclusive basis be reproduced without fee provided they are clearly and visibly credited", unless explicitly stated otherwise. Every image page shows its own credit line, links that policy, and carries the asset-level notice "Crediting this image with the full credit line, in a visible way is MANDATORY, if you want to use it without paying a fee." Pages give the image type (for example Observation or Collage), its pixel size, and links to the image files on the site's CDN.
- **NASA Images and Video Library** records have no structured per-asset rights field. NASA's media guidelines describe US copyright status only, and the target TV is outside the US. Cosmos cannot establish asset-level rights for the intended territory from these endpoints.
- **ESA.int multimedia** uses varying per-asset licences, including CC BY-SA 3.0 IGO, and is mid-migration without a stable feed.

## Decision

1. The initial providers are ESA/Hubble and ESA/Webb only. The HTTPS endpoint allow-list contains `esahubble.org`, `esawebb.org`, their `www.` and `cdn.` hosts, and nothing else. It is enforced for every request and every redirect hop.
2. Direct NASA endpoints and ESA.int are excluded. NASA imagery still appears where ESA/Hubble or ESA/Webb publishes it under its own policy, with NASA in the credit.
3. For each asset, the `DjangoplicityEvidenceVerifier` attests CC BY 4.0 only when **all** of these hold, using pages retrieved during the same refresh:
   - The site's usage-policy page still contains the exact licence sentence above and links the CC BY 4.0 legal code. If the sentence changes, every asset from that site is withdrawn.
   - The asset page is for the requested ID, has a non-empty credit line, shows the mandatory-credit notice, and links the usage policy.
   - The image URL is one of the page's own CDN renditions.
   - The title, caption and credit contain no wording that suggests an exception, such as ©, "copyright", "all rights reserved", "permission", "courtesy", or another CC variant. Any such wording means rejection.

   This supplements ADR 0002. A collection-wide policy is still not enough by itself; it must be combined with the asset-level notice, the credit, and the absence of exception wording on the asset's own page.
4. Admission additionally requires all of the following:
   - Type `Observation`, which excludes collages, illustrations, charts and composites with logos.
   - A landscape image.
   - A long edge of at least 1600 px.
   - JPEG magic bytes and a decodable image.
   - Decoded proportions that match the size published on the page.
   - A size of 30 MB or less.
   - A SHA-256 recorded at download time.
5. Display reproduces the full credit unaltered, together with the title, source, licence name and source page. The image is only scaled to fit, never cropped or stretched. That is recorded as the alteration status.
6. The use territory is the device locale's country. If it is unknown, every asset is rejected. CC BY 4.0 is a worldwide licence, so this keeps ADR 0002's binding without requesting location access.

## Consequences

- If either site changes its markup, its images are rejected (fail closed) until the parser is updated. Existing cached images remain while the policy check still passes.
- The rights policy version is now `3` (version 2 introduced these sources; version 3 corrected credit-text extraction). On each refresh, entries validated under an older policy version are removed and re-validated.
- No NASA-only imagery is shown. Adding a NASA source needs a new ADR describing asset-level evidence that applies outside the US.
- Refreshes run only when the app is opened, when the screensaver starts and the catalogue is stale, or on request. No background scheduler or extra permission is used. Only `INTERNET` is requested.
