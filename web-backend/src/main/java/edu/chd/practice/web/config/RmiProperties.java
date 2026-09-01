package edu.chd.practice.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "rmi")
public record RmiProperties(
        String host,
        int port,
        boolean tlsEnabled,
        String hmacSecret,
        Path hmacKeyFile,
        Duration timeout
) {
    public RmiProperties {
        host = host == null || host.isBlank() ? "127.0.0.1" : host;
        port = port <= 0 ? 1199 : port;
        hmacKeyFile = hmacKeyFile == null ? Path.of("runtime", "rmi-hmac.key") : hmacKeyFile;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
