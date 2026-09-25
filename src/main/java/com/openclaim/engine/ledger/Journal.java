package com.openclaim.engine.ledger;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One balanced financial event on a claim. Instances can only be built balanced; the database re-checks at commit.
 *
 * <p>Transaction time is not a property: the database stamps it on insert, so the application cannot supply it.
 *
 * @param externalReference what makes the event unique per claim and type, e.g. a ClaimResponse identifier or a
 *     payment's Idempotency-Key; replaying it is rejected by a unique constraint
 * @param validTime when the care was delivered (the encounter start)
 */
@Table("ledger_journal")
public record Journal(
        @Id UUID id,
        UUID claimId,
        LedgerEntryType entryType,
        String externalReference,
        Instant validTime,

        @MappedCollection(idColumn = "journal_id", keyColumn = "position")
        List<Posting> postings) {

    @PersistenceCreator
    public Journal {
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A journal needs at least two postings");
        }
        long sum = 0;
        for (Posting posting : postings) {
            if (posting.amountMinor() == 0) {
                throw new IllegalArgumentException("Postings must move a non-zero amount");
            }
            if (!posting.currency().equals(postings.getFirst().currency())) {
                throw new IllegalArgumentException("All postings of a journal must share one currency");
            }
            sum = Math.addExact(sum, posting.amountMinor());
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Journal postings must sum to zero; got " + sum);
        }
    }

    /** Claim accepted for billing: the payer owes the billed amount. */
    public static Journal charge(UUID claimId, long billedMinor, Currency currency, Instant validTime) {
        return transfer(
                claimId,
                LedgerEntryType.CHARGE,
                claimId.toString(),
                validTime,
                LedgerAccount.PAYER_AR,
                LedgerAccount.REVENUE,
                billedMinor,
                currency);
    }

    /** Payer paid its share. */
    public static Journal payerRemittance(
            UUID claimId, String reference, long paidMinor, Currency currency, Instant validTime) {
        return transfer(
                claimId,
                LedgerEntryType.PAYER_REMITTANCE,
                reference,
                validTime,
                LedgerAccount.CASH,
                LedgerAccount.PAYER_AR,
                paidMinor,
                currency);
    }

    /** Contractual reduction: billed minus eligible, never collected from anyone. */
    public static Journal writeOff(
            UUID claimId, String reference, long writeOffMinor, Currency currency, Instant validTime) {
        return transfer(
                claimId,
                LedgerEntryType.WRITE_OFF,
                reference,
                validTime,
                LedgerAccount.CONTRACTUAL_ADJ,
                LedgerAccount.PAYER_AR,
                writeOffMinor,
                currency);
    }

    /** Patient's share (co-pay, deductible) moves from the payer's receivable to the patient's. */
    public static Journal patientLiability(
            UUID claimId, String reference, long liabilityMinor, Currency currency, Instant validTime) {
        return transfer(
                claimId,
                LedgerEntryType.PATIENT_LIABILITY,
                reference,
                validTime,
                LedgerAccount.PATIENT_AR,
                LedgerAccount.PAYER_AR,
                liabilityMinor,
                currency);
    }

    /** Patient paid towards their balance. */
    public static Journal patientPayment(
            UUID claimId, String reference, long paidMinor, Currency currency, Instant validTime) {
        return transfer(
                claimId,
                LedgerEntryType.PATIENT_PAYMENT,
                reference,
                validTime,
                LedgerAccount.CASH,
                LedgerAccount.PATIENT_AR,
                paidMinor,
                currency);
    }

    private static Journal transfer(
            UUID claimId,
            LedgerEntryType type,
            String reference,
            Instant validTime,
            LedgerAccount debit,
            LedgerAccount credit,
            long amountMinor,
            Currency currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("%s amount must be positive; got %d".formatted(type, amountMinor));
        }
        String code = currency.getCurrencyCode();
        return new Journal(
                UUID.randomUUID(),
                claimId,
                type,
                reference,
                validTime,
                List.of(new Posting(debit, amountMinor, code), new Posting(credit, -amountMinor, code)));
    }
}
