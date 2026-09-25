package com.openclaim.engine.ledger;

import java.util.EnumMap;
import java.util.Map;

/** Sum of postings per account for one claim; accounts without postings are zero. */
public record Balances(Map<LedgerAccount, Long> byAccount) {

    public Balances {
        EnumMap<LedgerAccount, Long> complete = new EnumMap<>(LedgerAccount.class);
        for (LedgerAccount account : LedgerAccount.values()) {
            complete.put(account, byAccount.getOrDefault(account, 0L));
        }
        byAccount = Map.copyOf(complete);
    }

    public long of(LedgerAccount account) {
        return byAccount.get(account);
    }
}
