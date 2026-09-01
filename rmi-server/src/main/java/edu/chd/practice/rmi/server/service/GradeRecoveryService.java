package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.GradeCipherCodec;
import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.contract.dto.RecoverySnapshot;
import edu.chd.practice.rmi.server.integrity.GradeSnapshotCodec;
import edu.chd.practice.rmi.server.integrity.HashChainLedger;
import edu.chd.practice.rmi.server.integrity.LedgerEvent;
import edu.chd.practice.rmi.server.security.SecretMaterialProvider;
import edu.chd.practice.rmi.server.security.SnapshotCrypto;
import edu.chd.practice.rmi.server.sql.JdbcPreparedExecutor;
import edu.chd.practice.rmi.server.sql.SafeSqlBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static edu.chd.practice.rmi.contract.dto.Filter.of;
import static edu.chd.practice.rmi.contract.dto.FilterOperator.EQ;

@Component
public class GradeRecoveryService {
    private final HashChainLedger ledger;
    private final SnapshotCrypto crypto;
    private final SecretMaterialProvider secrets;
    private final JdbcTemplate jdbc;
    private final SafeSqlBuilder sqlBuilder;
    private final JdbcPreparedExecutor preparedJdbc;
    private final TransactionTemplate transactions;
    private final AuditService audit;

    public GradeRecoveryService(HashChainLedger ledger, SnapshotCrypto crypto,
                                SecretMaterialProvider secrets, JdbcTemplate jdbc,
                                SafeSqlBuilder sqlBuilder, JdbcPreparedExecutor preparedJdbc,
                                TransactionTemplate transactions, AuditService audit) {
        this.ledger = ledger;
        this.crypto = crypto;
        this.secrets = secrets;
        this.jdbc = jdbc;
        this.sqlBuilder = sqlBuilder;
        this.preparedJdbc = preparedJdbc;
        this.transactions = transactions;
        this.audit = audit;
    }

    public RecoverySnapshot decrypt(long sequence) {
        RecoveryRecord record = requireGradeRecord(sequence);
        Map<String, String> values = GradeSnapshotCodec.decode(crypto.decrypt(record.getEncryptedSnapshot()));
        validateSnapshot(record, values);
        return new RecoverySnapshot(record.getSequence(), record.getEventType(), record.getAggregateId(),
                values, record.getCreatedAtEpochMillis());
    }

