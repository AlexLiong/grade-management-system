package edu.chd.practice.rmi.server.sql;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JdbcPreparedExecutor {
    private final JdbcTemplate jdbc;

    public JdbcPreparedExecutor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String[][] queryStrings(PreparedSql prepared) {
        List<String[]> rows = jdbc.query(prepared.sql(), statement -> bind(statement, prepared.parameters()), resultSet -> {
            List<String[]> values = new ArrayList<>();
            while (resultSet.next()) {
                String[] row = new String[prepared.selectedColumns().size()];
                for (int column = 0; column < row.length; column++) {
                    Object value = resultSet.getObject(column + 1);
                    row[column] = format(value);
                }
                values.add(row);
            }
            return values;
        });
        return rows.toArray(String[][]::new);
    }

    public List<Map<String, String>> queryMaps(PreparedSql prepared) {
        return jdbc.query(prepared.sql(), statement -> bind(statement, prepared.parameters()), resultSet -> {
            List<Map<String, String>> rows = new ArrayList<>();
            while (resultSet.next()) {
                Map<String, String> row = new LinkedHashMap<>();
                for (int index = 0; index < prepared.selectedColumns().size(); index++) {
                    Object value = resultSet.getObject(index + 1);
                    row.put(prepared.selectedColumns().get(index), format(value));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    public long queryCount(PreparedSql prepared) {
        Long count = jdbc.query(prepared.sql(), statement -> bind(statement, prepared.parameters()), resultSet ->
                resultSet.next() ? resultSet.getLong(1) : 0L);
        return count == null ? 0 : count;
    }

    public int update(PreparedSql prepared) {
        return jdbc.update(prepared.sql(), statement -> bind(statement, prepared.parameters()));
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws java.sql.SQLException {
        for (int index = 0; index < parameters.size(); index++) statement.setObject(index + 1, parameters.get(index));
    }

    static String format(Object value) {
        if (value == null) return null;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant().toString();
        if (value instanceof ZonedDateTime dateTime) return dateTime.toInstant().toString();
        if (value instanceof LocalDateTime dateTime) return dateTime.toInstant(ZoneOffset.UTC).toString();
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof LocalDate date) return date.toString();
        if (value instanceof Time time) return time.toLocalTime().toString();
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return value.toString();
    }
}
