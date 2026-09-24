package com.openclaim.engine.rules.validators;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static com.openclaim.engine.rules.ScheduleFixtures.aSchedule;
import static com.openclaim.engine.rules.validators.RuleAssertions.assertCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.rules.RuleResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClinicalIndicationRuleTest {

    private final ClinicalIndicationRule rule = new ClinicalIndicationRule();

    @Test
    void passesWhenAnyDiagnosisIndicatesTheProcedure() {
        assertThat(rule.evaluate(
                                aSubmission().diagnoses("DX-OTHER", "DX-COPD").build(),
                                aSchedule().build())
                        .isFailed())
                .isFalse();
    }

    @Test
    void failsWhenNoDiagnosisIndicatesTheProcedure() {
        RuleResult result = rule.evaluate(
                aSubmission().diagnoses("DX-FRACTURE").build(), aSchedule().build());

        assertCodes(result).containsExactly("procedure-not-indicated");
        assertThat(result.violations().getFirst().message())
                .isEqualTo("Procedure code PROC-SPIRO is not clinically indicated by supplied diagnoses.");
        assertThat(result.violations().getFirst().expression())
                .isEqualTo("Claim.item.where(sequence = 1).productOrService");
    }

    @Test
    void failsWhenThereAreNoDiagnoses() {
        assertCodes(rule.evaluate(aSubmission().diagnoses().build(), aSchedule().build()))
                .containsExactly("procedure-not-indicated");
    }

    @Test
    void checksEveryItem() {
        RuleResult result = rule.evaluate(
                aSubmission().diagnoses("DX-ASTHMA").item("PROC-XRAY", 100).build(),
                aSchedule()
                        .procedure("PROC-XRAY", Set.of("DX-FRACTURE"), Set.of("AMB"))
                        .build());

        assertThat(result.violations())
                .singleElement()
                .satisfies(
                        v -> assertThat(v.expression()).isEqualTo("Claim.item.where(sequence = 2).productOrService"));
    }

    @Test
    void diagnosisFromAnotherCodeSystemDoesNotCount() {
        var submission = aSubmission().build();
        var foreign = new com.openclaim.engine.claim.ClaimSubmission(
                submission.identifier(),
                submission.patientMrn(),
                submission.payerCode(),
                submission.policyNumber(),
                submission.coverageKind(),
                submission.serviceStart(),
                submission.encounterClass(),
                java.util.List.of(new com.openclaim.engine.claim.Coding("http://hl7.org/fhir/sid/icd-10", "DX-ASTHMA")),
                submission.items(),
                submission.currency(),
                submission.totalMinor());

        assertCodes(rule.evaluate(foreign, aSchedule().build())).containsExactly("procedure-not-indicated");
    }

    @Test
    void leavesUnknownProceduresToTheAdministrativeRule() {
        assertThat(rule.evaluate(
                                aSubmission().item("PROC-NOPE", 1).build(),
                                aSchedule().build())
                        .isFailed())
                .isFalse();
    }
}
