package com.openclaim.engine.fhir;

import org.springframework.http.MediaType;

public final class FhirMediaTypes {

    public static final String FHIR_JSON_VALUE = "application/fhir+json";
    public static final MediaType FHIR_JSON = MediaType.parseMediaType(FHIR_JSON_VALUE);

    private FhirMediaTypes() {}
}
