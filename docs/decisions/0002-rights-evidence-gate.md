# ADR 0002: Asset-specific rights evidence gate

- Status: Accepted; applies only when licence checking is on, per [ADR 0004](0004-personal-use-defaults.md)
- Date: 2026-11-08

## Context

Provider APIs, download links, credits, and collection-wide policies do not by themselves establish that a particular asset may be cached and displayed as a screensaver. Initial checks found that available NASA and ESA/Hubble metadata can lack an explicit asset-specific licence statement.

The rights decision must remain independent of provider retrieval and catalogue admission, and it must reject uncertainty rather than infer a licence from a provider.

## Decision

Implement a standalone rights validator with an explicit asset-evidence verifier boundary. The validator rejects by default: no production evidence verifier, image discovery, or catalogue ingestion is included at this stage.

A verifier may attest to evidence only after establishing its authority and binding it to the exact asset, full credit, rights basis, applicable use and territory, and all relevant restrictions. Collection-wide terms alone are insufficient. The validator independently checks that the attested subject, use context, full credit, evidence scope, supported basis, ownership, coverage, use permission, timestamps, and approved source URLs satisfy the policy.

The initial supported use is personal, non-commercial screensaver display and caching throughout an explicitly supplied ISO country. Unknown or different uses and unknown territories are rejected; the validator does not infer the territory or request location access. The verifier must reject unresolved regional restrictions within that country. In particular, a U.S. public-domain determination is not automatically applicable worldwide.

The source URL checks are a syntactic guard, not proof of a document's contents or authority. Future retrieval must also enforce approved destinations across redirects and verify the evidence itself before returning a verified result. Unexpected verifier errors must remain visible and cannot become approvals.

The validator returns a rights clearance with its evidence and policy version, not an approved catalogue entry. Image integrity, checksum, retrieval time, alteration status, full provenance, and attribution requirements must still be checked and persisted before any asset can be admitted or displayed.

## Consequences

- Without a real source-specific evidence verifier, every candidate is rejected.
- Unit tests use synthetic attestations; they do not establish the rights of real provider assets.
- A provider integration must not treat a site's general policy, a credit line, or a downloadable image as an asset-specific verification.
- Policy changes require a version change, new tests, and revalidation of previously approved evidence.
- The physical DreamService milestone remains the prerequisite for image discovery and catalogue ingestion.
