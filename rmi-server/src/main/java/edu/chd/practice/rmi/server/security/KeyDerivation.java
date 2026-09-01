package edu.chd.practice.rmi.server.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

public final class KeyDerivation {
    private static final String ALGORITHM = "HmacSHA256";

    private KeyDerivation() {
    }

    public static byte[] hmacSha256(byte[] masterKey, String context) {
        if (masterKey == null || masterKey.length < 32) {
            throw new IllegalArgumentException("Master key must contain at least 32 bytes");
        }
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("Key derivation context is required");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(masterKey, ALGORITHM));
            return mac.doFinal(context.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }
}
