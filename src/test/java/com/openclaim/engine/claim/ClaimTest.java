package com.openclaim.engine.claim;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimTest {

    private static final ScrubViolation VIOLATION =
            new ScrubViolation("AdministrativeRule", "missing-mrn", "Missing patient identifier", null);

    @Test
    void draftCopiesSubmission() {
        ClaimSubmission submission = aSubmission().build();

        Claim claim = Claim.draft(UUID.randomUUID(), submission);

        assertThat(claim.status()).isEqualTo(ClaimStatus.DRAFT);
        assertThat(claim.identifier()).isEqualTo(submission.identifier());
        assertThat(claim.submissionHash()).isEqualTo(submission.fingerprint());
        assertThat(claim.totalBilledMinor()).isEqualTo(12_500);
        assertThat(claim.lines())
                .containsExactly(new ClaimLine(1, SubmissionFixtures.PROCEDURE_SYSTEM, "PROC-SPIRO", 12_500));
    }

    @Test
    void followsTheHappyPath() {
        Claim claim = Claim.draft(UUID.randomUUID(), aSubmission().build());

        claim.transitionTo(ClaimStatus.PRE_VALIDATED);
        claim.transitionTo(ClaimStatus.DISPATCHED);
        claim.transitionTo(ClaimStatus.ADJUDICATED_PARTIAL);
        claim.transitionTo(ClaimStatus.SETTLED);

        assertThat(claim.status()).isEqualTo(ClaimStatus.SETTLED);
    }

    @Test
    void refusesToSkipTheScrubber() {
        Claim claim = Claim.draft(UUID.randomUUID(), aSubmission().build());

        assertThatThrownBy(() -> claim.transitionTo(ClaimStatus.DISPATCHED))
                .isInstanceOfSatisfying(InvalidStateTransitionException.class, e -> {
                    assertThat(e.from()).isEqualTo(ClaimStatus.DRAFT);
                    assertThat(e.to()).isEqualTo(ClaimStatus.DISPATCHED);
                });
        assertThat(claim.status()).isEqualTo(ClaimStatus.DRAFT);
    }

    @Test
    void rejectionRecordsViolations() {
        Claim claim = Claim.draft(UUID.randomUUID(), aSubmission().build());

        claim.reject(List.of(VIOLATION));

        assertThat(claim.status()).isEqualTo(ClaimStatus.REJECTED_SCRUBBER);
        assertThat(claim.violations()).containsExactly(VIOLATION);
    }

    @Test
    void rejectionRequiresAtLeastOneViolation() {
        Claim claim = Claim.draft(UUID.randomUUID(), aSubmission().build());

        assertThatThrownBy(() -> claim.reject(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThat(claim.status()).isEqualTo(ClaimStatus.DRAFT);
    }

    @Test
    void cannotRejectAClaimThatAlreadyPassedTheScrubber() {
        Claim claim = Claim.draft(UUID.randomUUID(), aSubmission().build());
        claim.transitionTo(ClaimStatus.PRE_VALIDATED);

        assertThatThrownBy(() -> claim.reject(List.of(VIOLATION))).isInstanceOf(InvalidStateTransitionException.class);
        assertThat(claim.violations()).isEmpty();
    }
}
