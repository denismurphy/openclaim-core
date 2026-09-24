package com.openclaim.engine.claim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FingerprintTest {

    @Test
    void producesLowercaseHexSha256() {
        assertThat(Fingerprint.sha256("a")).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministic() {
        assertThat(Fingerprint.sha256("payer", "mrn")).isEqualTo(Fingerprint.sha256("payer", "mrn"));
    }

    @Test
    void fieldBoundariesAreUnambiguous() {
        // Plain concatenation (spec §5.2) would make these two collide.
        assertThat(Fingerprint.sha256("ab", "c")).isNotEqualTo(Fingerprint.sha256("a", "bc"));
    }

    @ParameterizedTest
    @ValueSource(strings = {";", ":", "#", "|", ",", "-", "\u001F", "\u0000", "1:", "0;"})
    void noSeparatorInsideAValueCanForgeAFieldBoundary(String separator) {
        assertThat(Fingerprint.sha256("a" + separator + "b", "c"))
                .isNotEqualTo(Fingerprint.sha256("a", "b" + separator + "c"));
        assertThat(Fingerprint.sha256("a" + separator, "b")).isNotEqualTo(Fingerprint.sha256("a", separator + "b"));
    }

    @Test
    void nullIsDistinctFromEmpty() {
        assertThat(Fingerprint.sha256((String) null)).isNotEqualTo(Fingerprint.sha256(""));
    }

    @Test
    void fieldCountMatters() {
        assertThat(Fingerprint.sha256("a")).isNotEqualTo(Fingerprint.sha256("a", ""));
    }
}
