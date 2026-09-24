package com.openclaim.engine.fhir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MinorUnitsTest {

    private static final Currency EUR = Currency.getInstance("EUR");
    private static final Currency JPY = Currency.getInstance("JPY");

    @ParameterizedTest
    @CsvSource({"12.34, 1234", "12.3, 1230", "12, 1200", "12.340, 1234", "0, 0", "0.01, 1", "-5.50, -550"})
    void convertsExactEuroAmounts(String amount, long expected) {
        assertThat(MinorUnits.toMinor(new BigDecimal(amount), EUR)).isEqualTo(expected);
    }

    @Test
    void currencyWithoutMinorUnitsIsNotScaled() {
        assertThat(MinorUnits.toMinor(new BigDecimal("1500"), JPY)).isEqualTo(1500);
    }

    @Test
    void rejectsFractionalMinorUnits() {
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("12.345"), EUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12.345")
                .hasMessageContaining("decimal places")
                .hasMessageContaining("EUR");
    }

    @Test
    void rejectsFractionalYen() {
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("1.5"), JPY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAmountsThatOverflowLong() {
        assertThatThrownBy(() -> MinorUnits.toMinor(new BigDecimal("1E+17"), EUR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("range");
    }

    @Test
    void rejectsCurrenciesWithoutDefinedMinorUnits() {
        assertThatThrownBy(() -> MinorUnits.toMinor(BigDecimal.ONE, Currency.getInstance("XXX")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XXX");
    }
}
