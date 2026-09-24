package com.openclaim.engine.rules.validators;

import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.rules.RuleResult;
import org.assertj.core.api.ListAssert;

final class RuleAssertions {

    private RuleAssertions() {}

    static ListAssert<String> assertCodes(RuleResult result) {
        return assertThat(result.violations().stream().map(ScrubViolation::code).toList());
    }
}
