package edu.chd.practice.rmi.server.sql;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

public enum ColumnType {
    STRING {
        @Override public Object convert(String value) { return value; }
    },
    LONG {
        @Override public Object convert(String value) { return value == null ? null : Long.valueOf(value); }
    },
    INTEGER {
        @Override public Object convert(String value) { return value == null ? null : Integer.valueOf(value); }
    },
    DECIMAL {
        @Override public Object convert(String value) { return value == null ? null : new BigDecimal(value); }
    },
    BOOLEAN {
        @Override public Object convert(String value) {
            if (value == null) return null;
            if ("true".equalsIgnoreCase(value) || "1".equals(value)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(value) || "0".equals(value)) return Boolean.FALSE;
            throw new IllegalArgumentException("Expected a boolean value");
        }
    },
    DATE {
        @Override public Object convert(String value) {
            return value == null ? null : Date.valueOf(LocalDate.parse(value));
        }
    },
    TIMESTAMP {
        @Override public Object convert(String value) {
            if (value == null) return null;
            try {
                return Timestamp.from(Instant.parse(value));
            } catch (RuntimeException ignored) {
                return Timestamp.valueOf(LocalDateTime.parse(value.replace(' ', 'T')));
            }
        }
    };

    public abstract Object convert(String value);
}
