package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.contract.Canonicalizable;
import edu.chd.practice.rmi.contract.RequestSignatures;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestAuthenticatorTest {
    @Test
    void futureDatedNonceRemainsRegisteredForItsEntireAcceptanceWindow() throws Exception {
        long now = 1_000_000L;
        long maxAge = 300_000L;
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        SecretMaterialProvider secrets = mock(SecretMaterialProvider.class);
        when(secrets.hmacKey()).thenReturn(key);
        SecurityProperties properties = new SecurityProperties();
        properties.setRequestMaxAgeSeconds(maxAge / 1_000L);
        NonceStore nonces = mock(NonceStore.class);
        when(nonces.register("web-backend", "nonce-1", now + 2 * maxAge)).thenReturn(true);
        RequestAuthenticator authenticator = new RequestAuthenticator(secrets, properties, nonces,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
        Canonicalizable payload = () -> "payload";
        InvocationContext unsigned = new InvocationContext("request-1", "web-backend", List.of("GATEWAY"),
                now + maxAge, "nonce-1", null);
        InvocationContext signed = unsigned.withSignature(RequestSignatures.sign(key, "select", payload, unsigned));

        assertTrue(authenticator.authenticate("select", payload, signed));
        verify(nonces).register("web-backend", "nonce-1", now + 2 * maxAge);
    }
}
