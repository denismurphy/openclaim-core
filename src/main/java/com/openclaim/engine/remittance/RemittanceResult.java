package com.openclaim.engine.remittance;

import com.openclaim.engine.claim.ClaimStatus;

/** @param replayed true when this ClaimResponse had already been applied and nothing changed */
public record RemittanceResult(StoredRemittance remittance, ClaimStatus claimStatus, boolean replayed) {}
