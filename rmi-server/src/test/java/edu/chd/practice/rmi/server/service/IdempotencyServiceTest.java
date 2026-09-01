package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.RemoteServiceException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyServiceTest {
    @Test
    void semanticProbeDistinguishesMissingHitAndSameKeyDifferentFingerprint() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:idempotency-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE idempotency_records(idempotency_key VARCHAR(128) PRIMARY KEY,"
                + "request_hash VARCHAR(128),result BOOLEAN,created_at TIMESTAMP)");
        IdempotencyService service = new IdempotencyService(jdbc);
        String databaseKey = service.databaseKey("teacher01", "submit:one");
        String expected = service.requestHash("manipulation.transaction", "teacher01", "fingerprint-a");

        assertFalse(service.find(databaseKey, expected).isPresent());
        service.store(databaseKey, expected, true);
        assertTrue(service.find(databaseKey, expected).orElseThrow());

        String different = service.requestHash("manipulation.transaction", "teacher01", "fingerprint-b");
        RemoteServiceException conflict = assertThrows(RemoteServiceException.class,
                () -> service.find(databaseKey, different));
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.getCode());
    }
}
