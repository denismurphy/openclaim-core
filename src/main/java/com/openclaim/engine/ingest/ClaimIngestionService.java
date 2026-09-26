package com.openclaim.engine.ingest;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.claim.ClaimSubmission;
import com.openclaim.engine.ledger.Journal;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.outbox.OutboxRepository;
import com.openclaim.engine.rules.RulesPipeline;
import com.openclaim.engine.rules.ValidationSummary;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Spec §5.2 {@code TransactionalClaimIngest}: scrubs a submission and, in one local transaction, stores the claim
 * with its outcome, and for an accepted claim also the CHARGE journal and the outbox message. Nothing is sent to
 * the payer here, so there is no dual write.
 *
 * <p>Re-transmissions are idempotent: a second submission with the same {@code Claim.identifier} and content
 * returns the stored claim; different content under that identifier is a conflict.
 */
@Service
public class ClaimIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ClaimIngestionService.class);

    private final RulesPipeline rules;
    private final ClaimRepository claims;
    private final Ledger ledger;
    private final OutboxRepository outbox;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ClaimIngestionService(
            RulesPipeline rules,
            ClaimRepository claims,
            Ledger ledger,
            OutboxRepository outbox,
            TransactionOperations transactions,
            Clock clock) {
        this.rules = rules;
        this.claims = claims;
        this.ledger = ledger;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public SubmissionResult submit(ClaimSubmission submission, PayerPayload payload) {
        try {
            Claim claim = transactions.execute(status -> ingest(submission, payload));
            log.info("Claim {} ingested with status {}", claim.id(), claim.status());
            return new SubmissionResult(claim, false);
        } catch (DuplicateKeyException e) {
            // Postgres aborts the transaction on a unique violation, so the replay lookup runs in a new one. A
            // concurrent identical submission waits on the unique index until the first commits, then lands here.
            return replay(submission, e);
        }
    }

    private Claim ingest(ClaimSubmission submission, PayerPayload payload) {
        Claim claim = Claim.draft(UUID.randomUUID(), submission, clock.instant());
        ValidationSummary summary = rules.evaluate(submission);
        if (summary.hasViolations()) {
            claim.reject(summary.violations());
            return claims.save(claim);
        }
        claim.transitionTo(ClaimStatus.PRE_VALIDATED);
        claims.save(claim);
        ledger.post(Journal.charge(claim.id(), claim.totalBilledMinor(), claim.currency(), claim.serviceStart()));
        outbox.enqueueDispatch(claim, payload.render(claim.id()));
        return claim;
    }

    private SubmissionResult replay(ClaimSubmission submission, DuplicateKeyException cause) {
        ClaimIdentifier identifier = submission.identifier();
        Claim existing = claims.findByIdentifierSystemAndIdentifierValue(identifier.system(), identifier.value())
                .orElseThrow(() -> cause);
        if (!existing.submissionHash().equals(submission.fingerprint())) {
            throw new ClaimConflictException(identifier);
        }
        log.info("Claim {} re-submitted; returning stored outcome {}", existing.id(), existing.status());
        return new SubmissionResult(existing, true);
    }
}
