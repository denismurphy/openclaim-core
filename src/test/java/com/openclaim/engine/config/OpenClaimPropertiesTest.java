package com.openclaim.engine.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class OpenClaimPropertiesTest {

    private static OpenClaimProperties withLease(Duration lease, String sslBundle) {
        return new OpenClaimProperties(
                Currency.getInstance("EUR"),
                ZoneId.of("Europe/Dublin"),
                new OpenClaimProperties.Fhir("https://ehr.example.org/mrn", DataSize.ofMegabytes(1), 50),
                new OpenClaimProperties.Security("https://issuer"),
                new OpenClaimProperties.Payer(
                        URI.create("https://payer"),
                        "/claims",
                        sslBundle,
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(10)),
                new OpenClaimProperties.Outbox(
                        true, Duration.ofSeconds(1), 10, lease, 8, Duration.ofSeconds(2), Duration.ofMinutes(10)));
    }

    @Test
    void leaseMustOutlastTheSlowestPayerCall() {
        assertThatThrownBy(() -> withLease(Duration.ofSeconds(12), "payer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
        assertThat(withLease(Duration.ofSeconds(13), "payer").outbox().leaseDuration())
                .hasSeconds(13);
    }

    @Test
    void blankSslBundleMeansNone() {
        assertThat(withLease(Duration.ofMinutes(1), " ").payer().sslBundle()).isNull();
        assertThat(withLease(Duration.ofMinutes(1), "payer").payer().sslBundle())
                .isEqualTo("payer");
    }
}
