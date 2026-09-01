package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.server.config.RmiProperties;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

@Component
public class SecretMaterialProvider {
    private static final int KEY_BYTES = 32;

    private final RmiProperties rmiProperties;
    private final SecurityProperties securityProperties;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();
    private byte[] hmacKey;
    private byte[] gradeKey;

    public SecretMaterialProvider(RmiProperties rmiProperties, SecurityProperties securityProperties,
                                  Environment environment) {
        this.rmiProperties = rmiProperties;
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @PostConstruct
    void initialize() {
        hmacKey = loadInlineOrFile(rmiProperties.getHmacSecret(),
                rmiProperties.getHmacKeyFile(), "RMI HMAC");
        gradeKey = loadInlineOrFile(securityProperties.getGradeKey(),
                securityProperties.getGradeKeyFile(), "grade data");
        if (gradeKey.length != KEY_BYTES) {
            throw new IllegalStateException("Grade data key must contain exactly 32 bytes");
        }
    }

    public byte[] hmacKey() {
        return hmacKey.clone();
    }

    public byte[] gradeKey() {
        return gradeKey.clone();
    }

    private byte[] loadInlineOrFile(String inline, String configuredPath, String label) {
        if (inline != null && !inline.isBlank()) {
            return validate(decodeSecret(inline.trim()), label);
        }
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalStateException(label + " key file path is required");
        }
        Path path = Path.of(configuredPath);
        boolean production = environment.acceptsProfiles(Profiles.of("prod"));
        if (production && !path.isAbsolute()) {
            throw new IllegalStateException(label + " key file must use an absolute path in prod");
        }
        try {
            Path absolute = path.toAbsolutePath().normalize();
            if (production) {
                if (!Files.isRegularFile(absolute)) {
                    throw new IllegalStateException(label + " key file must already exist in prod: " + absolute);
                }
                return validate(decodeSecret(
                        Files.readString(absolute, StandardCharsets.US_ASCII).trim()), label);
            }
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(absolute)) {
                byte[] generated = new byte[KEY_BYTES];
                secureRandom.nextBytes(generated);
                try {
                    Files.writeString(absolute, Base64.getEncoder().encodeToString(generated) + "\n",
                            StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE, StandardOpenOption.SYNC);
                    restrictPermissions(absolute);
                } catch (FileAlreadyExistsException ignored) {
                    // Another process won the atomic key-file creation race.
                }
            }
            return validate(decodeSecret(Files.readString(absolute, StandardCharsets.US_ASCII).trim()), label);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load or create " + label + " key file " + path, exception);
        }
    }

    private static byte[] decodeSecret(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static byte[] validate(byte[] value, String label) {
        if (value.length < KEY_BYTES) {
            throw new IllegalStateException(label + " secret must contain at least 32 bytes");
        }
        return value;
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }
}
