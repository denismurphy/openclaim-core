package com.openclaim.engine.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must run inside the transaction that commits the claim's state change. */
    public void enqueue(OutboxMessage message) {
        jdbc.sql("""
                        INSERT INTO outbox_message (id, claim_id, event_type, idempotency_key, payload, status)
                        VALUES (:id, :claimId, :eventType, :idempotencyKey, :payload, 'PENDING')""")
                .param("id", message.id())
                .param("claimId", message.claimId())
                .param("eventType", message.eventType())
                .param("idempotencyKey", message.idempotencyKey())
                .param("payload", message.payload())
                .update();
    }
}
