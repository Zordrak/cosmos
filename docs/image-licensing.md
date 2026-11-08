# Image licensing and admission policy

## Current mode: personal use

Cosmos is currently used only by its owner on their own television. It is not distributed. [ADR 0004](decisions/0004-personal-use-defaults.md) makes licence checking a setting:

- **Any public image** (default): any publicly available image from an enabled, allow-listed source is shown. No rights check is run. Entries record the rights basis `NOT_CHECKED_PERSONAL_USE`. Credits are still recorded, falling back to the source name, and shown according to the Credits setting.
- **CC BY only:** the fail-closed policy below applies in full.

Before any distribution to other users, CC BY only must become the default and credits must not be switchable off.

## Governing rule (CC BY only mode)

In this mode, Cosmos displays an image only when its rights basis is explicit, verifiable, and applicable to this use. The validator fails closed: missing, unclear, contradictory, or ambiguous evidence means rejection.

The fact that an image is discoverable through an API, downloadable, hosted by NASA or ESA, or displayed on an official website does not itself grant permission to cache or display it in an entertainment/screensaver application.
## Current sources

See [ADR 0003](decisions/0003-esa-sources-and-nasa-exclusion.md) for the full decision.

- **ESA/Hubble** (`esahubble.org`), **ESA/Webb** (`esawebb.org`), **ESO** (`eso.org`) and **NOIRLab** (`noirlab.edu`) are the sources. All of them publish under CC BY 4.0 ([ESA/Hubble terms](https://esahubble.org/copyright/), [ESA/Webb terms](https://esawebb.org/copyright/), [ESO terms](https://www.eso.org/public/outreach/copyright/), [NOIRLab terms](https://noirlab.edu/public/copyright/)). In CC BY only mode, an asset is admitted only when all of the following are true:
  - The site policy still contains the exact licence sentence.
  - The asset's own page shows its credit line and the "full credit line … is MANDATORY" notice, and links that policy.
  - The image comes from the page's own CDN rendition.
  - The title, caption and credit contain no exception wording (©, "copyright", "all rights reserved", "permission", "courtesy", or other CC variants).
  - The page type is `Observation`, or `Photographic` in ESO's or NOIRLab's curated picture-of-the-week series. This excludes collages and illustrations, which may contain logos; logos are excluded from the permissions. The type rule also applies in Any public image mode, for content reasons.
- **NASA endpoints are not used.** Their images are mostly below 4K. In addition, the NASA Images API has no per-asset rights field, and NASA's guidance addresses only U.S. copyright. Cosmos cannot establish asset-level rights for a non-U.S. territory from it. NASA imagery appears only where ESA/Hubble or ESA/Webb publishes it under its own licence, with NASA in the credit.
- **ESA.int multimedia is excluded.** Licences vary per asset, and the site is being migrated.

Any new provider requires an ADR, an allow-list entry, and parser and validator tests.

## Attribution and alteration

In CC BY only mode with Detailed credits, the full credit line is reproduced verbatim, together with the title, source, licence name (CC BY 4.0), and source page address. Subtle credits show the title and full credit. Credits can be turned off, which is acceptable only for personal use.

Images are stored byte-for-byte as downloaded. They are scaled, and cropped or stretched only according to the user's Picture fit setting; they are never recoloured or annotated. This is recorded as the alteration status `UNALTERED_FILE_DISPLAY_PER_SETTINGS`.
## Validator boundary

`LicenceValidator` (policy version `3`) still rejects by default and checks every attestation independently. Its checks cover:

- the subject, use context, and full credit;
- the evidence scope, basis, ownership, coverage, and use permission;
- timestamps and approved hosts.

The use territory is the device locale's country. If the country is unknown, the asset is rejected, and Cosmos does not request location access.

The validator does not establish the legal meaning of the evidence text. That is the verifier's job, and the verifier is deliberately conservative: any doubt means rejection.
## Required provenance

Every admitted asset retains the following (rights fields and evidence are empty for `NOT_CHECKED_PERSONAL_USE` entries):

- Provider and stable provider identifier, where available.
- Source/description page URL.
- Original image asset URL.
- Creator, photographer, or required credit line.
- Exact licence or public-domain rights basis.
- URL to the evidence supporting that rights basis.
- Retrieval timestamp and validation timestamp.
- Rights-policy version used for the decision.
- Content checksum (SHA-256).
- Whether the image has been altered, cropped, or otherwise modified.
- Any required attribution text and licence link.

Attribution remains associated with the cached asset offline, and is displayed according to the Credits setting.

## Admission process (CC BY only mode)

1. Discover a candidate from an approved provider (site feed or Top 100 listing).
2. Retrieve its source metadata and rights evidence.
3. Apply provider-specific rules and the exact asset-level rights checks.
4. Reject if any required field or evidence is absent or unclear.
5. Download directly to the device.
6. Validate content type, dimensions, file integrity, and checksum.
7. Persist provenance and mark the asset approved only after all checks pass.
8. Make only approved assets available to the image selector.

A failed or interrupted download must never produce an approved catalogue entry.

## Repository and distribution

Do not commit third-party images to this repository or package them into the application. Use synthetic or original project-created fixtures for development and tests.

The repository's MIT licence applies only to Cosmos software and documentation. It does not license NASA or ESA images or override their individual rights terms.

## Policy changes

Any expansion to new providers, ESA collections, or licence types requires an explicit documented decision and corresponding validator tests before implementation.
