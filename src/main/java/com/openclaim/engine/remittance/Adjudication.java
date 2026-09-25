package com.openclaim.engine.remittance;

import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.Fingerprint;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Canonical view of a payer's ClaimResponse, in minor units of the deployment currency. Amounts follow the HL7
 * adjudication code system: {@code submitted} (billed), {@code eligible} (allowed after contractual reduction) and
 * {@code benefit} (paid by the payer).
 *
 * @param claimId our claim id, when the response references {@code Claim/<id>}
 * @param claimIdentifier the submitter's claim identifier, when the response references the claim by identifier
 */
public record Adjudication(
        ClaimIdentifier identifier,
        @Nullable UUID claimId,
        @Nullable ClaimIdentifier claimIdentifier,
        long submittedMinor,
        long eligibleMinor,
        long benefitMinor) {

    public Adjudication {
        if (claimId == null && claimIdentifier == null) {
            throw new IllegalArgumentException("An adjudication must reference a claim");
        }
        if (submittedMinor < 0 || eligibleMinor < 0 || benefitMinor < 0) {
            throw new IllegalArgumentException("Adjudicated amounts must not be negative");
        }
        if (eligibleMinor > submittedMinor) {
            throw new IllegalArgumentException("Eligible amount exceeds the submitted amount");
        }
        if (benefitMinor > eligibleMinor) {
            throw new IllegalArgumentException("Benefit exceeds the eligible amount");
        }
    }

    /** Contractual reduction the provider writes off. */
    public long writeOffMinor() {
        return submittedMinor - eligibleMinor;
    }

    /** What the patient owes (co-pay, co-insurance, deductible). */
    public long patientLiabilityMinor() {
        return eligibleMinor - benefitMinor;
    }

    /** Content fingerprint used to tell a replay from a different response reusing the same identifier. */
    public String fingerprint() {
        return Fingerprint.sha256(
                "adjudication-v1",
                identifier.system(),
                identifier.value(),
                claimId == null ? null : claimId.toString(),
                claimIdentifier == null ? null : claimIdentifier.system(),
                claimIdentifier == null ? null : claimIdentifier.value(),
                Long.toString(submittedMinor),
                Long.toString(eligibleMinor),
                Long.toString(benefitMinor));
    }
}
