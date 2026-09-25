# OpenClaim Core

A healthcare claims clearinghouse engine built on **HL7 FHIR R5** and **Spring Boot 4**. It accepts claims from a
hospital EHR, scrubs them against billing rules, dispatches accepted claims to a payer gateway through a transactional
outbox, and reconciles the payer's adjudication into an append-only, double-entry ledger.

The original brief is in [`spec.md`](spec.md). Where this implementation departs from the brief, the reason is recorded
under [Design decisions](#design-decisions).

```
 EHR ──POST Claim/$submit──▶ ┌──────────────────────────── OpenClaim Core ─────────────────────────────┐
                             │ FHIR adapter ──▶ scrubber rules ──▶ claim + CHARGE journal + outbox row │  one local
 EHR ◀──ClaimResponse─────── │                                     (single transaction)               │  transaction
                             │                                                                         │
                             │ outbox relay ──lease──▶ POST + Idempotency-Key (mTLS) ─────────────────────▶ Payer
                             │                                                                         │
 Payer ──POST ClaimResponse─▶│ reconciler ──▶ WRITE_OFF / PAYER_REMITTANCE / PATIENT_LIABILITY journals│
 Billing ──patient payment──▶│ payments   ──▶ PATIENT_PAYMENT journal ──▶ SETTLED                      │
                             └─────────────────────────────────────────────────────────────────────────┘
```

## Claim lifecycle

```
DRAFT ──▶ PRE_VALIDATED ──▶ DISPATCHED ──▶ SETTLED
  │                              │            ▲
  ▼                              ▼            │  patient pays the rest
REJECTED_SCRUBBER        ADJUDICATED_PARTIAL ─┘
```

Transitions are enforced by `ClaimStatus.canTransitionTo`, an exhaustive `switch`, so a new status that doesn't decide
its outgoing transitions is a compile error. An exhaustive 6×6 table test pins the lifecycle.

## Running it

Prerequisites: **JDK 25** and a running **Docker** daemon (see [CONTRIBUTING.md](CONTRIBUTING.md)).

```bash
./mvnw verify                                        # unit + integration tests, formatting, coverage
scripts/dev-keys.sh                                  # once: local RSA key pair for dev tokens (git-ignored)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile starts `compose.yaml` automatically: PostgreSQL plus a WireMock stand-in payer on `localhost:8089`.

### Walkthrough

```bash
EHR=$(scripts/dev-token.sh "claims:write claims:read")
PAYER=$(scripts/dev-token.sh "remittance:write" ACME-HEALTH)
BILLING=$(scripts/dev-token.sh "payments:write")

# 1. Submit a claim: ClaimResponse with outcome "queued"
curl -s -X POST 'localhost:8080/fhir/Claim/$submit' -H "Authorization: Bearer $EHR" \
     -H 'Content-Type: application/fhir+json' --data @samples/claim-bundle.json
CLAIM=<id from the response>

# 2. Moments later the relay has delivered it: status DISPATCHED, payer receivable 205.50
curl -s localhost:8080/api/claims/$CLAIM -H "Authorization: Bearer $EHR"
curl -s localhost:8089/__admin/requests          # what the payer received, including Idempotency-Key

# 3. The payer adjudicates: eligible 180.00, pays 150.00, patient owes 30.00 (ADJUDICATED_PARTIAL)
curl -s -X POST localhost:8080/fhir/ClaimResponse -H "Authorization: Bearer $PAYER" \
     -H 'Content-Type: application/fhir+json' --data @samples/claim-response-partial.json

# 4. The patient pays: SETTLED; the ten postings sum to zero
curl -s -X POST localhost:8080/api/claims/$CLAIM/patient-payments -H "Authorization: Bearer $BILLING" \
     -H 'Content-Type: application/json' -H 'Idempotency-Key: till-0001' -d '{"amount": 30.00, "currency": "EUR"}'

# 5. A claim the scrubber rejects: outcome "error" with one entry per violation
curl -s -X POST 'localhost:8080/fhir/Claim/$submit' -H "Authorization: Bearer $EHR" \
     -H 'Content-Type: application/fhir+json' --data @samples/claim-bundle-invalid.json
```

Repeating any of these requests is safe: submissions, remittances and payments are all idempotent.

## API

| Endpoint | Scope | Success | Notes |
|---|---|---|---|
| `POST /fhir/Claim/$submit` | `claims:write` | 200 `ClaimResponse` | Body is a `collection` Bundle whose first entry is the Claim. `outcome=queued` or `outcome=error` with one `error` per violation. Identical re-submission replays; different content under the same `Claim.identifier` is 409. |
| `POST /fhir/ClaimResponse` | `remittance:write` + `payer_code` claim | 201, or 200 on replay | Payer adjudication. `request` references `Claim/<id>` or the claim identifier. |
| `GET /fhir/ClaimResponse/{id}` | `remittance:write` or `claims:read` | 200 | Payers see only their own. |
| `GET /api/claims/{id}` | `claims:read` | 200 JSON | Status, violations, balance per account, posting-level ledger. |
| `POST /api/claims/{id}/patient-payments` | `payments:write` | 201, or 200 on replay | `Idempotency-Key` header required. |
| `GET /actuator/health` | none | 200 | Everything else not listed is denied. |

FHIR endpoints report errors as `OperationOutcome` (400 unparseable, 413 too large, 415 media type, 422 unusable, 409
conflict); `/api/**` endpoints use RFC 9457 problem details.

## Architecture

Package-by-feature under `com.openclaim.engine`:

| Package | Responsibility |
|---|---|
| `fhir` | The only place that knows FHIR: strict JSON converter, Bundle-to-canonical mapping, ClaimResponse building and reading. |
| `claim` | Claim aggregate, lifecycle, canonical `ClaimSubmission`, fingerprints and idempotency keys. |
| `rules` | Scrubber pipeline and the four rules; pure functions over a fee schedule snapshot. |
| `ledger` | Balanced journals, balances, the `FinancialLedgerEntry` read model. |
| `ingest` | The `$submit` transaction. |
| `outbox` | Outbox storage, relay, payer HTTP client. |
| `remittance` | Adjudication reconciliation and patient payments. |
| `web` | Controllers and error mapping. |
| `config` | Typed, validated `openclaim.*` properties; security; clock. |

`ArchitectureTest` (ArchUnit) enforces the boundaries:
- no package cycles
- no HAPI types outside `fhir` and `web`
- no servlet or MVC types in the domain
- rules never touch the database
- postings only built inside `ledger`

### Ingest: no dual write

`ClaimIngestionService` maps and scrubs the submission. In **one local transaction** it then stores the claim with its
outcome and, if the claim was accepted, also stores the CHARGE journal and the outbox message. Nothing talks to the
payer during ingest, so a crash can't leave a claim that's been billed but never queued, or queued but never recorded.

Re-transmissions are detected by a unique constraint on `(Claim.identifier.system, value)`:
- **Duplicate detection:** Postgres aborts a transaction on a unique violation, so the replay lookup runs in a fresh transaction after catching `DuplicateKeyException`.
- **Content comparison:** the stored claim's content fingerprint decides replay (same content) versus conflict (409). The fingerprint is SHA-256 over the *mapped* model, not the raw bytes, so JSON formatting doesn't matter.
- **Concurrency:** a test fires 8 identical submissions at once and gets exactly one claim, one charge and one outbox row.

### Outbox relay: leases and fencing

1. **Reserve.** A single `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED) RETURNING` statement reserves a batch. It sets `IN_FLIGHT`, a lease expiry and a fresh **lease token**. No transaction is held during the HTTP call.
2. **Send.** POST each payload with its `Idempotency-Key`.
3. **Record.** Record the outcome with an update fenced by `lease_token`. On success, the relay takes the claim row lock and moves the claim to `DISPATCHED` in the same transaction.

If a relay stalls past its lease, another relay re-reserves the message with a new token, and the stale relay's writes
match no row. A relay that dies after the payer accepted a message leads to a re-send, which the payer de-duplicates by
`Idempotency-Key`. Delivery is at-least-once and effectively-once.

| Response | Result |
|---|---|
| 2xx, 409 | Delivered |
| Other 4xx | FAILED (resending the same payload can't succeed) |
| 5xx, 429, network error | Retry with exponential backoff and equal jitter, honouring `Retry-After` (seconds or HTTP-date), capped at `max-backoff` |
| `max-attempts` exhausted | FAILED |

Response bodies are never read or logged, because a payer may echo PHI back.

### Remittance

The reconciler holds the claim row lock while it:
1. records the remittance
2. posts the journals
3. checks that the payer receivable nets to zero
4. moves the claim to `SETTLED` or `ADJUDICATED_PARTIAL`

A remittance can arrive before the relay has recorded delivery, i.e. the payer answered faster than our acknowledgement committed. The remittance then counts as proof of delivery, but only if a delivery attempt has started; otherwise it's a 409.

A payer adjudicating another payer's claim gets the same response as for an unknown claim, so claim existence can't be probed.

Lock order is always claim row first, then outbox row.

### Ledger

Double-entry, append-only, bi-temporal:

| Journal (`entry_type`) | Debit | Credit |
|---|---|---|
| `CHARGE` (claim accepted) | PAYER_AR | REVENUE |
| `WRITE_OFF` (billed − eligible) | CONTRACTUAL_ADJ | PAYER_AR |
| `PAYER_REMITTANCE` (benefit) | CASH | PAYER_AR |
| `PATIENT_LIABILITY` (eligible − benefit) | PATIENT_AR | PAYER_AR |
| `PATIENT_PAYMENT` | CASH | PATIENT_AR |

The **database** enforces the invariants, not just the application:
- Triggers reject `UPDATE`, `DELETE` and `TRUNCATE`.
- A deferred constraint trigger requires every journal to have at least two postings, in one currency, summing to zero, checked at commit.
- A `BEFORE INSERT` trigger stamps `transaction_time` from the database clock, whatever the client sends.
- `valid_time` is when care was delivered (`Encounter.actualPeriod.start`).

Balances are always computed as sums of postings, never stored.

## FHIR R5 notes

The engine targets R5 (HAPI FHIR 8.12 `org.hl7.fhir.r5` models). The R5 shapes it depends on, checked against the
model classes:

| Element | R5 shape | What that means here |
|---|---|---|
| `Encounter.actualPeriod` | `Period` (R4: `Encounter.period`) | Source of the ledger's valid time. A date-only start is anchored in `openclaim.clinical-zone`; a time without a UTC offset is rejected, because HAPI would otherwise apply the server's default zone. |
| `Encounter.class` | `CodeableConcept` 0..* (R4: `Coding` 1..1) | The first v3-ActCode coding is the care setting. |
| `Coverage.subscriberId` | `Identifier` 0..* (R4: `string`) | Source of the policy number. |
| `Coverage.kind` | new in R5 (`insurance`, `self-pay`, `other`) | Only `insurance` can be billed to a payer. |
| `Claim.encounter` | `Reference` 0..* at claim level | Exactly one is required. Previously this was only on `Claim.item.encounter` [unverified for R4]. |
| `Claim.diagnosis.diagnosis[x]` | `CodeableConcept` or `Reference(Condition)` | Still a choice type, not `CodeableReference`; both forms are mapped. |
| `ClaimResponse.outcome` | `queued`, `complete`, `error`, `partial` | `partial` means processing was incomplete. It isn't a financial shortfall, so only `complete` adjudications are posted. |

Parsing is strict (unknown elements and invalid codes give 400), but HAPI's strict mode doesn't enforce minimum
cardinality. The mapper therefore requires only what makes a claim identifiable and storable, and leaves missing
business data (for example the patient's MRN) to the scrubber, which records it as a violation.

## Design decisions

These are the deliberate departures from `spec.md`:

1. **FHIR operations.**
   - **Ingest:** claims go through the standard `Claim/$submit` operation and are answered with a `ClaimResponse`.
   - **Remittance:** the payer sends a **ClaimResponse** rather than an ExplanationOfBenefit, because in FHIR the payer-to-provider adjudication is a ClaimResponse and the EOB is the member-facing summary.
2. **Policy number.** It's read from the Bundle's Coverage (`Coverage.subscriberId`). The spec's `Claim.insurance.coverage.identifier` is a reference's identifier, not a policy number.
3. **All rules run.** The spec's pseudocode collects every violation while its diagram halts at the first. Collecting all lets a submitter fix a claim in one round trip, and any violation still rejects the claim.
4. **Double-entry ledger with a `PATIENT_PAYMENT` type.** Single-entry can't represent `PATIENT_LIABILITY` (a reclassification between receivables) without double counting. Settling an `ADJUDICATED_PARTIAL` claim needs a payment event.
5. **Idempotency key.** It's SHA-256 over length-prefixed fields and includes the claim identifier's *system*. The spec's plain concatenation is ambiguous (`"ab"+"c"` = `"a"+"bc"`), and two EHRs can share identifier values.
6. **No `*RepositoryPort` interfaces.** Spring Data repositories are already the ports. The outbox relay lives in `outbox`, not `domain`, because it's infrastructure.
7. **Rate limiting.** The relay is limited by `batch-size` per `poll-interval` rather than a token bucket.
8. **Synthetic code systems.** Procedure and diagnosis codes are made up (`https://openclaim.example.org/CodeSystem/*`) to avoid licensing CPT or SNOMED CT. The seeded fee schedule is in `V1__fee_schedule.sql`.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `OPENCLAIM_JWT_PUBLIC_KEY_LOCATION` | required | PEM public key of the token issuer |
| `OPENCLAIM_JWT_ISSUER` | required | Expected `iss`. Validated locally: combining `issuer-uri` with a public key would make Boot switch to OIDC discovery over the network. |
| `OPENCLAIM_PAYER_BASE_URL` | required | Payer gateway; claims are POSTed to `{base}/claims` |
| `openclaim.payer.ssl-bundle` | `payer` | `spring.ssl.bundle.*` holding the client certificate and trusted CA (mutual TLS) |
| `openclaim.currency` | `EUR` | The single billing currency |
| `openclaim.clinical-zone` | `Europe/Dublin` | Anchors date-only encounter starts |
| `openclaim.fhir.mrn-system` | `https://ehr.example.org/mrn` | `Patient.identifier.system` of the MRN |
| `openclaim.fhir.max-request-size` / `max-items` | `1MB` / `50` | Input bounds |
| `openclaim.outbox.*` | see `OpenClaimProperties` | Poll interval, batch size, lease, attempts, backoff. Startup fails if the lease isn't longer than connect + read timeout. |

### Operations

- **Metrics:**
  - `openclaim.outbox.deliveries{outcome=delivered|retry|failed|lease-lost}`
  - `openclaim.outbox.messages{status}`
- **Failed deliveries:** rows in `FAILED` need a person. After fixing the cause, requeue with:

  ```sql
  UPDATE outbox_message
  SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = NULL
  WHERE id = '<id>' AND status = 'FAILED';
  ```

- **Logs** identify claims only by internal UUID; no patient data is logged.

## Testing

- **Unit tests (`*Test`):** the lifecycle, money arithmetic, fingerprints, FHIR mapping (fixture Bundles, including every structural rejection), each rule, retry timing.
- **Integration tests (`*IT`):** real PostgreSQL through Testcontainers.
  - The ledger's database triggers are exercised by committing for real. A rolled-back test transaction would never fire the deferred balance check.
  - The payer is WireMock, including a fault matrix and a mutual-TLS server with key stores generated per run.
- **Security tests:** real RS256 tokens signed with a per-JVM key go through the application's own decoder, so signature, issuer, audience and expiry are all exercised. No private key is committed.
- **Concurrency tests:**
  - duplicate submissions
  - two relays
  - a relay holding its reservation open while another reserves
  - concurrent patient payments
- **Mutation checks:** every guard was proven by removing it and watching a test fail, using `scripts/mutate.sh`. The script refuses a red baseline and rejects mutants that don't compile.

## Non-goals

- Multi-tenant isolation between EHRs
- Fee schedule effective dating
- Least-privilege database roles
- An outbox requeue API
- `Parameters`-wrapped or batch `$submit`
- FHIR profile validation (`hapi-fhir-validation`)
- NullAway (packages are `@NullMarked` with JSpecify)
- A real payer integration
