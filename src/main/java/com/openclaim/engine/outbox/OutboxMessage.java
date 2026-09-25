package com.openclaim.engine.outbox;

import java.util.UUID;

/**
 * A message to deliver to the payer gateway, written in the same transaction as the state change it announces.
 *
 * @param idempotencyKey sent as the {@code Idempotency-Key} header so re-deliveries are recognised by the payer
 * @param payload the exact request body, fixed at enqueue time
 */
public record OutboxMessage(UUID id, UUID claimId, String eventType, String idempotencyKey, String payload) {

    public static final String CLAIM_PRE_VALIDATED = "CLAIM_PRE_VALIDATED";
}
