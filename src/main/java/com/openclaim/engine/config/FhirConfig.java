package com.openclaim.engine.config;

import ca.uhn.fhir.context.FhirContext;
import com.openclaim.engine.fhir.FhirResourceHttpMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FhirConfig {

    /** Expensive to build and thread-safe once built; parsers created from it are not, so create one per use. */
    @Bean
    FhirContext fhirContext() {
        return FhirContext.forR5();
    }

    /** Picked up by Boot for both Spring MVC and auto-configured {@code RestClient} builders. */
    @Bean
    FhirResourceHttpMessageConverter fhirResourceHttpMessageConverter(
            FhirContext fhirContext, OpenClaimProperties properties) {
        return new FhirResourceHttpMessageConverter(
                fhirContext, properties.fhir().maxRequestSize().toBytes());
    }
}
