-- Claims (spec §3), append-only double-entry ledger (spec §5.1) and transactional outbox (spec §5.2).

CREATE TABLE claim (
    id                 UUID PRIMARY KEY,
    version            BIGINT      NOT NULL,
    identifier_system  TEXT        NOT NULL,
    identifier_value   TEXT        NOT NULL,
    submission_hash    TEXT        NOT NULL,
    status             TEXT        NOT NULL CHECK (status IN
                           ('DRAFT', 'REJECTED_SCRUBBER', 'PRE_VALIDATED', 'DISPATCHED', 'ADJUDICATED_PARTIAL', 'SETTLED')),
    patient_mrn        TEXT,
    payer_code         TEXT,
    currency           CHAR(3)     NOT NULL,
    total_billed_minor BIGINT      NOT NULL CHECK (total_billed_minor >= 0),
    service_start      TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    -- Re-transmissions of the same Claim.identifier are detected here (ingest idempotency).
    CONSTRAINT claim_identifier_unique UNIQUE (identifier_system, identifier_value)
);

CREATE TABLE claim_line (
    claim_id         UUID    NOT NULL REFERENCES claim (id),
    position         INT     NOT NULL,
    sequence         INT     NOT NULL,
    procedure_system TEXT    NOT NULL,
    procedure_code   TEXT    NOT NULL,
    net_minor        BIGINT  NOT NULL CHECK (net_minor >= 0),
    PRIMARY KEY (claim_id, position)
);

CREATE TABLE claim_diagnosis (
    claim_id UUID NOT NULL REFERENCES claim (id),
    position INT  NOT NULL,
    system   TEXT NOT NULL,
    code     TEXT NOT NULL,
    PRIMARY KEY (claim_id, position)
);

CREATE TABLE claim_violation (
    claim_id   UUID NOT NULL REFERENCES claim (id),
    position   INT  NOT NULL,
    rule       TEXT NOT NULL,
    code       TEXT NOT NULL,
    message    TEXT NOT NULL,
    expression TEXT,
    PRIMARY KEY (claim_id, position)
);

-- ---------------------------------------------------------------------------------------------------------------
-- Ledger. A journal is one financial event; its postings move money between accounts and must sum to zero.
-- transaction_time: when the row was written (set by the database). valid_time: when care was delivered.
-- ---------------------------------------------------------------------------------------------------------------

CREATE TABLE ledger_journal (
    id                 UUID        PRIMARY KEY,
    claim_id           UUID        NOT NULL REFERENCES claim (id),
    entry_type         TEXT        NOT NULL CHECK (entry_type IN
                           ('CHARGE', 'PAYER_REMITTANCE', 'WRITE_OFF', 'PATIENT_LIABILITY', 'PATIENT_PAYMENT')),
    external_reference TEXT        NOT NULL,
    transaction_time   TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_time         TIMESTAMPTZ NOT NULL,
    -- Replaying the same remittance or payment cannot book it twice.
    CONSTRAINT ledger_journal_reference_unique UNIQUE (claim_id, entry_type, external_reference)
);

CREATE INDEX ledger_journal_claim ON ledger_journal (claim_id);

CREATE TABLE ledger_posting (
    journal_id   UUID    NOT NULL REFERENCES ledger_journal (id),
    position     INT     NOT NULL,
    account      TEXT    NOT NULL CHECK (account IN
                     ('PAYER_AR', 'PATIENT_AR', 'REVENUE', 'CONTRACTUAL_ADJ', 'CASH')),
    amount_minor BIGINT  NOT NULL CHECK (amount_minor <> 0),
    currency     CHAR(3) NOT NULL,
    PRIMARY KEY (journal_id, position)
);

CREATE FUNCTION ledger_reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER ledger_journal_append_only BEFORE UPDATE OR DELETE ON ledger_journal
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_journal_no_truncate BEFORE TRUNCATE ON ledger_journal
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_posting_append_only BEFORE UPDATE OR DELETE ON ledger_posting
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_posting_no_truncate BEFORE TRUNCATE ON ledger_posting
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

-- The database clock is the single source of transaction time, whatever the application sends.
CREATE FUNCTION ledger_stamp_transaction_time() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.transaction_time := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_journal_transaction_time BEFORE INSERT ON ledger_journal
    FOR EACH ROW EXECUTE FUNCTION ledger_stamp_transaction_time();

-- Checked at commit, once every posting of the journal has been inserted.
CREATE FUNCTION ledger_check_journal_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target UUID;
    postings INT;
    total NUMERIC;
    currencies INT;
BEGIN
    -- Separate statements: PL/pgSQL resolves NEW's fields only when a statement runs, and each table has one of them.
    IF TG_TABLE_NAME = 'ledger_journal' THEN
        target := NEW.id;
    ELSE
        target := NEW.journal_id;
    END IF;
    SELECT count(*), coalesce(sum(amount_minor), 0), count(DISTINCT currency)
    INTO postings, total, currencies
    FROM ledger_posting WHERE journal_id = target;
    -- "postings < 2" is implied by the other two checks given the amount_minor <> 0 CHECK; it is kept so the
    -- invariant still holds if that CHECK is ever relaxed.
    IF postings < 2 OR total <> 0 OR currencies <> 1 THEN
        RAISE EXCEPTION 'journal % is unbalanced: % postings, sum %, % currencies', target, postings, total, currencies
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_journal_balanced AFTER INSERT ON ledger_journal
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_journal_balanced();
CREATE CONSTRAINT TRIGGER ledger_posting_balanced AFTER INSERT ON ledger_posting
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION ledger_check_journal_balanced();

-- The spec's FinancialLedgerEntry record, as a read-only view over the double-entry tables.
CREATE VIEW financial_ledger_entry AS
SELECT j.id AS journal_id, p.position, j.claim_id, j.entry_type, p.account, p.amount_minor, p.currency,
       j.external_reference AS adjudication_reference, j.transaction_time, j.valid_time
FROM ledger_journal j JOIN ledger_posting p ON p.journal_id = j.id;

-- ---------------------------------------------------------------------------------------------------------------
-- Transactional outbox. Written in the same transaction as the claim's PRE_VALIDATED transition.
-- ---------------------------------------------------------------------------------------------------------------

CREATE TABLE outbox_message (
    id              UUID        PRIMARY KEY,
    claim_id        UUID        NOT NULL UNIQUE REFERENCES claim (id),
    event_type      TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL UNIQUE,
    payload         TEXT        NOT NULL,
    status          TEXT        NOT NULL CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED')),
    attempts        INT         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token     UUID,
    lease_until     TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CHECK ((status = 'IN_FLIGHT') = (lease_token IS NOT NULL AND lease_until IS NOT NULL))
);

-- Only undelivered rows are ever polled.
CREATE INDEX outbox_message_due ON outbox_message (next_attempt_at) WHERE status IN ('PENDING', 'IN_FLIGHT');
