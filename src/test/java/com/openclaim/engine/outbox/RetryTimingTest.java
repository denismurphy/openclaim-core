package com.openclaim.engine.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RetryTimingTest {

    private static final Duration INITIAL = Duration.ofSeconds(2);
    private static final Duration MAX = Duration.ofMinutes(10);
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    /** Always picks the smallest or the largest value the jitter allows. */
    private static RandomGenerator extreme(boolean high) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public long nextLong(long bound) {
                return high ? bound - 1 : 0;
            }
        };
    }

    @ParameterizedTest(name = "attempt {0}: {1}ms..{2}ms")
    @CsvSource({"1, 1000, 2000", "2, 2000, 4000", "3, 4000, 8000", "9, 256000, 512000", "10, 300000, 600000"})
    void doublesPerAttemptWithinTheUpperHalfOfTheStep(int attempt, long lowMillis, long highMillis) {
        assertThat(OutboxRelay.backoff(attempt, INITIAL, MAX, extreme(false))).isEqualTo(Duration.ofMillis(lowMillis));
        assertThat(OutboxRelay.backoff(attempt, INITIAL, MAX, extreme(true))).isEqualTo(Duration.ofMillis(highMillis));
    }

    @ParameterizedTest
    @ValueSource(ints = {11, 40, 62, 63, 64, 1000, Integer.MAX_VALUE})
    void neverOverflowsOrExceedsTheCap(int attempt) {
        assertThat(OutboxRelay.backoff(attempt, INITIAL, MAX, extreme(true))).isEqualTo(MAX);
        assertThat(OutboxRelay.backoff(attempt, INITIAL, MAX, extreme(false))).isEqualTo(MAX.dividedBy(2));
    }

    @Test
    void retryAfterSeconds() {
        assertThat(PayerGatewayClient.retryAfter("120", NOW)).isEqualTo(Duration.ofSeconds(120));
        assertThat(PayerGatewayClient.retryAfter(" 7 ", NOW)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void retryAfterHttpDate() {
        assertThat(PayerGatewayClient.retryAfter("Fri, 25 Sep 2026 10:02:00 GMT", NOW))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-5", "soon", "1.5", "Fri, 25 Sep 2026 09:59:00 GMT", "99999999999999999999"})
    void unusableRetryAfterIsIgnored(String value) {
        assertThat(PayerGatewayClient.retryAfter(value, NOW)).isNull();
    }
}
