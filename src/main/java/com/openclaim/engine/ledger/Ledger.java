package com.openclaim.engine.ledger;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.jdbc.core.JdbcAggregateOperations;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Appends journals and reads balances. There is deliberately no update or delete path: journals are written with
 * {@code insert}, never {@code save}, and corrections are new journals.
 */
@Component
public class Ledger {

    private final JdbcAggregateOperations aggregates;
    private final JdbcClient jdbc;

    public Ledger(JdbcAggregateOperations aggregates, JdbcClient jdbc) {
        this.aggregates = aggregates;
        this.jdbc = jdbc;
    }

    public void post(Journal journal) {
        aggregates.insert(journal);
    }

    public Balances balances(UUID claimId) {
        Map<LedgerAccount, Long> sums = new EnumMap<>(LedgerAccount.class);
        jdbc.sql("""
                        SELECT p.account, sum(p.amount_minor) AS balance
                        FROM ledger_posting p JOIN ledger_journal j ON j.id = p.journal_id
                        WHERE j.claim_id = :claimId
                        GROUP BY p.account""")
                .param("claimId", claimId)
                .query((rs, row) -> sums.put(LedgerAccount.valueOf(rs.getString("account")), rs.getLong("balance")))
                .list();
        return new Balances(sums);
    }

    public List<LedgerEntryView> entries(UUID claimId) {
        return jdbc.sql("""
                        SELECT journal_id, entry_type, account, amount_minor, currency, adjudication_reference,
                               transaction_time, valid_time
                        FROM financial_ledger_entry
                        WHERE claim_id = :claimId
                        ORDER BY transaction_time, journal_id, position""")
                .param("claimId", claimId)
                .query((rs, row) -> new LedgerEntryView(
                        rs.getObject("journal_id", UUID.class),
                        LedgerEntryType.valueOf(rs.getString("entry_type")),
                        LedgerAccount.valueOf(rs.getString("account")),
                        rs.getLong("amount_minor"),
                        rs.getString("currency"),
                        rs.getString("adjudication_reference"),
                        rs.getObject("transaction_time", OffsetDateTime.class).toInstant(),
                        rs.getObject("valid_time", OffsetDateTime.class).toInstant()))
                .list();
    }
}
