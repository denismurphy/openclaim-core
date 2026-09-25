package com.openclaim.engine.fhir;

import java.util.Currency;
import org.hl7.fhir.r5.model.Money;
import org.hl7.fhir.r5.model.OperationOutcome.IssueType;

/** Reads FHIR {@code Money} as exact, non-negative minor units of the deployment currency. */
final class FhirMoney {

    private FhirMoney() {}

    /**
     * @param expression FHIRPath of {@code money}, reported with any problem
     * @throws FhirMappingException if value or currency is missing, the currency differs, the amount is negative, or
     *     it has more decimals than the currency allows
     */
    static long toMinor(Money money, Currency currency, String expression) {
        if (!money.hasValue() || !money.hasCurrency()) {
            throw new FhirMappingException(
                    IssueType.REQUIRED, expression, "An amount with value and currency is required");
        }
        if (!currency.getCurrencyCode().equals(money.getCurrency())) {
            throw new FhirMappingException(
                    IssueType.VALUE,
                    expression + ".currency",
                    "Only %s is accepted; found %s".formatted(currency.getCurrencyCode(), money.getCurrency()));
        }
        if (money.getValue().signum() < 0) {
            throw new FhirMappingException(IssueType.VALUE, expression, "Amounts must not be negative");
        }
        try {
            return MinorUnits.toMinor(money.getValue(), currency);
        } catch (IllegalArgumentException e) {
            throw new FhirMappingException(IssueType.VALUE, expression, e.getMessage());
        }
    }
}
