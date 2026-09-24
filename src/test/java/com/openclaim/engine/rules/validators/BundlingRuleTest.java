package com.openclaim.engine.rules.validators;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static com.openclaim.engine.rules.ScheduleFixtures.aSchedule;
import static com.openclaim.engine.rules.validators.RuleAssertions.assertCodes;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.rules.RuleResult;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BundlingRuleTest {

    private final BundlingRule rule = new BundlingRule();

    @Test
    void passesDistinctCompatibleProcedures() {
        RuleResult result = rule.evaluate(
                aSubmission().item("PROC-XRAY", 100).build(),
                aSchedule().procedure("PROC-XRAY", Set.of(), Set.of()).build());

        assertThat(result.isFailed()).isFalse();
    }

    @Test
    void flagsDuplicateProcedureLines() {
        RuleResult result = rule.evaluate(
                aSubmission().item("PROC-SPIRO", 100).build(), aSchedule().build());

        assertCodes(result).containsExactly("duplicate-procedure");
        assertThat(result.violations().getFirst().expression())
                .isEqualTo("Claim.item.where(sequence = 2).productOrService");
    }

    @Test
    void flagsMutuallyExclusiveProceduresOnce() {
        RuleResult result = rule.evaluate(
                aSubmission().item("PROC-SPIRO-LTD", 100).build(),
                aSchedule().conflict("PROC-SPIRO", "PROC-SPIRO-LTD").build());

        assertCodes(result).containsExactly("procedure-conflict");
        assertThat(result.violations().getFirst().message())
                .isEqualTo("Procedures PROC-SPIRO and PROC-SPIRO-LTD cannot be billed on the same claim");
    }

    @Test
    void reportsEveryConflictingPair() {
        RuleResult result = rule.evaluate(
                aSubmission().item("PROC-B", 1).item("PROC-C", 1).build(),
                aSchedule()
                        .conflict("PROC-SPIRO", "PROC-B")
                        .conflict("PROC-B", "PROC-C")
                        .build());

        assertCodes(result).containsExactly("procedure-conflict", "procedure-conflict");
    }
}
