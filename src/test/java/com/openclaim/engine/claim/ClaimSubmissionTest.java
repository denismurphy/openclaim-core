package com.openclaim.engine.claim;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClaimSubmissionTest {

    private static final String ID = "CLM-1";

    private static SubmissionFixtures base() {
        return aSubmission().identifier(SubmissionFixtures.CLAIM_SYSTEM, ID);
    }

    @Test
    void identicalContentHasIdenticalFingerprint() {
        assertThat(base().build().fingerprint()).isEqualTo(base().build().fingerprint());
    }

    @Test
    void movingMoneyBetweenLinesChangesFingerprintEvenThoughTotalIsUnchanged() {
        ClaimSubmission original =
                base().onlyItem("PROC-A", 100).item("PROC-B", 200).build();
        ClaimSubmission shifted =
                base().onlyItem("PROC-A", 200).item("PROC-B", 100).build();

        assertThat(shifted.totalMinor()).isEqualTo(original.totalMinor());
        assertThat(shifted.fingerprint()).isNotEqualTo(original.fingerprint());
    }

    @Test
    void totalMustEqualSumOfLines() {
        ClaimSubmission valid = base().build();

        assertThatThrownBy(() -> new ClaimSubmission(
                        valid.identifier(),
                        valid.patientMrn(),
                        valid.payerCode(),
                        valid.policyNumber(),
                        valid.coverageKind(),
                        valid.serviceStart(),
                        valid.encounterClass(),
                        valid.diagnoses(),
                        valid.items(),
                        valid.currency(),
                        valid.totalMinor() + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");
    }

    @Test
    void requiresAtLeastOneLine() {
        assertThatThrownBy(() -> base().noItems().build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("item");
    }

    @Test
    void rejectsNegativeLineAmounts() {
        assertThatThrownBy(() ->
                        base().onlyItem("PROC-A", 500).item("PROC-B", -100).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void rejectsTotalsThatOverflow() {
        assertThatThrownBy(() -> base().onlyItem("PROC-A", Long.MAX_VALUE)
                        .item("PROC-B", 1)
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    static Stream<Arguments> everyFieldChange() {
        return Stream.of(
                Arguments.of("identifier system", (UnaryOperator<SubmissionFixtures>) f -> f.identifier("other", ID)),
                Arguments.of("identifier value", (UnaryOperator<SubmissionFixtures>)
                        f -> f.identifier(SubmissionFixtures.CLAIM_SYSTEM, "CLM-2")),
                Arguments.of("mrn", (UnaryOperator<SubmissionFixtures>) f -> f.patientMrn("MRN-9")),
                Arguments.of("mrn absent", (UnaryOperator<SubmissionFixtures>) f -> f.patientMrn(null)),
                Arguments.of("payer", (UnaryOperator<SubmissionFixtures>) f -> f.payerCode("OTHER")),
                Arguments.of("policy", (UnaryOperator<SubmissionFixtures>) f -> f.policyNumber("POL999999")),
                Arguments.of("coverage kind", (UnaryOperator<SubmissionFixtures>) f -> f.coverageKind("self-pay")),
                Arguments.of("service start", (UnaryOperator<SubmissionFixtures>)
                        f -> f.serviceStart(Instant.parse("2026-09-02T09:30:00Z"))),
                Arguments.of("encounter class", (UnaryOperator<SubmissionFixtures>) f -> f.encounterClass("IMP")),
                Arguments.of("diagnosis", (UnaryOperator<SubmissionFixtures>) f -> f.diagnoses("DX-OTHER")),
                Arguments.of("extra diagnosis", (UnaryOperator<SubmissionFixtures>)
                        f -> f.diagnoses("DX-ASTHMA", "DX-OTHER")),
                Arguments.of("procedure", (UnaryOperator<SubmissionFixtures>) f -> f.onlyItem("PROC-OTHER", 12_500)),
                Arguments.of("amount", (UnaryOperator<SubmissionFixtures>) f -> f.onlyItem("PROC-SPIRO", 12_501)),
                Arguments.of("extra line", (UnaryOperator<SubmissionFixtures>) f -> f.item("PROC-OTHER", 0)),
                Arguments.of("currency", (UnaryOperator<SubmissionFixtures>) f -> f.currency("USD")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everyFieldChange")
    void anyContentChangeChangesFingerprint(String field, UnaryOperator<SubmissionFixtures> change) {
        assertThat(change.apply(base()).build().fingerprint())
                .isNotEqualTo(base().build().fingerprint());
    }
}
