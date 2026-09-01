package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.SecuritySupport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final RemoteDataGateway gateway;
    private final RuleBasedAuditAnomalyDetector anomalyDetector;

    public AuditService(RemoteDataGateway gateway, RuleBasedAuditAnomalyDetector anomalyDetector) {
        this.gateway = gateway;
        this.anomalyDetector = anomalyDetector;
    }

    public void record(String operation, String table, String recordKey, boolean success, String detail) {
        recordAs(SecuritySupport.principal().id(), operation, table, recordKey, success, detail);
    }

    public void recordAs(String actor, String operation, String table, String recordKey,
                         boolean success, String detail) {
        Instant now = Instant.now();
        gateway.executeAsSystem(new MutationCommand(MutationType.INSERT, "audit_logs", Map.of(
                "id", UUID.randomUUID().toString(),
                "request_id", requestId(),
                "actor", actor,
                "operation", operation,
                "table_name", table == null ? "" : table,
                "record_key", recordKey == null ? "" : recordKey,
                "success", Boolean.toString(success),
                "detail", sanitize(detail),
                "created_at", now.toString()
        ), List.of()));
        anomalyDetector.observe(actor, operation, table, recordKey, now);
    }

    public void ownershipDenied(String resourceType, String resourceId) {
        record("OWNERSHIP_DENIED_" + resourceType.toUpperCase(), resourceType, resourceId, false,
                "resource ownership check failed");
    }

    private String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object value = request.getAttribute("traceId");
            return value == null ? UUID.randomUUID().toString() : value.toString();
        }
        return UUID.randomUUID().toString();
    }

    private String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        String sanitized = detail.replaceAll("(?i)(password|token|secret)=([^,;\\s]+)", "$1=[REDACTED]");
        return sanitized.length() > 1000 ? sanitized.substring(0, 1000) : sanitized;
    }
}
