package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.server.integrity.HashChainLedger;
import edu.chd.practice.rmi.server.security.SecretMaterialProvider;
import edu.chd.practice.rmi.server.security.SnapshotCrypto;
import edu.chd.practice.rmi.server.sql.JdbcPreparedExecutor;
import edu.chd.practice.rmi.server.sql.SafeSqlBuilder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class GradeRecoveryServiceTest {
    @Test
    void recoveryApprovalRequiresExactSequenceTargetAndDifferentPeople() {
        String target = GradeRecoveryService.recoveryTarget(42);

        assertEquals("RECOVERY:42", target);
        assertTrue(GradeRecoveryService.isValidRecoveryApproval(
                "requester", "approver", "RECOVERY:42", target));
        assertFalse(GradeRecoveryService.isValidRecoveryApproval(
                "requester", "requester", "RECOVERY:42", target));
        assertFalse(GradeRecoveryService.isValidRecoveryApproval(
                "requester", "approver", "RECOVERY:41", target));
        assertFalse(GradeRecoveryService.isValidRecoveryApproval(
                "requester", "approver", "*", target));
    }

    @Test
    void originalRecoveryHistoryUsesItsExplicitScope() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:recovery-history-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE grade_history(
                  id VARCHAR(64), grade_id VARCHAR(64), action VARCHAR(64), original_ciphertext VARCHAR(255),
                  original_nonce VARCHAR(255), original_integrity VARCHAR(255), reason VARCHAR(255),
                  scope VARCHAR(24), batch_id VARCHAR(64), actor_id VARCHAR(64), created_at TIMESTAMP)
                """);
        GradeRecoveryService service = new GradeRecoveryService(mock(HashChainLedger.class),
                mock(SnapshotCrypto.class), mock(SecretMaterialProvider.class), jdbc,
                mock(SafeSqlBuilder.class), mock(JdbcPreparedExecutor.class),
                mock(TransactionTemplate.class), mock(AuditService.class));
        InvocationContext context = new InvocationContext(
                "request-1", "approver", List.of("ADMIN"), 1L, "nonce", "signature");

        service.storeCurrentHistory(Map.of(
                "id", "grade-1", "score_ciphertext", "before", "score_nonce", "nonce-before",
                "score_integrity", "integrity-before"), "restore reason", context);
        service.storeRestoredHistory(Map.of(
                "id", "grade-1", "score_ciphertext", "after", "score_nonce", "nonce-after",
                "score_integrity", "integrity-after"), "restore reason", context);

        assertEquals(List.of("ORIGINAL_RESTORE", "ORIGINAL_RESTORE"),
                jdbc.query("SELECT scope FROM grade_history ORDER BY action", (rs, row) -> rs.getString(1)));
    }
}
