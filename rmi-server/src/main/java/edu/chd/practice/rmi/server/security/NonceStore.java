package edu.chd.practice.rmi.server.security;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class NonceStore {
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();
    private final Clock clock;

    public NonceStore() {
        this(Clock.systemUTC());
    }

    NonceStore(Clock clock) {
        this.clock = clock;
    }

    public boolean register(String principal, String nonce, long expiresAtMillis) {
        long now = clock.millis();
        if ((calls.incrementAndGet() & 255) == 0) {
            seen.entrySet().removeIf(entry -> entry.getValue() < now);
        }
        String key = principal + '\u0000' + nonce;
        Long previous = seen.putIfAbsent(key, expiresAtMillis);
        if (previous != null && previous < now && seen.replace(key, previous, expiresAtMillis)) {
            return true;
        }
        return previous == null;
    }
}
