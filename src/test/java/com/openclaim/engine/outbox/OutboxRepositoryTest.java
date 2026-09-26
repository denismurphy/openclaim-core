package com.openclaim.engine.outbox;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.claim.ScrubViolation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Spec §1: a claim must reach PRE_VALIDATED before a dispatch can be scheduled. */
class OutboxRepositoryTest {

    private final JdbcClient jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
    private final OutboxRepository outbox = new OutboxRepository(jdbc);

    private static Claim draft() {
        return Claim.draft(UUID.randomUUID(), aSubmission().build(), Instant.parse("2026-09-01T09:00:00Z"));
    }

    @Test
    void queuesAPreValidatedClaim() {
        Claim claim = draft();
        claim.transitionTo(ClaimStatus.PRE_VALIDATED);

        outbox.enqueueDispatch(claim, "{}");

        verify(jdbc).sql(contains("INSERT INTO outbox_message"));
    }

    @Test
    void refusesADraft() {
        assertRefused(draft(), ClaimStatus.DRAFT);
    }

    @Test
    void refusesAClaimTheScrubberRejected() {
        Claim claim = draft();
        claim.reject(
                List.of(new ScrubViolation("AdministrativeRule", "payer-missing", "No payer.", "Coverage.insurer")));

        assertRefused(claim, ClaimStatus.REJECTED_SCRUBBER);
    }

    @Test
    void refusesAClaimAlreadyDispatched() {
        Claim claim = draft();
        claim.transitionTo(ClaimStatus.PRE_VALIDATED);
        claim.transitionTo(ClaimStatus.DISPATCHED);

        assertRefused(claim, ClaimStatus.DISPATCHED);
    }

    private void assertRefused(Claim claim, ClaimStatus status) {
        assertThatThrownBy(() -> outbox.enqueueDispatch(claim, "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(claim.id().toString())
                .hasMessageContaining(status.name());
        verifyNoInteractions(jdbc);
    }
}
