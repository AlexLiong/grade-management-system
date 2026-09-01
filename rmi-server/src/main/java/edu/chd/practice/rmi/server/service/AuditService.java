package edu.chd.practice.rmi.server.service;

import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void success(InvocationContext context, MutationCommand command, int affected) {
        write(context.getRequestId(), context.getPrincipal(), command.getType().name(), command.getTable(),
                true, "affected=" + affected + detail(command));
    }

    public void failure(InvocationContext context, String operation, String table, String code) {
        write(context == null ? "unknown" : context.getRequestId(),
                context == null ? "unknown" : context.getPrincipal(), operation, table, false, "code=" + code);
    }

    public void recovery(InvocationContext context, String gradeId, long sequence, String reason) {
        write(context.getRequestId(), context.getPrincipal(), "RESTORE", "grades", true,
                "gradeId=" + gradeId + ",ledgerSequence=" + sequence + ",reason=" + sanitize(reason));
    }

    private void write(String requestId, String actor, String operation, String table,
                       boolean success, String detail) {
        jdbc.update("""
                INSERT INTO audit_logs(id,request_id,actor,operation,table_name,record_key,success,detail,created_at)
                VALUES(?,?,?,?,?,?,?, ?,CURRENT_TIMESTAMP)
                """, UUID.randomUUID().toString(), requestId, actor, operation, table, null, success,
                truncate(detail, 2048));
    }

    private static String detail(MutationCommand command) {
        String reason = command.getReason() == null ? "" : ",reason=" + sanitize(command.getReason());
        String approval = command.getApprovalId() == null ? "" : ",approval=" + sanitize(command.getApprovalId());
        return reason + approval;
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]", " ");
    }

    private static String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }
}
