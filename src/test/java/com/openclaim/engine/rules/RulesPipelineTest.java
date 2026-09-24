package com.openclaim.engine.rules;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static com.openclaim.engine.rules.ScheduleFixtures.aSchedule;
import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.validators.AdministrativeRule;
import com.openclaim.engine.rules.validators.BundlingRule;
import com.openclaim.engine.rules.validators.CareSettingRule;
import com.openclaim.engine.rules.validators.ClinicalIndicationRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class RulesPipelineTest {

    private final RecordingRepository repository =
            new RecordingRepository(aSchedule().build());

    private RulesPipeline pipeline() {
        // deliberately out of order: the pipeline must honour @Order, not registration order
        return new RulesPipeline(
                List.of(
                        new BundlingRule(),
                        new CareSettingRule(),
                        new AdministrativeRule(),
                        new ClinicalIndicationRule()),
                repository);
    }

    @Test
    void runsRulesInSpecStageOrder() {
        assertThat(pipeline().rules())
                .extracting(ScrubberRule::name)
                .containsExactly("AdministrativeRule", "ClinicalIndicationRule", "CareSettingRule", "BundlingRule");
    }

    @Test
    void validClaimHasNoViolations() {
        assertThat(pipeline().evaluate(aSubmission().build()).hasViolations()).isFalse();
    }

    @Test
    void collectsViolationsFromEveryRuleInStageOrder() {
        ValidationSummary summary = pipeline()
                .evaluate(aSubmission()
                        .patientMrn(null)
                        .diagnoses("DX-FRACTURE")
                        .encounterClass("SS")
                        .item("PROC-SPIRO", 1)
                        .build());

        assertThat(summary.violations())
                .extracting(ScrubViolation::rule)
                .containsExactly(
                        "AdministrativeRule",
                        "ClinicalIndicationRule",
                        "ClinicalIndicationRule",
                        "CareSettingRule",
                        "CareSettingRule",
                        "BundlingRule");
    }

    @Test
    void loadsTheScheduleOnceForTheDistinctProcedures() {
        pipeline()
                .evaluate(
                        aSubmission().item("PROC-SPIRO", 1).item("PROC-XRAY", 1).build());

        assertThat(repository.calls).hasSize(1);
        assertThat(repository.calls.getFirst())
                .containsExactly(ScheduleFixtures.procedure("PROC-SPIRO"), ScheduleFixtures.procedure("PROC-XRAY"));
        assertThat(repository.payerCodes).containsExactly("ACME-HEALTH");
    }

    private static final class RecordingRepository implements FeeScheduleRepository {

        private final FeeScheduleSnapshot snapshot;
        final List<List<Coding>> calls = new ArrayList<>();
        final List<@Nullable String> payerCodes = new ArrayList<>();

        RecordingRepository(FeeScheduleSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public FeeScheduleSnapshot snapshotFor(Collection<Coding> procedures, @Nullable String payerCode) {
            calls.add(List.copyOf(procedures));
            payerCodes.add(payerCode);
            return snapshot;
        }
    }
}
