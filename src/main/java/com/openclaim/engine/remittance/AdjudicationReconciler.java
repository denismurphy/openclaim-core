package com.openclaim.engine.remittance;

import static com.openclaim.engine.remittance.RemittanceRejectedException.Reason.CONFLICT;
import static com.openclaim.engine.remittance.RemittanceRejectedException.Reason.UNKNOWN_CLAIM;
import static com.openclaim.engine.remittance.RemittanceRejectedException.Reason.UNRECONCILED;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.ledger.Balances;
import com.openclaim.engine.ledger.Journal;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import com.openclaim.engine.outbox.OutboxRepository;
import com.openclaim.engine.outbox.OutboxStatus;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Applies a payer's adjudication to a claim (spec §6 steps 9-11) in one transaction holding the claim row lock:
 * records the remittance, posts the WRITE_OFF, PAYER_REMITTANCE and PATIENT_LIABILITY journals, and moves the claim
 * to SETTLED (nothing left for the patient) or ADJUDICATED_PARTIAL.
 *
 * <p>A remittance can arrive before the relay has recorded its delivery. It is then itself proof of delivery, but
 * only if a delivery attempt has actually started; otherwise the payer cannot have the claim.
 */
@Service
public class AdjudicationReconciler {

    private static final Logger log = LoggerFactory.getLogger(AdjudicationReconciler.class);

    private final ClaimRepository claims;
    private final OutboxRepository outbox;
    private final Ledger ledger;
    private final RemittanceRepository remittances;
    private final TransactionOperations transactions;

    public AdjudicationReconciler(
            ClaimRepository claims,
            OutboxRepository outbox,
            Ledger ledger,
            RemittanceRepository remittances,
            TransactionOperations transactions) {
        this.claims = claims;
        this.outbox = outbox;
        this.ledger = ledger;
        this.remittances = remittances;
        this.transactions = transactions;
    }

    /**
     * @param payerCode the authenticated payer; it may only adjudicate its own claims
     * @param render renders the stored ClaimResponse given the remittance id assigned to it
     */
    public RemittanceResult apply(Adjudication adjudication, String payerCode, Function<UUID, String> render) {
        try {
            return transactions.execute(status -> applyLocked(adjudication, payerCode, render));
        } catch (DuplicateKeyException e) {
            return replay(adjudication, payerCode, e);
        }
    }

