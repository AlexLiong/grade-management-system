package edu.chd.practice.rmi.server.sql;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcPreparedExecutorTest {
    @Test
    void formatsTemporalValuesStably() {
        Instant instant = Instant.parse("2026-08-31T01:02:03.456Z");

        assertEquals("2026-08-31T01:02:03.456Z", JdbcPreparedExecutor.format(Timestamp.from(instant)));
        assertEquals("2026-08-31T01:02:03.456Z", JdbcPreparedExecutor.format(instant));
        assertEquals("2026-08-31T01:02:03Z", JdbcPreparedExecutor.format(
                OffsetDateTime.parse("2026-08-31T09:02:03+08:00")));
        assertEquals("2026-08-31", JdbcPreparedExecutor.format(Date.valueOf("2026-08-31")));
        assertEquals("12:34:56", JdbcPreparedExecutor.format(Time.valueOf("12:34:56")));
    }

    @Test
    void formatsDecimalsWithoutScientificNotationOrUnstableScale() {
        assertEquals("12.34", JdbcPreparedExecutor.format(new BigDecimal("12.3400")));
        assertEquals("100000000000000000000", JdbcPreparedExecutor.format(new BigDecimal("1E+20")));
        assertEquals("0", JdbcPreparedExecutor.format(new BigDecimal("0.000")));
    }
}
