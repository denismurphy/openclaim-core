package com.openclaim.engine.fhir;

import java.math.BigDecimal;
import java.util.Currency;

/** Exact conversion between FHIR {@code Money.value} decimals and integer minor units (e.g. cents). */
public final class MinorUnits {

    private MinorUnits() {}

    /**
     * Converts {@code amount} to minor units of {@code currency} without rounding.
     *
     * @throws IllegalArgumentException if the amount has more decimal places than the currency allows, does not
     *     fit in a {@code long}, or the currency has no defined minor unit (e.g. {@code XXX})
     */
    public static long toMinor(BigDecimal amount, Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new IllegalArgumentException(
                    "Currency %s has no defined minor unit".formatted(currency.getCurrencyCode()));
        }
        BigDecimal scaled = amount.movePointRight(digits);
        if (scaled.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Amount %s %s has more than %d decimal places"
                    .formatted(amount.toPlainString(), currency.getCurrencyCode(), digits));
        }
        try {
            return scaled.longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount %s %s is out of range".formatted(amount.toPlainString(), currency.getCurrencyCode()), e);
        }
    }

    public static BigDecimal toDecimal(long minor, Currency currency) {
        return BigDecimal.valueOf(minor, currency.getDefaultFractionDigits());
    }
}
