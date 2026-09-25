/**
 * Append-only, double-entry, bi-temporal ledger (spec §5.1). Balances are never stored; they are sums of postings.
 * The database enforces immutability, per-journal balance and transaction time (see {@code V2__claims_ledger_outbox.sql}).
 */
@NullMarked
package com.openclaim.engine.ledger;

import org.jspecify.annotations.NullMarked;
