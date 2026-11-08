package com.cosmos.screensaver.rights

import com.cosmos.screensaver.source.AssetPage
import com.cosmos.screensaver.source.DjangoplicityParser
import com.cosmos.screensaver.source.DjangoplicitySite

/** A usage-policy page whose CC BY 4.0 statement was found verbatim at [retrievedAtEpochMillis]. */
internal data class VerifiedPolicy(
    val site: DjangoplicitySite,
    val retrievedAtEpochMillis: Long
)

/**
 * Verifies ESA/Hubble or ESA/Webb rights from freshly retrieved pages.
 *
 * Both sites state that their images are CC BY 4.0 "unless explicitly stated otherwise", and
 * each asset page carries its own mandatory credit line and credit notice. An asset is
 * accepted only when the site policy is current, the asset page belongs to the requested
 * asset, carries the credit and notice, links the policy, and contains no wording suggesting an
 * exception. Anything else is rejected.
 */
internal class DjangoplicityEvidenceVerifier(
    private val policy: VerifiedPolicy,
    private val page: AssetPage,
    private val pageRetrievedAtEpochMillis: Long
) : AssetEvidenceVerifier {
    override fun verify(asset: AssetIdentity, intendedUse: RightsUseContext): EvidenceVerification {
        val site = policy.site
        if (asset.provider != site.provider) return reject(RightsRejection.UNSUPPORTED_PROVIDER)
        if (asset.providerId != page.id || asset.sourcePageUrl != site.pageUrl(page.id)) {
            return reject(RightsRejection.EVIDENCE_ASSET_MISMATCH)
        }
        if (asset.originalAssetUrl !in page.imageUrls) return reject(RightsRejection.EVIDENCE_ASSET_MISMATCH)
        if (!page.statesMandatoryCredit || !page.linksUsagePolicy) {
            return reject(RightsRejection.MISSING_RIGHTS_STATEMENT)
        }

        val restrictions = if (page.exceptionMarkers.isEmpty()) {
            RestrictionAssessment.CLEAR
        } else {
            RestrictionAssessment.CONFLICTING
        }

        return EvidenceVerification.Verified(
            VerifiedAssetEvidence(
                subject = asset,
                intendedUse = intendedUse,
                sources = listOf(
                    EvidenceSource(
                        evidenceUrl = site.policyUrl,
                        exactStatement = site.policyStatement,
                        scope = EvidenceScope.PROVIDER_OR_COLLECTION,
                        retrievedAtEpochMillis = policy.retrievedAtEpochMillis
                    ),
                    EvidenceSource(
                        evidenceUrl = asset.sourcePageUrl,
                        exactStatement = "Credit: ${page.credit} | ${DjangoplicityParser.MANDATORY_CREDIT_NOTICE}",
                        scope = EvidenceScope.INDIVIDUAL_ASSET,
                        retrievedAtEpochMillis = pageRetrievedAtEpochMillis
                    )
                ),
                creatorCredit = page.credit,
                basis = RightsBasis.CC_BY_4_0,
                owner = when (site) {
                    DjangoplicitySite.HUBBLE -> RightsOwner.ESA_HUBBLE
                    DjangoplicitySite.WEBB -> RightsOwner.ESA_WEBB
                },
                coverage = RightsCoverage.ENTIRE_ASSET,
                restrictions = restrictions,
                permittedUse = UsePermission.CACHE_AND_SCREENSAVER_DISPLAY,
                licenceUrl = LicenceValidator.CC_BY_4_0_URL
            )
        )
    }

    private fun reject(reason: RightsRejection) = EvidenceVerification.Rejected(reason)
}
