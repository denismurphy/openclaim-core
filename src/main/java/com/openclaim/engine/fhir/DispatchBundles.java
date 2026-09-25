package com.openclaim.engine.fhir;

import ca.uhn.fhir.context.FhirContext;
import java.util.UUID;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Claim;
import org.springframework.stereotype.Component;

/**
 * The body posted to the payer: a snapshot of the submitted collection Bundle, taken at ingest time, with the Claim's
 * logical id set to ours so the payer's ClaimResponse can reference {@code Claim/<id>}.
 */
@Component
public class DispatchBundles {

    private final FhirContext fhirContext;

    public DispatchBundles(FhirContext fhirContext) {
        this.fhirContext = fhirContext;
    }

    public String render(Bundle submitted, UUID claimId) {
        Bundle snapshot = submitted.copy();
        Claim claim = (Claim) snapshot.getEntryFirstRep().getResource();
        claim.setId(claimId.toString());
        return fhirContext.newJsonParser().encodeResourceToString(snapshot);
    }
}
