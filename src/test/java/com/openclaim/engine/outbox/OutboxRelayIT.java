package com.openclaim.engine.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.claim.IdempotencyKey;
import com.openclaim.engine.ingest.ClaimIngestionService;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the relay against a WireMock payer. Other test classes may leave undelivered messages in the shared
 * database, so every assertion is scoped to this test's own claim (by claim id or Idempotency-Key).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxRelayIT {

    static final WireMockServer payer = new WireMockServer(wireMockConfig().dynamicPort());

    @BeforeAll
    static void startPayer() {
        payer.start();
    }

    @AfterAll
    static void stopPayer() {
        payer.stop();
    }

    @DynamicPropertySource
    static void payerProperties(DynamicPropertyRegistry registry) {
        registry.add("OPENCLAIM_PAYER_BASE_URL", payer::baseUrl);
        registry.add("openclaim.payer.read-timeout", () -> "1s");
        registry.add("openclaim.outbox.max-attempts", () -> "3");
        registry.add("openclaim.outbox.initial-backoff", () -> "10s");
        registry.add("openclaim.outbox.max-backoff", () -> "5m");
    }

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    ClaimIngestionService ingestion;

    @Autowired
    ClaimRepository claims;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Claim claim;
    private String key;

    @BeforeEach
    void acceptedClaim() {
        payer.resetAll();
        payer.stubFor(post("/claims").willReturn(aResponse().withStatus(202)));
        claim = ingestion
                .submit(aSubmission().build(), id -> "{\"resourceType\":\"Bundle\",\"id\":\"" + id + "\"}")
                .claim();
        assertThat(claim.status()).isEqualTo(ClaimStatus.PRE_VALIDATED);
        key = IdempotencyKey.forDispatch(claim);
    }

    private void payerResponds(int status, Map<String, String> headers) {
        var response = aResponse().withStatus(status);
        headers.forEach(response::withHeader);
        payer.stubFor(post("/claims")
                .withHeader(PayerGatewayClient.IDEMPOTENCY_KEY, equalTo(key))
                .atPriority(1)
                .willReturn(response));
    }

    private Map<String, Object> message() {
        return jdbc.sql("SELECT * FROM outbox_message WHERE claim_id = :id")
                .param("id", claim.id())
                .query()
                .singleRow();
    }

    private ClaimStatus claimStatus() {
        return claims.findById(claim.id()).orElseThrow().status();
    }

    private int requestsForThisClaim() {
        return payer.findAll(postRequestedFor(urlEqualTo("/claims"))
                        .withHeader(PayerGatewayClient.IDEMPOTENCY_KEY, equalTo(key)))
                .size();
    }

    private Instant nextAttemptAt() {
        return ((java.sql.Timestamp) message().get("next_attempt_at")).toInstant();
    }

    /** Makes this test's message due now, so a pass picks it up regardless of backoff. */
    private void makeDue() {
        jdbc.sql("UPDATE outbox_message SET next_attempt_at = now() WHERE claim_id = :id")
                .param("id", claim.id())
                .update();
    }

    private void drain() {
        while (relay.runOnce() > 0) {
            // other classes' leftovers are delivered too; keep going until nothing is due
        }
    }

    @Test
    void acceptedDeliveryMarksSentAndDispatchesTheClaim() {
        drain();

        assertThat(message()).containsEntry("status", "SENT").containsEntry("attempts", 1);
        assertThat(message().get("sent_at")).isNotNull();
        assertThat(claimStatus()).isEqualTo(ClaimStatus.DISPATCHED);
        payer.verify(
                1,
                postRequestedFor(urlEqualTo("/claims"))
                        .withHeader(PayerGatewayClient.IDEMPOTENCY_KEY, equalTo(key))
                        .withHeader("Content-Type", equalTo("application/fhir+json"))
                        .withRequestBody(equalTo("{\"resourceType\":\"Bundle\",\"id\":\"" + claim.id() + "\"}")));
    }

    @Test
    void deliveredMessagesAreNotSentAgain() {
        drain();
        makeDue();
        drain();

        assertThat(requestsForThisClaim()).isEqualTo(1);
    }

    @Test
    void conflictMeansThePayerAlreadyHasIt() {
        payerResponds(409, Map.of());

        drain();

        assertThat(message()).containsEntry("status", "SENT");
        assertThat(claimStatus()).isEqualTo(ClaimStatus.DISPATCHED);
    }

    @Test
    void serverErrorIsRetriedWithBackoff() {
        payerResponds(503, Map.of());
        Instant before = Instant.now();

        drain();

        assertThat(message())
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1)
                .containsEntry("last_error", "HTTP 503")
                .containsEntry("lease_token", null);
        // first backoff step is initial-backoff (10s) with equal jitter: 5s..10s
        assertThat(nextAttemptAt())
                .isBetween(before.plusSeconds(4), Instant.now().plusSeconds(11));
        assertThat(claimStatus()).isEqualTo(ClaimStatus.PRE_VALIDATED);
    }

    @Test
    void retryAfterSecondsIsHonoured() {
        payerResponds(429, Map.of("Retry-After", "120"));
        Instant before = Instant.now();

        drain();

        assertThat(nextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(119));
    }

    @Test
    void retryAfterHttpDateIsHonoured() {
        String inTwoMinutes = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2));
        payerResponds(503, Map.of("Retry-After", inTwoMinutes));
        Instant before = Instant.now();

        drain();

        assertThat(nextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(115));
    }

    @Test
    void hostileRetryAfterIsCapped() {
        payerResponds(429, Map.of("Retry-After", "999999999"));

        drain();

        assertThat(nextAttemptAt())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(5)), within(10, ChronoUnit.SECONDS));
    }

    @Test
    void clientErrorFailsPermanently() {
        payerResponds(400, Map.of());

        drain();
        makeDue();
        drain();

        assertThat(message()).containsEntry("status", "FAILED").containsEntry("last_error", "HTTP 400");
        assertThat(requestsForThisClaim()).isEqualTo(1);
        assertThat(claimStatus()).isEqualTo(ClaimStatus.PRE_VALIDATED);
    }

    @Test
    void slowPayerTimesOutAndIsRetried() {
        payer.stubFor(post("/claims")
                .withHeader(PayerGatewayClient.IDEMPOTENCY_KEY, equalTo(key))
                .atPriority(1)
                .willReturn(aResponse().withStatus(202).withFixedDelay(3_000)));

        drain();

        assertThat(message()).containsEntry("status", "PENDING");
        assertThat((String) message().get("last_error")).startsWith("I/O error");
    }

    @Test
    void givesUpAfterMaxAttempts() {
        payerResponds(500, Map.of());

        for (int attempt = 1; attempt <= 3; attempt++) {
            makeDue();
            drain();
        }

        assertThat(message()).containsEntry("status", "FAILED").containsEntry("attempts", 3);
        assertThat((String) message().get("last_error")).contains("gave up after 3 attempts");
        assertThat(requestsForThisClaim()).isEqualTo(3);
    }

    @Test
    void aRelayThatLostItsLeaseCannotRecordAnOutcome() {
        drainOthers();
        List<ClaimedMessage> first = outbox.claimDue(100, Duration.ofSeconds(30));
        ClaimedMessage stale = first.stream()
                .filter(m -> m.claimId().equals(claim.id()))
                .findFirst()
                .orElseThrow();
        jdbc.sql("UPDATE outbox_message SET lease_until = now() - interval '1 second' WHERE claim_id = :id")
                .param("id", claim.id())
                .update();
        ClaimedMessage current = outbox.claimDue(100, Duration.ofSeconds(30)).stream()
                .filter(m -> m.claimId().equals(claim.id()))
                .findFirst()
                .orElseThrow();

        assertThat(current.leaseToken()).isNotEqualTo(stale.leaseToken());
        assertThat(current.attempts()).isEqualTo(2);
        assertThat(outbox.markSent(stale)).isFalse();
        assertThat(outbox.markFailed(stale, "late")).isFalse();
        assertThat(outbox.reschedule(stale, Instant.now(), "late")).isFalse();
        assertThat(outbox.markSent(current)).isTrue();
        assertThat(message()).containsEntry("status", "SENT");
    }

    @Test
    void concurrentRelaysDeliverEachMessageOnce() throws Exception {
        payer.stubFor(post("/claims").willReturn(aResponse().withStatus(202).withFixedDelay(200)));
        List<Claim> more = java.util.stream.IntStream.range(0, 6)
                .mapToObj(
                        i -> ingestion.submit(aSubmission().build(), id -> "{}").claim())
                .toList();

        CompletableFuture<Void> a = CompletableFuture.runAsync(this::drain);
        CompletableFuture<Void> b = CompletableFuture.runAsync(this::drain);
        CompletableFuture.allOf(a, b).get();

        for (Claim c : more) {
            assertThat(payer.findAll(postRequestedFor(urlEqualTo("/claims"))
                                    .withHeader(
                                            PayerGatewayClient.IDEMPOTENCY_KEY, equalTo(IdempotencyKey.forDispatch(c))))
                            .size())
                    .as("requests for claim %s", c.id())
                    .isEqualTo(1);
            assertThat(claims.findById(c.id()).orElseThrow().status()).isEqualTo(ClaimStatus.DISPATCHED);
        }
    }

    /**
     * Reservation is one short statement, so racing two relays rarely overlaps it; this holds relay A's reservation
     * open to force the overlap. Relay B must neither wait for A (SKIP LOCKED) nor take A's message.
     */
    @Test
    void reservationSkipsMessagesAnotherRelayHoldsWithoutWaiting() throws Exception {
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<List<ClaimedMessage>> relayA = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            List<ClaimedMessage> batch = outbox.claimDue(500, Duration.ofSeconds(30));
            reserved.countDown();
            awaitQuietly(release);
            return batch;
        }));
        try {
            assertThat(reserved.await(10, TimeUnit.SECONDS)).isTrue();

            List<ClaimedMessage> relayB = CompletableFuture.supplyAsync(
                            () -> outbox.claimDue(500, Duration.ofSeconds(30)))
                    .get(3, TimeUnit.SECONDS);

            assertThat(relayB).extracting(ClaimedMessage::claimId).doesNotContain(claim.id());
        } finally {
            release.countDown();
        }
        assertThat(relayA.get(10, TimeUnit.SECONDS))
                .extracting(ClaimedMessage::claimId)
                .contains(claim.id());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Delivers everything else that is due so the next claimDue returns this test's message in a small batch. */
    private void drainOthers() {
        jdbc.sql("UPDATE outbox_message SET next_attempt_at = now() + interval '1 hour' WHERE claim_id = :id")
                .param("id", claim.id())
                .update();
        drain();
        makeDue();
    }
}
