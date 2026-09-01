package edu.chd.practice.rmi.server.sql;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class SchemaRegistry {
    private static final Set<String> ALL_USERS = Set.of("ADMIN", "AUDITOR", "TEACHER", "STUDENT");
    private static final Set<String> STAFF = Set.of("ADMIN", "AUDITOR", "TEACHER");
    private static final Set<String> ADMIN_AUDIT = Set.of("ADMIN", "AUDITOR");
    private static final Set<String> ADMIN = Set.of("ADMIN");
    private static final Set<String> ADMIN_TEACHER = Set.of("ADMIN", "TEACHER");
    private static final Set<String> LOGIN_READ = Set.of("ADMIN", "AUDITOR", "GATEWAY");
    private static final Set<String> DOMAIN_ANALYTICS = Set.of(
            "ADMIN", "AUDITOR", "TEACHER", "STUDENT", "ANALYTICS");

    private final Map<String, TableDefinition> tables;

    public SchemaRegistry() {
        Map<String, TableDefinition> definitions = new LinkedHashMap<>();
        add(definitions, table("users", LOGIN_READ, ADMIN,
                c("id", ColumnType.STRING), c("username", ColumnType.STRING),
                c("password_hash", ColumnType.STRING), c("display_name", ColumnType.STRING),
                c("email", ColumnType.STRING), c("status", ColumnType.STRING),
                c("student_id", ColumnType.STRING), c("teacher_id", ColumnType.STRING),
                c("created_at", ColumnType.TIMESTAMP), c("updated_at", ColumnType.TIMESTAMP)));
        add(definitions, table("roles", LOGIN_READ, ADMIN,
                c("id", ColumnType.STRING), c("code", ColumnType.STRING), c("name", ColumnType.STRING)));
        add(definitions, table("user_roles", LOGIN_READ, ADMIN,
                c("user_id", ColumnType.STRING), c("role_id", ColumnType.STRING)));
        add(definitions, table("permissions", LOGIN_READ, ADMIN,
                c("id", ColumnType.STRING), c("code", ColumnType.STRING), c("name", ColumnType.STRING)));
        add(definitions, table("role_permissions", LOGIN_READ, ADMIN,
                c("role_id", ColumnType.STRING), c("permission_id", ColumnType.STRING)));
        add(definitions, table("user_permissions", LOGIN_READ, ADMIN,
                c("user_id", ColumnType.STRING), c("permission_id", ColumnType.STRING),
                c("granted", ColumnType.BOOLEAN), c("granted_by", ColumnType.STRING),
                c("created_at", ColumnType.TIMESTAMP)));
        add(definitions, table("orgs", ALL_USERS, ADMIN,
                c("id", ColumnType.STRING), c("code", ColumnType.STRING), c("name", ColumnType.STRING),
                c("parent_id", ColumnType.STRING)));
        add(definitions, table("students", ALL_USERS, ADMIN,
                c("id", ColumnType.STRING), c("student_no", ColumnType.STRING),
                c("user_id", ColumnType.STRING), c("name", ColumnType.STRING),
                c("gender", ColumnType.STRING), c("admission_year", ColumnType.INTEGER),
                c("class_name", ColumnType.STRING), c("major", ColumnType.STRING),
                c("status", ColumnType.STRING)));
        add(definitions, table("teachers", ALL_USERS, ADMIN,
                c("id", ColumnType.STRING), c("teacher_no", ColumnType.STRING),
                c("user_id", ColumnType.STRING), c("name", ColumnType.STRING),
                c("title", ColumnType.STRING), c("org_id", ColumnType.STRING),
                c("status", ColumnType.STRING)));
        add(definitions, table("courses", ALL_USERS, ADMIN,
                c("id", ColumnType.STRING), c("course_code", ColumnType.STRING),
                c("name", ColumnType.STRING), c("credit", ColumnType.DECIMAL),
                c("hours", ColumnType.INTEGER), c("org_id", ColumnType.STRING),
                c("status", ColumnType.STRING)));
        add(definitions, table("course_offerings", DOMAIN_ANALYTICS, ADMIN,
                c("id", ColumnType.STRING), c("course_id", ColumnType.STRING),
                c("teacher_id", ColumnType.STRING), c("academic_year", ColumnType.STRING),
                c("semester", ColumnType.INTEGER), c("class_name", ColumnType.STRING),
                c("capacity", ColumnType.INTEGER), c("status", ColumnType.STRING)));
        add(definitions, table("teacher_history_courses", Set.of("TEACHER"), Set.of(),
                c("id", ColumnType.STRING), c("course_id", ColumnType.STRING),
                c("teacher_id", ColumnType.STRING), c("course_code", ColumnType.STRING),
                c("course_name", ColumnType.STRING), c("search_text", ColumnType.STRING),
                c("academic_year", ColumnType.STRING), c("semester", ColumnType.INTEGER),
                c("class_name", ColumnType.STRING)));
        add(definitions, table("enrollments", DOMAIN_ANALYTICS, ADMIN_TEACHER,
                c("id", ColumnType.STRING), c("offering_id", ColumnType.STRING),
                c("student_id", ColumnType.STRING), c("status", ColumnType.STRING),
                c("enrolled_at", ColumnType.TIMESTAMP)));
        add(definitions, table("grading_schemes", ALL_USERS, ADMIN_TEACHER,
                c("id", ColumnType.STRING), c("offering_id", ColumnType.STRING),
                c("name", ColumnType.STRING), c("total_weight", ColumnType.DECIMAL),
                c("version", ColumnType.INTEGER), c("status", ColumnType.STRING)));
        add(definitions, table("grading_weights", ALL_USERS, ADMIN_TEACHER,
                c("id", ColumnType.STRING), c("scheme_id", ColumnType.STRING),
                c("item_code", ColumnType.STRING), c("item_name", ColumnType.STRING),
                c("weight", ColumnType.DECIMAL), c("max_score", ColumnType.DECIMAL),
                c("sort_order", ColumnType.INTEGER)));
        add(definitions, table("grades", DOMAIN_ANALYTICS, ADMIN_TEACHER,
                c("id", ColumnType.STRING), c("enrollment_id", ColumnType.STRING),
                c("scheme_id", ColumnType.STRING), c("score_ciphertext", ColumnType.STRING),
                c("score_nonce", ColumnType.STRING), c("score_integrity", ColumnType.STRING),
                c("key_version", ColumnType.INTEGER), c("status", ColumnType.STRING),
                c("version", ColumnType.INTEGER), c("submitted_by", ColumnType.STRING),
                c("submitted_at", ColumnType.TIMESTAMP), c("updated_at", ColumnType.TIMESTAMP)));
        add(definitions, table("teacher_course_historical_grades", Set.of("TEACHER"), Set.of(),
                c("id", ColumnType.STRING), c("enrollment_id", ColumnType.STRING),
                c("scheme_id", ColumnType.STRING), c("score_ciphertext", ColumnType.STRING),
                c("score_nonce", ColumnType.STRING), c("score_integrity", ColumnType.STRING),
                c("version", ColumnType.INTEGER), c("submitted_at", ColumnType.TIMESTAMP),
                c("updated_at", ColumnType.TIMESTAMP), c("student_id", ColumnType.STRING),
                c("student_no", ColumnType.STRING), c("student_name", ColumnType.STRING),
                c("offering_id", ColumnType.STRING), c("course_id", ColumnType.STRING),
                c("teacher_id", ColumnType.STRING), c("academic_year", ColumnType.STRING),
                c("semester", ColumnType.INTEGER)));
        add(definitions, table("grade_history", Set.of("ADMIN", "AUDITOR", "TEACHER"), Set.of(),
                c("id", ColumnType.STRING), c("grade_id", ColumnType.STRING),
                c("action", ColumnType.STRING), c("original_ciphertext", ColumnType.STRING),
                c("original_nonce", ColumnType.STRING), c("original_integrity", ColumnType.STRING),
                c("reason", ColumnType.STRING), c("scope", ColumnType.STRING),
                c("batch_id", ColumnType.STRING), c("actor_id", ColumnType.STRING),
                c("created_at", ColumnType.TIMESTAMP)));
        add(definitions, table("grade_analyses", ALL_USERS, ADMIN_TEACHER,
                c("id", ColumnType.STRING), c("offering_id", ColumnType.STRING),
                c("average_score", ColumnType.DECIMAL), c("max_score", ColumnType.DECIMAL),
                c("min_score", ColumnType.DECIMAL), c("pass_rate", ColumnType.DECIMAL),
                c("distribution_json", ColumnType.STRING), c("analysis_text", ColumnType.STRING),
                c("generated_at", ColumnType.TIMESTAMP), c("updated_by", ColumnType.STRING),
                c("updated_at", ColumnType.TIMESTAMP)));
        add(definitions, table("audit_logs", ADMIN_AUDIT, Set.of("SYSTEM"),
                c("id", ColumnType.STRING), c("request_id", ColumnType.STRING),
                c("actor", ColumnType.STRING), c("operation", ColumnType.STRING),
                c("table_name", ColumnType.STRING), c("record_key", ColumnType.STRING),
                c("success", ColumnType.BOOLEAN), c("detail", ColumnType.STRING),
                c("created_at", ColumnType.TIMESTAMP)));
        add(definitions, table("alerts", ADMIN_AUDIT, Set.of("ADMIN", "AUDITOR", "SYSTEM"),
                c("id", ColumnType.STRING), c("type", ColumnType.STRING),
                c("severity", ColumnType.STRING), c("message", ColumnType.STRING),
                c("status", ColumnType.STRING), c("related_table", ColumnType.STRING),
                c("related_id", ColumnType.STRING), c("created_at", ColumnType.TIMESTAMP),
                c("resolved_at", ColumnType.TIMESTAMP)));
        add(definitions, table("grade_exceptions", Set.of("ADMIN", "AUDITOR", "TEACHER", "STUDENT"),
                Set.of("ADMIN", "TEACHER", "STUDENT"),
                c("id", ColumnType.STRING), c("enrollment_id", ColumnType.STRING),
                c("type", ColumnType.STRING), c("description", ColumnType.STRING),
                c("status", ColumnType.STRING), c("reported_by", ColumnType.STRING),
                c("handled_by", ColumnType.STRING), c("created_at", ColumnType.TIMESTAMP),
                c("handled_at", ColumnType.TIMESTAMP)));
        add(definitions, table("reversion_requests", ADMIN, ADMIN,
                c("id", ColumnType.STRING), c("request_no", ColumnType.STRING),
                c("scope", ColumnType.STRING), c("target_filter", ColumnType.STRING),
                c("reason", ColumnType.STRING), c("status", ColumnType.STRING),
                c("requested_by", ColumnType.STRING), c("requested_at", ColumnType.TIMESTAMP),
                c("approved_at", ColumnType.TIMESTAMP), c("executed_at", ColumnType.TIMESTAMP)));
        add(definitions, table("high_risk_approvals", ADMIN_AUDIT, ADMIN,
                c("id", ColumnType.STRING), c("reversion_request_id", ColumnType.STRING),
                c("approver", ColumnType.STRING), c("decision", ColumnType.STRING),
                c("comment", ColumnType.STRING), c("created_at", ColumnType.TIMESTAMP)));
        add(definitions, table("idempotency_records", ADMIN_AUDIT, Set.of(),
                c("idempotency_key", ColumnType.STRING), c("request_hash", ColumnType.STRING),
                c("result", ColumnType.BOOLEAN), c("created_at", ColumnType.TIMESTAMP)));
        this.tables = Map.copyOf(definitions);
    }

    public TableDefinition requireTable(String name) {
        TableDefinition table = tables.get(name);
        if (table == null) throw new IllegalArgumentException("Table is not allowed: " + name);
        return table;
    }

    public Map<String, TableDefinition> tables() { return tables; }

    private static void add(Map<String, TableDefinition> target, TableDefinition table) {
        target.put(table.name(), table);
    }

    private static TableDefinition table(String name, Set<String> readRoles, Set<String> writeRoles,
                                         ColumnDefinition... columns) {
        Map<String, ColumnDefinition> columnMap = new LinkedHashMap<>();
        Arrays.stream(columns).forEach(column -> columnMap.put(column.name(), column));
        return new TableDefinition(name, Map.copyOf(columnMap), Set.copyOf(readRoles), Set.copyOf(writeRoles));
    }

    private static ColumnDefinition c(String name, ColumnType type) {
        return new ColumnDefinition(name, type);
    }

    public record ColumnDefinition(String name, ColumnType type) { }

    public record TableDefinition(String name, Map<String, ColumnDefinition> columns,
                                  Set<String> readRoles, Set<String> writeRoles) {
        public ColumnDefinition requireColumn(String name) {
            ColumnDefinition column = columns.get(name);
            if (column == null) throw new IllegalArgumentException("Column is not allowed for " + this.name + ": " + name);
            return column;
        }
    }
}
