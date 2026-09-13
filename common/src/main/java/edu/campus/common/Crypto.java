package edu.campus.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Crypto {
    private static final SecureRandom srandom = new SecureRandom();

    private Crypto() {
    }

    public static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmac(String key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean equal(String a, String b) {
        return a != null
                && b != null
                && MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    public static String encrypt(String key, String aad, String plain) {
        try {
            byte[] salt = new byte[16];
            srandom.nextBytes(salt);
            byte[] iv = new byte[12];
            srandom.nextBytes(iv);
            SecretKeySpec derivedKey = deriveKey(key, salt);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(
                    Cipher.ENCRYPT_MODE,
                    derivedKey,
                    new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return  Base64.getEncoder().encodeToString(salt)+
                    ":"+ Base64.getEncoder().encodeToString(iv)+
                    ":" + Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String decrypt(String key, String aad, String cipher) {
        try {
            String[] parts = cipher.split(":");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv   = Base64.getDecoder().decode(parts[1]);
            byte[] ct   = Base64.getDecoder().decode(parts[2]);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec derivedKey = deriveKey(key, salt);
            c.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(128, iv));
            c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(409, "INTEGRITY_FAILURE", "成绩完整性校验失败，请管理员从独立账本核查");
        }
    }

    private static final int PBKDF2_ITERATIONS = 1_000;

    private static SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] raw = f.generateSecret(spec).getEncoded();  // 32 字节
        return new SecretKeySpec(raw, "AES");
    }
}
