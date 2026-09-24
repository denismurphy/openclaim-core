package com.openclaim.engine.claim;

import java.util.UUID;

public class InvalidStateTransitionException extends RuntimeException {

    private final ClaimStatus from;
    private final ClaimStatus to;

    public InvalidStateTransitionException(UUID claimId, ClaimStatus from, ClaimStatus to) {
        super("Claim %s cannot move from %s to %s".formatted(claimId, from, to));
        this.from = from;
        this.to = to;
    }

    public ClaimStatus from() {
        return from;
    }

    public ClaimStatus to() {
        return to;
    }
}
