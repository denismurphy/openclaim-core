package com.openclaim.engine.claim;

/**
 * Deterministic key sent to the payer gateway as {@code Idempotency-Key} (spec §5.2), so a re-transmitted dispatch
 * cannot be booked twice.
 *
 * <p>Unlike the spec's plain concatenation, fields are length-prefixed (see {@link Fingerprint}) and the claim
 * identifier's <em>system</em> is included: two EHRs may issue the same identifier value.
 */
public final class IdempotencyKey {

    private static final String VERSION = "dispatch-v1";

    private IdempotencyKey() {}

    /**
     * @throws IllegalStateException if the claim lacks a payer or patient MRN; the scrubber rejects such claims
     *     before they can be dispatched
     */
    public static String forDispatch(Claim claim) {
        String payerCode = claim.payerCode();
        String patientMrn = claim.patientMrn();
        if (payerCode == null || patientMrn == null) {
            throw new IllegalStateException(
                    "Claim %s has no payer or patient MRN and cannot be dispatched".formatted(claim.id()));
        }
        return Fingerprint.sha256(
                VERSION,
                payerCode,
                patientMrn,
                claim.identifier().system(),
                claim.identifier().value(),
                Long.toString(claim.totalBilledMinor()));
    }
}
