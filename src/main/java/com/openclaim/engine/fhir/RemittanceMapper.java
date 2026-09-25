package com.openclaim.engine.fhir;

import com.openclaim.engine.claim.ClaimIdentifier;
import com.openclaim.engine.config.OpenClaimProperties;
import com.openclaim.engine.remittance.Adjudication;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r5.model.ClaimResponse;
import org.hl7.fhir.r5.model.Coding;
import org.hl7.fhir.r5.model.Enumerations;
import org.hl7.fhir.r5.model.Money;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;
import org.hl7.fhir.r5.model.Reference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Maps a payer's ClaimResponse to an {@link Adjudication}. Header-level {@code total[]} amounts are used, coded with
 * the HL7 adjudication code system; item-level adjudication is not needed to post the ledger.
 */
@Component
public class RemittanceMapper {

    public static final String ADJUDICATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/adjudication";

    private final Currency currency;

    public RemittanceMapper(OpenClaimProperties properties) {
        this.currency = properties.currency();
    }

    public Adjudication map(ClaimResponse response) {
        if (response.getOutcome() != Enumerations.ClaimProcessingCodes.COMPLETE) {
            throw new FhirMappingException(
                    IssueType.BUSINESSRULE,
                    "ClaimResponse.outcome",
                    "Only a complete adjudication can be posted; outcome was "
                            + (response.hasOutcome() ? response.getOutcome().toCode() : "missing"));
        }
        ClaimIdentifier identifier = response.getIdentifier().stream()
                .filter(id -> id.hasSystem() && id.hasValue())
                .findFirst()
                .map(id -> new ClaimIdentifier(id.getSystem(), id.getValue()))
                .orElseThrow(() -> new FhirMappingException(
                        IssueType.REQUIRED,
                        "ClaimResponse.identifier",
                        "ClaimResponse.identifier with system and value is required to detect re-transmissions"));
        Reference request = response.getRequest();
        UUID claimId = claimId(request);
        ClaimIdentifier claimIdentifier = request.hasIdentifier()
                        && request.getIdentifier().hasSystem()
                        && request.getIdentifier().hasValue()
                ? new ClaimIdentifier(
                        request.getIdentifier().getSystem(),
                        request.getIdentifier().getValue())
                : null;
        if (claimId == null && claimIdentifier == null) {
            throw new FhirMappingException(
                    IssueType.REQUIRED,
                    "ClaimResponse.request",
                    "ClaimResponse.request must reference Claim/<id> or carry the claim identifier");
        }

        Map<String, Long> totals = totals(response);
        long submitted = required(totals, "submitted");
        long benefit = required(totals, "benefit");
        long eligible = totals.getOrDefault("eligible", submitted);
        if (response.hasPayment() && response.getPayment().hasAmount()) {
            long paid = minorUnits(response.getPayment().getAmount(), "ClaimResponse.payment.amount");
            if (paid != benefit) {
                throw new FhirMappingException(
                        IssueType.VALUE,
                        "ClaimResponse.payment.amount",
                        "Payment amount does not equal the benefit total");
            }
        }
        try {
            return new Adjudication(identifier, claimId, claimIdentifier, submitted, eligible, benefit);
        } catch (IllegalArgumentException e) {
            throw new FhirMappingException(IssueType.VALUE, "ClaimResponse.total", e.getMessage());
        }
    }

    private static @Nullable UUID claimId(Reference request) {
        String reference = request.getReference();
        if (reference == null || !reference.startsWith("Claim/")) {
            return null;
        }
        try {
            return UUID.fromString(reference.substring("Claim/".length()));
        } catch (IllegalArgumentException e) {
            throw new FhirMappingException(
                    IssueType.NOTFOUND, "ClaimResponse.request", "Unknown claim reference " + reference);
        }
    }

    private Map<String, Long> totals(ClaimResponse response) {
        Map<String, Long> totals = new HashMap<>();
        for (int i = 0; i < response.getTotal().size(); i++) {
            ClaimResponse.TotalComponent total = response.getTotal().get(i);
            String expression = "ClaimResponse.total[" + i + "]";
            Coding category = total.getCategory().getCoding().stream()
                    .filter(c -> ADJUDICATION_SYSTEM.equals(c.getSystem()) && c.hasCode())
                    .findFirst()
                    .orElse(null);
            if (category == null) {
                continue; // categories from other code systems carry no meaning for posting
            }
            long amount = minorUnits(total.getAmount(), expression + ".amount");
            if (totals.putIfAbsent(category.getCode(), amount) != null) {
                throw new FhirMappingException(
                        IssueType.DUPLICATE, expression, "Total category " + category.getCode() + " appears twice");
            }
        }
        return totals;
    }

    private static long required(Map<String, Long> totals, String category) {
        Long amount = totals.get(category);
        if (amount == null) {
            throw new FhirMappingException(
                    IssueType.REQUIRED,
                    "ClaimResponse.total",
                    "A total with adjudication category '%s' is required".formatted(category));
        }
        return amount;
    }

    private long minorUnits(Money money, String expression) {
        return FhirMoney.toMinor(money, currency, expression);
    }
}
