package com.openclaim.engine.outbox;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** How one delivery attempt ended, as far as retrying is concerned. */
public sealed interface DeliveryResult {

    /** The payer has the claim: 2xx, or 409 meaning it already holds this Idempotency-Key. */
    record Delivered(int status) implements DeliveryResult {}

    /** Transient: 5xx, 429 or a network failure. {@code retryAfter} is the payer's hint, if it gave one. */
    record Retry(String reason, @Nullable Duration retryAfter) implements DeliveryResult {}

    /** Permanent: any other 4xx. Resending the same payload cannot succeed. */
    record Rejected(String reason) implements DeliveryResult {}
}
