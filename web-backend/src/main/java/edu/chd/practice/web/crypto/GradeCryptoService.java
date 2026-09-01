package edu.chd.practice.web.crypto;

import edu.chd.practice.rmi.contract.GradeCipherCodec;
import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;
import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import java.util.Arrays;

@Service
public class GradeCryptoService {
    private final byte[] masterKey;

    public GradeCryptoService(AppProperties properties, Environment environment) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        this.masterKey = production
                ? SecretMaterialLoader.loadRequired(properties.crypto().masterKey(),
                        properties.crypto().keyFile(), 32, "grade data")
                : SecretMaterialLoader.loadOrCreate(properties.crypto().masterKey(),
                        properties.crypto().keyFile(), 32);
        if (masterKey.length != 32) {
            throw new IllegalStateException("Grade master key must contain exactly 32 bytes");
        }
    }

    public ProtectedGrade encrypt(String gradeId, String plaintext) {
        try {
            EncryptedGradePayload encrypted = GradeCipherCodec.encrypt(masterKey, gradeId, plaintext);
            return new ProtectedGrade(encrypted.getCiphertext(), encrypted.getNonce(), encrypted.getIntegrity());
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CRYPTO_UNAVAILABLE",
                    "成绩加密服务不可用", exception);
        }
    }

    public String decrypt(String gradeId, String ciphertext, String nonce, String integrity) {
        try {
            return GradeCipherCodec.decrypt(masterKey, gradeId,
                    new EncryptedGradePayload(ciphertext, nonce, integrity));
        } catch (SecurityException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_INTEGRITY_FAILED",
                    "成绩密文无效或已被篡改", exception);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CRYPTO_UNAVAILABLE",
                    "成绩解密服务不可用", exception);
        }
    }

    public boolean verifyIntegrity(String gradeId, String ciphertext, String nonce, String integrity) {
        return GradeCipherCodec.verify(masterKey, gradeId,
                new EncryptedGradePayload(ciphertext, nonce, integrity));
    }

    public record ProtectedGrade(String ciphertext, String nonce, String integrity) {
    }
}
