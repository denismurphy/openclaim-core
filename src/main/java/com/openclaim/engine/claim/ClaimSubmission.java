package com.openclaim.engine.claim;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Canonical, FHIR-independent view of a submitted claim bundle.
 *
 * <p>Invariants: at least one item, no negative amounts, and {@code totalMinor} equals the sum of item amounts.
 *
 * <p>Only what is needed to identify and store the claim is mandatory. Business-relevant fields are nullable so
 * that the scrubber rules, not the FHIR mapper, decide whether their absence is acceptable.
 */
public record ClaimSubmission(
        ClaimIdentifier identifier,
        @Nullable String patientMrn,
        @Nullable String payerCode,
        @Nullable String policyNumber,
        @Nullable String coverageKind,
        Instant serviceStart,
        @Nullable Coding encounterClass,
        List<Coding> diagnoses,
        List<LineItem> items,
        Currency currency,
        long totalMinor) {

    public ClaimSubmission {
        diagnoses = List.copyOf(diagnoses);
        items = List.copyOf(items);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A claim needs at least one item");
        }
        long sum = 0;
        for (LineItem item : items) {
            if (item.netMinor() < 0) {
                throw new IllegalArgumentException("Item %d has a negative amount".formatted(item.sequence()));
            }
            try {
                sum = Math.addExact(sum, item.netMinor());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Sum of item amounts is out of range", e);
            }
        }
        if (sum != totalMinor) {
            throw new IllegalArgumentException(
                    "Claim total %d does not equal the sum of item amounts %d".formatted(totalMinor, sum));
        }
    }

    public record LineItem(int sequence, Coding procedure, long netMinor) {}

    /**
     * Content fingerprint used to tell an identical re-transmission apart from a different claim that reuses the same
     * identifier. Computed from the mapped model rather than raw bytes, so JSON formatting does not matter.
     */
    public String fingerprint() {
        List<@Nullable String> fields = new ArrayList<>();
        fields.add("claim-submission-v1");
        fields.add(identifier.system());
        fields.add(identifier.value());
        fields.add(patientMrn);
        fields.add(payerCode);
        fields.add(policyNumber);
        fields.add(coverageKind);
        fields.add(serviceStart.toString());
        fields.add(encounterClass == null ? null : encounterClass.system());
        fields.add(encounterClass == null ? null : encounterClass.code());
        fields.add(Integer.toString(diagnoses.size()));
        for (Coding diagnosis : diagnoses) {
            fields.add(diagnosis.system());
            fields.add(diagnosis.code());
        }
        fields.add(Integer.toString(items.size()));
        for (LineItem item : items) {
            fields.add(Integer.toString(item.sequence()));
            fields.add(item.procedure().system());
            fields.add(item.procedure().code());
            fields.add(Long.toString(item.netMinor()));
        }
        fields.add(currency.getCurrencyCode());
        return Fingerprint.sha256(fields.toArray(String[]::new));
    }
}
