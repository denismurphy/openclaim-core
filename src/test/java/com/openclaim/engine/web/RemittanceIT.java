package com.openclaim.engine.web;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import com.openclaim.engine.TestJwt;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.fhir.FhirFixtures;
import com.openclaim.engine.fhir.FhirMediaTypes;
import com.openclaim.engine.ingest.ClaimIngestionService;
import com.openclaim.engine.ledger.Balances;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import com.openclaim.engine.outbox.ClaimedMessage;
import com.openclaim.engine.outbox.OutboxRepository;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RemittanceIT {

    private static final String ACME = "ACME-HEALTH";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    FhirContext fhir;

    @Autowired
    ClaimIngestionService ingestion;

    @Autowired
    ClaimRepository claims;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcClient jdbc;

    /** A pre-validated claim billing 125.00 EUR to ACME-HEALTH. */
    private Claim acceptedClaim() {
        Claim claim = ingestion.submit(aSubmission().build(), id -> "{}").claim();
        assertThat(claim.status()).isEqualTo(ClaimStatus.PRE_VALIDATED);
        return claim;
    }

    /** What the relay does on a successful delivery. */
    private Claim dispatched() {
        Claim claim = acceptedClaim();
        jdbc.sql("UPDATE outbox_message SET status = 'SENT', attempts = 1, sent_at = now() WHERE claim_id = :id")
                .param("id", claim.id())
                .update();
        Claim loaded = claims.findById(claim.id()).orElseThrow();
        loaded.transitionTo(ClaimStatus.DISPATCHED);
        return claims.save(loaded);
    }

    /** The fixture ClaimResponse, re-pointed at {@code claim} with a unique identifier and the given totals. */
    private String claimResponse(Claim claim, String eligible, String benefit, Consumer<ClaimResponse> change) {
        ClaimResponse response = fhir.newJsonParser()
                .setParserErrorHandler(new StrictErrorHandler())
                .parseResource(ClaimResponse.class, FhirFixtures.json("/fhir/claim-response.json"));
        response.getIdentifierFirstRep().setValue("ADJ-" + UUID.randomUUID());
        response.setRequest(new Reference("Claim/" + claim.id()));
        for (ClaimResponse.TotalComponent total : response.getTotal()) {
            String category = total.getCategory().getCodingFirstRep().getCode();
            switch (category) {
                case "submitted" -> total.getAmount().setValue(new BigDecimal("125.00"));
                case "eligible" -> total.getAmount().setValue(new BigDecimal(eligible));
                case "benefit" -> total.getAmount().setValue(new BigDecimal(benefit));
                default -> total.getAmount().setValue(BigDecimal.ZERO);
            }
        }
        response.getPayment().getAmount().setValue(new BigDecimal(benefit));
        change.accept(response);
        return fhir.newJsonParser().encodeResourceToString(response);
    }

    private String claimResponse(Claim claim, String eligible, String benefit) {
        return claimResponse(claim, eligible, benefit, r -> {});
    }

    private static String payerToken(String payerCode) {
        return "Bearer "
                + TestJwt.builder("remittance:write")
                        .claim("payer_code", payerCode)
                        .sign();
    }

    private MvcTestResult post(String body) {
        return post(body, payerToken(ACME));
    }

    private MvcTestResult post(String body, String authorization) {
        return mvc.post()
                .uri("/fhir/ClaimResponse")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(FhirMediaTypes.FHIR_JSON)
                .content(body)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private OperationOutcome.OperationOutcomeIssueComponent issue(MvcTestResult result) {
        return fhir.newJsonParser()
                .parseResource(OperationOutcome.class, body(result))
                .getIssueFirstRep();
    }

    private ClaimStatus statusOf(Claim claim) {
        return claims.findById(claim.id()).orElseThrow().status();
    }

    private int journals(Claim claim) {
        return jdbc.sql("SELECT count(*) FROM ledger_journal WHERE claim_id = :id")
                .param("id", claim.id())
                .query(Integer.class)
                .single();
    }

    @Test
    void partialAdjudicationPostsWriteOffPaymentAndPatientShare() {
        Claim claim = dispatched();

        MvcTestResult result = post(claimResponse(claim, "100.00", "80.00"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).startsWith("/fhir/ClaimResponse/");
        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.ADJUDICATED_PARTIAL);
        Balances balances = ledger.balances(claim.id());
        assertThat(balances.of(LedgerAccount.PAYER_AR)).isZero();
        assertThat(balances.of(LedgerAccount.CONTRACTUAL_ADJ)).isEqualTo(2_500);
        assertThat(balances.of(LedgerAccount.CASH)).isEqualTo(8_000);
        assertThat(balances.of(LedgerAccount.PATIENT_AR)).isEqualTo(2_000);
        assertThat(journals(claim)).isEqualTo(4);
    }

    @Test
    void fullPaymentSettlesTheClaim() {
        Claim claim = dispatched();

        assertThat(post(claimResponse(claim, "125.00", "125.00"))).hasStatus(HttpStatus.CREATED);

        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.SETTLED);
        assertThat(ledger.balances(claim.id()).of(LedgerAccount.PATIENT_AR)).isZero();
        assertThat(journals(claim))
                .as("CHARGE + PAYER_REMITTANCE only; zero amounts post nothing")
                .isEqualTo(2);
    }

    @Test
    void replayReturnsTheStoredResponseAndPostsNothing() {
        Claim claim = dispatched();
        String response = claimResponse(claim, "100.00", "80.00");
        MvcTestResult first = post(response);

        MvcTestResult second = post(response);

        assertThat(second).hasStatusOk();
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(journals(claim)).isEqualTo(4);
    }

    @Test
    void storedResponseCanBeReadBackByThePayerAndTheProvider() {
        Claim claim = dispatched();
        String location =
                post(claimResponse(claim, "100.00", "80.00")).getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(mvc.get()
                        .uri(location)
                        .header(HttpHeaders.AUTHORIZATION, payerToken(ACME))
                        .exchange())
                .hasStatusOk()
                .bodyText()
                .contains(location.substring(location.lastIndexOf('/') + 1));
        assertThat(mvc.get()
                        .uri(location)
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer("claims:read"))
                        .exchange())
                .hasStatusOk();
        assertThat(mvc.get()
                        .uri(location)
                        .header(HttpHeaders.AUTHORIZATION, payerToken("NORTHWIND-MUTUAL"))
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyText()
                .contains("\"resourceType\":\"OperationOutcome\"");
    }

    @Test
    void sameIdentifierWithDifferentContentIsAConflict() {
        Claim claim = dispatched();
        String first = claimResponse(claim, "100.00", "80.00");
        post(first);
        String identifier = fhir.newJsonParser()
                .parseResource(ClaimResponse.class, first)
                .getIdentifierFirstRep()
                .getValue();

        MvcTestResult changed = post(claimResponse(
                claim, "100.00", "90.00", r -> r.getIdentifierFirstRep().setValue(identifier)));

        assertThat(changed).hasStatus(HttpStatus.CONFLICT);
        assertThat(ledger.balances(claim.id()).of(LedgerAccount.CASH)).isEqualTo(8_000);
    }

    @Test
    void aSecondDifferentAdjudicationIsAConflict() {
        Claim claim = dispatched();
        post(claimResponse(claim, "125.00", "0.00"));

        assertThat(post(claimResponse(claim, "125.00", "125.00"))).hasStatus(HttpStatus.CONFLICT);
        assertThat(ledger.balances(claim.id()).of(LedgerAccount.CASH)).isZero();
    }

    @Test
    void submittedTotalMustMatchWhatWasBilled() {
        Claim claim = dispatched();

        MvcTestResult result = post(claimResponse(
                claim,
                "100.00",
                "80.00",
                r -> r.getTotal().stream()
                        .filter(t ->
                                t.getCategory().getCodingFirstRep().getCode().equals("submitted"))
                        .forEach(t -> t.getAmount().setValue(new BigDecimal("130.00")))));

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(issue(result).getCode()).isEqualTo(OperationOutcome.IssueType.BUSINESSRULE);
        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.DISPATCHED);
        assertThat(journals(claim)).isEqualTo(1);
    }

    @Test
    void anotherPayersClaimLooksExactlyLikeAnUnknownClaim() {
        Claim claim = dispatched();

        MvcTestResult otherPayer = post(claimResponse(claim, "100.00", "80.00"), payerToken("NORTHWIND-MUTUAL"));
        MvcTestResult unknown = post(claimResponse(
                claim, "100.00", "80.00", r -> r.setRequest(new Reference("Claim/" + UUID.randomUUID()))));

        assertThat(otherPayer).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(unknown).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(issue(otherPayer).getDiagnostics()).isEqualTo(issue(unknown).getDiagnostics());
        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.DISPATCHED);
    }

    @Test
    void tokenWithoutAPayerIsForbidden() {
        Claim claim = dispatched();

        assertThat(post(claimResponse(claim, "100.00", "80.00"), TestJwt.bearer("remittance:write")))
                .hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void claimCanBeReferencedByItsIdentifier() {
        Claim claim = dispatched();

        MvcTestResult result = post(claimResponse(
                claim,
                "125.00",
                "125.00",
                r -> r.setRequest(new Reference()
                        .setIdentifier(new Identifier()
                                .setSystem(claim.identifier().system())
                                .setValue(claim.identifier().value())))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void remittanceBeforeTheRelayRecordsDeliveryIsProofOfDelivery() {
        Claim claim = acceptedClaim();
        UUID leaseToken = UUID.randomUUID();
        jdbc.sql("""
                        UPDATE outbox_message SET status = 'IN_FLIGHT', attempts = 1, lease_token = :token,
                            lease_until = now() + interval '1 minute'
                        WHERE claim_id = :id""").param("token", leaseToken).param("id", claim.id()).update();
        ClaimedMessage inFlight = jdbc.sql(
                        "SELECT id, claim_id, idempotency_key, payload, lease_token, attempts FROM outbox_message WHERE claim_id = :id")
                .param("id", claim.id())
                .query(ClaimedMessage.class)
                .single();

        assertThat(post(claimResponse(claim, "100.00", "80.00"))).hasStatus(HttpStatus.CREATED);

        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.ADJUDICATED_PARTIAL);
        assertThat(jdbc.sql("SELECT status FROM outbox_message WHERE claim_id = :id")
                        .param("id", claim.id())
                        .query(String.class)
                        .single())
                .isEqualTo("SENT");
        assertThat(outbox.markSent(inFlight))
                .as("the relay's late acknowledgement changes nothing")
                .isFalse();
    }

    @Test
    void remittanceWhileARetryIsScheduledIsProofOfDelivery() {
        Claim claim = acceptedClaim();
        jdbc.sql("""
                        UPDATE outbox_message SET status = 'PENDING', attempts = 1, last_error = 'I/O error',
                            next_attempt_at = now() + interval '1 minute'
                        WHERE claim_id = :id""").param("id", claim.id()).update();

        assertThat(post(claimResponse(claim, "125.00", "125.00"))).hasStatus(HttpStatus.CREATED);

        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void remittanceForAClaimNeverSentIsAConflict() {
        Claim claim = acceptedClaim();

        assertThat(post(claimResponse(claim, "100.00", "80.00"))).hasStatus(HttpStatus.CONFLICT);
        assertThat(statusOf(claim)).isEqualTo(ClaimStatus.PRE_VALIDATED);
        assertThat(journals(claim)).isEqualTo(1);
    }

    @Test
    void rejectedClaimsCannotBeAdjudicated() {
        Claim rejected = ingestion
                .submit(aSubmission().patientMrn(null).build(), id -> "{}")
                .claim();
        assertThat(rejected.status()).isEqualTo(ClaimStatus.REJECTED_SCRUBBER);

        assertThat(post(claimResponse(rejected, "100.00", "80.00"))).hasStatus(HttpStatus.CONFLICT);
    }
}
