package com.openclaim.engine.claim;

/**
 * Claim lifecycle (spec §3). Transitions only move forward; the exhaustive {@code switch} makes adding a
 * status without deciding its outgoing transitions a compile error.
 */
public enum ClaimStatus {
    DRAFT,
    REJECTED_SCRUBBER,
    PRE_VALIDATED,
    DISPATCHED,
    ADJUDICATED_PARTIAL,
    SETTLED;

    public boolean canTransitionTo(ClaimStatus target) {
        return switch (this) {
            case DRAFT -> target == PRE_VALIDATED || target == REJECTED_SCRUBBER;
            case PRE_VALIDATED -> target == DISPATCHED;
            case DISPATCHED -> target == SETTLED || target == ADJUDICATED_PARTIAL;
            case ADJUDICATED_PARTIAL -> target == SETTLED;
            case REJECTED_SCRUBBER, SETTLED -> false;
        };
    }
}
