package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.InvocationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public final class RequestSignatures {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private RequestSignatures() {
    }

    public static String sign(byte[] secret, String operation, Canonicalizable payload,
                              InvocationContext context) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("The RMI shared secret must contain at least 32 bytes");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(canonicalBytes(operation, payload, context)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    public static boolean verify(byte[] secret, String operation, Canonicalizable payload,
                                 InvocationContext context) {
        if (context == null || context.getSignature() == null) {
            return false;
        }
        try {
            byte[] expected = Base64.getDecoder().decode(sign(secret, operation, payload, context));
            byte[] actual = Base64.getDecoder().decode(context.getSignature());
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] canonicalBytes(String operation, Canonicalizable payload,
                                         InvocationContext context) {
        String body = CanonicalForms.value(operation)
                + CanonicalForms.value(payload == null ? "" : payload.canonicalForm())
                + context.canonicalIdentity();
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
