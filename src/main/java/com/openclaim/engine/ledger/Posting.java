package com.openclaim.engine.ledger;

import org.springframework.data.relational.core.mapping.Table;

/** One side of a journal: positive amounts debit the account, negative amounts credit it. */
@Table("ledger_posting")
public record Posting(LedgerAccount account, long amountMinor, String currency) {}
