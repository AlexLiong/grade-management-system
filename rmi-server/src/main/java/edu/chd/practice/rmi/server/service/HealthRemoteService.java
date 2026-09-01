package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.HealthInterface;
import edu.chd.practice.rmi.contract.dto.HealthStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;

@Service
public class HealthRemoteService implements HealthInterface {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public HealthRemoteService(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public HealthStatus check() {
        try (Connection connection = dataSource.getConnection()) {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            return new HealthStatus(value != null && value == 1,
                    connection.getMetaData().getDatabaseProductName(), "1.0.0", System.currentTimeMillis());
        } catch (Exception exception) {
            return new HealthStatus(false, "unavailable", "1.0.0", System.currentTimeMillis());
        }
    }
}
