package com.openclaim.engine.remittance;

import static com.openclaim.engine.remittance.PaymentRejectedException.Reason.CONFLICT;
import static com.openclaim.engine.remittance.PaymentRejectedException.Reason.OVERPAYMENT;
import static com.openclaim.engine.remittance.PaymentRejectedException.Reason.UNKNOWN_CLAIM;

import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import com.openclaim.engine.claim.ClaimStatus;
import com.openclaim.engine.ledger.Journal;
import com.openclaim.engine.ledger.Ledger;
import com.openclaim.engine.ledger.LedgerAccount;
import com.openclaim.engine.ledger.LedgerEntryType;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Books a patient's payment towards an {@code ADJUDICATED_PARTIAL} claim and settles the claim once nothing is
 * outstanding. Runs under the claim row lock, so concurrent payments cannot jointly overpay.
 */
@Service
public class PatientPaymentService {

    private static final Logger log = LoggerFactory.getLogger(PatientPaymentService.class);

    private final ClaimRepository claims;
    private final Ledger ledger;
    private final TransactionOperations transactions;

    public PatientPaymentService(ClaimRepository claims, Ledger ledger, TransactionOperations transactions) {
        this.claims = claims;
        this.ledger = ledger;
        this.transactions = transactions;
    }

    /** @param idempotencyKey client-chosen key; repeating it with the same amount replays the booking */
    public PaymentResult pay(UUID claimId, String idempotencyKey, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("A payment must be a positive amount");
        }
        return transactions.execute(status -> {
            Claim claim = claims.findLockedById(claimId)
                    .orElseThrow(() -> new PaymentRejectedException(UNKNOWN_CLAIM, "No claim " + claimId));

            // Before the state check: the payment that settled a claim must still replay once the claim is SETTLED.
            Optional<Long> booked = ledger.bookedAmount(claimId, LedgerEntryType.PATIENT_PAYMENT, idempotencyKey);
            if (booked.isPresent()) {
                if (booked.get() != amountMinor) {
                    throw new PaymentRejectedException(
                            CONFLICT, "This Idempotency-Key was already used for a different amount");
                }
                return new PaymentResult(claim.status(), outstanding(claimId), true);
            }
            if (claim.status() != ClaimStatus.ADJUDICATED_PARTIAL) {
                throw new PaymentRejectedException(
                        CONFLICT, "Claim %s is %s and has no patient balance".formatted(claimId, claim.status()));
            }
            long outstanding = outstanding(claimId);
            if (amountMinor > outstanding) {
                throw new PaymentRejectedException(
                        OVERPAYMENT,
                        "Payment of %d exceeds the outstanding patient balance of %d"
                                .formatted(amountMinor, outstanding));
            }
            ledger.post(Journal.patientPayment(
                    claimId, idempotencyKey, amountMinor, claim.currency(), claim.serviceStart()));
            long remaining = outstanding - amountMinor;
            if (remaining == 0) {
                claim.transitionTo(ClaimStatus.SETTLED);
            }
            // Saved even when the status is unchanged: bumping the version keeps @Version a backstop to the row lock.
            claims.save(claim);
            log.info("Patient payment booked on claim {}; now {}", claimId, claim.status());
            return new PaymentResult(claim.status(), remaining, false);
        });
    }

    private long outstanding(UUID claimId) {
        return ledger.balances(claimId).of(LedgerAccount.PATIENT_AR);
    }
}
