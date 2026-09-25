package com.openclaim.engine.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.fhir.parser.StrictErrorHandler;
import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.remittance.Adjudication;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Consumer;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Money;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.hl7.fhir.r5.model.Reference;
import org.junit.jupiter.api.Test;

class RemittanceMapperTest {

    static final String CLAIM_RESPONSE = "/fhir/claim-response.json";

    private final RemittanceMapper mapper = new RemittanceMapper(FhirFixtures.properties());

    static ClaimResponse claimResponse() {
        return FhirFixtures.CONTEXT
                .newJsonParser()
                .setParserErrorHandler(new StrictErrorHandler())
                .parseResource(ClaimResponse.class, FhirFixtures.json(CLAIM_RESPONSE));
    }

    private Adjudication mapWith(Consumer<ClaimResponse> change) {
        ClaimResponse response = claimResponse();
        change.accept(response);
        return mapper.map(response);
    }

    private void assertRejected(Consumer<ClaimResponse> change, IssueType type, String message) {
        assertThatThrownBy(() -> mapWith(change)).isInstanceOfSatisfying(FhirMappingException.class, e -> {
            assertThat(e.issueType()).isEqualTo(type);
            assertThat(e.getMessage()).contains(message);
        });
    }

    private static ClaimResponse.TotalComponent total(ClaimResponse response, String code) {
        return response.getTotal().stream()
                .filter(t -> t.getCategory().getCodingFirstRep().getCode().equals(code))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void mapsHeaderTotals() {
        Adjudication adjudication = mapper.map(claimResponse());

        assertThat(adjudication.identifier())
                .isEqualTo(new ClaimIdentifier("https://payer.example.org/adjudication", "ADJ-0001"));
        assertThat(adjudication.claimId()).isEqualTo(new UUID(0, 0));
        assertThat(adjudication.submittedMinor()).isEqualTo(20_550);
        assertThat(adjudication.eligibleMinor()).isEqualTo(18_000);
        assertThat(adjudication.benefitMinor()).isEqualTo(15_000);
        assertThat(adjudication.writeOffMinor()).isEqualTo(2_550);
        assertThat(adjudication.patientLiabilityMinor()).isEqualTo(3_000);
    }

    @Test
    void eligibleDefaultsToSubmitted() {
        Adjudication adjudication = mapWith(r -> r.getTotal().remove(total(r, "eligible")));

        assertThat(adjudication.writeOffMinor()).isZero();
        assertThat(adjudication.patientLiabilityMinor()).isEqualTo(5_550);
    }

    @Test
    void claimCanBeReferencedByIdentifier() {
        Adjudication adjudication = mapWith(r -> r.setRequest(new Reference()
                .setIdentifier(new Identifier()
                        .setSystem("https://ehr.example.org/claims")
                        .setValue("CLM-1"))));

        assertThat(adjudication.claimId()).isNull();
        assertThat(adjudication.claimIdentifier())
                .isEqualTo(new ClaimIdentifier("https://ehr.example.org/claims", "CLM-1"));
    }

    @Test
    void totalsInOtherCodeSystemsAreIgnored() {
        Adjudication adjudication = mapWith(r -> r.addTotal()
                .setCategory(
                        new CodeableConcept().addCoding(new org.hl7.fhir.r5.model.Coding("https://x", "benefit", null)))
                .setAmount(new Money().setValue(BigDecimal.ONE).setCurrency("EUR")));

        assertThat(adjudication.benefitMinor()).isEqualTo(15_000);
    }

    @Test
    void onlyCompleteAdjudicationsArePosted() {
        assertRejected(r -> r.setOutcome(Enumerations.ClaimProcessingCodes.PARTIAL), IssueType.BUSINESSRULE, "partial");
        assertRejected(r -> r.setOutcome(Enumerations.ClaimProcessingCodes.QUEUED), IssueType.BUSINESSRULE, "queued");
    }

    @Test
    void requiresAnIdentifier() {
        assertRejected(r -> r.getIdentifier().clear(), IssueType.REQUIRED, "identifier");
    }

    @Test
    void requiresAClaimReference() {
        assertRejected(r -> r.setRequest(new Reference("Patient/1")), IssueType.REQUIRED, "request");
    }

    @Test
    void malformedClaimIdIsNotFound() {
        assertRejected(r -> r.setRequest(new Reference("Claim/not-a-uuid")), IssueType.NOTFOUND, "not-a-uuid");
    }

    @Test
    void requiresSubmittedAndBenefitTotals() {
        assertRejected(r -> r.getTotal().remove(total(r, "submitted")), IssueType.REQUIRED, "'submitted'");
        assertRejected(r -> r.getTotal().remove(total(r, "benefit")), IssueType.REQUIRED, "'benefit'");
    }

    @Test
    void duplicateCategoriesAreAmbiguous() {
        assertRejected(r -> r.addTotal(total(r, "benefit").copy()), IssueType.DUPLICATE, "benefit");
    }

    @Test
    void benefitCannotExceedEligible() {
        assertRejected(
                r -> {
                    total(r, "benefit").getAmount().setValue(new BigDecimal("180.01"));
                    r.getPayment().getAmount().setValue(new BigDecimal("180.01"));
                },
                IssueType.VALUE,
                "Benefit exceeds");
    }

    @Test
    void eligibleCannotExceedSubmitted() {
        assertRejected(
                r -> total(r, "eligible").getAmount().setValue(new BigDecimal("205.51")),
                IssueType.VALUE,
                "Eligible amount exceeds");
    }

    @Test
    void paymentMustMatchBenefit() {
        assertRejected(
                r -> r.getPayment().getAmount().setValue(new BigDecimal("149.99")), IssueType.VALUE, "Payment amount");
    }

    @Test
    void foreignCurrencyIsRejected() {
        assertRejected(r -> total(r, "benefit").getAmount().setCurrency("USD"), IssueType.VALUE, "found USD");
    }

    @Test
    void negativeAmountsAreRejected() {
        assertRejected(
                r -> total(r, "benefit").getAmount().setValue(new BigDecimal("-1")), IssueType.VALUE, "negative");
    }
}
