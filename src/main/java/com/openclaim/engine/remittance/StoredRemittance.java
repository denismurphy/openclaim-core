package com.openclaim.engine.remittance;

import java.util.UUID;

/** @param resource the payer's ClaimResponse as stored, with its id set to {@code id} */
public record StoredRemittance(UUID id, UUID claimId, String contentHash, String resource) {}
