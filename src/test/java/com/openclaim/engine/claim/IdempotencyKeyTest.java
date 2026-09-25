package com.openclaim.engine.claim;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    private static Claim claim(SubmissionFixtures submission) {
        return Claim.draft(UUID.randomUUID(), submission.build(), Instant.EPOCH);
    }

    @Test
    void isAHexSha256() {
        assertThat(IdempotencyKey.forDispatch(claim(aSubmission()))).matches("[0-9a-f]{64}");
    }

    @Test
    void sameBusinessFieldsGiveTheSameKeyRegardlessOfInternalId() {
        SubmissionFixtures submission = aSubmission().identifier("sys", "CLM-1");

        assertThat(IdempotencyKey.forDispatch(claim(submission)))
                .isEqualTo(IdempotencyKey.forDispatch(claim(submission)));
    }

    @Test
    void sameIdentifierValueFromDifferentSystemsDoesNotCollide() {
        Claim fromEhrA = claim(aSubmission().identifier("https://ehr-a.example.org", "CLM-1"));
        Claim fromEhrB = claim(aSubmission().identifier("https://ehr-b.example.org", "CLM-1"));

        assertThat(IdempotencyKey.forDispatch(fromEhrA)).isNotEqualTo(IdempotencyKey.forDispatch(fromEhrB));
    }

    @Test
    void differentTotalGivesDifferentKey() {
        SubmissionFixtures a = aSubmission().identifier("sys", "CLM-1").onlyItem("PROC-SPIRO", 100);
        SubmissionFixtures b = aSubmission().identifier("sys", "CLM-1").onlyItem("PROC-SPIRO", 101);

        assertThat(IdempotencyKey.forDispatch(claim(a))).isNotEqualTo(IdempotencyKey.forDispatch(claim(b)));
    }

    @Test
    void requiresTheFieldsTheScrubberGuarantees() {
        assertThatThrownBy(() -> IdempotencyKey.forDispatch(claim(aSubmission().payerCode(null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IdempotencyKey.forDispatch(claim(aSubmission().patientMrn(null))))
                .isInstanceOf(IllegalStateException.class);
    }
}