    private RemittanceResult applyLocked(Adjudication adjudication, String payerCode, Function<UUID, String> render) {
        Claim claim = resolveClaimId(adjudication)
                .flatMap(claims::findLockedById)
                .filter(c -> payerCode.equals(c.payerCode()))
                .orElseThrow(() -> new RemittanceRejectedException(
                        UNKNOWN_CLAIM, "The ClaimResponse does not reference a claim submitted to this payer"));

        // Checked before the claim's state: an applied remittance has already moved the claim on, so a replay would
        // otherwise be mistaken for a conflict. The claim lock serialises this check with the insert below.
        Optional<StoredRemittance> existing = remittances.findByIdentifier(adjudication.identifier());
        if (existing.isPresent()) {
            return replayOf(existing.get(), claim, adjudication);
        }

        if (claim.status() == ClaimStatus.PRE_VALIDATED) {
            acceptAsProofOfDelivery(claim);
        } else if (claim.status() != ClaimStatus.DISPATCHED) {
            throw new RemittanceRejectedException(
                    CONFLICT, "Claim %s is %s and cannot be adjudicated".formatted(claim.id(), claim.status()));
        }
        if (adjudication.submittedMinor() != claim.totalBilledMinor()) {
            throw new RemittanceRejectedException(
                    UNRECONCILED,
                    "Submitted total %d does not equal the billed total %d"
                            .formatted(adjudication.submittedMinor(), claim.totalBilledMinor()));
        }

        UUID remittanceId = UUID.randomUUID();
        String resource = render.apply(remittanceId);
        remittances.insert(remittanceId, claim.id(), adjudication, resource);
        String reference = adjudication.identifier().system() + "|"
                + adjudication.identifier().value();
        if (adjudication.writeOffMinor() > 0) {
            ledger.post(Journal.writeOff(
                    claim.id(), reference, adjudication.writeOffMinor(), claim.currency(), claim.serviceStart()));
        }
        if (adjudication.benefitMinor() > 0) {
            ledger.post(Journal.payerRemittance(
                    claim.id(), reference, adjudication.benefitMinor(), claim.currency(), claim.serviceStart()));
        }
        if (adjudication.patientLiabilityMinor() > 0) {
            ledger.post(Journal.patientLiability(
                    claim.id(),
                    reference,
                    adjudication.patientLiabilityMinor(),
                    claim.currency(),
                    claim.serviceStart()));
        }

        Balances balances = ledger.balances(claim.id());
        if (balances.of(LedgerAccount.PAYER_AR) != 0) {
            throw new IllegalStateException("Claim %s payer receivable is %d after adjudication, expected 0"
                    .formatted(claim.id(), balances.of(LedgerAccount.PAYER_AR)));
        }
        claim.transitionTo(
                balances.of(LedgerAccount.PATIENT_AR) == 0 ? ClaimStatus.SETTLED : ClaimStatus.ADJUDICATED_PARTIAL);
        claims.save(claim);
        log.info("Claim {} adjudicated; now {}", claim.id(), claim.status());
        return new RemittanceResult(
                new StoredRemittance(remittanceId, claim.id(), adjudication.fingerprint(), resource),
                claim.status(),
                false);
    }

    private void acceptAsProofOfDelivery(Claim claim) {
        OutboxRepository.DispatchState dispatch = outbox.lockDispatchState(claim.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Pre-validated claim %s has no outbox message".formatted(claim.id())));
        boolean deliveryStarted = dispatch.status() == OutboxStatus.IN_FLIGHT || dispatch.attempts() > 0;
        if (!deliveryStarted) {
            throw new RemittanceRejectedException(
                    CONFLICT, "Claim %s has not been sent to the payer yet".formatted(claim.id()));
        }
        outbox.markDeliveredByRemittance(claim.id());
        claim.transitionTo(ClaimStatus.DISPATCHED);
    }

    private Optional<UUID> resolveClaimId(Adjudication adjudication) {
        if (adjudication.claimId() != null) {
            return Optional.of(adjudication.claimId());
        }
        ClaimIdentifier identifier = adjudication.claimIdentifier();
        return claims.findByIdentifierSystemAndIdentifierValue(identifier.system(), identifier.value())
                .map(Claim::id);
    }

    /** A concurrent insert won the race on a unique constraint; decide replay or conflict from what it stored. */
    private RemittanceResult replay(Adjudication adjudication, String payerCode, DuplicateKeyException cause) {
        StoredRemittance stored = remittances
                .findByIdentifier(adjudication.identifier())
                .orElseThrow(() ->
                        new RemittanceRejectedException(CONFLICT, "The claim already has a different adjudication"));
        Claim claim = claims.findById(stored.claimId()).orElseThrow(() -> cause);
        if (!payerCode.equals(claim.payerCode())) {
            throw new RemittanceRejectedException(
                    UNKNOWN_CLAIM, "The ClaimResponse does not reference a claim submitted to this payer");
        }
        return replayOf(stored, claim, adjudication);
    }

    private static RemittanceResult replayOf(StoredRemittance stored, Claim claim, Adjudication adjudication) {
        if (!stored.claimId().equals(claim.id()) || !stored.contentHash().equals(adjudication.fingerprint())) {
            throw new RemittanceRejectedException(
                    CONFLICT, "A different ClaimResponse was already received with this identifier");
        }
        return new RemittanceResult(stored, claim.status(), true);
    }
}
