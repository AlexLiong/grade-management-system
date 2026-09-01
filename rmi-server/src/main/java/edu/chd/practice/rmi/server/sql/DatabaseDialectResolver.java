package edu.chd.practice.rmi.server.sql;

import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Component
public class DatabaseDialectResolver {
    private final DataSource dataSource;
    private volatile DatabaseDialect cached;

    public DatabaseDialectResolver(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DatabaseDialect resolve() {
        DatabaseDialect result = cached;
        if (result != null) return result;
        synchronized (this) {
            if (cached == null) {
                try (Connection connection = dataSource.getConnection()) {
                    cached = DatabaseDialect.fromProductName(connection.getMetaData().getDatabaseProductName());
                } catch (SQLException exception) {
                    throw new IllegalStateException("Unable to determine database dialect", exception);
                }
            }
            return cached;
        }
    }
}
