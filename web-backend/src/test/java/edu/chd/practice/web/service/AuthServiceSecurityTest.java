package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.CredentialFingerprint;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceSecurityTest {
    private static final Pattern BCRYPT_HASH = Pattern.compile("\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}");
    @Test
    void interactiveLoginFailsClosedWhenAccountHasMultipleRoles() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(gateway.selectAsGateway(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(Map.of(
                        "id", "user-1", "username", "mixed", "password_hash", "hash",
                        "display_name", "Mixed", "status", "ACTIVE"));
                case "user_roles" -> List.of(
                        Map.of("role_id", "role-teacher"), Map.of("role_id", "role-student"));
                case "roles" -> List.of(
                        Map.of("id", "role-teacher", "code", "TEACHER"),
                        Map.of("id", "role-student", "code", "STUDENT"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        AuthService service = new AuthService(gateway, passwordEncoder);

        assertThatThrownBy(() -> service.authenticate("mixed", "password"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(403);
                    assertThat(error.code()).isEqualTo("INTERACTIVE_LOGIN_FORBIDDEN");
                });
    }

    @Test
    void unknownAccountUsesTheConfiguredBcryptCostBeforeFailing() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(gateway.selectAsGateway(any())).thenReturn(List.of());
        when(passwordEncoder.matches("password", AuthService.DUMMY_PASSWORD_HASH)).thenReturn(false);
        AuthService service = new AuthService(gateway, passwordEncoder);

        assertThatThrownBy(() -> service.authenticate("missing", "password"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INVALID_CREDENTIALS"));
        assertThat(AuthService.DUMMY_PASSWORD_HASH).startsWith("$2y$12$");
        verify(passwordEncoder).matches("password", AuthService.DUMMY_PASSWORD_HASH);
    }

    @Test
    void everySeededAccountUsesTheConfiguredBcryptCostAndDemoPassword() throws Exception {
        String sql = Files.readString(Path.of("..", "rmi-server", "src", "main", "resources", "demo-data.sql"));
        Matcher matcher = BCRYPT_HASH.matcher(sql);
        Set<String> hashes = new java.util.LinkedHashSet<>();
        int count = 0;
        while (matcher.find()) {
            assertThat(matcher.group(1)).isEqualTo("12");
            hashes.add(matcher.group());
            count++;
        }

        assertThat(count).isEqualTo(12);
        assertThat(hashes).allMatch(hash -> new BCryptPasswordEncoder(12).matches("password", hash));
    }

    @Test
    void loginBindsTheCurrentPasswordHashFingerprint() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        stubActiveStudent(gateway, "bcrypt-hash", "Original Name", "2026-08-31T00:00:00Z");
        when(passwordEncoder.matches("password", "bcrypt-hash")).thenReturn(true);
        AuthService service = new AuthService(gateway, passwordEncoder);

        UserPrincipal principal = service.authenticate("student", "password");

        assertThat(principal.credentialFingerprint())
                .isEqualTo(CredentialFingerprint.fromPasswordHash("bcrypt-hash"));
    }

    @Test
    void passwordResetRevokesThePreviouslyIssuedCredentialFingerprint() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        stubActiveStudent(gateway, "new-bcrypt-hash", "Student", "2026-08-31T00:00:01Z");
        AuthService service = new AuthService(gateway, mock(PasswordEncoder.class));
        UserPrincipal tokenPrincipal = tokenPrincipal("old-bcrypt-hash");

        assertThatThrownBy(() -> service.refresh(tokenPrincipal))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(401);
                    assertThat(error.code()).isEqualTo("SESSION_REVOKED");
                });
    }

    @Test
    void profileUpdateDoesNotRevokeATokenWhenThePasswordHashIsUnchanged() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        stubActiveStudent(gateway, "same-bcrypt-hash", "Updated Name", "2026-08-31T00:00:01Z");
        AuthService service = new AuthService(gateway, mock(PasswordEncoder.class));

        UserPrincipal refreshed = service.refresh(tokenPrincipal("same-bcrypt-hash"));

        assertThat(refreshed.displayName()).isEqualTo("Updated Name");
        assertThat(refreshed.credentialFingerprint())
                .isEqualTo(CredentialFingerprint.fromPasswordHash("same-bcrypt-hash"));
    }

    private void stubActiveStudent(RemoteDataGateway gateway, String passwordHash,
                                   String displayName, String updatedAt) {
        when(gateway.selectAsGateway(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(Map.of(
                        "id", "user-1", "username", "student", "password_hash", passwordHash,
                        "display_name", displayName, "status", "ACTIVE", "updated_at", updatedAt));
                case "user_roles" -> List.of(Map.of("role_id", "role-student"));
                case "roles" -> List.of(Map.of("id", "role-student", "code", "STUDENT"));
                case "role_permissions", "user_permissions" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
    }

    private UserPrincipal tokenPrincipal(String passwordHash) {
        return new UserPrincipal("user-1", "student", "Student", null,
                Set.of("STUDENT"), Set.of(), true,
                CredentialFingerprint.fromPasswordHash(passwordHash));
    }
}
