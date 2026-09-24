package com.openclaim.engine.rules;

import com.openclaim.engine.claim.ScrubViolation;
import java.util.List;

/** Outcome of one rule: passed when it found no violations. */
public record RuleResult(List<ScrubViolation> violations) {

    private static final RuleResult PASSED = new RuleResult(List.of());

    public RuleResult {
        violations = List.copyOf(violations);
    }

    public static RuleResult passed() {
        return PASSED;
    }

    public static RuleResult of(List<ScrubViolation> violations) {
        return violations.isEmpty() ? PASSED : new RuleResult(violations);
    }

    public boolean isFailed() {
        return !violations.isEmpty();
    }
}
