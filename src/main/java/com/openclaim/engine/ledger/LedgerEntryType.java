package com.openclaim.engine.ledger;

/** The financial event a journal records. {@code PATIENT_PAYMENT} extends the spec's list; see README. */
public enum LedgerEntryType {
    CHARGE,
    PAYER_REMITTANCE,
    WRITE_OFF,
    PATIENT_LIABILITY,
    PATIENT_PAYMENT
}
