package com.openclaim.engine.rules;

import com.openclaim.engine.claim.ScrubViolation;
import java.util.List;

public record ValidationSummary(List<ScrubViolation> violations) {

    public ValidationSummary {
        violations = List.copyOf(violations);
    }

    public boolean hasViolations() {
        return !violations.isEmpty();
    }
}
