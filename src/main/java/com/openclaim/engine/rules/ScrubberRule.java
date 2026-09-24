package com.openclaim.engine.rules;

import com.openclaim.engine.claim.ClaimSubmission;

/**
 * One stage of the scrubber. Implementations are Spring beans ordered with {@link org.springframework.core.annotation.Order};
 * every rule runs, and all violations are reported together.
 */
public interface ScrubberRule {

    /** Stable name recorded with each violation, e.g. {@code AdministrativeRule}. */
    String name();

    RuleResult evaluate(ClaimSubmission submission, FeeScheduleSnapshot schedule);
}
