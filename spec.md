# Software Specification: FHIR R5 Claims Clearinghouse Engine (OpenClaim Core)

An enterprise-grade, portfolio-ready Spring Boot MVP designed to automate healthcare revenue cycle management (RCM) using the **HL7 FHIR R5 (5.0)** specification. The system ingests clinical encounters, validates billing codes through a deterministic rules scrubbing engine, maintains an append-only financial ledger, and reliably dispatches claims using the Transactional Outbox pattern.

---

## 1. System Topology & Architectural Invariants

The application operates as an asynchronous clearinghouse bridge between clinical Point of Care (EHR/PAS) systems and private health payer gateways.

```
[ Clinical EHR / Hospital PAS ]
              │
              │ (HTTP POST FHIR R5 Bundle)
              ▼
┌────────────────────────────────────────────────────────┐
│ OpenClaim Core Engine (Spring Boot + FHIR R5)          │
│                                                        │
│  ┌──────────────────┐       ┌───────────────────────┐  │
│  │ FHIR R5 Ingest   │──────▶│ Pre-Submission       │  │
│  │ Parser & Adapter │       │ Rules Pipeline        │  │
│  └──────────────────┘       └──────────┬────────────┘  │
│                                        │               │
│  ┌──────────────────┐       ┌──────────▼────────────┐  │
│  │ Append-Only      │◀──────│ Claim State Machine   │  │
│  │ Financial Ledger │       │ (Validation Lifecycle)│  │
│  └──────────────────┘       └──────────┬────────────┘  │
│                                        │               │
│                             ┌──────────▼────────────┐  │
│                             │ Transactional Outbox  │  │
│                             └──────────┬────────────┘  │
└────────────────────────────────────────┼───────────────┘
                                         │
                                         ▼ (mTLS / Idempotent POST)
                               [ External Payer Gateway ]

```

### Architectural Invariants

* **Zero Direct State Mutation on Balances:** All financial changes (adjudication reductions, deductions, co-pays, settlements) are recorded exclusively as append-only ledger journals.
* **Compile-Time & State-Driven Pipeline Guards:** Claims cannot bypass the rules engine; a claim must explicitly achieve a pre-validated state before scheduling an outbox message.
* **Deterministic Idempotency:** Re-transmissions across unreliable hospital or payer network interfaces cannot create duplicate entries or duplicate billing actions.

---

## 2. HL7 FHIR R5 Domain Mapping

FHIR R5 introduces refined structures for encounters, coverage subscribers, and financial tracking compared to legacy R4 specifications.

| Clinical / Financial Concept | FHIR R5 Resource | Key R5 Element Paths & Refinements |
| --- | --- | --- |
| **Patient Demographics** | `Patient` | `identifier`, `name`, `birthDate`, `gender` |
| **Clinical Encounter** | `Encounter` | `actualPeriod` (replaces legacy `period`), `class` (`CodeableConcept`), `subject` (`Reference<Patient>`) |
| **Diagnostic Morbidity** | `Condition` | `clinicalStatus`, `code.coding` (ICD-10/ICD-11 ontologies), `subject` |
| **Insurance Coverage** | `Coverage` | `kind`, `subscriberId` (now an array of `Identifier`), `beneficiary`, `insurer` (Reference), `relationship` |
| **Medical Billing Claim** | `Claim` | `use` (`#claim`), `status`, `encounter` (Reference array), `patient`, `item[].productOrService`, `item[].net` |
| **Payer Remittance** | `ExplanationOfBenefit` | `adjudication[].category`, `adjudication[].amount`, `outcome`, `total[].amount` |

---

## 3. Claim Lifecycle & State Machine

A claim progresses through non-reversible states. State transitions are strictly enforced before ledger generation or network communication can occur.

