package com.openclaim.engine.fhir;

import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.jspecify.annotations.Nullable;

/** Builds single-issue {@link OperationOutcome}s for error responses on FHIR endpoints. */
public final class OperationOutcomes {

    private OperationOutcomes() {}

    public static OperationOutcome error(IssueType type, String diagnostics) {
        return error(type, diagnostics, null);
    }

    public static OperationOutcome error(IssueType type, String diagnostics, @Nullable String expression) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.addIssue()
                .setSeverity(IssueSeverity.ERROR)
                .setCode(type)
                .setDiagnostics(diagnostics);
        if (expression != null) {
            issue.addExpression(expression);
        }
        return outcome;
    }

    public static OperationOutcome from(FhirMappingException e) {
        return error(
                e.issueType(), e.getMessage() == null ? e.issueType().getDisplay() : e.getMessage(), e.expression());
    }
}
