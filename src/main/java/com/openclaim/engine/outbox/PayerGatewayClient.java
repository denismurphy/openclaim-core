package com.openclaim.engine.outbox;

import com.openclaim.engine.config.OpenClaimProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Posts a claim to the payer gateway with its {@code Idempotency-Key}, over mutual TLS when an SSL bundle is
 * configured, and classifies the outcome. Response bodies are never read or logged: a payer may echo PHI.
 */
@Component
public class PayerGatewayClient {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    /**
     * Payloads are FHIR JSON Bundles rendered at ingest time. Declared here rather than imported from the FHIR adapter
     * so the outbox stays independent of it.
     */
    static final MediaType PAYLOAD_TYPE = MediaType.parseMediaType("application/fhir+json");

    private final RestClient restClient;
    private final String submitPath;
    private final Clock clock;

    public PayerGatewayClient(
            RestClient.Builder builder, SslBundles sslBundles, OpenClaimProperties properties, Clock clock) {
        OpenClaimProperties.Payer payer = properties.payer();
        HttpClientSettings settings =
                HttpClientSettings.defaults().withTimeouts(payer.connectTimeout(), payer.readTimeout());
        if (payer.sslBundle() != null) {
            settings = settings.withSslBundle(sslBundles.getBundle(payer.sslBundle()));
        }
        this.restClient = builder.baseUrl(payer.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk().build(settings))
                .build();
        this.submitPath = payer.submitPath();
        this.clock = clock;
    }

    public DeliveryResult deliver(ClaimedMessage message) {
        try {
            return restClient
                    .post()
                    .uri(submitPath)
                    .contentType(PAYLOAD_TYPE)
                    .header(IDEMPOTENCY_KEY, message.idempotencyKey())
                    .body(message.payload())
                    .exchange((request, response) -> classify(response.getStatusCode(), response.getHeaders()));
        } catch (ResourceAccessException e) {
            return new DeliveryResult.Retry(
                    "I/O error: " + e.getMostSpecificCause().getClass().getSimpleName(), null);
        }
    }

    private DeliveryResult classify(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (status.is2xxSuccessful() || code == 409) {
            return new DeliveryResult.Delivered(code);
        }
        if (code == 429 || status.is5xxServerError()) {
            return new DeliveryResult.Retry(
                    "HTTP " + code, retryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER), clock.instant()));
        }
        return new DeliveryResult.Rejected("HTTP " + code);
    }

    /** RFC 9110 {@code Retry-After}: delay-seconds or an HTTP-date. Anything unparseable or in the past is ignored. */
    static @Nullable Duration retryAfter(@Nullable String value, Instant now) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return seconds > 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException notSeconds) {
            try {
                Duration until = Duration.between(
                        now,
                        ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                                .toInstant());
                return until.isPositive() ? until : null;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
