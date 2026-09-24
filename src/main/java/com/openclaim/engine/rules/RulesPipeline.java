package com.openclaim.engine.rules;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.claim.ScrubViolation;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link ScrubberRule} in {@code @Order} and collects all violations.
 *
 * <p>The spec's pseudocode collects every violation while its diagram halts at the first; collecting all is kept so a
 * submitter can fix a claim in one round trip. Any violation still rejects the claim.
 */
@Component
public class RulesPipeline {

    private final List<ScrubberRule> rules;
    private final FeeScheduleRepository feeSchedule;

    public RulesPipeline(List<ScrubberRule> rules, FeeScheduleRepository feeSchedule) {
        List<ScrubberRule> ordered = new ArrayList<>(rules);
        AnnotationAwareOrderComparator.sort(ordered);
        this.rules = List.copyOf(ordered);
        this.feeSchedule = feeSchedule;
    }

    public ValidationSummary evaluate(ClaimSubmission submission) {
        List<Coding> procedures = submission.items().stream()
                .map(ClaimSubmission.LineItem::procedure)
                .distinct()
                .toList();
        FeeScheduleSnapshot schedule = feeSchedule.snapshotFor(procedures, submission.payerCode());
        List<ScrubViolation> violations = new ArrayList<>();
        for (ScrubberRule rule : rules) {
            violations.addAll(rule.evaluate(submission, schedule).violations());
        }
        return new ValidationSummary(violations);
    }

    List<ScrubberRule> rules() {
        return rules;
    }
}
