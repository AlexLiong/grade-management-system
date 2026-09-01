package edu.chd.practice.rmi.server.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class SnapshotCrypto {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "grade-ledger-snapshot-v1".getBytes(StandardCharsets.UTF_8);

    private static final String KEY_CONTEXT = "grade-ledger-snapshot-aes-v1";

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public SnapshotCrypto(SecretMaterialProvider secrets) {
        this(secrets.gradeKey());
    }

    SnapshotCrypto(byte[] gradeMasterKey) {
        this.key = KeyDerivation.hmacSha256(gradeMasterKey, KEY_CONTEXT);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt grade recovery snapshot", exception);
        }
    }

    public String decrypt(String encryptedSnapshot) {
        if (encryptedSnapshot == null || !encryptedSnapshot.startsWith("v1.")) {
            throw new IllegalArgumentException("Unsupported recovery snapshot version");
        }
        String[] parts = encryptedSnapshot.split("\\.", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Malformed recovery snapshot");
        try {
            byte[] nonce = Base64.getUrlDecoder().decode(parts[1]);
            if (nonce.length != NONCE_BYTES) throw new IllegalArgumentException("Malformed recovery nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(Base64.getUrlDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new SecurityException("Recovery snapshot authentication failed", exception);
        }
    }
}
