package com.openclaim.engine.remittance;

/**
 * The remittance cannot be applied to the claim in its current state (HTTP 409), or references no claim this payer
 * submitted (HTTP 422). The two causes are kept distinct only so the web layer can pick the status.
 */
public class RemittanceRejectedException extends RuntimeException {

    public enum Reason {
        /** Unknown claim, or a claim of another payer; deliberately indistinguishable to the caller. */
        UNKNOWN_CLAIM,
        /** The claim is not awaiting adjudication, or already has a different remittance. */
        CONFLICT,
        /** Amounts do not reconcile with what was billed. */
        UNRECONCILED
    }

    private final Reason reason;

    public RemittanceRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
