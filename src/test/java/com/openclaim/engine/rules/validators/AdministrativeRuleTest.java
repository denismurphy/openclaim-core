package com.openclaim.engine.rules.validators;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static com.openclaim.engine.rules.ScheduleFixtures.aSchedule;
import static com.openclaim.engine.rules.validators.RuleAssertions.assertCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.RuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdministrativeRuleTest {

    private final AdministrativeRule rule = new AdministrativeRule();

    @Test
    void passesACompleteClaim() {
        assertThat(rule.evaluate(aSubmission().build(), aSchedule().build()).isFailed())
                .isFalse();
    }

    @Test
    void missingPatientIdentifier() {
        RuleResult result = rule.evaluate(
                aSubmission().patientMrn(null).build(), aSchedule().build());

        assertCodes(result).containsExactly("patient-identifier-missing");
        ScrubViolation violation = result.violations().getFirst();
        assertThat(violation.rule()).isEqualTo("AdministrativeRule");
        assertThat(violation.message()).isEqualTo("Missing mandatory patient identifier.");
        assertThat(violation.expression()).isEqualTo("Claim.patient");
    }

    @Test
    void missingPayer() {
        assertCodes(rule.evaluate(
                        aSubmission().payerCode(null).build(), aSchedule().build()))
                .containsExactly("payer-missing");
    }

    @Test
    void unregisteredPayer() {
        assertCodes(rule.evaluate(
                        aSubmission().build(), aSchedule().unknownPayer().build()))
                .containsExactly("payer-unknown");
    }

    @Test
    void missingPolicyNumber() {
        assertCodes(rule.evaluate(
                        aSubmission().policyNumber(null).build(), aSchedule().build()))
                .containsExactly("policy-number-missing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC12", "ABCDEFGHIJKLM", "abc123456", "POL-12345", "POL 12345", "POL12345\n", "ＰＯＬ１２３４５６"})
    void malformedPolicyNumber(String policyNumber) {
        RuleResult result = rule.evaluate(
                aSubmission().policyNumber(policyNumber).build(), aSchedule().build());

        assertCodes(result).containsExactly("policy-number-format");
        assertThat(result.violations().getFirst().message())
                .isEqualTo("Payer policy number does not match structural format.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABC123", "ABCDEFGHIJKL", "123456789012"})
    void wellFormedPolicyNumbers(String policyNumber) {
        assertThat(rule.evaluate(
                                aSubmission().policyNumber(policyNumber).build(),
                                aSchedule().build())
                        .isFailed())
                .isFalse();
    }

    @Test
    void coverageThatIsNotInsurance() {
        assertCodes(rule.evaluate(
                        aSubmission().coverageKind("self-pay").build(),
                        aSchedule().build()))
                .containsExactly("coverage-not-insurance");
    }

    @Test
    void unknownProcedureCode() {
        RuleResult result = rule.evaluate(
                aSubmission().item("PROC-NOPE", 100).build(), aSchedule().build());

        assertCodes(result).containsExactly("procedure-unknown");
        assertThat(result.violations().getFirst().expression())
                .isEqualTo("Claim.item.where(sequence = 2).productOrService");
    }

    @Test
    void reportsEveryAdministrativeProblemAtOnce() {
        RuleResult result = rule.evaluate(
                aSubmission()
                        .patientMrn(null)
                        .policyNumber("bad")
                        .item("PROC-NOPE", 1)
                        .build(),
                aSchedule().unknownPayer().build());

        assertCodes(result)
                .containsExactly(
                        "patient-identifier-missing", "payer-unknown", "policy-number-format", "procedure-unknown");
    }
}
