package com.openclaim.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.openclaim.engine.TestJwt;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.claim.IdempotencyKey;
import com.openclaim.engine.fhir.FhirFixtures;
import com.openclaim.engine.fhir.FhirMediaTypes;
import com.openclaim.engine.ledger.Balances;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.hl7.fhir.r5.model.Enumerations.ClaimProcessingCodes;
import org.hl7.fhir.r5.model.OperationOutcome;
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
class ClaimSubmitIT {

    private static final String SUBMIT = "/fhir/Claim/$submit";
    private static final String BUNDLE = FhirFixtures.json(FhirFixtures.CLAIM_BUNDLE);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    FhirContext fhir;

    @Autowired
    ClaimRepository claims;

    @Autowired
    Ledger ledger;

    @Autowired
    JdbcClient jdbc;

    /** The reference bundle under a fresh Claim.identifier, so tests never collide (ledger rows cannot be deleted). */
    private static String uniqueBundle() {
        return BUNDLE.replace("CLM-2026-0001", "CLM-" + UUID.randomUUID());
    }

    private static String identifierOf(String bundle) {
        int start = bundle.indexOf("CLM-");
        return bundle.substring(start, bundle.indexOf('"', start));
    }

    private MvcTestResult submit(String body) {
        return submit(body, FhirMediaTypes.FHIR_JSON);
    }

    private MvcTestResult submit(String body, MediaType contentType) {
        return mvc.post()
                .uri(SUBMIT)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer("claims:write"))
                .contentType(contentType)
                .content(body)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T extends org.hl7.fhir.r5.model.Resource> T parse(MvcTestResult result, Class<T> type) {
        return fhir.newJsonParser().parseResource(type, body(result));
    }

    private Claim storedClaim(String bundle) {
        return claims.findByIdentifierSystemAndIdentifierValue("https://ehr.example.org/claims", identifierOf(bundle))
                .orElseThrow();
    }

    private int count(String sql, Object claimId) {
        return jdbc.sql(sql).param("claimId", claimId).query(Integer.class).single();
    }

    private int claimsWithIdentifier(String bundle) {
        return jdbc.sql("SELECT count(*) FROM claim WHERE identifier_value = :value")
                .param("value", identifierOf(bundle))
                .query(Integer.class)
                .single();
    }

    @Test
    void acceptedClaimIsPreValidatedChargedAndQueuedAtomically() {
        String bundle = uniqueBundle();

        MvcTestResult result = submit(bundle);

        assertThat(result).hasStatusOk().hasContentType("application/fhir+json;charset=UTF-8");
        ClaimResponse response = parse(result, ClaimResponse.class);
        Claim claim = storedClaim(bundle);
        assertThat(response.getOutcome()).isEqualTo(ClaimProcessingCodes.QUEUED);
        assertThat(response.getIdPart()).isEqualTo(claim.id().toString());
        assertThat(response.getRequest().getReference()).isEqualTo("Claim/" + claim.id());
        assertThat(response.getPatient().getIdentifier().getValue()).isEqualTo("MRN-0001");
        assertThat(claim.status()).isEqualTo(ClaimStatus.PRE_VALIDATED);

        Balances balances = ledger.balances(claim.id());
        assertThat(balances.of(LedgerAccount.PAYER_AR)).isEqualTo(20_550);
        assertThat(balances.of(LedgerAccount.REVENUE)).isEqualTo(-20_550);

        Map<String, Object> outbox = jdbc.sql("SELECT * FROM outbox_message WHERE claim_id = :claimId")
                .param("claimId", claim.id())
                .query()
                .singleRow();
        assertThat(outbox.get("status")).isEqualTo("PENDING");
        assertThat(outbox.get("event_type")).isEqualTo("CLAIM_PRE_VALIDATED");
        assertThat(outbox.get("idempotency_key")).isEqualTo(IdempotencyKey.forDispatch(claim));
        Bundle payload = fhir.newJsonParser().parseResource(Bundle.class, (String) outbox.get("payload"));
        assertThat(payload.getEntryFirstRep().getResource().getIdPart())
                .isEqualTo(claim.id().toString());
        assertThat(payload.getEntry()).hasSize(5);
    }

    @Test
    void scrubberRejectionIsStoredAndReportedWithoutChargeOrDispatch() {
        String bundle = uniqueBundle().replace("\"PROC-CONSULT\"", "\"PROC-NOT-IN-SCHEDULE\"");

        MvcTestResult result = submit(bundle);

        assertThat(result).hasStatusOk();
        ClaimResponse response = parse(result, ClaimResponse.class);
        assertThat(response.getOutcome()).isEqualTo(ClaimProcessingCodes.ERROR);
        assertThat(response.getError()).singleElement().satisfies(error -> {
            assertThat(error.getCode().getCodingFirstRep().getCode()).isEqualTo("procedure-unknown");
            assertThat(error.getExpression())
                    .extracting(Object::toString)
                    .containsExactly("Claim.item.where(sequence = 2).productOrService");
        });
        Claim claim = storedClaim(bundle);
        assertThat(claim.status()).isEqualTo(ClaimStatus.REJECTED_SCRUBBER);
        assertThat(count("SELECT count(*) FROM ledger_journal WHERE claim_id = :claimId", claim.id()))
                .isZero();
        assertThat(count("SELECT count(*) FROM outbox_message WHERE claim_id = :claimId", claim.id()))
                .isZero();
    }

