package edu.chd.practice.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        URI apiUrl,
        String token,
        long maxBytes,
        Duration timeout,
        Set<String> allowedContentTypes
) {
    public OcrProperties {
        maxBytes = maxBytes <= 0 ? 5 * 1024 * 1024 : maxBytes;
        timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10) : timeout;
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? Set.of("image/jpeg", "image/png", "image/webp")
                : Set.copyOf(allowedContentTypes);
    }

    public boolean configured() {
        return apiUrl != null && "https".equalsIgnoreCase(apiUrl.getScheme())
                && apiUrl.getHost() != null && !apiUrl.getHost().isBlank()
                && token != null && !token.isBlank();
    }
}
