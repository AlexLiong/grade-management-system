package edu.chd.practice.web.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CredentialFingerprint {
    private static final int SHA_256_HEX_LENGTH = 64;

    private CredentialFingerprint() {
    }

    public static String fromPasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("Password hash is required");
        }
        return HexFormat.of().formatHex(digest(passwordHash));
    }

    public static boolean matches(String expected, String passwordHash) {
        if (!isValid(expected) || passwordHash == null || passwordHash.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(HexFormat.of().parseHex(expected), digest(passwordHash));
    }

    public static boolean isValid(String value) {
        if (value == null || value.length() != SHA_256_HEX_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] digest(String passwordHash) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(passwordHash.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
