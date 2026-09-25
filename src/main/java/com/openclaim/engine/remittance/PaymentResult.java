package com.openclaim.engine.remittance;

import com.openclaim.engine.claim.ClaimStatus;

/**
 * @param outstandingMinor what the patient still owes after this payment
 * @param replayed true when this Idempotency-Key had already been booked and nothing changed
 */
public record PaymentResult(ClaimStatus claimStatus, long outstandingMinor, boolean replayed) {}
