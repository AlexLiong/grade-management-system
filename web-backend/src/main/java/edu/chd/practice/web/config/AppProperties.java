package edu.chd.practice.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Security security,
        Crypto crypto,
        List<String> allowedOrigins,
        boolean trustProxy
) {
    public AppProperties {
        security = security == null ? new Security(null, null, null, 0, 0) : security;
        crypto = crypto == null ? new Crypto(null, null) : crypto;
        allowedOrigins = allowedOrigins == null ? List.of("http://localhost:5173") : List.copyOf(allowedOrigins);
    }

    public record Security(
            String jwtSecret,
            Path jwtKeyFile,
            Duration tokenTtl,
            int loginMaxAttempts,
            int loginWindowMinutes
    ) {
        public Security {
            jwtKeyFile = jwtKeyFile == null ? Path.of("runtime", "jwt.key") : jwtKeyFile;
            tokenTtl = tokenTtl == null ? Duration.ofHours(2) : tokenTtl;
            loginMaxAttempts = loginMaxAttempts <= 0 ? 5 : loginMaxAttempts;
            loginWindowMinutes = loginWindowMinutes <= 0 ? 10 : loginWindowMinutes;
        }
    }

    public record Crypto(String masterKey, Path keyFile) {
        public Crypto {
            keyFile = keyFile == null ? Path.of("runtime", "grade-data.key") : keyFile;
        }
    }
}
