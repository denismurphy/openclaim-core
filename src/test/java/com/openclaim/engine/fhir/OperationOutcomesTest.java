package com.openclaim.engine.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import org.hl7.fhir.r5.model.OperationOutcome;
import org.hl7.fhir.r5.model.OperationOutcome.IssueSeverity;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.junit.jupiter.api.Test;

class OperationOutcomesTest {

    @Test
    void carriesTheMappingProblem() {
        OperationOutcome outcome = OperationOutcomes.from(
                new FhirMappingException(IssueType.VALUE, "Claim.total", "Claim.total does not match"));

        OperationOutcome.OperationOutcomeIssueComponent issue = outcome.getIssueFirstRep();
        assertThat(outcome.getIssue()).hasSize(1);
        assertThat(issue.getSeverity()).isEqualTo(IssueSeverity.ERROR);
        assertThat(issue.getCode()).isEqualTo(IssueType.VALUE);
        assertThat(issue.getDiagnostics()).isEqualTo("Claim.total does not match");
        assertThat(issue.getExpression()).extracting(Object::toString).containsExactly("Claim.total");
    }

    @Test
    void expressionIsOptional() {
        assertThat(OperationOutcomes.error(IssueType.SECURITY, "Forbidden")
                        .getIssueFirstRep()
                        .getExpression())
                .isEmpty();
    }
}
