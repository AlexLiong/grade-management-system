package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.IntegrityReport;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.RecoveryEvidenceRequest;
import edu.chd.practice.rmi.contract.dto.RecoveryQuery;
import edu.chd.practice.rmi.contract.dto.RecoveryRecord;
import edu.chd.practice.rmi.contract.dto.RecoverySnapshot;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AdminSecurityService extends RemoteTableSupport {
    private final ResourceAccessService access;
    private final AuditService auditService;

    public AdminSecurityService(RemoteDataGateway gateway, ResourceAccessService access,
                                AuditService auditService) {
        super(gateway);
        this.access = access;
        this.auditService = auditService;
    }

    public PageResult<AdminDtos.AuditLogView> auditLogs(String actor, String operation,
                                                        Instant from, Instant to, int page, int size) {
        access.requirePermission("AUDIT_READ");
        List<Filter> filters = new ArrayList<>();
        if (actor != null && !actor.isBlank()) filters.add(Filter.of("actor", FilterOperator.EQ, actor));
        if (operation != null && !operation.isBlank()) {
            filters.add(Filter.of("operation", FilterOperator.LIKE, "%" + operation + "%"));
        }
        if (from != null && to != null) {
            filters.add(new Filter("created_at", FilterOperator.BETWEEN, List.of(from.toString(), to.toString())));
        } else if (from != null) {
            filters.add(Filter.of("created_at", FilterOperator.GE, from.toString()));
        } else if (to != null) {
            filters.add(Filter.of("created_at", FilterOperator.LE, to.toString()));
        }
        long total = count("audit_logs", filters);
        List<AdminDtos.AuditLogView> items = rows("audit_logs", List.of("id", "request_id", "actor",
                        "operation", "table_name", "record_key", "success", "detail", "created_at"), filters,
                List.of(new Sort("created_at", SortDirection.DESC)), page, size).stream()
                .map(row -> new AdminDtos.AuditLogView(row.get("id"), row.get("request_id"), row.get("actor"),
                        row.get("operation"), row.get("table_name"), row.get("record_key"),
                        Boolean.parseBoolean(row.get("success")), row.get("detail"), instant(row, "created_at")))
                .toList();
        return page(items, page, size, total);
    }

    public PageResult<AdminDtos.AlertView> alerts(String status, String severity, int page, int size) {
        access.requirePermission("ALERT_MANAGE");
        List<Filter> filters = new ArrayList<>();
        if (status != null && !status.isBlank()) filters.add(Filter.of("status", FilterOperator.EQ, status));
        if (severity != null && !severity.isBlank()) filters.add(Filter.of("severity", FilterOperator.EQ, severity));
        long total = count("alerts", filters);
        List<AdminDtos.AlertView> items = rows("alerts", List.of("id", "type", "severity", "message",
                        "status", "related_table", "related_id", "created_at", "resolved_at"), filters,
                List.of(new Sort("created_at", SortDirection.DESC)), page, size).stream().map(this::alert).toList();
        return page(items, page, size, total);
    }

    public AdminDtos.AlertView resolveAlert(String id) {
        access.requirePermission("ALERT_MANAGE");
        requireOne("alerts", List.of("id"), List.of(Filter.of("id", FilterOperator.EQ, id)),
                "ALERT_NOT_FOUND", "告警不存在");
        gateway.execute(new MutationCommand(MutationType.UPDATE, "alerts", Map.of(
                "status", "RESOLVED", "resolved_at", Instant.now().toString()), List.of(
                Filter.of("id", FilterOperator.EQ, id), Filter.of("status", FilterOperator.EQ, "OPEN"))));
        auditService.record("ALERT_RESOLVED", "alerts", id, true, "");
        return alert(requireOne("alerts", List.of("id", "type", "severity", "message", "status",
                        "related_table", "related_id", "created_at", "resolved_at"),
                List.of(Filter.of("id", FilterOperator.EQ, id)), "ALERT_NOT_FOUND", "告警不存在"));
    }

    public AdminDtos.IntegrityView verifyIntegrity() {
        access.requirePermission("INTEGRITY_VERIFY");
        IntegrityReport report = gateway.verifyLedger();
        auditService.record("INTEGRITY_CHAIN_VERIFIED", "ledger", "all", report.isValid(),
                "checked=" + report.getCheckedEntries());
        return new AdminDtos.IntegrityView(report.isValid(), report.getCheckedEntries(),
                report.getFirstInvalidSequence(), report.getMessage());
    }

    public List<AdminDtos.RecoveryEvidence> recoveryEvidence(String gradeId, int limit) {
        access.requirePermission("GRADE_RESTORE_ORIGINAL");
        return gateway.recovery(new RecoveryQuery("GRADE", gradeId, Math.min(limit, 100))).stream()
                .map(this::evidence).toList();
    }

    public AdminDtos.RecoverySnapshotView previewRecovery(long sequence, String reason) {
        access.requirePermission("GRADE_RESTORE_ORIGINAL");
        RecoverySnapshot snapshot = gateway.decryptRecovery(new RecoveryEvidenceRequest(sequence, reason));
        auditService.record("RECOVERY_EVIDENCE_PREVIEWED", "ledger", Long.toString(sequence), true,
                "reason=" + reason);
        return new AdminDtos.RecoverySnapshotView(snapshot.getSequence(), snapshot.getEventType(),
                snapshot.getAggregateId(), snapshot.getOriginalValues(),
                Instant.ofEpochMilli(snapshot.getCreatedAtEpochMillis()));
    }

    private AdminDtos.AlertView alert(Map<String, String> row) {
        return new AdminDtos.AlertView(row.get("id"), row.get("type"), row.get("severity"), row.get("message"),
                row.get("status"), row.get("related_table"), row.get("related_id"),
                instant(row, "created_at"), instant(row, "resolved_at"));
    }

    private AdminDtos.RecoveryEvidence evidence(RecoveryRecord record) {
        return new AdminDtos.RecoveryEvidence(record.getSequence(), record.getEventType(),
                record.getAggregateType(), record.getAggregateId(), record.getPreviousHash(),
                record.getEntryHash(), Instant.ofEpochMilli(record.getCreatedAtEpochMillis()), record.getActor());
    }
}
