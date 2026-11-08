# Image licensing and admission policy

## Governing rule

Cosmos displays an image only when its rights basis is explicit, verifiable, and applicable to this use. The validator fails closed: missing, unclear, contradictory, or ambiguous evidence means rejection.

The fact that an image is discoverable through an API, downloadable, hosted by NASA or ESA, or displayed on an official website does not itself grant permission to cache or display it in an entertainment/screensaver application.

## NASA

Admit only material that is demonstrably NASA-owned and public domain, with no conflicting rights notice or third-party restriction.

Reject:
- Third-party copyrighted material.
- Contractor, partner, or contributor material where the applicable rights are not clear.
- Images credited as used by permission or subject to separate restrictions.
- Records with missing, contradictory, or ambiguous rights information.

NASA Images and Video Library API access is a discovery mechanism, not a licence. Inspect the individual asset record and source context.

[NASA's media usage guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/) describe imagery as not in the public domain while also saying NASA content is generally not subject to U.S. copyright. Neither statement establishes an individual asset's rights or that the intended screensaver use is permitted. The guidelines also discuss third-party material and separate restrictions involving logos, endorsement, and identifiable people. Check those restrictions independently; reject the asset if their applicability or any necessary permission is unclear.

## ESA

The initial ESA scope is restricted to individually verified ESA/Hubble assets explicitly licensed under Creative Commons Attribution 4.0 International (CC BY 4.0), or another licence separately approved through a documented decision.

Record the exact licence and comply with attribution and indication-of-changes requirements. The [ESA/Hubble usage terms](https://esahubble.org/copyright/) require the full image credit to remain clear, visible, and unaltered, and exclude logos from the general permission. Do not assume that all ESA multimedia is reusable under the same terms or infer an individual asset's rights solely from its presence on the site. Exclude general ESA archive assets unless the exact asset terms clearly permit this use or written permission has been obtained and recorded.

## Evidence availability

Spot checks on 2026-11-08 show why discovery metadata alone is insufficient:

- A [NASA Images API search result for `PIA01973`](https://images-api.nasa.gov/search?nasa_id=PIA01973&media_type=image) had no structured copyright or licence field. This is one record, not a claim about all NASA records; additional asset-specific evidence is necessary.
- The checked [ESA/Hubble asset page](https://esahubble.org/images/heic1509a/) included a credit and a site-wide usage-policy link, but no asset-specific licence statement in its page markup. A general policy link alone does not meet the Cosmos per-asset evidence requirement.

These observations do not establish that either asset is unusable. They do mean that an implementation must find and validate sufficient asset-specific evidence or reject the candidate, rather than silently treating a provider-wide default as approval.

## Current validator boundary

The standalone `LicenceValidator` defaults to rejecting every candidate because no production evidence verifier exists yet. It does not retrieve images or evidence, and a rights clearance is not catalogue admission.

An evidence verifier must establish that an authoritative, asset-specific statement applies to the exact asset, complete credit, intended caching and screensaver use, and all rights in the image. It must reject missing, conflicting, restricted, or uncertain evidence. The validator checks the resulting attestation, but does not independently establish the legal meaning or authenticity of its text. Source URL checks alone are not verification. See [ADR 0002](decisions/0002-rights-evidence-gate.md).

The current validator accepts only an explicitly supplied ISO country code and personal, non-commercial screensaver use. It binds that context to the evidence and resulting rights clearance; it does not infer a territory from device settings or request location access. An unknown territory or unsupported use is rejected. In particular, U.S. public-domain status must not be assumed to establish rights in another territory.

The current ESA/Hubble URL guard accepts only `esahubble.org`, `www.esahubble.org`, and `cdn.esahubble.org`; the official ESA/Hubble page links to the latter for image files. This host allowlist does not approve any asset or replace rights verification. Other ESA hosts require a documented policy decision.

## Required provenance

Every admitted asset must retain:

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

Attribution must be displayed with the image in the screensaver. It must remain associated with the cached asset offline.

## Admission process

1. Discover a candidate from an approved provider.
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
