package com.openclaim.engine.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

@Table("claim")
public class Claim {

    @Id
    private final UUID id;

    @Version
    private @Nullable Long version;

    private final String identifierSystem;
    private final String identifierValue;
    private final String submissionHash;
    private ClaimStatus status;
    private final @Nullable String patientMrn;
    private final @Nullable String payerCode;
    private final String currency;
    private final long totalBilledMinor;
    private final Instant serviceStart;
    private final Instant createdAt;

    @MappedCollection(idColumn = "claim_id", keyColumn = "position")
    private final List<ClaimLine> lines;

    @MappedCollection(idColumn = "claim_id", keyColumn = "position")
    private final List<ClaimDiagnosis> diagnoses;

    @MappedCollection(idColumn = "claim_id", keyColumn = "position")
    private final List<ScrubViolation> violations;

    @PersistenceCreator
    Claim(
            UUID id,
            @Nullable Long version,
            String identifierSystem,
            String identifierValue,
            String submissionHash,
            ClaimStatus status,
            @Nullable String patientMrn,
            @Nullable String payerCode,
            String currency,
            long totalBilledMinor,
            Instant serviceStart,
            Instant createdAt,
            List<ClaimLine> lines,
            List<ClaimDiagnosis> diagnoses,
            List<ScrubViolation> violations) {
        this.id = id;
        this.version = version;
        this.identifierSystem = identifierSystem;
        this.identifierValue = identifierValue;
        this.submissionHash = submissionHash;
        this.status = status;
        this.patientMrn = patientMrn;
        this.payerCode = payerCode;
        this.currency = currency;
        this.totalBilledMinor = totalBilledMinor;
        this.serviceStart = serviceStart;
        this.createdAt = createdAt;
        this.lines = new ArrayList<>(lines);
        this.diagnoses = new ArrayList<>(diagnoses);
        this.violations = new ArrayList<>(violations);
    }

    public static Claim draft(UUID id, ClaimSubmission submission, Instant createdAt) {
        return new Claim(
                id,
                null,
                submission.identifier().system(),
                submission.identifier().value(),
                submission.fingerprint(),
                ClaimStatus.DRAFT,
                submission.patientMrn(),
                submission.payerCode(),
                submission.currency().getCurrencyCode(),
                submission.totalMinor(),
                submission.serviceStart(),
                createdAt,
                submission.items().stream()
                        .map(item -> new ClaimLine(
                                item.sequence(),
                                item.procedure().system(),
                                item.procedure().code(),
                                item.netMinor()))
                        .toList(),
                submission.diagnoses().stream()
                        .map(d -> new ClaimDiagnosis(d.system(), d.code()))
                        .toList(),
                List.of());
    }

    /**
     * Moves the claim along its lifecycle.
     *
     * @throws InvalidStateTransitionException if the lifecycle does not allow {@code target} from the current status
     */
    public void transitionTo(ClaimStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(id, status, target);
        }
        this.status = target;
    }

    /** Records scrubber violations and moves the claim to {@link ClaimStatus#REJECTED_SCRUBBER}. */
    public void reject(List<ScrubViolation> found) {
        if (found.isEmpty()) {
            throw new IllegalArgumentException("A claim can only be rejected with at least one violation");
        }
        transitionTo(ClaimStatus.REJECTED_SCRUBBER);
        violations.addAll(found);
    }

    public UUID id() {
        return id;
    }

    public ClaimIdentifier identifier() {
        return new ClaimIdentifier(identifierSystem, identifierValue);
    }

    public String submissionHash() {
        return submissionHash;
    }

    public ClaimStatus status() {
        return status;
    }

    public @Nullable String patientMrn() {
        return patientMrn;
    }

    public @Nullable String payerCode() {
        return payerCode;
    }

    public Currency currency() {
        return Currency.getInstance(currency);
    }

    public long totalBilledMinor() {
        return totalBilledMinor;
    }

    public Instant serviceStart() {
        return serviceStart;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<ClaimLine> lines() {
        return List.copyOf(lines);
    }

    public List<ClaimDiagnosis> diagnoses() {
        return List.copyOf(diagnoses);
    }

    public List<ScrubViolation> violations() {
        return List.copyOf(violations);
    }
}
