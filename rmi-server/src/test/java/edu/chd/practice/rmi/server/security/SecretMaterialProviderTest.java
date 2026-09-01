package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.server.config.RmiProperties;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretMaterialProviderTest {
    @TempDir
    Path tempDirectory;

    @Test
    void productionRejectsRelativeOrMissingKeyFilesInsteadOfGeneratingThem() {
        RmiProperties relativeRmi = new RmiProperties();
        SecurityProperties relativeSecurity = new SecurityProperties();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        assertThrows(IllegalStateException.class, () ->
                new SecretMaterialProvider(relativeRmi, relativeSecurity, production).initialize());

        RmiProperties missingRmi = new RmiProperties();
        missingRmi.setHmacKeyFile(tempDirectory.resolve("missing-hmac.key").toString());
        SecurityProperties missingSecurity = new SecurityProperties();
        missingSecurity.setGradeKeyFile(tempDirectory.resolve("missing-grade.key").toString());
        assertThrows(IllegalStateException.class, () ->
                new SecretMaterialProvider(missingRmi, missingSecurity, production).initialize());
    }

    @Test
    void productionAcceptsExplicitInlineKeys() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 7);
        String encoded = Base64.getEncoder().encodeToString(key);
        RmiProperties rmi = new RmiProperties();
        rmi.setHmacSecret(encoded);
        SecurityProperties security = new SecurityProperties();
        security.setGradeKey(encoded);
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        SecretMaterialProvider provider = new SecretMaterialProvider(rmi, security, production);
        provider.initialize();

        assertArrayEquals(key, provider.hmacKey());
        assertArrayEquals(key, provider.gradeKey());
    }
}
