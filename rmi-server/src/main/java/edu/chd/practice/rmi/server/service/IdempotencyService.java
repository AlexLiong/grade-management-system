package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.RemoteServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Component
public class IdempotencyService {
    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String databaseKey(String principal, String key) {
        return digest(principal + "\u0000" + key);
    }

    public String requestHash(String operation, String principal, String canonicalPayload) {
        return digest(operation + "\u0000" + principal + "\u0000" + canonicalPayload);
    }

    public Optional<Boolean> find(String databaseKey, String expectedHash) throws RemoteServiceException {
        List<Row> rows = jdbc.query("SELECT request_hash,result FROM idempotency_records WHERE idempotency_key=?",
                (rs, row) -> new Row(rs.getString(1), rs.getBoolean(2)), databaseKey);
        if (rows.isEmpty()) return Optional.empty();
        if (!MessageDigest.isEqual(rows.get(0).hash().getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII))) {
            throw new RemoteServiceException("IDEMPOTENCY_CONFLICT", "Idempotency key was used for a different request");
        }
        return Optional.of(rows.get(0).result());
    }

    public void store(String databaseKey, String requestHash, boolean result) {
        jdbc.update("""
                INSERT INTO idempotency_records(idempotency_key,request_hash,result,created_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP)
                """, databaseKey, requestHash, result);
    }

    public void reserve(String databaseKey, String requestHash) {
        store(databaseKey, requestHash, false);
    }

    public void complete(String databaseKey, String requestHash) {
        int updated = jdbc.update("""
                UPDATE idempotency_records SET result=TRUE
                WHERE idempotency_key=? AND request_hash=?
                """, databaseKey, requestHash);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency reservation disappeared before completion");
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Row(String hash, boolean result) { }
}
