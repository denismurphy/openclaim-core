package com.openclaim.engine.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.openclaim.engine.TestJwt;
import com.openclaim.engine.TestcontainersConfiguration;
import com.openclaim.engine.fhir.FhirMediaTypes;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Every request carries a real signed token, so this exercises the decoder configuration, not a mock of it. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityIT {

    @Autowired
    MockMvcTester mvc;

    private MvcTestResult submitWith(String authorization) {
        return mvc.post()
                .uri("/fhir/Claim/$submit")
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(FhirMediaTypes.FHIR_JSON)
                .content("{\"resourceType\":\"Bundle\",\"type\":\"collection\"}")
                .exchange();
    }

    @Test
    void healthIsPublic() {
        assertThat(mvc.get().uri("/actuator/health").exchange()).hasStatusOk();
    }

    @Test
    void missingTokenIsUnauthenticated() {
        assertThat(mvc.post()
                        .uri("/fhir/Claim/$submit")
                        .contentType(FhirMediaTypes.FHIR_JSON)
                        .content("{}")
                        .exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void correctScopeReachesTheEndpoint() {
        // The body is structurally unusable, so reaching the mapper (422) proves authentication and authorisation
        // passed.
        assertThat(submitWith(TestJwt.bearer("claims:write"))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void wrongScopeIsForbidden() {
        assertThat(submitWith(TestJwt.bearer("claims:read remittance:write"))).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void expiredTokenIsRejected() {
        String token = TestJwt.builder("claims:write")
                .expiredAt(Instant.now().minusSeconds(3600))
                .sign();

        assertThat(submitWith("Bearer " + token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String token =
                TestJwt.builder("claims:write").issuer("https://evil.example").sign();

        assertThat(submitWith("Bearer " + token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenForAnotherAudienceIsRejected() {
        String token =
                TestJwt.builder("claims:write").audience("some-other-api").sign();

        assertThat(submitWith("Bearer " + token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = TestJwt.builder("claims:write").signedWithAnotherKey().sign();

        assertThat(submitWith("Bearer " + token)).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unmappedPathsAreDeniedEvenWhenAuthenticated() {
        assertThat(mvc.get()
                        .uri("/actuator/env")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.bearer("claims:write claims:read"))
                        .exchange())
                .hasStatus(HttpStatus.FORBIDDEN);
    }
}
