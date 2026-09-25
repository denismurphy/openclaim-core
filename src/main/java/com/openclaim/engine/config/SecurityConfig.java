package com.openclaim.engine.config;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token API. Each client is authorised by OAuth2 scope: the EHR submits and reads claims, the payer
 * posts remittances, the billing office records patient payments. Anything not listed is denied.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /** Added by Boot to the public-key decoder's default validators (signature, expiry, audience). */
    @Bean
    OAuth2TokenValidator<Jwt> issuerValidator(OpenClaimProperties properties) {
        return new JwtIssuerValidator(properties.security().issuer());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        requests -> requests.requestMatchers(GET, "/actuator/health", "/actuator/health/**")
                                .permitAll()
                                .requestMatchers(POST, "/fhir/Claim/$submit")
                                .hasAuthority("SCOPE_claims:write")
                                .requestMatchers(POST, "/fhir/ClaimResponse")
                                .hasAuthority("SCOPE_remittance:write")
                                .requestMatchers(GET, "/fhir/ClaimResponse/*")
                                .hasAnyAuthority("SCOPE_remittance:write", "SCOPE_claims:read")
                                .requestMatchers(GET, "/api/claims/*")
                                .hasAuthority("SCOPE_claims:read")
                                .requestMatchers(POST, "/api/claims/*/patient-payments")
                                .hasAuthority("SCOPE_payments:write")
                                .anyRequest()
                                .denyAll())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Bearer tokens are not sent automatically by browsers, so CSRF protection does not apply.
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
