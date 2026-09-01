package edu.chd.practice.rmi.server.sql;

import java.util.List;

public record PreparedSql(String sql, List<Object> parameters, List<String> selectedColumns) {
    public PreparedSql {
        parameters = List.copyOf(parameters);
        selectedColumns = List.copyOf(selectedColumns);
    }
}
