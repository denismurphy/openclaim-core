package com.openclaim.engine.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * @param currency the single currency this deployment bills in; submissions in any other currency are rejected
 * @param clinicalZone zone used to anchor date-only encounter starts to an instant
 */
@Validated
@ConfigurationProperties("openclaim")
public record OpenClaimProperties(
        @NotNull @DefaultValue("EUR") Currency currency,
        @NotNull @DefaultValue("Europe/Dublin") ZoneId clinicalZone,
        @Valid @NotNull @DefaultValue Fhir fhir,
        @Valid @NotNull Security security,
        @Valid @NotNull Payer payer,
        @Valid @NotNull @DefaultValue Outbox outbox) {

    /**
     * @param mrnSystem {@code Patient.identifier.system} that carries the medical record number
     * @param maxRequestSize upper bound on a FHIR request body
     * @param maxItems upper bound on {@code Claim.item} entries per claim
     */
    public record Fhir(
            @NotBlank @DefaultValue("https://ehr.example.org/mrn")
            String mrnSystem,

            @NotNull @DefaultValue("1MB") DataSize maxRequestSize,
            @Min(1) @Max(999) @DefaultValue("50") int maxItems) {}

    /**
     * @param issuer required {@code iss} of every bearer token. Validated locally against the configured public key;
     *     {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is deliberately not used because, combined with
     *     a public key, Boot switches to OIDC discovery over the network.
     */
    public record Security(@NotBlank String issuer) {}

    /**
     * @param baseUrl payer gateway base URL; claims are POSTed to {@code baseUrl + submitPath}
     * @param sslBundle name of a {@code spring.ssl.bundle.*} entry holding the client certificate and trusted CA for
     *     mutual TLS, or {@code null} for plain HTTP(S) (local development only)
     */
    public record Payer(
            @NotNull URI baseUrl,
            @NotBlank @DefaultValue("/claims") String submitPath,
            @Nullable String sslBundle,
            @NotNull @DefaultValue("2s") Duration connectTimeout,
            @NotNull @DefaultValue("10s") Duration readTimeout) {

        public Payer {
            if (sslBundle != null && sslBundle.isBlank()) {
                sslBundle = null;
            }
        }
    }

    /**
     * Relay tuning. Throughput is bounded by {@code batchSize} per {@code pollInterval}, which is the MVP's rate limit.
     *
     * @param leaseDuration how long a claimed message is reserved for one relay; must exceed the payer timeouts so a
     *     slow but successful call is never re-claimed mid-flight
     */
    public record Outbox(
            @DefaultValue("true") boolean relayEnabled,
            @NotNull @DefaultValue("1s") Duration pollInterval,
            @Min(1) @Max(500) @DefaultValue("10") int batchSize,
            @NotNull @DefaultValue("60s") Duration leaseDuration,
            @Min(1) @DefaultValue("8") int maxAttempts,
            @NotNull @DefaultValue("2s") Duration initialBackoff,
            @NotNull @DefaultValue("10m") Duration maxBackoff) {}

    public OpenClaimProperties {
        Duration worstCall = payer.connectTimeout().plus(payer.readTimeout());
        if (outbox.leaseDuration().compareTo(worstCall) <= 0) {
            throw new IllegalArgumentException(
                    "openclaim.outbox.lease-duration (%s) must exceed payer connect + read timeout (%s)"
                            .formatted(outbox.leaseDuration(), worstCall));
        }
    }
}
