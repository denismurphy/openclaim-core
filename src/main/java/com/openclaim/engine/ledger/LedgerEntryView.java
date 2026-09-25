package com.openclaim.engine.ledger;

import java.time.Instant;
import java.util.UUID;

/** Read model matching the spec's {@code FinancialLedgerEntry} (one row per posting). */
public record LedgerEntryView(
        UUID journalId,
        LedgerEntryType entryType,
        LedgerAccount account,
        long amountMinor,
        String currency,
        String adjudicationReference,
        Instant transactionTime,
        Instant validTime) {}
