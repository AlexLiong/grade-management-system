package edu.chd.practice.web.security;

import edu.chd.practice.web.config.AppProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtServiceTest {
    @Test
    void logoutRevocationRejectsOnlyTheRevokedToken() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 11);
        AppProperties properties = new AppProperties(new AppProperties.Security(
                Base64.getEncoder().encodeToString(key), null, Duration.ofMinutes(10), 5, 10),
                null, List.of("http://localhost:5173"), false);
        JwtService service = new JwtService(properties, new MockEnvironment());
        UserPrincipal principal = new UserPrincipal("user-1", "admin", "管理员", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true,
                CredentialFingerprint.fromPasswordHash("bcrypt-hash"));

        String revoked = service.create(principal);
        String stillValid = service.create(principal);
        assertNotEquals(revoked, stillValid);
        assertEquals("user-1", service.parse(revoked).id());

        service.revoke(revoked);

        assertThrows(JwtException.class, () -> service.parse(revoked));
        assertEquals("user-1", service.parse(stillValid).id());
    }

    @Test
    void credentialFingerprintSurvivesSignedTokenRoundTrip() {
        byte[] key = key();
        JwtService service = service(key);
        String fingerprint = CredentialFingerprint.fromPasswordHash("bcrypt-hash");
        UserPrincipal principal = new UserPrincipal("user-1", "admin", "管理员", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true, fingerprint);

        UserPrincipal parsed = service.parse(service.create(principal));

        assertEquals(fingerprint, parsed.credentialFingerprint());
    }

    @Test
    void legacyTokenWithoutCredentialFingerprintFailsClosed() {
        byte[] key = key();
        JwtService service = service(key);
        Instant now = Instant.now();
        String legacyToken = Jwts.builder()
                .subject("user-1")
                .issuer("secure-grade-web")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(600)))
                .claim("username", "admin")
                .claim("roles", Set.of("ADMIN"))
                .claim("permissions", Set.of("USER_MANAGE"))
                .signWith(Keys.hmacShaKeyFor(key))
                .compact();

        assertThrows(JwtException.class, () -> service.parse(legacyToken));
    }

    @Test
    void tokenCreationFailsWhenPrincipalHasNoCredentialFingerprint() {
        JwtService service = service(key());
        UserPrincipal legacyPrincipal = new UserPrincipal("user-1", "admin", "管理员", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true);

        assertThrows(IllegalArgumentException.class, () -> service.create(legacyPrincipal));
    }

    private JwtService service(byte[] key) {
        AppProperties properties = new AppProperties(new AppProperties.Security(
                Base64.getEncoder().encodeToString(key), null, Duration.ofMinutes(10), 5, 10),
                null, List.of("http://localhost:5173"), false);
        return new JwtService(properties, new MockEnvironment());
    }

    private byte[] key() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 23);
        return key;
    }
}
