package com.openclaim.engine.outbox;

import java.util.UUID;

/**
 * A message reserved by one relay. {@code leaseToken} fences every later update: if the lease expired and another
 * relay re-claimed the message, this holder's writes match no row.
 *
 * @param attempts delivery attempts including this one
 */
public record ClaimedMessage(
        UUID id, UUID claimId, String idempotencyKey, String payload, UUID leaseToken, int attempts) {}
