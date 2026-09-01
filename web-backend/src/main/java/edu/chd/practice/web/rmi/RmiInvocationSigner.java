package edu.chd.practice.web.rmi;

import edu.chd.practice.rmi.contract.Canonicalizable;
import edu.chd.practice.rmi.contract.RequestSignatures;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.web.config.RmiProperties;
import edu.chd.practice.web.crypto.SecretMaterialLoader;
import edu.chd.practice.web.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class RmiInvocationSigner {
    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public RmiInvocationSigner(RmiProperties properties, Environment environment) {
        boolean production = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        this.secret = production
                ? SecretMaterialLoader.loadRequired(properties.hmacSecret(), properties.hmacKeyFile(), 32,
                        "RMI HMAC")
                : SecretMaterialLoader.loadOrCreate(properties.hmacSecret(), properties.hmacKeyFile(), 32);
    }

    public InvocationContext sign(String operation, Canonicalizable payload) {
        InvocationContext unsigned = unsignedForCurrentUser(operation, payload);
        return unsigned.withSignature(RequestSignatures.sign(secret, operation, payload, unsigned));
    }

    public InvocationContext signAsGateway(String operation, Canonicalizable payload) {
        return signInternal(operation, payload, "GATEWAY");
    }

    public InvocationContext signAsSystem(String operation, Canonicalizable payload) {
        return signInternal(operation, payload, "SYSTEM");
    }

    public InvocationContext signAsAnalytics(String operation, Canonicalizable payload) {
        return signInternal(operation, payload, "ANALYTICS");
    }

    private InvocationContext unsignedForCurrentUser(String operation, Canonicalizable payload) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = "anonymous";
        List<String> roles = List.of();
        if (authentication != null && authentication.getPrincipal() instanceof UserPrincipal user) {
            principal = user.username();
            roles = user.roles().stream().sorted().toList();
        }
        byte[] nonceBytes = new byte[24];
        random.nextBytes(nonceBytes);
        return new InvocationContext(requestId(operation, payload), principal, roles, Instant.now().toEpochMilli(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes), null);
    }

    private InvocationContext signInternal(String operation, Canonicalizable payload, String role) {
        byte[] nonceBytes = new byte[24];
        random.nextBytes(nonceBytes);
        InvocationContext unsigned = new InvocationContext(requestId(operation, payload), "web-backend", List.of(role),
                Instant.now().toEpochMilli(), Base64.getUrlEncoder().withoutPadding()
                .encodeToString(nonceBytes), null);
        return unsigned.withSignature(RequestSignatures.sign(secret, operation, payload, unsigned));
    }

    private String requestId(String operation, Canonicalizable payload) {
        String traceId = UUID.randomUUID().toString();
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object requestTraceId = request.getAttribute("traceId");
            if (requestTraceId != null) {
                traceId = requestTraceId.toString();
            }
        }
        String canonicalPayload = payload == null ? "<null>" : payload.canonicalForm();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (traceId + "\u0000" + operation + "\u0000" + canonicalPayload)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
