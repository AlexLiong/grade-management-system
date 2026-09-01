package edu.chd.practice.web.crypto;

import edu.chd.practice.web.error.ApiException;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

public final class SecretMaterialLoader {
    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretMaterialLoader() {
    }

    public static byte[] loadOrCreate(String configured, Path file, int byteLength) {
        if (configured != null && !configured.isBlank()) {
            return decode(configured, byteLength);
        }
        try {
            Path absolute = file.toAbsolutePath().normalize();
            if (Files.exists(absolute)) {
                return decode(Files.readString(absolute, StandardCharsets.US_ASCII).trim(), byteLength);
            }
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            byte[] generated = new byte[byteLength];
            RANDOM.nextBytes(generated);
            try {
                Files.writeString(absolute, Base64.getEncoder().encodeToString(generated) + System.lineSeparator(),
                        StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException raceWinnerCreatedFile) {
                return decode(Files.readString(absolute, StandardCharsets.US_ASCII).trim(), byteLength);
            }
            try {
                Files.setPosixFilePermissions(absolute, Set.of(PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX development platforms still receive a non-source-controlled runtime key.
            }
            return generated;
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "KEY_MATERIAL_UNAVAILABLE",
                    "安全密钥无法读取或创建", exception);
        }
    }

    public static byte[] loadRequired(String configured, Path file, int byteLength, String label) {
        if (configured != null && !configured.isBlank()) {
            return decode(configured, byteLength);
        }
        if (file == null || !file.isAbsolute()) {
            throw new IllegalStateException("Production requires " + label
                    + " as an environment secret or absolute key file");
        }
        Path absolute = file.normalize();
        if (!Files.isRegularFile(absolute) || !Files.isReadable(absolute)) {
            throw new IllegalStateException("Required " + label + " key file is missing or unreadable: " + absolute);
        }
        try {
            return decode(Files.readString(absolute, StandardCharsets.US_ASCII).trim(), byteLength);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read required " + label + " key file: " + absolute,
                    exception);
        }
    }

    private static byte[] decode(String value, int minimumLength) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ignored) {
            bytes = value.getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length < minimumLength) {
            throw new IllegalStateException("Configured secret must contain at least " + minimumLength + " bytes");
        }
        return bytes;
    }
}
