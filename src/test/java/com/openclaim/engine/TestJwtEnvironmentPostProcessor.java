package com.openclaim.engine;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Test defaults for every application context: {@link TestJwt}'s per-JVM key and issuer, an unroutable payer without
 * TLS, and the outbox relay switched off so tests drive it explicitly with {@code OutboxRelay.runOnce()}. Individual
 * tests override these with {@code @DynamicPropertySource}, which takes precedence.
 */
public class TestJwtEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "openclaimTestDefaults",
                        Map.of(
                                "OPENCLAIM_JWT_PUBLIC_KEY_LOCATION", "file:" + TestJwt.publicKeyPem(),
                                "OPENCLAIM_JWT_ISSUER", TestJwt.ISSUER,
                                "OPENCLAIM_PAYER_BASE_URL", "http://payer.invalid",
                                "openclaim.payer.ssl-bundle", "",
                                "openclaim.outbox.relay-enabled", "false")));
    }
}
