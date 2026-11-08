package com.cosmos.screensaver.rights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenceValidatorTest {
    @Test
    fun rejectsByDefaultWhenNoEvidenceVerifierExists() {
        assertEquals(
            RightsDecision.Rejected(RightsRejection.VERIFIER_UNAVAILABLE),
            LicenceValidator().validate(nasaCandidate())
        )
    }

    @Test
    fun preservesVerifiedNasaRightsAndProvenance() {
        val candidate = nasaCandidate().copy(creatorCredit = "NASA / Synthetic creator\nOther credit")
        val evidence = nasaEvidence(candidate)

        val decision = validator(evidence).validate(candidate)

        assertTrue(decision is RightsDecision.RightsCleared)
        val rights = (decision as RightsDecision.RightsCleared).rights
        assertEquals(identity(candidate), rights.asset)
        assertEquals(useContext(candidate), rights.intendedUse)
        assertEquals(candidate.creatorCredit, rights.creatorCredit)
        assertEquals(RightsBasis.NASA_PUBLIC_DOMAIN, rights.basis)
        assertEquals(evidence.sources, rights.evidenceSources)
        assertEquals(null, rights.licenceUrl)
        assertEquals(NOW, rights.validatedAtEpochMillis)
        assertEquals(LicenceValidator.POLICY_VERSION, rights.policyVersion)
    }

    @Test
    fun preservesAllEvidenceWithoutSharingTheVerifiersMutableList() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val sources = evidence.sources.toMutableList()
        sources.add(
            EvidenceSource(
                evidenceUrl = "https://nasa.gov/synthetic-policy",
                exactStatement = "Synthetic supplementary policy statement.",
                scope = EvidenceScope.PROVIDER_OR_COLLECTION,
                retrievedAtEpochMillis = RETRIEVED_AT
            )
        )

        val decision = validator(evidence.copy(sources = sources)).validate(candidate)
        assertTrue(decision is RightsDecision.RightsCleared)
        val rights = (decision as RightsDecision.RightsCleared).rights
        val expected = sources.toList()
        sources.clear()

        assertEquals(expected, rights.evidenceSources)
        assertThrows(UnsupportedOperationException::class.java) {
            (rights.evidenceSources as MutableList<EvidenceSource>).clear()
        }
    }

    @Test
    fun permitsExplicitEsaLicenceCoveringThirdPartyContributions() {
        val candidate = esaCandidate()
        val evidence = esaEvidence(candidate).copy(owner = RightsOwner.OTHER)

        val decision = validator(evidence).validate(candidate)

        assertTrue(decision is RightsDecision.RightsCleared)
        val rights = (decision as RightsDecision.RightsCleared).rights
        assertEquals(candidate.creatorCredit, rights.creatorCredit)
        assertEquals(RightsBasis.CC_BY_4_0, rights.basis)
        assertEquals(LicenceValidator.CC_BY_4_0_URL, rights.licenceUrl)
    }

    @Test
    fun passesTheExactRequestedTerritoryAndUseToTheVerifier() {
        val candidate = nasaCandidate().copy(
            intendedUse = RequestedUse("GB", RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)
        )
        val evidence = nasaEvidence(candidate)
        var seenAsset: AssetIdentity? = null
        var seenUse: RightsUseContext? = null
        val validator = LicenceValidator(
            verifier = AssetEvidenceVerifier { asset, intendedUse ->
                seenAsset = asset
                seenUse = intendedUse
                EvidenceVerification.Verified(evidence)
            },
            currentTimeMillis = { NOW }
        )

        assertTrue(validator.validate(candidate) is RightsDecision.RightsCleared)
        assertEquals(identity(candidate), seenAsset)
        assertEquals(useContext(candidate), seenUse)
    }

    @Test
    fun rejectsMalformedCandidatesBeforeCallingVerifier() {
        val candidate = nasaCandidate()
        val cases = listOf(
            candidate.copy(provider = null) to RightsRejection.UNSUPPORTED_PROVIDER,
            candidate.copy(providerId = null) to RightsRejection.INVALID_ASSET_ID,
            candidate.copy(providerId = "  ") to RightsRejection.INVALID_ASSET_ID,
            candidate.copy(providerId = " test ") to RightsRejection.INVALID_ASSET_ID,
            candidate.copy(sourcePageUrl = null) to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "http://images.nasa.gov/images/test") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "https://nasa.gov.evil.test/images/test") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "https://user@nasa.gov/images/test") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "https://nasa.gov:8443/images/test") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "https://nasa.gov/") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(sourcePageUrl = "https://nasa.gov/invalid url") to RightsRejection.INVALID_SOURCE_URL,
            candidate.copy(originalAssetUrl = null) to RightsRejection.INVALID_ASSET_URL,
            candidate.copy(originalAssetUrl = "https://esahubble.org/images/test.jpg") to RightsRejection.INVALID_ASSET_URL,
            candidate.copy(creatorCredit = null) to RightsRejection.MISSING_CREDIT,
            candidate.copy(creatorCredit = " \n ") to RightsRejection.MISSING_CREDIT,
            candidate.copy(intendedUse = null) to RightsRejection.MISSING_USE_CONTEXT,
            candidate.copy(intendedUse = RequestedUse(null, RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)) to
                RightsRejection.INVALID_USE_TERRITORY,
            candidate.copy(intendedUse = RequestedUse("ZZ", RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)) to
                RightsRejection.INVALID_USE_TERRITORY,
            candidate.copy(intendedUse = RequestedUse("us", RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)) to
                RightsRejection.INVALID_USE_TERRITORY,
            candidate.copy(intendedUse = RequestedUse("US ", RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER)) to
                RightsRejection.INVALID_USE_TERRITORY,
            candidate.copy(intendedUse = RequestedUse("US", null)) to RightsRejection.UNSUPPORTED_USE,
            candidate.copy(intendedUse = RequestedUse("US", RightsUseScope.OTHER)) to RightsRejection.UNSUPPORTED_USE,
            candidate.copy(intendedUse = RequestedUse("US", RightsUseScope.UNKNOWN)) to RightsRejection.UNSUPPORTED_USE
        )

        cases.forEach { (invalid, expected) ->
            var called = false
            val validator = LicenceValidator(
                verifier = AssetEvidenceVerifier { _, _ ->
                    called = true
                    EvidenceVerification.Rejected(RightsRejection.MISSING_EVIDENCE)
                }
            )

            assertEquals(RightsDecision.Rejected(expected), validator.validate(invalid))
            assertFalse(called)
        }
    }

    @Test
    fun rejectsEvidenceForAnotherAssetOrAnEntireCollection() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val source = evidence.sources.single()
        val cases = listOf(
            evidence.copy(subject = evidence.subject.copy(provider = AssetProvider.ESA_HUBBLE)) to
                RightsRejection.EVIDENCE_ASSET_MISMATCH,
            evidence.copy(subject = evidence.subject.copy(providerId = "another")) to
                RightsRejection.EVIDENCE_ASSET_MISMATCH,
            evidence.copy(subject = evidence.subject.copy(sourcePageUrl = "https://nasa.gov/another")) to
                RightsRejection.EVIDENCE_ASSET_MISMATCH,
            evidence.copy(subject = evidence.subject.copy(originalAssetUrl = "https://nasa.gov/another.jpg")) to
                RightsRejection.EVIDENCE_ASSET_MISMATCH,
            evidence.copy(intendedUse = evidence.intendedUse.copy(territory = "GB")) to
                RightsRejection.EVIDENCE_USE_MISMATCH,
            evidence.copy(intendedUse = evidence.intendedUse.copy(scope = RightsUseScope.OTHER)) to
                RightsRejection.EVIDENCE_USE_MISMATCH,
            evidence.copy(sources = emptyList()) to RightsRejection.MISSING_EVIDENCE,
            evidence.copy(sources = listOf(source.copy(scope = EvidenceScope.PROVIDER_OR_COLLECTION))) to
                RightsRejection.NON_ASSET_EVIDENCE,
            evidence.copy(sources = listOf(source.copy(scope = EvidenceScope.UNKNOWN))) to
                RightsRejection.AMBIGUOUS_EVIDENCE
        )

        cases.forEach { (invalid, expected) -> assertRejected(candidate, invalid, expected) }
    }

    @Test
    fun requiresApprovedEvidenceSourceAndFullMatchingCredit() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val source = evidence.sources.single()
        val invalidUrls = listOf(
            "http://nasa.gov/evidence",
            "https://nasa.gov.evil.test/evidence",
            "https://person@nasa.gov/evidence",
            "https://nasa.gov:8443/evidence",
            "https://nasa.gov/",
            "https://creativecommons.org/licenses/by/4.0/",
            "not a URL"
        )

        invalidUrls.forEach { url ->
            assertRejected(
                candidate,
                evidence.copy(sources = listOf(source.copy(evidenceUrl = url))),
                RightsRejection.INVALID_EVIDENCE_URL
            )
        }
        assertRejected(
            candidate,
            evidence.copy(sources = listOf(source.copy(exactStatement = "  "))),
            RightsRejection.MISSING_RIGHTS_STATEMENT
        )
        assertRejected(
            candidate,
            evidence.copy(creatorCredit = "NASA"),
            RightsRejection.CREDIT_MISMATCH
        )
        assertTrue(
            validator(evidence.copy(sources = listOf(source.copy(evidenceUrl = "${source.evidenceUrl}#rights"))))
                .validate(candidate) is RightsDecision.RightsCleared
        )
    }

    @Test
    fun rejectsUnapprovedEsaSubdomains() {
        val candidate = esaCandidate()
        val evidence = esaEvidence(candidate)
        val unapproved = "https://other.esahubble.org/images/synthetic-esa-1.jpg"

        assertEquals(
            RightsDecision.Rejected(RightsRejection.INVALID_ASSET_URL),
            validator(evidence).validate(candidate.copy(originalAssetUrl = unapproved))
        )
        val source = evidence.sources.single().copy(evidenceUrl = unapproved)
        assertRejected(
            candidate,
            evidence.copy(sources = listOf(source)),
            RightsRejection.INVALID_EVIDENCE_URL
        )
    }

    @Test
    fun acceptsTheOfficialWwwEsaHubbleHostWhenEvidenceIsVerified() {
        val candidate = esaCandidate().copy(
            sourcePageUrl = "https://WWW.ESAHUBBLE.ORG/images/synthetic-esa-1/"
        )
        val evidence = esaEvidence(candidate)
        val source = evidence.sources.single().copy(
            evidenceUrl = "https://www.esahubble.org/images/synthetic-esa-1/rights"
        )

        assertTrue(
            validator(evidence.copy(sources = listOf(source)))
                .validate(candidate) is RightsDecision.RightsCleared
        )
    }

    @Test
    fun rejectsUnsafeSupplementaryEvidenceEvenWhenAnAssetStatementExists() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val primary = evidence.sources.single()
        val supplementary = EvidenceSource(
            evidenceUrl = "https://nasa.gov/synthetic-policy",
            exactStatement = "Synthetic supplementary policy statement.",
            scope = EvidenceScope.PROVIDER_OR_COLLECTION,
            retrievedAtEpochMillis = RETRIEVED_AT
        )
        val cases = listOf(
            supplementary.copy(scope = EvidenceScope.UNKNOWN) to RightsRejection.AMBIGUOUS_EVIDENCE,
            supplementary.copy(evidenceUrl = "https://other.example/policy") to RightsRejection.INVALID_EVIDENCE_URL,
            supplementary.copy(exactStatement = " ") to RightsRejection.MISSING_RIGHTS_STATEMENT,
            supplementary.copy(retrievedAtEpochMillis = NOW + 1L) to RightsRejection.INVALID_EVIDENCE_TIMESTAMP
        )

        cases.forEach { (invalid, expected) ->
            assertRejected(candidate, evidence.copy(sources = listOf(primary, invalid)), expected)
        }
    }

    @Test
    fun rejectsPartialUnknownRestrictedOrConflictingRights() {
        val candidate = esaCandidate()
        val evidence = esaEvidence(candidate)
        val cases = listOf(
            evidence.copy(coverage = RightsCoverage.PARTIAL) to RightsRejection.INCOMPLETE_RIGHTS_COVERAGE,
            evidence.copy(coverage = RightsCoverage.UNKNOWN) to RightsRejection.INCOMPLETE_RIGHTS_COVERAGE,
            evidence.copy(restrictions = RestrictionAssessment.RESTRICTED) to RightsRejection.PERMISSION_RESTRICTED,
            evidence.copy(restrictions = RestrictionAssessment.CONFLICTING) to RightsRejection.CONFLICTING_EVIDENCE,
            evidence.copy(restrictions = RestrictionAssessment.UNKNOWN) to RightsRejection.AMBIGUOUS_EVIDENCE,
            evidence.copy(permittedUse = UsePermission.RESTRICTED) to RightsRejection.PERMISSION_RESTRICTED,
            evidence.copy(permittedUse = UsePermission.UNKNOWN) to RightsRejection.AMBIGUOUS_EVIDENCE
        )

        cases.forEach { (invalid, expected) -> assertRejected(candidate, invalid, expected) }
    }

    @Test
    fun requiresNasaFederalWorkAndPublicDomainBasis() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val cases = listOf(
            evidence.copy(basis = RightsBasis.UNKNOWN) to RightsRejection.UNSUPPORTED_RIGHTS_BASIS,
            evidence.copy(basis = RightsBasis.CC_BY_4_0) to RightsRejection.UNSUPPORTED_RIGHTS_BASIS,
            evidence.copy(owner = RightsOwner.OTHER) to RightsRejection.INVALID_OWNERSHIP,
            evidence.copy(owner = RightsOwner.UNKNOWN) to RightsRejection.INVALID_OWNERSHIP,
            evidence.copy(licenceUrl = LicenceValidator.CC_BY_4_0_URL) to RightsRejection.CONFLICTING_EVIDENCE
        )

        cases.forEach { (invalid, expected) -> assertRejected(candidate, invalid, expected) }
    }

    @Test
    fun requiresExplicitFullEsaCcByFourLicence() {
        val candidate = esaCandidate()
        val evidence = esaEvidence(candidate)
        val cases = listOf(
            evidence.copy(basis = RightsBasis.NASA_PUBLIC_DOMAIN) to RightsRejection.UNSUPPORTED_RIGHTS_BASIS,
            evidence.copy(basis = RightsBasis.UNKNOWN) to RightsRejection.UNSUPPORTED_RIGHTS_BASIS,
            evidence.copy(owner = RightsOwner.UNKNOWN) to RightsRejection.INVALID_OWNERSHIP,
            evidence.copy(licenceUrl = null) to RightsRejection.INVALID_LICENCE_URL,
            evidence.copy(licenceUrl = "https://creativecommons.org/licenses/by-nc/4.0/") to
                RightsRejection.INVALID_LICENCE_URL,
            evidence.copy(licenceUrl = "https://creativecommons.org.evil.test/licenses/by/4.0/") to
                RightsRejection.INVALID_LICENCE_URL
        )

        cases.forEach { (invalid, expected) -> assertRejected(candidate, invalid, expected) }
    }

    @Test
    fun rejectsMissingOrFutureEvidenceTimestamps() {
        val candidate = nasaCandidate()
        val evidence = nasaEvidence(candidate)
        val source = evidence.sources.single()

        listOf(0L, -1L, NOW + 1L).forEach { retrievedAt ->
            assertRejected(
                candidate,
                evidence.copy(sources = listOf(source.copy(retrievedAtEpochMillis = retrievedAt))),
                RightsRejection.INVALID_EVIDENCE_TIMESTAMP
            )
        }
        val invalidClock = validator(evidence, now = 0L)
        assertEquals(
            RightsDecision.Rejected(RightsRejection.INVALID_EVIDENCE_TIMESTAMP),
            invalidClock.validate(candidate)
        )
    }

    @Test
    fun surfacesVerificationFailuresWithoutApproving() {
        val failures = listOf(
            RightsRejection.MISSING_EVIDENCE,
            RightsRejection.AMBIGUOUS_EVIDENCE,
            RightsRejection.CONFLICTING_EVIDENCE,
            RightsRejection.PERMISSION_RESTRICTED
        )

        failures.forEach { failure ->
            val validator = LicenceValidator(
                verifier = AssetEvidenceVerifier { _, _ -> EvidenceVerification.Rejected(failure) }
            )
            assertEquals(RightsDecision.Rejected(failure), validator.validate(nasaCandidate()))
        }
    }

    @Test
    fun propagatesUnexpectedVerifierErrors() {
        val failure = IllegalStateException("Synthetic verifier failure")
        val validator = LicenceValidator(
            verifier = AssetEvidenceVerifier { _, _ -> throw failure }
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            validator.validate(nasaCandidate())
        }

        assertSame(failure, thrown)
    }

    private fun assertRejected(
        candidate: RightsCandidate,
        evidence: VerifiedAssetEvidence,
        expected: RightsRejection
    ) {
        assertEquals(RightsDecision.Rejected(expected), validator(evidence).validate(candidate))
    }

    private fun validator(evidence: VerifiedAssetEvidence, now: Long = NOW) = LicenceValidator(
        verifier = AssetEvidenceVerifier { _, _ -> EvidenceVerification.Verified(evidence) },
        currentTimeMillis = { now }
    )

    private fun nasaCandidate() = RightsCandidate(
        provider = AssetProvider.NASA,
        providerId = "synthetic-nasa-1",
        sourcePageUrl = "https://images.nasa.gov/details/synthetic-nasa-1",
        originalAssetUrl = "https://images-assets.nasa.gov/image/synthetic-nasa-1/original.jpg",
        creatorCredit = "NASA / Synthetic creator",
        intendedUse = requestedUse()
    )

    private fun esaCandidate() = RightsCandidate(
        provider = AssetProvider.ESA_HUBBLE,
        providerId = "synthetic-esa-1",
        sourcePageUrl = "https://esahubble.org/images/synthetic-esa-1/",
        originalAssetUrl = "https://cdn.esahubble.org/images/synthetic-esa-1.jpg",
        creatorCredit = "ESA/Hubble / Synthetic contributors",
        intendedUse = requestedUse()
    )

    private fun requestedUse() = RequestedUse(
        territory = "US",
        scope = RightsUseScope.PERSONAL_NONCOMMERCIAL_SCREENSAVER
    )

    private fun useContext(candidate: RightsCandidate): RightsUseContext {
        val requestedUse = requireNotNull(candidate.intendedUse)
        return RightsUseContext(requireNotNull(requestedUse.territory), requireNotNull(requestedUse.scope))
    }

    private fun identity(candidate: RightsCandidate) = AssetIdentity(
        provider = requireNotNull(candidate.provider),
        providerId = requireNotNull(candidate.providerId),
        sourcePageUrl = requireNotNull(candidate.sourcePageUrl),
        originalAssetUrl = requireNotNull(candidate.originalAssetUrl)
    )

    private fun nasaEvidence(candidate: RightsCandidate) = VerifiedAssetEvidence(
        subject = identity(candidate),
        intendedUse = useContext(candidate),
        sources = listOf(
            EvidenceSource(
                evidenceUrl = "https://images.nasa.gov/details/synthetic-nasa-1/rights",
                exactStatement = "Synthetic asset-specific NASA public-domain statement.",
                scope = EvidenceScope.INDIVIDUAL_ASSET,
                retrievedAtEpochMillis = RETRIEVED_AT
            )
        ),
        creatorCredit = requireNotNull(candidate.creatorCredit),
        basis = RightsBasis.NASA_PUBLIC_DOMAIN,
        owner = RightsOwner.NASA_FEDERAL_GOVERNMENT_WORK,
        coverage = RightsCoverage.ENTIRE_ASSET,
        restrictions = RestrictionAssessment.CLEAR,
        permittedUse = UsePermission.CACHE_AND_SCREENSAVER_DISPLAY,
        licenceUrl = null
    )

    private fun esaEvidence(candidate: RightsCandidate) = VerifiedAssetEvidence(
        subject = identity(candidate),
        intendedUse = useContext(candidate),
        sources = listOf(
            EvidenceSource(
                evidenceUrl = "https://esahubble.org/images/synthetic-esa-1/rights",
                exactStatement = "Synthetic asset-specific CC BY 4.0 statement.",
                scope = EvidenceScope.INDIVIDUAL_ASSET,
                retrievedAtEpochMillis = RETRIEVED_AT
            )
        ),
        creatorCredit = requireNotNull(candidate.creatorCredit),
        basis = RightsBasis.CC_BY_4_0,
        owner = RightsOwner.ESA_HUBBLE,
        coverage = RightsCoverage.ENTIRE_ASSET,
        restrictions = RestrictionAssessment.CLEAR,
        permittedUse = UsePermission.CACHE_AND_SCREENSAVER_DISPLAY,
        licenceUrl = LicenceValidator.CC_BY_4_0_URL
    )

    companion object {
        private const val RETRIEVED_AT = 1_000L
        private const val NOW = 2_000L
    }
}
