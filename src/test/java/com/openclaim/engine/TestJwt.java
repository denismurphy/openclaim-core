package com.openclaim.engine;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Mints real RS256 tokens for tests. The key pair is generated per JVM, so no private key is ever committed, and
 * requests go through the application's real JWT decoder (signature, issuer, audience and expiry checks).
 */
public final class TestJwt {

    public static final String ISSUER = "https://auth.openclaim.test";
    public static final String AUDIENCE = "openclaim-core";

    private static final KeyPair KEYS = generate();
    private static final Path PUBLIC_KEY = writePublicKey(KEYS);

    private TestJwt() {}

    public static Path publicKeyPem() {
        return PUBLIC_KEY;
    }

    /** A valid token for the given space-separated scopes. */
    public static String token(String scopes) {
        return builder(scopes).sign();
    }

    public static Builder builder(String scopes) {
        return new Builder(scopes);
    }

    public static String bearer(String scopes) {
        return "Bearer " + token(scopes);
    }

    public static final class Builder {

        private final JWTClaimsSet.Builder claims;
        private RSAPrivateKey signingKey = (RSAPrivateKey) KEYS.getPrivate();

        private Builder(String scopes) {
            Instant now = Instant.now();
            this.claims = new JWTClaimsSet.Builder()
                    .jwtID(UUID.randomUUID().toString())
                    .subject("test-client")
                    .issuer(ISSUER)
                    .audience(AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                    .claim("scope", scopes);
        }

        public Builder issuer(String issuer) {
            claims.issuer(issuer);
            return this;
        }

        public Builder audience(String audience) {
            claims.audience(audience);
            return this;
        }

        public Builder expiredAt(Instant expiry) {
            claims.expirationTime(Date.from(expiry));
            return this;
        }

        public Builder claim(String name, Object value) {
            claims.claim(name, value);
            return this;
        }

        public Builder claims(Map<String, Object> values) {
            values.forEach(claims::claim);
            return this;
        }

        public Builder signedWithAnotherKey() {
            this.signingKey = (RSAPrivateKey) generate().getPrivate();
            return this;
        }

        public String sign() {
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
                jwt.sign(new RSASSASigner(signingKey));
                return jwt.serialize();
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path writePublicKey(KeyPair keys) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        try {
            Path file = Files.createTempFile("openclaim-test-jwt", ".pem");
            file.toFile().deleteOnExit();
            return Files.writeString(file, pem);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
