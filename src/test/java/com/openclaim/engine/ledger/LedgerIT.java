package com.openclaim.engine.ledger;

import static com.openclaim.engine.claim.SubmissionFixtures.aSubmission;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.claim.Claim;
import com.openclaim.engine.claim.ClaimRepository;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Commits for real: the balance check is a deferred constraint trigger that only fires at commit, so a rolled-back
 * test transaction would never exercise it. Ledger rows cannot be deleted, so every test uses its own claim.
 */
@DataJdbcTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TestcontainersConfiguration.class, Ledger.class})
class LedgerIT {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant CARE = Instant.parse("2026-09-01T08:30:00Z");

    @Autowired
    Ledger ledger;

    @Autowired
    ClaimRepository claims;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID claimId;

    @BeforeEach
    void newClaim() {
        claimId = claims.save(Claim.draft(UUID.randomUUID(), aSubmission().build(), CARE))
                .id();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private UUID postCharge() {
        Journal charge = Journal.charge(claimId, 12_500, EUR, CARE);
        inTransaction(() -> ledger.post(charge));
        return charge.id();
    }

    @Test
    void balancesFollowTheFullLifecycle() {
        inTransaction(() -> {
            ledger.post(Journal.charge(claimId, 12_500, EUR, CARE));
            ledger.post(Journal.writeOff(claimId, "CR-1", 2_500, EUR, CARE));
            ledger.post(Journal.payerRemittance(claimId, "CR-1", 8_000, EUR, CARE));
            ledger.post(Journal.patientLiability(claimId, "CR-1", 2_000, EUR, CARE));
        });
        Balances adjudicated = ledger.balances(claimId);
        inTransaction(() -> ledger.post(Journal.patientPayment(claimId, "PAY-1", 2_000, EUR, CARE)));
        Balances settled = ledger.balances(claimId);

        assertThat(adjudicated.of(LedgerAccount.PAYER_AR)).isZero();
        assertThat(adjudicated.of(LedgerAccount.PATIENT_AR)).isEqualTo(2_000);
        assertThat(settled.of(LedgerAccount.PATIENT_AR)).isZero();
        assertThat(settled.of(LedgerAccount.CASH)).isEqualTo(10_000);
        assertThat(settled.of(LedgerAccount.REVENUE)).isEqualTo(-12_500);
        assertThat(settled.of(LedgerAccount.CONTRACTUAL_ADJ)).isEqualTo(2_500);
    }

    @Test
    void entriesExposeTheSpecsLedgerRecord() {
        postCharge();

        List<LedgerEntryView> entries = ledger.entries(claimId);

        assertThat(entries).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.entryType()).isEqualTo(LedgerEntryType.CHARGE);
            assertThat(entry.validTime()).isEqualTo(CARE);
            assertThat(entry.adjudicationReference()).isEqualTo(claimId.toString());
        });
        assertThat(entries).extracting(LedgerEntryView::amountMinor).containsExactly(12_500L, -12_500L);
    }

    @Test
    void databaseStampsTransactionTimeWhateverTheClientSends() {
        UUID journalId = UUID.randomUUID();
        inTransaction(() -> {
            jdbc.sql("""
                            INSERT INTO ledger_journal (id, claim_id, entry_type, external_reference, transaction_time, valid_time)
                            VALUES (:id, :claim, 'CHARGE', 'forged', '1999-01-01T00:00:00Z', :care)""")
                    .param("id", journalId)
                    .param("claim", claimId)
                    .param("care", java.sql.Timestamp.from(CARE))
                    .update();
            insertPosting(journalId, 0, "PAYER_AR", 100);
            insertPosting(journalId, 1, "REVENUE", -100);
        });

        Instant stamped = jdbc.sql("SELECT transaction_time FROM ledger_journal WHERE id = :id")
                .param("id", journalId)
                .query(java.time.OffsetDateTime.class)
                .single()
                .toInstant();
        assertThat(stamped).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void journalsCannotBeUpdated() {
        UUID journalId = postCharge();

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_journal SET external_reference = 'x' WHERE id = :id")
                        .param("id", journalId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void postingsCannotBeUpdated() {
        UUID journalId = postCharge();

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_posting SET amount_minor = 1 WHERE journal_id = :id")
                        .param("id", journalId)
                        .update())
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void journalsAndPostingsCannotBeDeleted() {
        UUID journalId = postCharge();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_posting WHERE journal_id = :id")
                        .param("id", journalId)
                        .update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger_journal WHERE id = :id")
                        .param("id", journalId)
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    void ledgerCannotBeTruncated() {
        postCharge();

        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_posting").update()).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger_journal CASCADE").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void unbalancedJournalIsRejectedAtCommit() {
        UUID journalId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
                    insertJournal(journalId, "unbalanced");
                    insertPosting(journalId, 0, "PAYER_AR", 100);
                    insertPosting(journalId, 1, "REVENUE", -99);
                }))
                .hasMessageContaining("unbalanced");
        assertThat(journalExists(journalId)).isFalse();
    }

    @Test
    void journalWithoutPostingsIsRejectedAtCommit() {
        UUID journalId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> insertJournal(journalId, "empty")))
                .hasMessageContaining("unbalanced");
        assertThat(journalExists(journalId)).isFalse();
    }

    @Test
    void mixedCurrencyJournalIsRejectedAtCommit() {
        UUID journalId = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
                    insertJournal(journalId, "mixed");
                    insertPosting(journalId, 0, "PAYER_AR", 100);
                    jdbc.sql("""
                                    INSERT INTO ledger_posting (journal_id, position, account, amount_minor, currency)
                                    VALUES (:id, 1, 'REVENUE', -100, 'USD')""").param("id", journalId).update();
                }))
                .hasMessageContaining("unbalanced");
    }

    @Test
    void postingsAddedLaterCannotUnbalanceACommittedJournal() {
        UUID journalId = postCharge();

        assertThatThrownBy(() -> inTransaction(() -> insertPosting(journalId, 2, "CASH", 1)))
                .hasMessageContaining("unbalanced");
    }

    @Test
    void theSameEventCannotBeBookedTwice() {
        inTransaction(() -> ledger.post(Journal.patientPayment(claimId, "PAY-1", 1, EUR, CARE)));

        assertThatThrownBy(
                        () -> inTransaction(() -> ledger.post(Journal.patientPayment(claimId, "PAY-1", 1, EUR, CARE))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private void insertJournal(UUID journalId, String reference) {
        jdbc.sql("""
                        INSERT INTO ledger_journal (id, claim_id, entry_type, external_reference, valid_time)
                        VALUES (:id, :claim, 'CHARGE', :ref, now())""")
                .param("id", journalId)
                .param("claim", claimId)
                .param("ref", reference)
                .update();
    }

    private void insertPosting(UUID journalId, int position, String account, long amount) {
        jdbc.sql("""
                        INSERT INTO ledger_posting (journal_id, position, account, amount_minor, currency)
                        VALUES (:id, :position, :account, :amount, 'EUR')""")
                .param("id", journalId)
                .param("position", position)
                .param("account", account)
                .param("amount", amount)
                .update();
    }

    private boolean journalExists(UUID journalId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger_journal WHERE id = :id)")
                .param("id", journalId)
                .query(Boolean.class)
                .single();
    }
}
