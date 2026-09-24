package com.openclaim.engine.rules.validators;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.FeeScheduleSnapshot;
import com.openclaim.engine.rules.RuleResult;
import com.openclaim.engine.rules.ScrubberRule;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Stage 1: identifiers, payer registration, policy number format and procedure codes are present and valid. */
@Component
@Order(100)
public class AdministrativeRule implements ScrubberRule {

    private static final String NAME = "AdministrativeRule";
    private static final Pattern POLICY_NUMBER = Pattern.compile("[A-Z0-9]{6,12}");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(ClaimSubmission submission, FeeScheduleSnapshot schedule) {
        List<ScrubViolation> violations = new ArrayList<>();
        if (submission.patientMrn() == null) {
            violations.add(
                    violation("patient-identifier-missing", "Missing mandatory patient identifier.", "Claim.patient"));
        }
        if (submission.payerCode() == null) {
            violations.add(violation("payer-missing", "The focal coverage names no insurer.", "Coverage.insurer"));
        } else if (!schedule.payerKnown()) {
            violations.add(violation(
                    "payer-unknown",
                    "Payer %s is not registered with this clearinghouse.".formatted(submission.payerCode()),
                    "Coverage.insurer"));
        }
        String policyNumber = submission.policyNumber();
        if (policyNumber == null) {
            violations.add(violation(
                    "policy-number-missing", "The focal coverage has no subscriber id.", "Coverage.subscriberId"));
        } else if (!POLICY_NUMBER.matcher(policyNumber).matches()) {
            violations.add(violation(
                    "policy-number-format",
                    "Payer policy number does not match structural format.",
                    "Coverage.subscriberId"));
        }
        if (submission.coverageKind() != null && !"insurance".equals(submission.coverageKind())) {
            violations.add(violation(
                    "coverage-not-insurance",
                    "Coverage kind %s cannot be billed to a payer.".formatted(submission.coverageKind()),
                    "Coverage.kind"));
        }
        for (ClaimSubmission.LineItem item : submission.items()) {
            if (schedule.rulesFor(item.procedure()).isEmpty()) {
                violations.add(violation(
                        "procedure-unknown",
                        "Procedure code %s is not in the fee schedule."
                                .formatted(item.procedure().code()),
                        Expressions.procedureOf(item.sequence())));
            }
        }
        return RuleResult.of(violations);
    }

    private static ScrubViolation violation(String code, String message, String expression) {
        return new ScrubViolation(NAME, code, message, expression);
    }
}