    public boolean restore(long sequence, String reason, String approvalId, InvocationContext context) {
        RecoveryRecord source = requireGradeRecord(sequence);
        Map<String, String> values = new HashMap<>(
                GradeSnapshotCodec.decode(crypto.decrypt(source.getEncryptedSnapshot())));
        validateSnapshot(source, values);
        String gradeId = values.get("id");
        Boolean restored = transactions.execute(status -> {
            requireRecoveryApproval(approvalId, sequence);
            int consumed = jdbc.update("""
                    UPDATE reversion_requests SET status='EXECUTED',executed_at=CURRENT_TIMESTAMP
                    WHERE id=? AND scope='LARGE' AND status='APPROVED'
                    """, approvalId);
            if (consumed != 1) throw new IllegalArgumentException("Recovery approval was already consumed");

            List<Map<String, Object>> current = jdbc.queryForList("SELECT * FROM grades WHERE id=?", gradeId);
            if (!current.isEmpty()) storeCurrentHistory(current.get(0), reason, context);

            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM grades WHERE id=?", Long.class, gradeId);
            MutationCommand command;
            if (exists != null && exists > 0) {
                Map<String, String> updates = new HashMap<>(values);
                updates.remove("id");
                command = new MutationCommand(MutationType.UPDATE, "grades", updates,
                        List.of(of("id", EQ, gradeId)), reason, null);
            } else {
                command = new MutationCommand(MutationType.INSERT, "grades", values, List.of(), reason, null);
            }
            int affected = preparedJdbc.update(sqlBuilder.mutation(command));
            if (affected != 1) throw new IllegalStateException("Recovery did not affect exactly one grade");
            storeRestoredHistory(values, reason, context);
            audit.recovery(context, gradeId, sequence, reason);
            String encrypted = crypto.encrypt(GradeSnapshotCodec.encode(values));
            ledger.appendBatch(List.of(new LedgerEvent("GRADE_RESTORE", "GRADE", gradeId,
                    encrypted, context.getPrincipal())));
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(restored);
    }

    private void requireRecoveryApproval(String approvalId, long sequence) {
        if (approvalId == null || approvalId.isBlank()) {
            throw new IllegalArgumentException("Recovery requires an approved reversion request");
        }
        List<Approval> approvals = jdbc.query("""
                SELECT rr.requested_by,rr.target_filter,a.approver
                FROM reversion_requests rr JOIN high_risk_approvals a ON a.reversion_request_id=rr.id
                WHERE rr.id=? AND rr.scope='LARGE' AND rr.status='APPROVED' AND a.decision='APPROVED'
                """, (rs, row) -> new Approval(rs.getString(1), rs.getString(2), rs.getString(3)), approvalId);
        String expectedTarget = recoveryTarget(sequence);
        boolean valid = approvals.stream().anyMatch(approval ->
                isValidRecoveryApproval(approval.requestedBy(), approval.approver(),
                        approval.targetFilter(), expectedTarget));
        if (!valid) throw new IllegalArgumentException("Recovery approval is invalid or mismatched");
    }

    static String recoveryTarget(long sequence) {
        return "RECOVERY:" + sequence;
    }

    static boolean isValidRecoveryApproval(String requestedBy, String approver,
                                           String targetFilter, String expectedTarget) {
        return requestedBy != null && approver != null && !Objects.equals(requestedBy, approver)
                && Objects.equals(targetFilter, expectedTarget);
    }

    private RecoveryRecord requireGradeRecord(long sequence) {
        RecoveryRecord record = ledger.readSequence(sequence);
        if (!"GRADE".equals(record.getAggregateType())) {
            throw new IllegalArgumentException("Ledger sequence is not a grade recovery record");
        }
        return record;
    }

    private void validateSnapshot(RecoveryRecord record, Map<String, String> values) {
        String id = values.get("id");
        if (id == null || !id.equals(record.getAggregateId())) {
            throw new SecurityException("Recovery snapshot aggregate identifier mismatch");
        }
        EncryptedGradePayload payload = new EncryptedGradePayload(values.get("score_ciphertext"),
                values.get("score_nonce"), values.get("score_integrity"));
        if (!GradeCipherCodec.verify(secrets.gradeKey(), id, payload)) {
            throw new SecurityException("Recovery snapshot contains an invalid grade signature");
        }
    }

    void storeCurrentHistory(Map<String, Object> current, String reason, InvocationContext context) {
        jdbc.update("""
                INSERT INTO grade_history(id,grade_id,action,original_ciphertext,original_nonce,
                  original_integrity,reason,scope,batch_id,actor_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), text(current.get("id")), "GRADE_BEFORE_RESTORE",
                text(current.get("score_ciphertext")), text(current.get("score_nonce")),
                text(current.get("score_integrity")), reason, "ORIGINAL_RESTORE", context.getRequestId(),
                context.getPrincipal());
    }

    void storeRestoredHistory(Map<String, String> values, String reason, InvocationContext context) {
        jdbc.update("""
                INSERT INTO grade_history(id,grade_id,action,original_ciphertext,original_nonce,
                  original_integrity,reason,scope,batch_id,actor_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), values.get("id"), "GRADE_RESTORE",
                values.get("score_ciphertext"), values.get("score_nonce"), values.get("score_integrity"),
                reason, "ORIGINAL_RESTORE", context.getRequestId(), context.getPrincipal());
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private record Approval(String requestedBy, String targetFilter, String approver) { }
}
