package com.openclaim.engine.claim;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openclaim.engine.TestcontainersConfiguration;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(TestcontainersConfiguration.class)
class ClaimRepositoryIT {

    private static final Instant CREATED = Instant.parse("2026-09-01T12:00:00Z");

    @Autowired
    ClaimRepository claims;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Claim newClaim() {
        return Claim.draft(
                UUID.randomUUID(), aSubmission().item("PROC-CONSULT", 5_000).build(), CREATED);
    }

    @Test
    void roundTripsTheWholeAggregate() {
        Claim claim = newClaim();
        claim.reject(List.of(new ScrubViolation("AdministrativeRule", "payer-unknown", "Unknown payer", null)));

        claims.save(claim);
        Claim loaded = claims.findById(claim.id()).orElseThrow();

        assertThat(loaded.status()).isEqualTo(ClaimStatus.REJECTED_SCRUBBER);
        assertThat(loaded.identifier()).isEqualTo(claim.identifier());
        assertThat(loaded.submissionHash()).isEqualTo(claim.submissionHash());
        assertThat(loaded.totalBilledMinor()).isEqualTo(17_500);
        assertThat(loaded.serviceStart()).isEqualTo(claim.serviceStart());
        assertThat(loaded.createdAt()).isEqualTo(CREATED.truncatedTo(ChronoUnit.MICROS));
        assertThat(loaded.lines()).containsExactlyElementsOf(claim.lines());
        assertThat(loaded.diagnoses()).containsExactlyElementsOf(claim.diagnoses());
        assertThat(loaded.violations()).containsExactlyElementsOf(claim.violations());
    }

    @Test
    void lockedLoadReturnsTheCompleteAggregate() {
        Claim claim = claims.save(newClaim());

        Claim locked = new TransactionTemplate(transactionManager)
                .execute(status -> claims.findLockedById(claim.id()).orElseThrow());

        assertThat(locked.lines()).hasSize(2);
        assertThat(locked.diagnoses()).hasSize(1);
    }

    @Test
    void lockedLoadBlocksAConcurrentLocker() throws Exception {
        Claim claim = claims.save(newClaim());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            claims.findLockedById(claim.id()).orElseThrow();
            locked.countDown();
            await(release);
        }));
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                        jdbc.sql("SET LOCAL lock_timeout = '200ms'").update();
                        claims.findLockedById(claim.id());
                    }))
                    .rootCause()
                    .isInstanceOfSatisfying(
                            SQLException.class,
                            e -> assertThat(e.getSQLState())
                                    .as("lock_not_available")
                                    .isEqualTo("55P03"));
        } finally {
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void staleWritesAreRejected() {
        Claim claim = claims.save(newClaim());
        Claim first = claims.findById(claim.id()).orElseThrow();
        Claim second = claims.findById(claim.id()).orElseThrow();

        first.transitionTo(ClaimStatus.PRE_VALIDATED);
        claims.save(first);
        second.reject(List.of(new ScrubViolation("r", "c", "m", null)));

        assertThatThrownBy(() -> claims.save(second)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(claims.findById(claim.id()).orElseThrow().status()).isEqualTo(ClaimStatus.PRE_VALIDATED);
    }

    @Test
    void identifierIsUniquePerSystem() {
        Claim original = claims.save(newClaim());
        Claim sameIdentifier = Claim.draft(
                UUID.randomUUID(),
                aSubmission()
                        .identifier(
                                original.identifier().system(),
                                original.identifier().value())
                        .build(),
                CREATED);

        assertThatThrownBy(() -> claims.save(sameIdentifier)).isInstanceOf(DuplicateKeyException.class);
        assertThat(claims.findByIdentifierSystemAndIdentifierValue(
                        original.identifier().system(), original.identifier().value()))
                .map(Claim::id)
                .contains(original.id());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
