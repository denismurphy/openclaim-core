package com.openclaim.engine.outbox;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.config.OpenClaimProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delivers outbox messages to the payer (spec §6 steps 6-8). One pass:
 *
 * <ol>
 *   <li>Reserve a batch with a lease (one short statement; no transaction spans the HTTP call).
 *   <li>POST each message with its Idempotency-Key.
 *   <li>Record the outcome with an update fenced by the lease token. On success, in the same transaction and after
 *       taking the claim row lock, move the claim to DISPATCHED.
 * </ol>
 *
 * <p>If a relay dies after the payer accepted a message but before step 3, the lease expires and the message is sent
 * again; the payer de-duplicates by Idempotency-Key. Delivery is therefore at-least-once, effectively-once.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final ClaimRepository claims;
    private final PayerGatewayClient payer;
    private final TransactionOperations transactions;
    private final OpenClaimProperties.Outbox settings;
    private final Clock clock;
    private final Map<String, Counter> outcomes;
    private final Map<OutboxStatus, AtomicLong> backlog = new EnumMap<>(OutboxStatus.class);

    public OutboxRelay(
            OutboxRepository outbox,
            ClaimRepository claims,
            PayerGatewayClient payer,
            TransactionOperations transactions,
            OpenClaimProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.outbox = outbox;
        this.claims = claims;
        this.payer = payer;
        this.transactions = transactions;
        this.settings = properties.outbox();
        this.clock = clock;
        this.outcomes = Map.of(
                "delivered", counter(meters, "delivered"),
                "retry", counter(meters, "retry"),
                "failed", counter(meters, "failed"),
                "lease-lost", counter(meters, "lease-lost"));
        for (OutboxStatus status : OutboxStatus.values()) {
            AtomicLong value = new AtomicLong();
            backlog.put(status, value);
            Gauge.builder("openclaim.outbox.messages", value, AtomicLong::get)
                    .tag("status", status.name())
                    .description("Outbox messages by status, as of the last relay pass")
                    .register(meters);
        }
    }

    /** @return the number of messages this pass reserved */
    public int runOnce() {
        List<ClaimedMessage> batch = outbox.claimDue(settings.batchSize(), settings.leaseDuration());
        for (ClaimedMessage message : batch) {
            process(message);
        }
        outbox.countByStatus().forEach((status, count) -> backlog.get(status).set(count));
        return batch.size();
    }

    private void process(ClaimedMessage message) {
        if (message.attempts() > settings.maxAttempts()) {
            // Only reachable when earlier attempts ended in a crash or lost lease rather than a recorded outcome.
            fail(message, "Gave up after %d attempts".formatted(settings.maxAttempts()));
            return;
        }
        DeliveryResult result = payer.deliver(message);
        switch (result) {
            case DeliveryResult.Delivered delivered -> delivered(message, delivered);
            case DeliveryResult.Rejected rejected -> fail(message, rejected.reason());
            case DeliveryResult.Retry retry
            when message.attempts() >= settings.maxAttempts() ->
                fail(message, retry.reason() + "; gave up after %d attempts".formatted(message.attempts()));
            case DeliveryResult.Retry retry -> retry(message, retry);
        }
    }

    private void delivered(ClaimedMessage message, DeliveryResult.Delivered delivered) {
        boolean recorded = Boolean.TRUE.equals(transactions.execute(status -> {
            Claim claim = claims.findLockedById(message.claimId())
                    .orElseThrow(
                            () -> new IllegalStateException("Outbox message for missing claim " + message.claimId()));
            if (!outbox.markSent(message)) {
                return false;
            }
            if (claim.status() == ClaimStatus.PRE_VALIDATED) {
                claim.transitionTo(ClaimStatus.DISPATCHED);
                claims.save(claim);
            }
            return true;
        }));
        if (recorded) {
            outcomes.get("delivered").increment();
            log.info("Claim {} dispatched (payer HTTP {})", message.claimId(), delivered.status());
        } else {
            leaseLost(message);
        }
    }

    private void retry(ClaimedMessage message, DeliveryResult.Retry retry) {
        Duration delay = backoff(
                message.attempts(), settings.initialBackoff(), settings.maxBackoff(), ThreadLocalRandom.current());
        if (retry.retryAfter() != null && retry.retryAfter().compareTo(delay) > 0) {
            delay = retry.retryAfter();
        }
        if (delay.compareTo(settings.maxBackoff()) > 0) {
            delay = settings.maxBackoff();
        }
        if (outbox.reschedule(message, clock.instant().plus(delay), retry.reason())) {
            outcomes.get("retry").increment();
            log.warn(
                    "Claim {} delivery attempt {} failed ({}); retrying in {}",
                    message.claimId(),
                    message.attempts(),
                    retry.reason(),
                    delay);
        } else {
            leaseLost(message);
        }
    }

    private void fail(ClaimedMessage message, String reason) {
        if (outbox.markFailed(message, reason)) {
            outcomes.get("failed").increment();
            log.error("Claim {} could not be delivered and needs attention: {}", message.claimId(), reason);
        } else {
            leaseLost(message);
        }
    }

    private void leaseLost(ClaimedMessage message) {
        outcomes.get("lease-lost").increment();
        log.warn("Claim {} outbox lease was lost; another relay or a remittance settled it", message.claimId());
    }

    /**
     * Exponential backoff with equal jitter: the step doubles per attempt up to {@code max}; the delay is uniformly
     * distributed over the upper half of the step, so retries spread out without ever collapsing to zero.
     */
    static Duration backoff(int attempts, Duration initial, Duration max, RandomGenerator random) {
        long maxMillis = max.toMillis();
        int doublings = Math.max(0, attempts - 1);
        long step = doublings >= Long.numberOfLeadingZeros(Math.max(1, initial.toMillis())) - 1
                ? maxMillis
                : Math.min(maxMillis, initial.toMillis() << doublings);
        long half = step / 2;
        return Duration.ofMillis(half + random.nextLong(step - half + 1));
    }

    private static Counter counter(MeterRegistry meters, String outcome) {
        return Counter.builder("openclaim.outbox.deliveries")
                .tag("outcome", outcome)
                .description("Outbox delivery attempts by outcome")
                .register(meters);
    }
}
