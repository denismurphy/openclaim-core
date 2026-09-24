package com.openclaim.engine.rules.validators;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.FeeScheduleSnapshot;
import com.openclaim.engine.rules.RuleResult;
import com.openclaim.engine.rules.ScrubberRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Stage 2: every procedure is indicated by at least one of the claim's diagnoses. As in the spec, the claim's
 * diagnoses are matched as a set; per-item {@code diagnosisSequence} linking is not evaluated.
 */
@Component
@Order(200)
public class ClinicalIndicationRule implements ScrubberRule {

    private static final String NAME = "ClinicalIndicationRule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(ClaimSubmission submission, FeeScheduleSnapshot schedule) {
        List<ScrubViolation> violations = new ArrayList<>();
        for (ClaimSubmission.LineItem item : submission.items()) {
            schedule.rulesFor(item.procedure())
                    .filter(rules -> Collections.disjoint(rules.allowedDiagnoses(), submission.diagnoses()))
                    .ifPresent(rules -> violations.add(new ScrubViolation(
                            NAME,
                            "procedure-not-indicated",
                            "Procedure code %s is not clinically indicated by supplied diagnoses."
                                    .formatted(item.procedure().code()),
                            Expressions.procedureOf(item.sequence()))));
        }
        return RuleResult.of(violations);
    }
}
