package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.contract.Canonicalizable;
import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.RequestSignatures;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.regex.Pattern;

@Component
public class RequestAuthenticator {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:@/-]{1,128}");

    private final SecretMaterialProvider secrets;
    private final SecurityProperties properties;
    private final NonceStore nonceStore;
    private final Clock clock;

    @Autowired
    public RequestAuthenticator(SecretMaterialProvider secrets, SecurityProperties properties,
                                NonceStore nonceStore) {
        this(secrets, properties, nonceStore, Clock.systemUTC());
    }

    RequestAuthenticator(SecretMaterialProvider secrets, SecurityProperties properties,
                         NonceStore nonceStore, Clock clock) {
        this.secrets = secrets;
        this.properties = properties;
        this.nonceStore = nonceStore;
        this.clock = clock;
    }

    /** Returns false for an authenticated replay, allowing idempotent writes to return cached results. */
    public boolean authenticate(String operation, Canonicalizable payload, InvocationContext context)
            throws RemoteServiceException {
        if (context == null || !safe(context.getRequestId()) || !safe(context.getPrincipal())
                || !safe(context.getNonce()) || context.getRoles().size() > 16) {
            throw new RemoteServiceException("AUTH_CONTEXT_INVALID", "Invalid invocation context");
        }
        long maxAge;
        try {
            maxAge = Math.multiplyExact(properties.getRequestMaxAgeSeconds(), 1_000L);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("security.request-max-age-seconds is too large", exception);
        }
        if (maxAge < 1) throw new IllegalStateException("security.request-max-age-seconds must be positive");
        long now = clock.millis();
        long earliest = now < Long.MIN_VALUE + maxAge ? Long.MIN_VALUE : now - maxAge;
        long latest = now > Long.MAX_VALUE - maxAge ? Long.MAX_VALUE : now + maxAge;
        if (context.getTimestampEpochMillis() < earliest || context.getTimestampEpochMillis() > latest) {
            throw new RemoteServiceException("AUTH_TIMESTAMP_EXPIRED", "Invocation timestamp is outside the allowed window");
        }
        if (!RequestSignatures.verify(secrets.hmacKey(), operation, payload, context)) {
            throw new RemoteServiceException("AUTH_SIGNATURE_INVALID", "Invocation signature is invalid");
        }
        long timestamp = context.getTimestampEpochMillis();
        long expiresAt = timestamp > Long.MAX_VALUE - maxAge ? Long.MAX_VALUE : timestamp + maxAge;
        return nonceStore.register(context.getPrincipal(), context.getNonce(), expiresAt);
    }

    private static boolean safe(String value) {
        return value != null && SAFE_ID.matcher(value).matches();
    }
}
