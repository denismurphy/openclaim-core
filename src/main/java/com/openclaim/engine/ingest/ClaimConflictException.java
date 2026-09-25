package com.openclaim.engine.ingest;

import com.openclaim.engine.claim.ClaimIdentifier;

/** A different claim was already submitted under the same {@code Claim.identifier}. Rendered as HTTP 409. */
public class ClaimConflictException extends RuntimeException {

    public ClaimConflictException(ClaimIdentifier identifier) {
        super("A different claim was already submitted with identifier %s|%s; corrected claims need a new identifier"
                .formatted(identifier.system(), identifier.value()));
    }
}
