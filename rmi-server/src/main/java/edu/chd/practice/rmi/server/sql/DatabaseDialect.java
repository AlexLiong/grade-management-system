package edu.chd.practice.rmi.server.sql;

import java.util.List;
import java.util.Locale;

public enum DatabaseDialect {
    H2 {
        @Override void appendPage(StringBuilder sql, List<Object> parameters, long offset, int pageSize) {
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(pageSize);
            parameters.add(offset);
        }
    },
    MYSQL {
        @Override void appendPage(StringBuilder sql, List<Object> parameters, long offset, int pageSize) {
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(pageSize);
            parameters.add(offset);
        }
    },
    SQL_SERVER {
        @Override void appendPage(StringBuilder sql, List<Object> parameters, long offset, int pageSize) {
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            parameters.add(offset);
            parameters.add(pageSize);
        }
    },
    ORACLE {
        @Override void appendPage(StringBuilder sql, List<Object> parameters, long offset, int pageSize) {
            sql.append(" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
            parameters.add(offset);
            parameters.add(pageSize);
        }
    };

    abstract void appendPage(StringBuilder sql, List<Object> parameters, long offset, int pageSize);

    public static DatabaseDialect fromProductName(String productName) {
        String normalized = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (normalized.contains("microsoft") || normalized.contains("sql server")) return SQL_SERVER;
        if (normalized.contains("oracle")) return ORACLE;
        if (normalized.contains("mysql") || normalized.contains("mariadb")) return MYSQL;
        if (normalized.contains("h2")) return H2;
        throw new IllegalStateException("Unsupported database dialect: " + productName);
    }
}
