package com.openclaim.engine.web;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.TestJwt;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.ingest.ClaimIngestionService;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import com.openclaim.engine.remittance.Adjudication;
import com.openclaim.engine.remittance.AdjudicationReconciler;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PatientPaymentIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ClaimIngestionService ingestion;

    @Autowired
    AdjudicationReconciler reconciler;

    @Autowired
    ClaimRepository claims;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcClient jdbc;

    /** Billed 125.00, eligible 100.00, payer paid 80.00: the patient owes 20.00. */
    private Claim partiallyAdjudicated() {
        Claim claim = dispatched();
        reconciler.apply(
                new Adjudication(
                        new ClaimIdentifier("https://payer.example.org/adjudication", "ADJ-" + UUID.randomUUID()),
                        claim.id(),
                        null,
                        12_500,
                        10_000,
                        8_000),
                "ACME-HEALTH",
                id -> "{}");
        assertThat(status(claim)).isEqualTo(ClaimStatus.ADJUDICATED_PARTIAL);
        return claim;
    }

    private Claim dispatched() {
        Claim claim = ingestion.submit(aSubmission().build(), id -> "{}").claim();
        jdbc.sql("UPDATE outbox_message SET status = 'SENT', attempts = 1 WHERE claim_id = :id")
                .param("id", claim.id())
                .update();
        Claim loaded = claims.findById(claim.id()).orElseThrow();
        loaded.transitionTo(ClaimStatus.DISPATCHED);
        return claims.save(loaded);
    }

    private MvcTestResult pay(Claim claim, String key, String amount) {
        return pay(
                claim.id(),
                key,
                "{\"amount\": " + amount + ", \"currency\": \"EUR\"}",
                TestJwt.bearer("payments:write"));
    }

    private MvcTestResult pay(UUID claimId, String key, String body, String authorization) {
        var request = mvc.post()
                .uri("/api/claims/{id}/patient-payments", claimId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.exchange();
    }

    private ClaimStatus status(Claim claim) {
        return claims.findById(claim.id()).orElseThrow().status();
    }

    private long patientBalance(Claim claim) {
        return ledger.balances(claim.id()).of(LedgerAccount.PATIENT_AR);
    }

    private int payments(Claim claim) {
        return jdbc.sql("SELECT count(*) FROM ledger_journal WHERE claim_id = :id AND entry_type = 'PATIENT_PAYMENT'")
                .param("id", claim.id())
                .query(Integer.class)
                .single();
    }

    @Test
    void payingTheFullShareSettlesTheClaim() {
        Claim claim = partiallyAdjudicated();

        MvcTestResult result = pay(claim, "pay-1", "20.00");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("SETTLED");
        assertThat(result).bodyJson().extractingPath("$.outstanding").isEqualTo(0.0);
        assertThat(status(claim)).isEqualTo(ClaimStatus.SETTLED);
        assertThat(patientBalance(claim)).isZero();
    }

    @Test
    void instalmentsSettleOnTheLastOne() {
        Claim claim = partiallyAdjudicated();

        assertThat(pay(claim, "pay-1", "5.00")).hasStatus(HttpStatus.CREATED);
        assertThat(status(claim)).isEqualTo(ClaimStatus.ADJUDICATED_PARTIAL);
        assertThat(pay(claim, "pay-2", "15.00")).hasStatus(HttpStatus.CREATED);

        assertThat(status(claim)).isEqualTo(ClaimStatus.SETTLED);
        assertThat(payments(claim)).isEqualTo(2);
    }

    @Test
    void retryWithTheSameKeyBooksOnceEvenAfterSettlement() {
        Claim claim = partiallyAdjudicated();
        pay(claim, "pay-1", "20.00");

        MvcTestResult retry = pay(claim, "pay-1", "20.00");

        assertThat(retry).hasStatusOk();
        assertThat(retry).bodyJson().extractingPath("$.status").isEqualTo("SETTLED");
        assertThat(payments(claim)).isEqualTo(1);
    }

    @Test
    void reusingAKeyForADifferentAmountIsAConflict() {
        Claim claim = partiallyAdjudicated();
        pay(claim, "pay-1", "5.00");

        assertThat(pay(claim, "pay-1", "6.00")).hasStatus(HttpStatus.CONFLICT);
        assertThat(patientBalance(claim)).isEqualTo(1_500);
    }

    @Test
    void overpaymentIsRejected() {
        Claim claim = partiallyAdjudicated();

        MvcTestResult result = pay(claim, "pay-1", "20.01");

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(result.getResponse().getContentType()).isEqualTo("application/problem+json");
        assertThat(payments(claim)).isZero();
    }

    @Test
    void claimsWithoutAPatientBalanceCannotBePaid() {
        Claim claim = dispatched();

        assertThat(pay(claim, "pay-1", "1.00")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void unknownClaim() {
        assertThat(pay(
                        UUID.randomUUID(),
                        "pay-1",
                        "{\"amount\": 1, \"currency\": \"EUR\"}",
                        TestJwt.bearer("payments:write")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void idempotencyKeyIsRequiredAndBounded() {
        Claim claim = partiallyAdjudicated();
        String body = "{\"amount\": 1, \"currency\": \"EUR\"}";
        String token = TestJwt.bearer("payments:write");

        assertThat(pay(claim.id(), null, body, token)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pay(claim.id(), "has space", body, token)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pay(claim.id(), "k".repeat(129), body, token)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(payments(claim)).isZero();
    }

    @Test
    void invalidAmounts() {
        Claim claim = partiallyAdjudicated();
        String token = TestJwt.bearer("payments:write");

        assertThat(pay(claim.id(), "a", "{\"amount\": 0, \"currency\": \"EUR\"}", token))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pay(claim.id(), "b", "{\"amount\": -5, \"currency\": \"EUR\"}", token))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pay(claim.id(), "c", "{\"currency\": \"EUR\"}", token)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(pay(claim.id(), "d", "{\"amount\": 1.005, \"currency\": \"EUR\"}", token))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pay(claim.id(), "e", "{\"amount\": 1, \"currency\": \"USD\"}", token))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(pay(claim.id(), "f", "not json", token)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(payments(claim)).isZero();
    }

    @Test
    void concurrentPaymentsCannotJointlyOverpay() throws Exception {
        Claim claim = partiallyAdjudicated();
        int payers = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcTestResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(payers)) {
            for (int i = 0; i < payers; i++) {
                String key = "concurrent-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return pay(claim, key, "20.00");
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<MvcTestResult> result : results) {
                int status = result.get().getResponse().getStatus();
                assertThat(status).isIn(201, 409, 422);
                if (status == 201) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(1);
        }
        assertThat(patientBalance(claim)).isZero();
        assertThat(payments(claim)).isEqualTo(1);
    }

    @Test
    void requiresThePaymentsScope() {
        Claim claim = partiallyAdjudicated();

        assertThat(pay(
                        claim.id(),
                        "k",
                        "{\"amount\": 1, \"currency\": \"EUR\"}",
                        TestJwt.bearer("claims:write claims:read")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void claimViewShowsLifecycleAndLedger() {
        Claim claim = partiallyAdjudicated();
        pay(claim, "pay-1", "20.00");

        MvcTestResult view = mvc.get()
                .uri("/api/claims/{id}", claim.id())
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer("claims:read"))
                .exchange();

        assertThat(view).hasStatusOk();
        assertThat(view).bodyJson().extractingPath("$.status").isEqualTo("SETTLED");
        assertThat(view).bodyJson().extractingPath("$.totalBilled").isEqualTo(125.0);
        assertThat(view).bodyJson().extractingPath("$.balances.PAYER_AR").isEqualTo(0.0);
        assertThat(view).bodyJson().extractingPath("$.balances.PATIENT_AR").isEqualTo(0.0);
        assertThat(view).bodyJson().extractingPath("$.balances.CASH").isEqualTo(100.0);
        assertThat(view).bodyJson().extractingPath("$.ledger.length()").isEqualTo(10);
        assertThat(mvc.get()
                        .uri("/api/claims/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer("claims:read"))
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType("application/problem+json");
    }
}