```
   [ DRAFT ] ───────────┐
       │ (Scrubbing     │ (Rules Violation)
       │  Passes)       │
       ▼                ▼
[ PRE_VALIDATED ]   [ REJECTED_SCRUBBER ]
       │
       │ (Outbox Worker Dispatch)
       ▼
 [ DISPATCHED ]
       │
       ├─────────────────────────┐
       │ (Payer Accepts in Full) │ (Shortfall / Excess Applied)
       ▼                         ▼
   [ SETTLED ]           [ ADJUDICATED_PARTIAL ]
                                 │
                                 │ (Patient Balance Collected)
                                 ▼
                             [ SETTLED ]

```

### State Transition Pseudocode

```
ENUM ClaimStatus:
    DRAFT, REJECTED_SCRUBBER, PRE_VALIDATED, DISPATCHED, ADJUDICATED_PARTIAL, SETTLED

CLASS ClaimAggregate:
    FIELD id: UUID
    FIELD status: ClaimStatus
    FIELD patientMrn: String
    FIELD payerCode: String
    FIELD totalBilledCents: Long
    FIELD version: Long

    METHOD transitionTo(newStatus: ClaimStatus):
        IF NOT isValidTransition(this.status, newStatus) THEN
            THROW InvalidStateTransitionException(this.status, newStatus)
        END IF
        this.status = newStatus

    PRIVATE METHOD isValidTransition(current: ClaimStatus, target: ClaimStatus) -> Boolean:
        MATCH current:
            CASE DRAFT:
                RETURN target IN [PRE_VALIDATED, REJECTED_SCRUBBER]
            CASE PRE_VALIDATED:
                RETURN target == DISPATCHED
            CASE DISPATCHED:
                RETURN target IN [SETTLED, ADJUDICATED_PARTIAL]
            CASE ADJUDICATED_PARTIAL:
                RETURN target == SETTLED
            DEFAULT:
                RETURN FALSE

```

---

## 4. Multi-Stage Rules Scrubbing Engine

Before external submission, claims pass through an ordered sequence of validation rules. A single failure marks the claim as `REJECTED_SCRUBBER` and logs the exact violation for auditing.

```
Incoming Canonical Model
         │
         ▼
[ Stage 1: Administrative & Identifier Verification ]
         │ (Valid)
         ▼
[ Stage 2: Clinical Indication Matching (ICD-10 vs Procedure) ]
         │ (Valid)
         ▼
[ Stage 3: Care Setting Compatibility (Inpatient vs Day-Case) ]
         │ (Valid)
         ▼
[ Stage 4: Bundling & Code Conflict Enforcement ]
         │
         ├──▶ (Any Failure) ──▶ Set REJECTED_SCRUBBER (Halt Pipeline)
         ▼
   (All Pass) ──▶ Set PRE_VALIDATED (Schedule Outbox Dispatch)

```

### Rules Engine Pseudocode

