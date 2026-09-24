package com.openclaim.engine.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Builder for canonical submissions in tests; defaults describe a claim that passes every scrubber rule. */
public final class SubmissionFixtures {

    public static final String CLAIM_SYSTEM = "https://ehr.example.org/claims";
    public static final String PROCEDURE_SYSTEM = "https://openclaim.example.org/CodeSystem/procedure";
    public static final String DIAGNOSIS_SYSTEM = "https://openclaim.example.org/CodeSystem/diagnosis";
    public static final String ACT_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";

    private ClaimIdentifier identifier = new ClaimIdentifier(CLAIM_SYSTEM, "CLM-" + UUID.randomUUID());
    private @Nullable String patientMrn = "MRN-0001";
    private @Nullable String payerCode = "ACME-HEALTH";
    private @Nullable String policyNumber = "POL123456";
    private @Nullable String coverageKind = "insurance";
    private Instant serviceStart = Instant.parse("2026-09-01T09:30:00Z");
    private @Nullable Coding encounterClass = new Coding(ACT_CODE_SYSTEM, "AMB");
    private List<Coding> diagnoses = new ArrayList<>(List.of(new Coding(DIAGNOSIS_SYSTEM, "DX-ASTHMA")));
    private List<ClaimSubmission.LineItem> items = new ArrayList<>(
            List.of(new ClaimSubmission.LineItem(1, new Coding(PROCEDURE_SYSTEM, "PROC-SPIRO"), 12_500)));
    private Currency currency = Currency.getInstance("EUR");

    private SubmissionFixtures() {}

    public static SubmissionFixtures aSubmission() {
        return new SubmissionFixtures();
    }

    public SubmissionFixtures identifier(String system, String value) {
        this.identifier = new ClaimIdentifier(system, value);
        return this;
    }

    public SubmissionFixtures patientMrn(@Nullable String patientMrn) {
        this.patientMrn = patientMrn;
        return this;
    }

    public SubmissionFixtures payerCode(@Nullable String payerCode) {
        this.payerCode = payerCode;
        return this;
    }

    public SubmissionFixtures policyNumber(@Nullable String policyNumber) {
        this.policyNumber = policyNumber;
        return this;
    }

    public SubmissionFixtures coverageKind(@Nullable String coverageKind) {
        this.coverageKind = coverageKind;
        return this;
    }

    public SubmissionFixtures serviceStart(Instant serviceStart) {
        this.serviceStart = serviceStart;
        return this;
    }

    public SubmissionFixtures encounterClass(@Nullable String code) {
        this.encounterClass = code == null ? null : new Coding(ACT_CODE_SYSTEM, code);
        return this;
    }

    public SubmissionFixtures diagnoses(String... codes) {
        this.diagnoses = new ArrayList<>();
        for (String code : codes) {
            diagnoses.add(new Coding(DIAGNOSIS_SYSTEM, code));
        }
        return this;
    }

    public SubmissionFixtures noItems() {
        this.items = new ArrayList<>();
        return this;
    }

    public SubmissionFixtures item(String procedureCode, long netMinor) {
        items.add(
                new ClaimSubmission.LineItem(items.size() + 1, new Coding(PROCEDURE_SYSTEM, procedureCode), netMinor));
        return this;
    }

    public SubmissionFixtures onlyItem(String procedureCode, long netMinor) {
        return noItems().item(procedureCode, netMinor);
    }

    public SubmissionFixtures currency(String code) {
        this.currency = Currency.getInstance(code);
        return this;
    }

    public ClaimSubmission build() {
        long total =
                items.stream().mapToLong(ClaimSubmission.LineItem::netMinor).sum();
        return new ClaimSubmission(
                identifier,
                patientMrn,
                payerCode,
                policyNumber,
                coverageKind,
                serviceStart,
                encounterClass,
                diagnoses,
                items,
                currency,
                total);
    }
}
