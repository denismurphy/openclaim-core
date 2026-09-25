package com.openclaim.engine.fhir;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.claim.ScrubViolation;
import com.openclaim.engine.config.OpenClaimProperties;
import java.util.Date;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.hl7.fhir.r5.model.CodeableConcept;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Identifier;
import org.hl7.fhir.r5.model.Reference;
import org.springframework.stereotype.Component;

/**
 * The {@code Claim/$submit} response. Built only from the submitted Claim and the stored claim, so an identical
 * re-submission receives an identical response.
 */
@Component
public class ClaimResponseFactory {

    public static final String VIOLATION_SYSTEM = "https://openclaim.example.org/CodeSystem/scrubber-violation";

    private final String mrnSystem;

    public ClaimResponseFactory(OpenClaimProperties properties) {
        this.mrnSystem = properties.fhir().mrnSystem();
    }

    public ClaimResponse forSubmission(org.hl7.fhir.r5.model.Claim submitted, Claim claim) {
        ClaimResponse response = new ClaimResponse();
        response.setId(claim.id().toString());
        response.setStatus(Enumerations.FinancialResourceStatusCodes.ACTIVE);
        response.setType(submitted.getType());
        response.setUse(Enumerations.Use.CLAIM);
        response.setCreated(Date.from(claim.createdAt()));
        response.setRequest(new Reference("Claim/" + claim.id())
                .setIdentifier(new Identifier()
                        .setSystem(claim.identifier().system())
                        .setValue(claim.identifier().value())));
        if (claim.patientMrn() != null) {
            response.setPatient(new Reference()
                    .setIdentifier(new Identifier().setSystem(mrnSystem).setValue(claim.patientMrn())));
        }
        if (claim.status() == ClaimStatus.REJECTED_SCRUBBER) {
            response.setOutcome(Enumerations.ClaimProcessingCodes.ERROR);
            response.setDisposition("Rejected by pre-submission scrubbing; see error for each violation");
            for (ScrubViolation violation : claim.violations()) {
                ClaimResponse.ErrorComponent error = response.addError();
                error.setCode(new CodeableConcept()
                        .addCoding(new org.hl7.fhir.r5.model.Coding(VIOLATION_SYSTEM, violation.code(), null))
                        .setText(violation.message()));
                if (violation.expression() != null) {
                    error.addExpression(violation.expression());
                }
            }
        } else {
            response.setOutcome(Enumerations.ClaimProcessingCodes.QUEUED);
            response.setDisposition("Accepted and queued for submission to the payer");
        }
        return response;
    }
}
