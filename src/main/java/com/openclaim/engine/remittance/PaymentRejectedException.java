package com.openclaim.engine.remittance;

/** A patient payment that cannot be booked. */
public class PaymentRejectedException extends RuntimeException {

    public enum Reason {
        /** No such claim (HTTP 404). */
        UNKNOWN_CLAIM,
        /** The claim has no patient balance to pay, or the key was used for a different amount (HTTP 409). */
        CONFLICT,
        /** The payment exceeds the patient's outstanding balance (HTTP 422). */
        OVERPAYMENT
    }

    private final Reason reason;

    public PaymentRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
