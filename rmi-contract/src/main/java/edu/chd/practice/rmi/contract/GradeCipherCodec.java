package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** Shared Web/RMI AES-256-GCM format for encrypted grade JSON. */
public final class GradeCipherCodec {
    private static final byte[] AES_LABEL = "grade-aes-gcm".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HMAC_LABEL = "grade-hmac-sha256".getBytes(StandardCharsets.US_ASCII);
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private GradeCipherCodec() {
    }

    public static EncryptedGradePayload encrypt(byte[] masterKey, String gradeId, String plaintext) {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        return encrypt(masterKey, gradeId, plaintext, nonce);
    }

    public static EncryptedGradePayload encrypt(byte[] masterKey, String gradeId, String plaintext,
                                                byte[] nonce) {
        validate(masterKey, gradeId, nonce);
        try {
            byte[] aesKey = derive(masterKey, AES_LABEL);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(gradeId.getBytes(StandardCharsets.UTF_8));
            String nonceText = URL_ENCODER.encodeToString(nonce);
            String ciphertext = "v1." + URL_ENCODER.encodeToString(
                    cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
            return new EncryptedGradePayload(ciphertext, nonceText,
                    integrity(masterKey, gradeId, nonceText, ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt grade payload", exception);
        }
    }

    public static String decrypt(byte[] masterKey, String gradeId, EncryptedGradePayload payload) {
        if (!verify(masterKey, gradeId, payload)) {
            throw new SecurityException("Grade integrity verification failed");
        }
        if (!payload.getCiphertext().startsWith("v1.")) {
            throw new IllegalArgumentException("Unsupported grade ciphertext version");
        }
        byte[] nonce = URL_DECODER.decode(payload.getNonce());
        validate(masterKey, gradeId, nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(masterKey, AES_LABEL), "AES"),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(gradeId.getBytes(StandardCharsets.UTF_8));
            byte[] plaintext = cipher.doFinal(URL_DECODER.decode(payload.getCiphertext().substring(3)));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (AEADBadTagException exception) {
            throw new SecurityException("Grade authentication tag verification failed", exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt grade payload", exception);
        }
    }

    public static boolean verify(byte[] masterKey, String gradeId, EncryptedGradePayload payload) {
        if (payload == null || payload.getCiphertext() == null || payload.getNonce() == null
                || payload.getIntegrity() == null) return false;
        try {
            String expected = integrity(masterKey, gradeId, payload.getNonce(), payload.getCiphertext());
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    payload.getIntegrity().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException | RuntimeException exception) {
            return false;
        }
    }

    private static String integrity(byte[] masterKey, String gradeId, String nonce, String ciphertext)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(derive(masterKey, HMAC_LABEL), "HmacSHA256"));
        mac.update(gradeId.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0);
        mac.update(nonce.getBytes(StandardCharsets.US_ASCII));
        mac.update((byte) 0);
        return URL_ENCODER.encodeToString(mac.doFinal(ciphertext.getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] derive(byte[] master, byte[] label) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(master, "HmacSHA256"));
        return mac.doFinal(label);
    }

    private static void validate(byte[] masterKey, String gradeId, byte[] nonce) {
        if (masterKey == null || masterKey.length != 32) {
            throw new IllegalArgumentException("Grade master key must contain exactly 32 bytes");
        }
        if (gradeId == null || gradeId.isBlank()) throw new IllegalArgumentException("gradeId is required");
        if (nonce == null || nonce.length != 12) throw new IllegalArgumentException("GCM nonce must contain 12 bytes");
    }
}