```
INTERFACE ScrubberRule:
    METHOD evaluate(fhirClaim: FHIR_R5_Claim, fhirEncounter: FHIR_R5_Encounter) -> RuleResult
    METHOD getPriority() -> Integer

CLASS AdministrativeRule IMPLEMENTS ScrubberRule:
    METHOD evaluate(fhirClaim, fhirEncounter) -> RuleResult:
        IF fhirClaim.patient IS NULL OR fhirClaim.patient.identifier IS EMPTY THEN
            RETURN RuleResult.failed("Missing mandatory patient identifier.")
        END IF
        
        policyNumber = EXTRACT fhirClaim.insurance.coverage.identifier
        IF NOT MATCHES(policyNumber, "^[A-Z0-9]{6,12}$") THEN
            RETURN RuleResult.failed("Payer policy number does not match structural format.")
        END IF

        RETURN RuleResult.passed()

CLASS ClinicalIndicationRule IMPLEMENTS ScrubberRule:
    METHOD evaluate(fhirClaim, fhirEncounter) -> RuleResult:
        diagnoses = EXTRACT fhirClaim.diagnosis.codeableReferences
        procedures = EXTRACT fhirClaim.item.productOrService

        FOR EACH procedure IN procedures:
            allowedIndications = FeeScheduleRepository.findAllowedDiagnoses(procedure.code)
            IF NOT CONTAINS_ANY(diagnoses, allowedIndications) THEN
                RETURN RuleResult.failed(
                    "Procedure code " + procedure.code + 
                    " is not clinically indicated by supplied ICD-10 diagnoses."
                )
            END IF
        END FOR

        RETURN RuleResult.passed()

CLASS CareSettingRule IMPLEMENTS ScrubberRule:
    METHOD evaluate(fhirClaim, fhirEncounter) -> RuleResult:
        encounterClass = fhirEncounter.class.coding.first().code // e.g., AMB, IMP, SS
        
        FOR EACH item IN fhirClaim.item:
            allowedSettings = FeeScheduleRepository.findAllowedSettings(item.productOrService.code)
            IF encounterClass NOT IN allowedSettings THEN
                RETURN RuleResult.failed(
                    "Procedure " + item.productOrService.code + 
                    " requires setting " + allowedSettings + " but encounter was " + encounterClass
                )
            END IF
        END FOR

        RETURN RuleResult.passed()

CLASS RulesPipelineOrchestrator:
    FIELD registeredRules: LIST OF ScrubberRule

    METHOD execute(claim, encounter) -> ValidationSummary:
        violations = EMPTY_LIST
        FOR EACH rule IN registeredRules SORTED BY rule.getPriority():
            result = rule.evaluate(claim, encounter)
            IF result.isFailed() THEN
                violations.ADD(result.getViolation())
            END IF
        END FOR
        RETURN ValidationSummary(violations)

```

---

## 5. Persistence, Outbox & Audit Ledger

### 5.1 Append-Only Bi-Temporal Financial Ledger

Direct balance updates are forbidden. Every financial adjustment creates a journal entry tracking two timelines:

1. **Transaction Time:** The exact wall-clock timestamp the record was written to the database.
2. **Valid Time:** The real-world timestamp when the clinical care occurred.

```
RECORD FinancialLedgerEntry:
    id: UUID
    claimId: UUID
    entryType: LedgerEntryType  // CHARGE, PAYER_REMITTANCE, PATIENT_LIABILITY, WRITE_OFF
    amountCents: Long
    currency: String
    adjudicationReference: String
    transactionTime: Instant
    validTime: Instant

```

### 5.2 Transactional Outbox & Deterministic Idempotency

To eliminate dual-write hazards between database updates and external HTTP dispatches, the claim status transition and the outbox event are persisted inside a single local database transaction.

```
ALGORITHM TransactionalClaimIngest:
    INPUT: FHIR_R5_Bundle
    
    BEGIN TRANSACTION
        claimEntity = ParseAndPersistDraft(FHIR_R5_Bundle)
        encounter = ExtractEncounter(FHIR_R5_Bundle)
        
        validationSummary = RulesPipelineOrchestrator.execute(claimEntity, encounter)
        
        IF validationSummary.hasViolations() THEN
            claimEntity.transitionTo(REJECTED_SCRUBBER)
            PersistViolations(claimEntity.id, validationSummary.violations)
            COMMIT TRANSACTION
            RETURN ValidationFailureResponse(validationSummary.violations)
        END IF

        claimEntity.transitionTo(PRE_VALIDATED)
        
        // Compute cryptographic fingerprint to prevent duplicate gateway dispatches
        idempotencyKey = HASH_SHA256(
            claimEntity.payerCode + 
            claimEntity.patientMrn + 
            claimEntity.claimReference + 
            claimEntity.totalBilledCents
        )

        outboxEvent = NEW OutboxEvent(
            aggregateId = claimEntity.id,
            eventType = "CLAIM_PRE_VALIDATED",
            payload = SerializeToJson(claimEntity),
            idempotencyKey = idempotencyKey,
            status = "PENDING"
        )
        
        Persist(claimEntity)
        Persist(outboxEvent)
    COMMIT TRANSACTION

    RETURN SuccessResponse(claimEntity.id, claimEntity.status)

```

---

## 6. End-to-End Execution Sequence

