package com.openclaim.engine.ledger;

/** Receivable (debit-positive) and contra accounts a claim's money moves between. */
public enum LedgerAccount {
    /** Owed by the payer. */
    PAYER_AR,
    /** Owed by the patient. */
    PATIENT_AR,
    REVENUE,
    /** Contractual reduction agreed with the payer. */
    CONTRACTUAL_ADJ,
    CASH
}
