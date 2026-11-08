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

## ESA

The initial ESA scope is restricted to individually verified ESA/Hubble assets explicitly licensed under Creative Commons Attribution 4.0 International (CC BY 4.0), or another licence separately approved through a documented decision.

Record the exact licence and comply with attribution and indication-of-changes requirements. Do not assume that all ESA multimedia is reusable under the same terms. Exclude general ESA archive assets unless the exact asset terms clearly permit this use or written permission has been obtained and recorded.

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