    @Test
    void identicalResubmissionReplaysTheOriginalResponse() {
        String bundle = uniqueBundle();

        String first = body(submit(bundle));
        MvcTestResult second = submit(bundle.replace("\n", "").replace("  ", " "));

        assertThat(second).hasStatusOk();
        assertThat(body(second)).isEqualTo(first);
        assertThat(claimsWithIdentifier(bundle)).isEqualTo(1);
        Claim claim = storedClaim(bundle);
        assertThat(count("SELECT count(*) FROM outbox_message WHERE claim_id = :claimId", claim.id()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger_journal WHERE claim_id = :claimId", claim.id()))
                .isEqualTo(1);
    }

    @Test
    void resubmittingARejectedClaimReplaysTheRejection() {
        String bundle =
                uniqueBundle().replace("\"DX-COPD\"", "\"DX-HYPERTENSION\"").replace("\"AMB\"", "\"SS\"");

        String first = body(submit(bundle));

        assertThat(body(submit(bundle))).isEqualTo(first);
        assertThat(first).contains("\"outcome\":\"error\"");
    }

    @Test
    void differentContentUnderAnExistingIdentifierIsAConflict() {
        String bundle = uniqueBundle();
        submit(bundle);

        MvcTestResult conflict = submit(bundle.replace("\"value\": 80.50", "\"value\": 90.50")
                .replace("\"value\": 205.50", "\"value\": 215.50"));

        assertThat(conflict).hasStatus(HttpStatus.CONFLICT);
        assertThat(parse(conflict, OperationOutcome.class).getIssueFirstRep().getCode())
                .isEqualTo(OperationOutcome.IssueType.DUPLICATE);
        assertThat(storedClaim(bundle).totalBilledMinor()).isEqualTo(20_550);
    }

    @Test
    void concurrentIdenticalSubmissionsCreateOneClaim() throws Exception {
        String bundle = uniqueBundle();
        int clients = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcTestResult>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int i = 0; i < clients; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return submit(bundle);
                }));
            }
            start.countDown();
            for (Future<MvcTestResult> result : results) {
                assertThat(result.get()).hasStatusOk();
            }
        }

        Claim claim = storedClaim(bundle);
        assertThat(claimsWithIdentifier(bundle)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_message WHERE claim_id = :claimId", claim.id()))
                .isEqualTo(1);
        assertThat(ledger.balances(claim.id()).of(LedgerAccount.PAYER_AR)).isEqualTo(20_550);
        for (Future<MvcTestResult> result : results) {
            assertThat(parse(result.get(), ClaimResponse.class).getIdPart())
                    .isEqualTo(claim.id().toString());
        }
    }

    @Test
    void unknownElementIsBadRequestAndNothingIsStored() {
        String bundle = uniqueBundle().replace("\"use\": \"claim\"", "\"use\": \"claim\", \"bogus\": true");

        MvcTestResult result = submit(bundle);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(parse(result, OperationOutcome.class).getIssueFirstRep().getDiagnostics())
                .contains("bogus");
        assertThat(claimsWithIdentifier(bundle)).isZero();
    }

    @Test
    void unmappableBundleIsUnprocessableAndNothingIsStored() {
        String bundle = uniqueBundle().replace("\"currency\": \"EUR\"", "\"currency\": \"USD\"");

        MvcTestResult result = submit(bundle);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
        OperationOutcome.OperationOutcomeIssueComponent issue =
                parse(result, OperationOutcome.class).getIssueFirstRep();
        assertThat(issue.getCode()).isEqualTo(OperationOutcome.IssueType.VALUE);
        assertThat(issue.getExpression()).isNotEmpty();
        assertThat(claimsWithIdentifier(bundle)).isZero();
    }

    @Test
    void oversizedBodyIsRejected() {
        String padded = uniqueBundle().replaceFirst("\\{", "{\"id\":\"" + "x".repeat(1_100_000) + "\",");

        assertThat(submit(padded)).hasStatus(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void xmlIsNotSupported() {
        assertThat(submit("<Bundle/>", MediaType.APPLICATION_XML)).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void plainJsonContentTypeIsAccepted() {
        assertThat(submit(uniqueBundle(), MediaType.APPLICATION_JSON)).hasStatusOk();
    }
}
