package com.openclaim.engine.ingest;

import java.util.UUID;

/** Renders the body sent to the payer for a claim, once its internal id is known. Keeps FHIR out of this package. */
@FunctionalInterface
public interface PayerPayload {

    String render(UUID claimId);
}
