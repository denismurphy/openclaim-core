package com.openclaim.engine.claim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * SHA-256 over an unambiguous encoding of a field list. Each field is length-prefixed, so no value can forge a
 * field boundary (unlike delimiter-joined or plain concatenation), and {@code null} encodes differently from the
 * empty string.
 */
public final class Fingerprint {

    private Fingerprint() {}

    public static String sha256(@Nullable String... fields) {
        StringBuilder encoded = new StringBuilder();
        encoded.append(fields.length).append('#');
        for (String field : fields) {
            if (field == null) {
                encoded.append("-;");
            } else {
                encoded.append(field.length()).append(':').append(field).append(';');
            }
        }
        return HexFormat.of().formatHex(digest().digest(encoded.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
