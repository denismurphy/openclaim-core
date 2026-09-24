package com.openclaim.engine.fhir;

import org.hl7.fhir.r5.model.OperationOutcome.IssueType;

/**
 * A parsed FHIR resource cannot be turned into the canonical model: a required element is missing, a reference does
 * not resolve, or a value is outside what this service supports. Rendered as HTTP 422 with an OperationOutcome.
 *
 * @see OperationOutcomes
 */
public class FhirMappingException extends RuntimeException {

    private final IssueType issueType;
    private final String expression;

    public FhirMappingException(IssueType issueType, String expression, String message) {
        super(message);
        this.issueType = issueType;
        this.expression = expression;
    }

    public IssueType issueType() {
        return issueType;
    }

    /** FHIRPath of the offending element. */
    public String expression() {
        return expression;
    }
}