```
Hospital EHR                OpenClaim Engine                  External Payer
    │                              │                                │
    │ 1. POST FHIR R5 Bundle       │                                │
    ├─────────────────────────────▶│                                │
    │                              │                                │
    │                              │ 2. Parse FHIR R5 Resources     │
    │                              │ 3. Execute Rules Pipeline      │
    │                              │ 4. Atomic Commit:              │
    │                              │    - Claim (PRE_VALIDATED)     │
    │                              │    - Outbox Event              │
    │ 5. HTTP 201 Created          │                                │
    │◀─────────────────────────────┤                                │
    │                              │                                │
    │                              │ 6. Outbox Poller triggers      │
    │                              │    transmission worker         │
    │                              │                                │
    │                              │ 7. POST Claim Payload          │
    │                              │    Header: Idempotency-Key     │
    │                              ├───────────────────────────────▶│
    │                              │                                │
    │                              │ 8. HTTP 200 / 202 Ack          │
    │                              │◀───────────────────────────────┤
    │                              │                                │
    │                              │ 9. Ingest Remittance Callback  │
    │                              │◀───────────────────────────────┤
    │                              │                                │
    │                              │ 10. Append Ledger Journals     │
    │                              │     (Payer Settled,            │
    │                              │      Patient Excess Pending)   │
    │                              │ 11. Claim -> ADJUDICATED_PARTIAL

```

---

## 7. High-Level Modular Project Blueprint

A clean, modular architecture separating FHIR infrastructure from core revenue business rules:

```
com.openclaim.engine
├── config
│   └── FhirR5ContextFactory        // Singleton FHIR R5 parser manager
├── domain
│   ├── model
│   │   ├── ClaimAggregate          // Domain entity enforcing transitions
│   │   ├── ClaimStatus             // Lifecycle state definitions
│   │   └── FinancialLedgerEntry    // Immutable ledger record
│   ├── outbox
│   │   ├── OutboxMessage           // Transactional outbox model
│   │   └── OutboxRelayWorker       // Background poller with rate limiter
│   └── repository
│       ├── ClaimRepositoryPort     // Storage interfaces
│       └── LedgerRepositoryPort
├── fhir
│   ├── FhirR5ParserAdapter         // HAPI FHIR R5 parser wrapper
│   └── CanonicalDomainMapper       // Converts R5 resources to internal domain types
├── rules
│   ├── ScrubberRuleContract        // Strategy interface for validations
│   ├── RuleResult                  // Result container with violation explanations
│   ├── registry
│   │   └── RuleChainRegistry       // Ordered chain execution engine
│   └── validators
│       ├── AdministrativeRule
│       ├── ClinicalIndicationRule
│       ├── CareSettingRule
│       └── BundlingRule
├── service
│   ├── IngestionOrchestrator       // Coordinates parsing, rules, and outbox
│   └── AdjudicationReconciler      // Handles remittance callbacks and ledger posting
└── web
    ├── ClaimIngestEndpoint         // REST boundary accepting FHIR JSON bundles
    └── AdjudicationCallbackEndpoint// Webhook receiving payer remittance results

```

---

## 8. Portfolio Showcase Strategy

To demonstrate senior engineering capabilities on GitHub, structure the project documentation and tests around these engineering highlights:

1. **Modern FHIR R5 Standard Adoption:** Explicitly document the migration from R4 to R5, showing understanding of evolving healthcare data protocols (e.g., changes to `Encounter.actualPeriod`, `Encounter.class`, and polymorphic code references).
2. **Deterministic Domain Rules (Chain of Responsibility):** Showcase how business validation is separated into composable, isolated rules rather than monolithic conditionals.
3. **Resilience via Transactional Outbox:** Explain how saving the claim state and the outbound message atomically solves the dual-write problem across distributed network boundaries.
4. **Auditability via Append-Only Accounting:** Highlight that financial data is modeled bi-temporally with immutable entries, mirroring the compliance requirements of financial and healthcare software.