-- Payer adjudication results (ClaimResponse), one per claim (spec §6 steps 9-11).

CREATE TABLE remittance (
    id                      UUID        PRIMARY KEY,
    claim_id                UUID        NOT NULL REFERENCES claim (id),
    identifier_system       TEXT        NOT NULL,
    identifier_value        TEXT        NOT NULL,
    content_hash            TEXT        NOT NULL,
    submitted_minor         BIGINT      NOT NULL CHECK (submitted_minor >= 0),
    eligible_minor          BIGINT      NOT NULL CHECK (eligible_minor >= 0),
    benefit_minor           BIGINT      NOT NULL CHECK (benefit_minor >= 0),
    write_off_minor         BIGINT      NOT NULL CHECK (write_off_minor >= 0),
    patient_liability_minor BIGINT      NOT NULL CHECK (patient_liability_minor >= 0),
    resource                TEXT        NOT NULL,
    received_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A replayed ClaimResponse is recognised by its identifier.
    CONSTRAINT remittance_identifier_unique UNIQUE (identifier_system, identifier_value),
    -- A claim is adjudicated once; a second, different ClaimResponse is a conflict.
    CONSTRAINT remittance_claim_unique UNIQUE (claim_id),
    CHECK (submitted_minor = write_off_minor + benefit_minor + patient_liability_minor)
);
