package com.openclaim.engine.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JournalTest {

    private static final UUID CLAIM = UUID.randomUUID();
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Instant CARE = Instant.parse("2026-09-01T08:30:00Z");

    private static Journal raw(Posting... postings) {
        return new Journal(UUID.randomUUID(), CLAIM, LedgerEntryType.CHARGE, "ref", CARE, List.of(postings));
    }

    @Test
    void chargeDebitsPayerReceivableAgainstRevenue() {
        Journal charge = Journal.charge(CLAIM, 20_550, EUR, CARE);

        assertThat(charge.entryType()).isEqualTo(LedgerEntryType.CHARGE);
        assertThat(charge.externalReference()).isEqualTo(CLAIM.toString());
        assertThat(charge.postings())
                .containsExactly(
                        new Posting(LedgerAccount.PAYER_AR, 20_550, "EUR"),
                        new Posting(LedgerAccount.REVENUE, -20_550, "EUR"));
    }

    @Test
    void eachEventMovesMoneyBetweenTheDocumentedAccounts() {
        assertThat(Journal.payerRemittance(CLAIM, "r", 1, EUR, CARE).postings())
                .extracting(Posting::account)
                .containsExactly(LedgerAccount.CASH, LedgerAccount.PAYER_AR);
        assertThat(Journal.writeOff(CLAIM, "r", 1, EUR, CARE).postings())
                .extracting(Posting::account)
                .containsExactly(LedgerAccount.CONTRACTUAL_ADJ, LedgerAccount.PAYER_AR);
        assertThat(Journal.patientLiability(CLAIM, "r", 1, EUR, CARE).postings())
                .extracting(Posting::account)
                .containsExactly(LedgerAccount.PATIENT_AR, LedgerAccount.PAYER_AR);
        assertThat(Journal.patientPayment(CLAIM, "r", 1, EUR, CARE).postings())
                .extracting(Posting::account)
                .containsExactly(LedgerAccount.CASH, LedgerAccount.PATIENT_AR);
    }

    @Test
    void rejectsUnbalancedPostings() {
        assertThatThrownBy(() -> raw(
                        new Posting(LedgerAccount.PAYER_AR, 100, "EUR"),
                        new Posting(LedgerAccount.REVENUE, -99, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sum to zero");
    }

    @Test
    void rejectsSinglePosting() {
        assertThatThrownBy(() -> raw(new Posting(LedgerAccount.PAYER_AR, 100, "EUR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroPostings() {
        assertThatThrownBy(() -> raw(
                        new Posting(LedgerAccount.PAYER_AR, 0, "EUR"), new Posting(LedgerAccount.REVENUE, 0, "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-zero");
    }

    @Test
    void rejectsMixedCurrencies() {
        assertThatThrownBy(() -> raw(
                        new Posting(LedgerAccount.PAYER_AR, 100, "EUR"),
                        new Posting(LedgerAccount.REVENUE, -100, "USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void factoriesRejectNonPositiveAmounts() {
        assertThatThrownBy(() -> Journal.charge(CLAIM, 0, EUR, CARE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Journal.patientPayment(CLAIM, "r", -5, EUR, CARE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
