package edu.chd.practice.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.CanonicalForms;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.RecoveryRestoreRequest;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GradeReversionService extends RemoteTableSupport {
    private final ResourceAccessService access;
    private final AuditService auditService;
    private final GradeCryptoService crypto;
    private final ObjectMapper objectMapper;

    public GradeReversionService(RemoteDataGateway gateway, ResourceAccessService access,
                                 AuditService auditService, GradeCryptoService crypto,
                                 ObjectMapper objectMapper) {
        super(gateway);
        this.access = access;
        this.auditService = auditService;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    public void smallReversion(AdminDtos.SmallReversionRequest request) {
        access.requirePermission("GRADE_REVERT_SMALL");
        access.requirePermission("GRADE_READ");
        String semanticFingerprint = SemanticFingerprint.of("admin.small-reversion",
                request.gradeIds(), request.reason());
        if (gateway.transactionCompleted(request.idempotencyKey(), semanticFingerprint)) {
            return;
        }
        Set<String> ids = new LinkedHashSet<>(request.gradeIds());
        if (ids.size() != request.gradeIds().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_GRADE_ID", "撤销列表包含重复成绩");
        }
        List<Map<String, String>> grades = rows("grades", List.of("id", "status", "version",
                        "score_ciphertext", "score_nonce", "score_integrity"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))), List.of(), 0, 10);
        if (grades.size() != ids.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRADE_NOT_FOUND", "部分成绩不存在");
        }
        List<MutationCommand> commands = new ArrayList<>();
        for (Map<String, String> grade : grades) {
            if (!"SUBMITTED".equals(grade.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "GRADE_STATUS_INVALID", "只有已提交成绩可撤销");
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("status", "DRAFT");
            values.put("version", Integer.toString(integer(grade, "version") + 1));
            values.put("updated_at", Instant.now().toString());
            GradePayload payload = readPayload(grade);
            if (payload.makeupRawScore() != null || payload.makeupStatus() != null) {
                GradePayload reset = resetMakeup(payload);
                GradeCryptoService.ProtectedGrade encrypted = crypto.encrypt(grade.get("id"), writePayload(reset));
                values.put("score_ciphertext", encrypted.ciphertext());
                values.put("score_nonce", encrypted.nonce());
                values.put("score_integrity", encrypted.integrity());
            }
            commands.add(new MutationCommand(MutationType.UPDATE, "grades", values, List.of(
                    Filter.of("id", FilterOperator.EQ, grade.get("id")),
                    Filter.of("version", FilterOperator.EQ, grade.get("version")),
                    Filter.of("status", FilterOperator.EQ, "SUBMITTED")),
                    "ADMIN_SMALL_REVERSION:" + request.reason(), null));
        }
        gateway.transaction(new TransactionRequest(request.idempotencyKey(), semanticFingerprint, commands));
        auditService.record("ADMIN_SMALL_GRADE_REVERSION", "grades", String.join(",", ids), true,
                "reason=" + request.reason());
    }

    public AdminDtos.ReversionView create(AdminDtos.CreateReversionRequest request) {
        UserPrincipal principal = access.requirePermission("GRADE_REVERT_REQUEST");
        if (request.scope() == AdminDtos.ReversionScope.SMALL_BATCH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SMALL_REVERSION_DIRECT_ONLY",
                    "十条以内的小撤销请使用小撤销接口");
        }
        if (request.scope() == AdminDtos.ReversionScope.ORIGINAL_RESTORE) {
            access.requirePermission("GRADE_RESTORE_ORIGINAL");
        }
        String canonicalTarget = canonicalTarget(request.scope(), request.targetFilter());
        String semanticFingerprint = SemanticFingerprint.of("admin.reversion-request", request.scope(),
                canonicalTarget, request.reason());
        String deterministic = SemanticFingerprint.of("admin.reversion-request-id", principal.id(),
                request.idempotencyKey());
        String id = UUID.fromString(deterministic.substring(0, 8) + "-" + deterministic.substring(8, 12)
                + "-" + deterministic.substring(12, 16) + "-" + deterministic.substring(16, 20)
                + "-" + deterministic.substring(20, 32)).toString();
        if (gateway.transactionCompleted(request.idempotencyKey(), semanticFingerprint)) {
            return findInternal(id);
        }
        Instant now = Instant.now();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("request_no", "REV-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC).format(now) + "-" + id.substring(0, 8));
        values.put("scope", "LARGE");
        values.put("target_filter", canonicalTarget);
        values.put("reason", request.reason());
        values.put("status", "PENDING");
        values.put("requested_by", principal.username());
        values.put("requested_at", now.toString());
        gateway.transaction(new TransactionRequest(request.idempotencyKey(), semanticFingerprint, List.of(
                new MutationCommand(MutationType.INSERT, "reversion_requests", values, List.of()))));
        auditService.record("REVERSION_REQUEST_CREATED", "reversion_requests", id, true,
                "scope=" + request.scope());
        return findInternal(id);
    }

    public PageResult<AdminDtos.ReversionView> list(String status, int page, int size) {
        access.requireAnyPermission("GRADE_REVERT_REQUEST", "GRADE_REVERT_APPROVE");
        List<Filter> filters = status == null || status.isBlank() ? List.of()
                : List.of(Filter.of("status", FilterOperator.EQ, status));
        long total = count("reversion_requests", filters);
        List<Map<String, String>> requests = rows("reversion_requests", columns(), filters,
                List.of(new Sort("requested_at", SortDirection.DESC)), page, size);
        Map<String, Map<String, String>> approvals = approvals(values(requests, "id"));
        List<AdminDtos.ReversionView> items = requests.stream()
                .map(request -> view(request, approvals.get(request.get("id")))).toList();
        return page(items, page, size, total);
    }

    public AdminDtos.ReversionView get(String id) {
        access.requireAnyPermission("GRADE_REVERT_REQUEST", "GRADE_REVERT_APPROVE");
        return findInternal(id);
    }

    public PageResult<AdminDtos.AdminGradeView> grades(String courseQuery, String studentQuery,
                                                       String status, int page, int size) {
        access.requirePermission("GRADE_READ");
        String course = normalizeQuery(courseQuery);
        String student = normalizeQuery(studentQuery);
        String normalizedStatus = normalizeQuery(status).toUpperCase(Locale.ROOT);
        if (!normalizedStatus.isEmpty()
                && !Set.of("DRAFT", "SUBMITTED").contains(normalizedStatus)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GRADE_STATUS_INVALID", "成绩状态筛选值无效");
        }

        Set<String> enrollmentIds = matchingEnrollmentIds(course, student);
        if (enrollmentIds != null && enrollmentIds.isEmpty()) {
            return page(List.of(), page, size, 0);
        }
        List<Filter> gradeFilters = new ArrayList<>();
        if (!normalizedStatus.isEmpty()) {
            gradeFilters.add(Filter.of("status", FilterOperator.EQ, normalizedStatus));
        }
        if (enrollmentIds != null) {
            gradeFilters.add(new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)));
        }

        long total = count("grades", gradeFilters);
        List<Map<String, String>> gradeRows = rows("grades",
                List.of("id", "enrollment_id", "status", "version", "submitted_at", "updated_at"),
                gradeFilters, List.of(new Sort("updated_at", SortDirection.DESC)), page, size);
        if (gradeRows.isEmpty()) {
            return page(List.of(), page, size, total);
        }

        Map<String, Map<String, String>> enrollments = byId("enrollments",
                List.of("id", "offering_id", "student_id"), values(gradeRows, "enrollment_id"));
        Map<String, Map<String, String>> students = byId("students",
                List.of("id", "student_no", "name"), values(enrollments.values(), "student_id"));
        Map<String, Map<String, String>> offerings = byId("course_offerings",
                List.of("id", "course_id", "academic_year", "semester", "class_name"),
                values(enrollments.values(), "offering_id"));
        Map<String, Map<String, String>> courses = byId("courses",
                List.of("id", "course_code", "name"), values(offerings.values(), "course_id"));

        List<AdminDtos.AdminGradeView> items = gradeRows.stream().map(grade -> {
            Map<String, String> enrollment = enrollments.get(grade.get("enrollment_id"));
            Map<String, String> studentRow = enrollment == null ? null : students.get(enrollment.get("student_id"));
            Map<String, String> offering = enrollment == null ? null : offerings.get(enrollment.get("offering_id"));
            Map<String, String> courseRow = offering == null ? null : courses.get(offering.get("course_id"));
            return new AdminDtos.AdminGradeView(grade.get("id"), grade.get("enrollment_id"),
                    grade.get("status"), integer(grade, "version"),
                    value(enrollment, "student_id"), value(studentRow, "student_no"), value(studentRow, "name"),
                    value(enrollment, "offering_id"), value(offering, "course_id"),
                    value(courseRow, "course_code"), value(courseRow, "name"),
                    value(offering, "academic_year"), integerOrZero(offering, "semester"),
                    value(offering, "class_name"), instant(grade, "submitted_at"), instant(grade, "updated_at"));
        }).toList();
        return page(items, page, size, total);
    }

    public AdminDtos.ReversionView approve(String id, AdminDtos.ReviewRequest review) {
        UserPrincipal approver = access.requirePermission("GRADE_REVERT_APPROVE");
        Map<String, String> request = request(id);
        if (!Set.of("PENDING", "APPROVED").contains(request.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "REVERSION_ALREADY_REVIEWED", "该申请已完成复核");
        }
        if (approver.username().equals(request.get("requested_by"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN", "发起人不能审批自己的申请");
        }
        requireExecutionPermission(request);
        if ("APPROVED".equals(request.get("status"))) {
            executeApproved(request, id);
            auditService.record("REVERSION_APPROVED_EXECUTION_RETRIED", "reversion_requests", id, true,
                    "actor=" + approver.username());
            return findInternal(id);
        }
        String approvalId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        gateway.transaction(new TransactionRequest("approve-reversion:" + id, List.of(
                new MutationCommand(MutationType.INSERT, "high_risk_approvals", Map.of(
                        "id", approvalId, "reversion_request_id", id, "approver", approver.username(),
                        "decision", "APPROVED", "comment", review.comment(), "created_at", now.toString()),
                        List.of()),
                new MutationCommand(MutationType.UPDATE, "reversion_requests", Map.of(
                        "status", "APPROVED", "approved_at", now.toString()), List.of(
                        Filter.of("id", FilterOperator.EQ, id),
                        Filter.of("status", FilterOperator.EQ, "PENDING")))
        )));
        executeApproved(request, id);
        auditService.record("REVERSION_REQUEST_APPROVED_EXECUTED", "reversion_requests", id, true,
                "approver=" + approver.username());
        return findInternal(id);
    }

    public AdminDtos.ReversionView reject(String id, AdminDtos.ReviewRequest review) {
        UserPrincipal approver = access.requirePermission("GRADE_REVERT_APPROVE");
        Map<String, String> request = request(id);
        if (!"PENDING".equals(request.get("status"))) {
            throw new ApiException(HttpStatus.CONFLICT, "REVERSION_ALREADY_REVIEWED", "该申请已完成复核");
        }
        if (approver.username().equals(request.get("requested_by"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN", "发起人不能复核自己的申请");
        }
        String approvalId = UUID.randomUUID().toString();
        gateway.transaction(new TransactionRequest("reject-reversion:" + id, List.of(
                new MutationCommand(MutationType.INSERT, "high_risk_approvals", Map.of(
                        "id", approvalId, "reversion_request_id", id, "approver", approver.username(),
                        "decision", "REJECTED", "comment", review.comment(),
                        "created_at", Instant.now().toString()), List.of()),
                new MutationCommand(MutationType.UPDATE, "reversion_requests", Map.of("status", "REJECTED"),
                        List.of(Filter.of("id", FilterOperator.EQ, id),
                                Filter.of("status", FilterOperator.EQ, "PENDING")))
        )));
        auditService.record("REVERSION_REQUEST_REJECTED", "reversion_requests", id, true,
                "approver=" + approver.username());
        return findInternal(id);
    }

    private void executeApproved(Map<String, String> request, String approvalId) {
        if (request.get("target_filter").startsWith("RECOVERY:")) {
            long sequence = parseSequence(request.get("target_filter"));
            gateway.restoreGrade(new RecoveryRestoreRequest(sequence, request.get("reason"), approvalId));
            return;
        }
        List<String> gradeIds = parseCanonicalGradeIds(request.get("target_filter"));
        List<Map<String, String>> grades = rows("grades", List.of("id", "status", "version"),
                List.of(new Filter("id", FilterOperator.IN, gradeIds)), List.of(), 0, 500);
        if (grades.size() != gradeIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRADE_NOT_FOUND", "复核目标中部分成绩不存在");
        }
        MutationCommand delete = new MutationCommand(MutationType.DELETE, "grades", Map.of(),
                List.of(new Filter("id", FilterOperator.IN, gradeIds)), request.get("reason"), approvalId);
        gateway.transaction(new TransactionRequest("execute-reversion:" + request.get("id"), List.of(delete)));
    }

    private void requireExecutionPermission(Map<String, String> request) {
        if (request.get("target_filter").startsWith("RECOVERY:")) {
            access.requirePermission("GRADE_RESTORE_ORIGINAL");
        } else {
            access.requirePermission("GRADE_READ");
        }
    }

    private Set<String> matchingEnrollmentIds(String courseQuery, String studentQuery) {
        if (courseQuery.isEmpty() && studentQuery.isEmpty()) {
            return null;
        }
        List<Filter> filters = new ArrayList<>();
        if (!courseQuery.isEmpty()) {
            Set<String> courseIds = matchingIds("courses", List.of("course_code", "name"), courseQuery);
            if (courseIds.isEmpty()) return Set.of();
            long offeringCount = count("course_offerings",
                    List.of(new Filter("course_id", FilterOperator.IN, List.copyOf(courseIds))));
            requireLookupLimit(offeringCount);
            Set<String> offeringIds = values(rows("course_offerings", List.of("id"),
                    List.of(new Filter("course_id", FilterOperator.IN, List.copyOf(courseIds))),
                    List.of(), 0, 500), "id");
            if (offeringIds.isEmpty()) return Set.of();
            filters.add(new Filter("offering_id", FilterOperator.IN, List.copyOf(offeringIds)));
        }
        if (!studentQuery.isEmpty()) {
            Set<String> studentIds = matchingIds("students", List.of("student_no", "name"), studentQuery);
            if (studentIds.isEmpty()) return Set.of();
            filters.add(new Filter("student_id", FilterOperator.IN, List.copyOf(studentIds)));
        }
        long enrollmentCount = count("enrollments", filters);
        requireLookupLimit(enrollmentCount);
        return values(rows("enrollments", List.of("id"), filters, List.of(), 0, 500), "id");
    }

    private Set<String> matchingIds(String table, List<String> searchableColumns, String query) {
        Set<String> ids = new LinkedHashSet<>();
        for (String column : searchableColumns) {
            List<Filter> filters = List.of(Filter.of(column, FilterOperator.LIKE, "%" + query + "%"));
            requireLookupLimit(count(table, filters));
            ids.addAll(values(rows(table, List.of("id"), filters, List.of(), 0, 500), "id"));
            requireLookupLimit(ids.size());
        }
        return ids;
    }

    private void requireLookupLimit(long count) {
        if (count > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GRADE_LOOKUP_TOO_BROAD",
                    "匹配记录超过 500 条，请增加课程或学生筛选条件");
        }
    }

    private Map<String, Map<String, String>> byId(String table, List<String> columns, Set<String> ids) {
        if (ids.isEmpty()) return Map.of();
        return rows(table, columns, List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))),
                List.of(), 0, 500).stream().collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
    }

    private Set<String> values(Iterable<Map<String, String>> source, String column) {
        Set<String> result = new LinkedHashSet<>();
        for (Map<String, String> row : source) {
            String value = row.get(column);
            if (value != null) result.add(value);
        }
        return result;
    }

    private String normalizeQuery(String value) {
        return value == null ? "" : value.strip();
    }

    private String value(Map<String, String> row, String key) {
        return row == null ? null : row.get(key);
    }

    private int integerOrZero(Map<String, String> row, String key) {
        return row == null ? 0 : integer(row, key);
    }

    private String canonicalTarget(AdminDtos.ReversionScope scope, String target) {
        if (scope == AdminDtos.ReversionScope.ORIGINAL_RESTORE) {
            return "RECOVERY:" + parseSequence(target);
        }
        List<String> gradeIds = parseGradeIds(target);
        return CanonicalForms.collection(List.of(new Filter("id", FilterOperator.IN, gradeIds)));
    }

    static List<String> parseGradeIds(String value) {
        if (value == null || !value.startsWith("gradeIds:")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARGET_FILTER_INVALID",
                    "批量目标格式必须为 gradeIds:id1,id2,...");
        }
        List<String> tokens = java.util.Arrays.stream(
                        value.substring("gradeIds:".length()).split(",", -1))
                .map(String::strip).toList();
        if (tokens.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,64}"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARGET_FILTER_INVALID",
                    "成绩目标包含非法编号");
        }
        List<String> ids = tokens.stream().distinct().sorted().toList();
        if (ids.isEmpty() || ids.size() > 500) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARGET_FILTER_INVALID", "成绩目标数量必须为 1 到 500");
        }
        return ids;
    }

    private long parseSequence(String value) {
        try {
            if (value == null) throw new NumberFormatException();
            String prefix = value.startsWith("RECOVERY:") ? "RECOVERY:"
                    : value.startsWith("ledgerSequence:") ? "ledgerSequence:"
                    : value.startsWith("sequence:") ? "sequence:" : null;
            if (prefix == null) throw new NumberFormatException();
            long sequence = Long.parseLong(value.substring(prefix.length()));
            if (sequence <= 0) throw new NumberFormatException();
            return sequence;
        } catch (NumberFormatException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TARGET_FILTER_INVALID",
                    "恢复目标格式必须为 RECOVERY:正整数");
        }
    }

    private Map<String, String> request(String id) {
        return requireOne("reversion_requests", columns(), List.of(Filter.of("id", FilterOperator.EQ, id)),
                "REVERSION_REQUEST_NOT_FOUND", "撤销申请不存在");
    }

    private AdminDtos.ReversionView findInternal(String id) {
        Map<String, String> request = request(id);
        return view(request, approval(id));
    }

    private List<String> columns() {
        return List.of("id", "request_no", "scope", "target_filter", "reason", "status", "requested_by",
                "requested_at", "approved_at", "executed_at");
    }

    private AdminDtos.ReversionView view(Map<String, String> row, Map<String, String> approval) {
        AdminDtos.ReversionScope scope = row.get("target_filter").startsWith("RECOVERY:")
                ? AdminDtos.ReversionScope.ORIGINAL_RESTORE : AdminDtos.ReversionScope.LARGE_BATCH;
        return new AdminDtos.ReversionView(row.get("id"), row.get("request_no"),
                scope, row.get("target_filter"), row.get("reason"),
                row.get("status"), row.get("requested_by"), instant(row, "requested_at"),
                instant(row, "approved_at"), instant(row, "executed_at"),
                value(approval, "approver"), value(approval, "comment"),
                approval == null ? null : instant(approval, "created_at"));
    }

    private Map<String, String> approval(String requestId) {
        List<Map<String, String>> rows = rows("high_risk_approvals",
                List.of("reversion_request_id", "approver", "decision", "comment", "created_at"),
                List.of(Filter.of("reversion_request_id", FilterOperator.EQ, requestId)),
                List.of(new Sort("created_at", SortDirection.DESC)), 0, 1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Map<String, String>> approvals(Set<String> requestIds) {
        if (requestIds.isEmpty()) return Map.of();
        List<Map<String, String>> rows = rows("high_risk_approvals",
                List.of("reversion_request_id", "approver", "decision", "comment", "created_at"),
                List.of(new Filter("reversion_request_id", FilterOperator.IN, List.copyOf(requestIds))),
                List.of(new Sort("created_at", SortDirection.DESC)), 0, 500);
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        for (Map<String, String> approval : rows) {
            result.putIfAbsent(approval.get("reversion_request_id"), approval);
        }
        return result;
    }

    private List<String> parseCanonicalGradeIds(String canonical) {
        try {
            CanonicalCursor outer = new CanonicalCursor(canonical);
            List<String> filters = outer.collection();
            if (filters.size() != 1 || !outer.finished()) throw new IllegalArgumentException();
            CanonicalCursor filter = new CanonicalCursor(filters.get(0));
            if (!"id".equals(filter.value()) || !"IN".equals(filter.value())) throw new IllegalArgumentException();
            List<String> ids = filter.collection();
            if (!filter.finished() || ids.isEmpty()) throw new IllegalArgumentException();
            return ids;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "APPROVED_TARGET_INVALID",
                    "已批准的成绩目标格式无效");
        }
    }

    private GradePayload readPayload(Map<String, String> grade) {
        String plaintext = crypto.decrypt(grade.get("id"), grade.get("score_ciphertext"),
                grade.get("score_nonce"), grade.get("score_integrity"));
        try {
            return objectMapper.readValue(plaintext, GradePayload.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_PAYLOAD_INVALID", "成绩明文结构无效", exception);
        }
    }

    private String writePayload(GradePayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GRADE_SERIALIZATION_FAILED",
                    "成绩数据无法序列化", exception);
        }
    }

    static GradePayload resetMakeup(GradePayload payload) {
        return new GradePayload(payload.componentScores(), payload.regularScore(),
                null, null, payload.regularScore(), null);
    }

    record GradePayload(Map<String, BigDecimal> componentScores, BigDecimal regularScore,
                        BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                        BigDecimal finalScore, String makeupStatus) {
    }

    private static final class CanonicalCursor {
        private final String text;
        private int offset;

        private CanonicalCursor(String text) { this.text = text; }

        String value() {
            int colon = text.indexOf(':', offset);
            if (colon < 0) throw new IllegalArgumentException();
            int length = Integer.parseInt(text.substring(offset, colon));
            offset = colon + 1;
            if (length < 0 || offset + length > text.length()) throw new IllegalArgumentException();
            String value = text.substring(offset, offset + length);
            offset += length;
            return value;
        }

        List<String> collection() {
            int bracket = text.indexOf('[', offset);
            if (bracket < 0) throw new IllegalArgumentException();
            int count = Integer.parseInt(text.substring(offset, bracket));
            offset = bracket + 1;
            List<String> values = new ArrayList<>();
            for (int index = 0; index < count; index++) values.add(value());
            if (offset >= text.length() || text.charAt(offset++) != ']') throw new IllegalArgumentException();
            return values;
        }

        boolean finished() { return offset == text.length(); }
    }
}
