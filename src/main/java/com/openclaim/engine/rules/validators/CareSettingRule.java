package com.openclaim.engine.rules.validators;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.Coding;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.FeeScheduleSnapshot;
import com.openclaim.engine.rules.RuleResult;
import com.openclaim.engine.rules.ScrubberRule;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Stage 3: every procedure may be billed in the encounter's care setting (v3-ActCode class, e.g. AMB, IMP, SS). */
@Component
@Order(300)
public class CareSettingRule implements ScrubberRule {

    private static final String NAME = "CareSettingRule";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(ClaimSubmission submission, FeeScheduleSnapshot schedule) {
        Coding encounterClass = submission.encounterClass();
        if (encounterClass == null || !Expressions.V3_ACT_CODE.equals(encounterClass.system())) {
            return RuleResult.of(List.of(new ScrubViolation(
                    NAME,
                    "encounter-class-missing",
                    "The encounter has no v3-ActCode class to check the care setting against.",
                    "Encounter.class")));
        }
        String setting = encounterClass.code();
        List<ScrubViolation> violations = new ArrayList<>();
        for (ClaimSubmission.LineItem item : submission.items()) {
            schedule.rulesFor(item.procedure())
                    .filter(rules -> !rules.allowedSettings().contains(setting))
                    .ifPresent(rules -> violations.add(new ScrubViolation(
                            NAME,
                            "care-setting-mismatch",
                            "Procedure %s requires setting %s but encounter was %s"
                                    .formatted(
                                            item.procedure().code(), new TreeSet<>(rules.allowedSettings()), setting),
                            Expressions.procedureOf(item.sequence()))));
        }
        return RuleResult.of(violations);
    }
}
