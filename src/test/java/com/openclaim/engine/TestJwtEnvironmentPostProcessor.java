package com.openclaim.engine;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Points every test application context at {@link TestJwt}'s per-JVM public key and issuer. */
public class TestJwtEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "testJwt",
                        Map.of(
                                "OPENCLAIM_JWT_PUBLIC_KEY_LOCATION",
                                "file:" + TestJwt.publicKeyPem(),
                                "OPENCLAIM_JWT_ISSUER",
                                TestJwt.ISSUER)));
    }
}
