package edu.chd.practice.web.security;

import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginRateLimiter {
    private final int maximumAttempts;
    private final Duration window;
    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(AppProperties properties) {
        this.maximumAttempts = properties.security().loginMaxAttempts();
        this.window = Duration.ofMinutes(properties.security().loginWindowMinutes());
    }

    public void check(String clientAddress, String username) {
        if (blocked(ipKey(clientAddress)) || blocked(userKey(username))) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_RATE_LIMITED",
                    "登录失败次数过多，请稍后再试");
        }
    }

    public void failed(String clientAddress, String username) {
        increment(ipKey(clientAddress));
        increment(userKey(username));
    }

    public void succeeded(String clientAddress, String username) {
        attempts.remove(userKey(username));
    }

    private boolean blocked(String key) {
        AttemptWindow current = attempts.get(key);
        if (current == null) {
            return false;
        }
        if (current.expired(window)) {
            attempts.remove(key, current);
            return false;
        }
        return current.failures() >= maximumAttempts;
    }

    private void increment(String key) {
        Instant now = Instant.now();
        attempts.compute(key, (ignored, current) -> current == null || current.expired(window)
                ? new AttemptWindow(now, 1)
                : new AttemptWindow(current.startedAt(), current.failures() + 1));
    }

    private String ipKey(String clientAddress) {
        return "ip\n" + (clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.strip());
    }

    private String userKey(String username) {
        return "user\n" + (username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT));
    }

    private record AttemptWindow(Instant startedAt, int failures) {
        boolean expired(Duration duration) {
            return startedAt.plus(duration).isBefore(Instant.now());
        }
    }
}
