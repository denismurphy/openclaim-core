package com.openclaim.engine.rules.validators;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.FeeScheduleSnapshot;
import com.openclaim.engine.rules.RuleResult;
import com.openclaim.engine.rules.ScrubberRule;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Stage 4: no procedure is billed twice, and no mutually exclusive procedures share a claim. */
@Component
@Order(400)
public class BundlingRule implements ScrubberRule {

    private static final String NAME = "BundlingRule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(ClaimSubmission submission, FeeScheduleSnapshot schedule) {
        List<ScrubViolation> violations = new ArrayList<>();
        Set<Coding> seen = new HashSet<>();
        List<ClaimSubmission.LineItem> distinct = new ArrayList<>();
        for (ClaimSubmission.LineItem item : submission.items()) {
            if (seen.add(item.procedure())) {
                distinct.add(item);
            } else {
                violations.add(new ScrubViolation(
                        NAME,
                        "duplicate-procedure",
                        "Procedure %s is billed more than once"
                                .formatted(item.procedure().code()),
                        Expressions.procedureOf(item.sequence())));
            }
        }
        for (int i = 0; i < distinct.size(); i++) {
            for (int j = i + 1; j < distinct.size(); j++) {
                ClaimSubmission.LineItem first = distinct.get(i);
                ClaimSubmission.LineItem second = distinct.get(j);
                if (schedule.conflicting(first.procedure(), second.procedure())) {
                    violations.add(new ScrubViolation(
                            NAME,
                            "procedure-conflict",
                            "Procedures %s and %s cannot be billed on the same claim"
                                    .formatted(
                                            first.procedure().code(),
                                            second.procedure().code()),
                            Expressions.procedureOf(second.sequence())));
                }
            }
        }
        return RuleResult.of(violations);
    }
}
