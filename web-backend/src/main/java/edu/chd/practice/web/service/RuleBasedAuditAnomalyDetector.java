package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Explicitly rule based; this is not represented as an LSTM or other trained model. */
@Component
public class RuleBasedAuditAnomalyDetector {
    private static final int HIGH_FREQUENCY_THRESHOLD = 30;
    private final RemoteDataGateway gateway;
    private final Map<String, ArrayDeque<Instant>> recentByActor = new ConcurrentHashMap<>();

    public RuleBasedAuditAnomalyDetector(RemoteDataGateway gateway) {
        this.gateway = gateway;
    }

    public void observe(String actor, String operation, String tableName, String recordKey, Instant occurredAt) {
        String anomaly = null;
        int hour = occurredAt.atZone(ZoneId.systemDefault()).getHour();
        if (hour < 5 && isBulkGradeOperation(operation, tableName)) {
            anomaly = "深夜执行批量成绩操作";
        }
        if (isHighFrequency(actor, occurredAt)) {
            anomaly = "一分钟内高频操作超过 " + HIGH_FREQUENCY_THRESHOLD + " 次";
        }
        if (operation.contains("OWNERSHIP_DENIED")) {
            anomaly = "非授课教师尝试访问或修改课程成绩";
        }
        if (anomaly != null) {
            gateway.executeAsSystem(new MutationCommand(MutationType.INSERT, "alerts", Map.of(
                    "id", UUID.randomUUID().toString(),
                    "type", "AUDIT_SEQUENCE_ANOMALY",
                    "severity", operation.contains("OWNERSHIP_DENIED") ? "HIGH" : "MEDIUM",
                    "message", anomaly + "; actor=" + actor + "; operation=" + operation,
                    "status", "OPEN",
                    "related_table", tableName == null ? "audit_logs" : tableName,
                    "related_id", recordKey == null ? "" : recordKey,
                    "created_at", occurredAt.toString()
            ), java.util.List.of()));
        }
    }

    private boolean isBulkGradeOperation(String operation, String tableName) {
        return "grades".equals(tableName) && (operation.contains("BATCH") || operation.contains("SUBMIT")
                || operation.contains("REVERT") || operation.contains("RESTORE"));
    }

    private boolean isHighFrequency(String actor, Instant now) {
        ArrayDeque<Instant> deque = recentByActor.computeIfAbsent(actor, ignored -> new ArrayDeque<>());
        synchronized (deque) {
            Instant cutoff = now.minusSeconds(60);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(cutoff)) {
                deque.removeFirst();
            }
            deque.addLast(now);
            return deque.size() > HIGH_FREQUENCY_THRESHOLD;
        }
    }
}
