package com.openclaim.engine.claim;

import org.jspecify.annotations.Nullable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A scrubber rule failure recorded on a rejected claim.
 *
 * @param code stable machine-readable violation code
 * @param expression FHIRPath of the offending element in the submitted Claim, when it can be pinpointed
 */
@Table("claim_violation")
public record ScrubViolation(
        String rule, String code, String message, @Nullable String expression) {}
