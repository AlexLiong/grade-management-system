package edu.chd.practice.web.health;

import edu.chd.practice.rmi.contract.dto.HealthStatus;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component("rmi")
public class RmiHealthIndicator implements HealthIndicator {
    private final RemoteDataGateway gateway;

    public RmiHealthIndicator(RemoteDataGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Health health() {
        try {
            HealthStatus status = gateway.health();
            Health.Builder builder = status.isHealthy() ? Health.up() : Health.down();
            return builder.withDetail("database", status.getDatabase())
                    .withDetail("serviceVersion", status.getServiceVersion())
                    .withDetail("serverTime", Instant.ofEpochMilli(status.getServerTimeEpochMillis()))
                    .build();
        } catch (RuntimeException exception) {
            return Health.down().withException(exception).build();
        }
    }
}
