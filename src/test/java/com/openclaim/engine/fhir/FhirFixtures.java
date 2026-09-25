package com.openclaim.engine.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.StrictErrorHandler;
import com.openclaim.engine.config.OpenClaimProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Currency;
import org.hl7.fhir.r5.model.Bundle;
import org.hl7.fhir.r5.model.Claim;
import org.hl7.fhir.r5.model.Resource;
import org.springframework.util.unit.DataSize;

public final class FhirFixtures {

    public static final FhirContext CONTEXT = FhirContext.forR5();
    public static final String CLAIM_BUNDLE = "/fhir/claim-bundle.json";

    private FhirFixtures() {}

    public static OpenClaimProperties properties() {
        return new OpenClaimProperties(
                Currency.getInstance("EUR"),
                ZoneId.of("Europe/Dublin"),
                new OpenClaimProperties.Fhir("https://ehr.example.org/mrn", DataSize.ofMegabytes(1), 50),
                new OpenClaimProperties.Security("https://auth.openclaim.test"),
                new OpenClaimProperties.Payer(
                        java.net.URI.create("http://payer.invalid"),
                        "/claims",
                        null,
                        java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(2)),
                new OpenClaimProperties.Outbox(
                        false,
                        java.time.Duration.ofSeconds(1),
                        10,
                        java.time.Duration.ofSeconds(30),
                        8,
                        java.time.Duration.ofSeconds(2),
                        java.time.Duration.ofMinutes(10)));
    }

    public static String json(String classpathResource) {
        try (InputStream in = FhirFixtures.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Bundle claimBundle() {
        return CONTEXT.newJsonParser()
                .setParserErrorHandler(new StrictErrorHandler())
                .parseResource(Bundle.class, json(CLAIM_BUNDLE));
    }

    public static Claim claimOf(Bundle bundle) {
        return (Claim) bundle.getEntryFirstRep().getResource();
    }

    public static <T extends Resource> T entry(Bundle bundle, Class<T> type) {
        return bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }
}
