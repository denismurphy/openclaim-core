package com.openclaim.engine.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.ZoneId;
import java.util.Currency;
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
        @Valid @NotNull @DefaultValue Fhir fhir) {

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
}
