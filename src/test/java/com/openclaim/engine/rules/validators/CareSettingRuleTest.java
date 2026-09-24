package com.openclaim.engine.rules.validators;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static com.openclaim.engine.rules.ScheduleFixtures.aSchedule;
import static com.openclaim.engine.rules.validators.RuleAssertions.assertCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.rules.RuleResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CareSettingRuleTest {

    private final CareSettingRule rule = new CareSettingRule();

    @Test
    void passesWhenTheSettingIsAllowed() {
        assertThat(rule.evaluate(
                                aSubmission().encounterClass("IMP").build(),
                                aSchedule().build())
                        .isFailed())
                .isFalse();
    }

    @Test
    void failsWhenTheSettingIsNotAllowed() {
        RuleResult result = rule.evaluate(
                aSubmission().encounterClass("SS").build(),
                aSchedule()
                        .procedure("PROC-SPIRO", Set.of("DX-ASTHMA"), Set.of("AMB"))
                        .build());

        assertCodes(result).containsExactly("care-setting-mismatch");
        assertThat(result.violations().getFirst().message())
                .isEqualTo("Procedure PROC-SPIRO requires setting [AMB] but encounter was SS");
    }

    @Test
    void listsAllowedSettingsInAStableOrder() {
        RuleResult result = rule.evaluate(
                aSubmission().encounterClass("SS").build(),
                aSchedule()
                        .procedure("PROC-SPIRO", Set.of("DX-ASTHMA"), Set.of("IMP", "AMB", "EMER"))
                        .build());

        assertThat(result.violations().getFirst().message()).contains("[AMB, EMER, IMP]");
    }

    @Test
    void failsWhenTheEncounterHasNoClass() {
        RuleResult result = rule.evaluate(
                aSubmission().encounterClass(null).build(), aSchedule().build());

        assertCodes(result).containsExactly("encounter-class-missing");
        assertThat(result.violations().getFirst().expression()).isEqualTo("Encounter.class");
    }

    @Test
    void classFromAnotherCodeSystemIsTreatedAsMissing() {
        ClaimSubmission base = aSubmission().build();
        ClaimSubmission foreign = new ClaimSubmission(
                base.identifier(),
                base.patientMrn(),
                base.payerCode(),
                base.policyNumber(),
                base.coverageKind(),
                base.serviceStart(),
                new Coding("https://local.example.org/setting", "AMB"),
                base.diagnoses(),
                base.items(),
                base.currency(),
                base.totalMinor());

        assertCodes(rule.evaluate(foreign, aSchedule().build())).containsExactly("encounter-class-missing");
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
