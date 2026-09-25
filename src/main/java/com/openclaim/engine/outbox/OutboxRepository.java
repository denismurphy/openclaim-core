package com.openclaim.engine.outbox;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    /**
     * Reserves up to {@code limit} due messages for {@code lease} in one statement (committed on its own, so no
     * transaction is held while the caller talks to the payer). Due means pending and past its backoff, or in flight
     * with an expired lease (its relay crashed or stalled). {@code SKIP LOCKED} lets concurrent relays take disjoint
     * batches.
     */
    public List<ClaimedMessage> claimDue(int limit, Duration lease) {
        return jdbc.sql("""
                        UPDATE outbox_message m
                        SET status = 'IN_FLIGHT',
                            lease_token = gen_random_uuid(),
                            lease_until = now() + make_interval(secs => :leaseSeconds),
                            attempts = m.attempts + 1
                        WHERE m.id IN (
                            SELECT id FROM outbox_message
                            WHERE (status = 'PENDING' AND next_attempt_at <= now())
                               OR (status = 'IN_FLIGHT' AND lease_until <= now())
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING m.id, m.claim_id, m.idempotency_key, m.payload, m.lease_token, m.attempts""")
                .param("leaseSeconds", lease.toMillis() / 1000.0)
                .param("limit", limit)
                .query((rs, row) -> new ClaimedMessage(
                        rs.getObject("id", UUID.class),
                        rs.getObject("claim_id", UUID.class),
                        rs.getString("idempotency_key"),
                        rs.getString("payload"),
                        rs.getObject("lease_token", UUID.class),
                        rs.getInt("attempts")))
                .list();
    }

    /** @return false if the lease was lost (expired and re-claimed, or settled by a remittance) */
    public boolean markSent(ClaimedMessage message) {
        return jdbc.sql("""
                        UPDATE outbox_message
                        SET status = 'SENT', sent_at = now(), lease_token = NULL, lease_until = NULL, last_error = NULL
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'IN_FLIGHT'""")
                        .param("id", message.id())
                        .param("leaseToken", message.leaseToken())
                        .update()
                == 1;
    }

    /** @return false if the lease was lost */
    public boolean reschedule(ClaimedMessage message, Instant nextAttemptAt, String error) {
        return jdbc.sql("""
                        UPDATE outbox_message
                        SET status = 'PENDING', next_attempt_at = :next, lease_token = NULL, lease_until = NULL,
                            last_error = :error
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'IN_FLIGHT'""")
                        .param("id", message.id())
                        .param("leaseToken", message.leaseToken())
                        .param("next", Timestamp.from(nextAttemptAt))
                        .param("error", error)
                        .update()
                == 1;
    }

    /** @return false if the lease was lost */
    public boolean markFailed(ClaimedMessage message, String error) {
        return jdbc.sql("""
                        UPDATE outbox_message
                        SET status = 'FAILED', lease_token = NULL, lease_until = NULL, last_error = :error
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'IN_FLIGHT'""")
                        .param("id", message.id())
                        .param("leaseToken", message.leaseToken())
                        .param("error", error)
                        .update()
                == 1;
    }

    /**
     * Status of a claim's dispatch message, row-locked until the transaction ends. Callers must already hold the claim
     * row lock (lock order: claim, then outbox).
     */
    public Optional<DispatchState> lockDispatchState(UUID claimId) {
        return jdbc.sql("SELECT status, attempts FROM outbox_message WHERE claim_id = :claimId FOR UPDATE")
                .param("claimId", claimId)
                .query((rs, row) ->
                        new DispatchState(OutboxStatus.valueOf(rs.getString("status")), rs.getInt("attempts")))
                .optional();
    }

    /** Settles the dispatch message because the payer's response proves it was delivered. */
    public void markDeliveredByRemittance(UUID claimId) {
        jdbc.sql("""
                        UPDATE outbox_message
                        SET status = 'SENT', sent_at = now(), lease_token = NULL, lease_until = NULL
                        WHERE claim_id = :claimId AND status <> 'SENT'""").param("claimId", claimId).update();
    }

    public Map<OutboxStatus, Long> countByStatus() {
        Map<OutboxStatus, Long> counts = new EnumMap<>(OutboxStatus.class);
        for (OutboxStatus status : OutboxStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.sql("SELECT status, count(*) AS n FROM outbox_message GROUP BY status")
                .query((rs, row) -> counts.put(OutboxStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list();
        return counts;
    }

    /** @param attempts delivery attempts so far; zero means the payer cannot have received the claim */
    public record DispatchState(OutboxStatus status, int attempts) {}
}
