package com.openclaim.engine.web;

import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.fhir.ClaimResponseFactory;
import com.openclaim.engine.fhir.ClaimSubmissionMapper;
import com.openclaim.engine.fhir.DispatchBundles;
import com.openclaim.engine.fhir.FhirMediaTypes;
import com.openclaim.engine.ingest.ClaimIngestionService;
import com.openclaim.engine.ingest.SubmissionResult;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Claim;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * FHIR {@code Claim/$submit}: the body is a collection Bundle whose first entry is the Claim. Responds 200 with a
 * ClaimResponse ({@code queued} or {@code error}), including for an identical re-submission.
 */
@RestController
public class ClaimSubmitController {

    private final ClaimSubmissionMapper mapper;
    private final ClaimIngestionService ingestion;
    private final ClaimResponseFactory responses;
    private final DispatchBundles dispatchBundles;

    public ClaimSubmitController(
            ClaimSubmissionMapper mapper,
            ClaimIngestionService ingestion,
            ClaimResponseFactory responses,
            DispatchBundles dispatchBundles) {
        this.mapper = mapper;
        this.ingestion = ingestion;
        this.responses = responses;
        this.dispatchBundles = dispatchBundles;
    }

    @PostMapping(
            path = "/fhir/Claim/$submit",
            consumes = {FhirMediaTypes.FHIR_JSON_VALUE, MediaType.APPLICATION_JSON_VALUE},
            produces = FhirMediaTypes.FHIR_JSON_VALUE)
    public ClaimResponse submit(@RequestBody Bundle bundle) {
        ClaimSubmission submission = mapper.map(bundle);
        SubmissionResult result = ingestion.submit(submission, claimId -> dispatchBundles.render(bundle, claimId));
        return responses.forSubmission((Claim) bundle.getEntryFirstRep().getResource(), result.claim());
    }
}
