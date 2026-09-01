package edu.chd.practice.web.security;

import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.crypto.SecretMaterialLoader;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JwtService {
    private final SecretKey key;
    private final AppProperties.Security properties;
    private final ConcurrentHashMap<String, Instant> revokedUntil = new ConcurrentHashMap<>();

    public JwtService(AppProperties properties, Environment environment) {
        this.properties = properties.security();
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        byte[] material = production
                ? SecretMaterialLoader.loadRequired(this.properties.jwtSecret(),
                        this.properties.jwtKeyFile(), 32, "JWT")
                : SecretMaterialLoader.loadOrCreate(this.properties.jwtSecret(),
                        this.properties.jwtKeyFile(), 32);
        this.key = Keys.hmacShaKeyFor(material);
    }

    public String create(UserPrincipal principal) {
        if (!CredentialFingerprint.isValid(principal.credentialFingerprint())) {
            throw new IllegalArgumentException("JWT principal is missing a valid credential fingerprint");
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(principal.id())
                .id(UUID.randomUUID().toString())
                .issuer("secure-grade-web")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.tokenTtl())))
                .claim("username", principal.username())
                .claim("name", principal.displayName())
                .claim("organizationId", principal.organizationId())
                .claim("roles", principal.roles())
                .claim("permissions", principal.permissions())
                .claim("credentialFingerprint", principal.credentialFingerprint())
                .signWith(key)
                .compact();
    }

    public UserPrincipal parse(String token) {
        Claims claims = verifiedClaims(token);
        Instant now = Instant.now();
        removeExpiredRevocations(now);
        Instant revokedThrough = revokedUntil.get(revocationKey(claims, token));
        if (revokedThrough != null && revokedThrough.isAfter(now)) {
            throw new JwtException("JWT has been revoked");
        }
        String credentialFingerprint = claims.get("credentialFingerprint", String.class);
        if (!CredentialFingerprint.isValid(credentialFingerprint)) {
            throw new JwtException("JWT is missing a valid credential fingerprint");
        }
        return new UserPrincipal(claims.getSubject(), claims.get("username", String.class),
                claims.get("name", String.class), claims.get("organizationId", String.class),
                stringSet(claims.get("roles", List.class)), stringSet(claims.get("permissions", List.class)), true,
                credentialFingerprint);
    }

    public void revoke(String token) {
        Claims claims = verifiedClaims(token);
        Date expiration = claims.getExpiration();
        if (expiration == null) {
            throw new JwtException("JWT cannot be revoked without expiration");
        }
        Instant now = Instant.now();
        removeExpiredRevocations(now);
        Instant expiresAt = expiration.toInstant();
        if (expiresAt.isAfter(now)) {
            revokedUntil.put(revocationKey(claims, token), expiresAt);
        }
    }

    private Claims verifiedClaims(String token) {
        return Jwts.parser().verifyWith(key).requireIssuer("secure-grade-web")
                .build().parseSignedClaims(token).getPayload();
    }

    private void removeExpiredRevocations(Instant now) {
        revokedUntil.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
    }

    private String revocationKey(Claims claims, String token) {
        String tokenId = claims.getId();
        if (tokenId != null && !tokenId.isBlank()) {
            return "jti:" + tokenId;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Set<String> stringSet(List<?> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public long ttlSeconds() {
        return properties.tokenTtl().toSeconds();
    }
}
