package edu.chd.practice.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class TeacherGradeService extends RemoteTableSupport {
    private static final Logger log = LoggerFactory.getLogger(TeacherGradeService.class);
    private static final List<String> GRADE_COLUMNS = List.of("id", "enrollment_id", "scheme_id",
            "score_ciphertext", "score_nonce", "score_integrity", "key_version", "status", "version",
            "submitted_by", "submitted_at", "updated_at");
    private final ResourceAccessService access;
    private final GradeCryptoService crypto;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public TeacherGradeService(RemoteDataGateway gateway, ResourceAccessService access,
                               GradeCryptoService crypto, ObjectMapper objectMapper,
                               AuditService auditService) {
        super(gateway);
        this.access = access;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public PageResult<GradeDtos.GradeView> gradeSheet(String offeringId, String status,
                                                      int page, int size) {
        access.requireTeachingOffering(offeringId, "GRADE_READ");
        return readGradeSheet(offeringId, status, page, size, false);
    }

    private PageResult<GradeDtos.GradeView> readGradeSheet(String offeringId, String status,
                                                           int page, int size, boolean includeArchivedScheme) {
        Map<String, String> scheme = includeArchivedScheme
                ? schemeForRead(offeringId) : activeScheme(offeringId, null);
        List<Map<String, String>> enrollments = rows("enrollments",
                List.of("id", "student_id", "status"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)),
                List.of(new Sort("enrolled_at", SortDirection.ASC)), 0, 500);
        if (enrollments.isEmpty()) {
            return page(List.of(), page, size, 0);
        }
        Set<String> enrollmentIds = values(enrollments, "id");
        List<Filter> gradeFilters = new ArrayList<>();
        gradeFilters.add(new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)));
        gradeFilters.add(Filter.of("scheme_id", FilterOperator.EQ, scheme.get("id")));
        if (status != null && !status.isBlank()) {
            gradeFilters.add(Filter.of("status", FilterOperator.EQ, status));
        }
        Map<String, Map<String, String>> grades = rows("grades", GRADE_COLUMNS, gradeFilters,
                List.of(), 0, 500).stream().collect(Collectors.toMap(row -> row.get("enrollment_id"),
                Function.identity(), (left, right) -> left));
        Map<String, Map<String, String>> students = students(values(enrollments, "student_id"));

        List<GradeDtos.GradeView> views = new ArrayList<>();
        for (Map<String, String> enrollment : enrollments) {
            Map<String, String> grade = grades.get(enrollment.get("id"));
            if (grade == null && status != null && !status.isBlank()) {
                continue;
            }
            Map<String, String> student = students.get(enrollment.get("student_id"));
            views.add(grade == null ? emptyGrade(enrollment, student, scheme.get("id"))
                    : gradeView(grade, enrollment, student));
        }
        int from = Math.min(page * size, views.size());
        int to = Math.min(from + size, views.size());
        return page(views.subList(from, to), page, size, views.size());
    }

    public List<GradeDtos.GradeView> saveDrafts(String offeringId, GradeDtos.BatchDraftRequest request) {
        UserPrincipal principal = access.requirePermission("GRADE_DRAFT_WRITE");
        access.requirePermission("GRADE_READ");
        access.requireOpenTeachingOffering(offeringId, "GRADE_DRAFT_WRITE");
        String semanticFingerprint = SemanticFingerprint.of("teacher.grade-draft", offeringId,
                request.schemeId(), request.entries());
        if (gateway.transactionCompleted(request.idempotencyKey(), semanticFingerprint)) {
            return replayedDraftViews(offeringId, request);
        }
        Map<String, String> scheme = activeScheme(offeringId, request.schemeId());
        List<WeightRule> rules = weightRules(scheme.get("id"));
        if (rules.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "WEIGHTS_NOT_CONFIGURED", "请先配置评分权重");
        }
        Set<String> requestedEnrollments = request.entries().stream()
                .map(GradeDtos.GradeEntryInput::enrollmentId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestedEnrollments.size() != request.entries().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_ENROLLMENT", "批次中存在重复学生");
        }
        String enrollmentResourceId = ownershipResourceId(offeringId, requestedEnrollments);
        List<Map<String, String>> enrollmentRows = auditedOwnershipQuery("enrollments", enrollmentResourceId,
                () -> rows("enrollments", List.of("id", "student_id"), List.of(
                        Filter.of("offering_id", FilterOperator.EQ, offeringId),
                        new Filter("id", FilterOperator.IN, List.copyOf(requestedEnrollments))),
                        List.of(), 0, 500));
        if (enrollmentRows.size() != requestedEnrollments.size()) {
            auditService.ownershipDenied("enrollments", enrollmentResourceId);
            throw new ApiException(HttpStatus.FORBIDDEN, "ENROLLMENT_OUTSIDE_OFFERING",
                    "批次包含不属于该课程的选课记录");
        }
        Map<String, Map<String, String>> enrollmentById = enrollmentRows.stream()
                .collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
        Map<String, Map<String, String>> studentById = students(values(enrollmentRows, "student_id"));
        Map<String, Map<String, String>> existingByEnrollment = rows("grades", GRADE_COLUMNS, List.of(
                Filter.of("scheme_id", FilterOperator.EQ, scheme.get("id")),
                new Filter("enrollment_id", FilterOperator.IN, List.copyOf(requestedEnrollments))),
                List.of(), 0, 500).stream().collect(Collectors.toMap(row -> row.get("enrollment_id"),
                Function.identity()));
        List<MutationCommand> commands = new ArrayList<>();
        List<GradeDtos.GradeView> savedViews = new ArrayList<>();
        int clearedMakeupDrafts = 0;
        Instant now = Instant.now();
        for (GradeDtos.GradeEntryInput entry : request.entries()) {
            Map<String, String> existing = existingByEnrollment.get(entry.enrollmentId());
            GradePayload existingPayload = existing == null ? null : readPayload(existing);
            if (entry.examType() == GradeDtos.ExamType.REGULAR && existing != null
                    && Set.of("SUBMITTED", "MAKEUP_DRAFT").contains(existing.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "GRADE_ALREADY_SUBMITTED",
                        "已提交成绩必须先撤销后才能修改");
            }
            if (entry.examType() == GradeDtos.ExamType.RETAKE) {
                if (existingPayload == null || !"SUBMITTED".equals(existing.get("status"))) {
                    throw new ApiException(HttpStatus.CONFLICT, "REGULAR_GRADE_NOT_SUBMITTED",
                            "只有已提交且未及格的正考成绩才能录入补考");
                }
                if (existingPayload.regularScore().compareTo(BigDecimal.valueOf(60)) >= 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_NOT_ALLOWED",
                            "正考已及格，不允许录入补考成绩");
                }
                if (existingPayload.makeupStatus() != null
                        && !"DRAFT".equals(existingPayload.makeupStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_ALREADY_SUBMITTED",
                            "已提交补考成绩必须先撤销后才能修改");
                }
            }
            int currentVersion = existing == null ? 0 : integer(existing, "version");
            if (currentVersion != entry.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_FAILED",
                        "成绩已被他人修改，请刷新后重试");
            }
            GradePayload payload = calculate(entry, rules, existingPayload);
            String gradeId = existing == null ? UUID.randomUUID().toString() : existing.get("id");
            GradeCryptoService.ProtectedGrade protectedGrade = crypto.encrypt(gradeId, writePayload(payload));
            Map<String, String> values = new LinkedHashMap<>();
            values.put("score_ciphertext", protectedGrade.ciphertext());
            values.put("score_nonce", protectedGrade.nonce());
            values.put("score_integrity", protectedGrade.integrity());
            values.put("status", entry.examType() == GradeDtos.ExamType.RETAKE ? existing.get("status") : "DRAFT");
            values.put("version", Integer.toString(currentVersion + 1));
            values.put("updated_at", now.toString());
            if (existing == null) {
                values.put("id", gradeId);
                values.put("enrollment_id", entry.enrollmentId());
                values.put("scheme_id", scheme.get("id"));
                values.put("key_version", "1");
                commands.add(new MutationCommand(MutationType.INSERT, "grades", values, List.of(),
                        "教师批量暂存", null));
            } else {
                boolean clearingMakeupDraft = entry.examType() == GradeDtos.ExamType.RETAKE
                        && entry.makeupRawScore() == null;
                String mutationReason = clearingMakeupDraft ? "MAKEUP_CLEAR"
                        : entry.examType() == GradeDtos.ExamType.RETAKE ? "MAKEUP_DRAFT" : "REGULAR_DRAFT";
                if (clearingMakeupDraft) {
                    clearedMakeupDrafts++;
                }
                commands.add(new MutationCommand(MutationType.UPDATE, "grades", values, List.of(
                        Filter.of("id", FilterOperator.EQ, gradeId),
                        Filter.of("version", FilterOperator.EQ, Integer.toString(currentVersion)),
                        Filter.of("status", FilterOperator.EQ, existing.get("status"))),
                        mutationReason, null));
            }
            Map<String, String> enrollment = enrollmentById.get(entry.enrollmentId());
            Map<String, String> student = studentById.get(enrollment.get("student_id"));
            savedViews.add(draftView(gradeId, enrollment, student, scheme.get("id"), payload,
                    values.get("status"), currentVersion + 1,
                    existing == null ? null : instant(existing, "submitted_at"), now));
        }
        gateway.transaction(new TransactionRequest(request.idempotencyKey(), semanticFingerprint, commands));
        recordPostCommitAudit("GRADE_BATCH_DRAFT", "grades", offeringId,
                "count=" + request.entries().size() + "; makeupCleared=" + clearedMakeupDrafts);
        return List.copyOf(savedViews);
    }

    public void submit(String offeringId, GradeDtos.BatchActionRequest request) {
        boolean changed = changeStatus(offeringId, request, "GRADE_SUBMIT", "SUBMITTED", "SUBMITTED");
        if (!changed) {
            return;
        }
        try {
            detectAnomalies(offeringId, request.gradeIds());
        } catch (RuntimeException exception) {
            log.error("Post-commit anomaly detection failed for offering {} and grades {}",
                    offeringId, request.gradeIds(), exception);
        }
    }

    public void withdraw(String offeringId, GradeDtos.BatchActionRequest request) {
        changeStatus(offeringId, request, "GRADE_WITHDRAW", "DRAFT", "TEACHER_WITHDRAW");
    }

    public GradeDtos.Statistics statistics(String offeringId) {
        access.requireTeachingOffering(offeringId, "GRADE_ANALYTICS_READ");
        String savedAnalysis = savedAnalysis(offeringId);
        List<BigDecimal> scores = submittedObservations(offeringId).stream()
                .map(GradeObservation::finalScore).sorted().toList();
        if (scores.isEmpty()) {
            return new GradeDtos.Statistics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, emptyDistribution(),
                    "当前没有已提交成绩，暂不能形成统计结论。", savedAnalysis);
        }
        BigDecimal count = BigDecimal.valueOf(scores.size());
        BigDecimal average = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(count, 2, RoundingMode.HALF_UP);
        BigDecimal variance = scores.stream().map(score -> score.subtract(average).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add).divide(count, 8, RoundingMode.HALF_UP);
        BigDecimal stddev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal median = scores.size() % 2 == 1 ? scores.get(scores.size() / 2)
                : scores.get(scores.size() / 2 - 1).add(scores.get(scores.size() / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        long passed = scores.stream().filter(score -> score.compareTo(BigDecimal.valueOf(60)) >= 0).count();
        BigDecimal passRate = BigDecimal.valueOf(passed * 100.0 / scores.size())
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Long> distribution = distribution(scores);
        String narrative = "共 " + scores.size() + " 名学生，平均分 " + average + "，及格率 " + passRate
                + "%；标准差 " + stddev + (stddev.compareTo(BigDecimal.valueOf(15)) > 0
                ? "，成绩离散程度较高，建议复核极端分数。" : "，成绩分布相对稳定。");
        return new GradeDtos.Statistics(scores.size(), average, scores.get(scores.size() - 1), scores.get(0),
                median, passRate, stddev, distribution, narrative, savedAnalysis);
    }

    public GradeDtos.SavedAnalysis saveAnalysis(String offeringId, GradeDtos.AnalysisNoteRequest request) {
        UserPrincipal principal = access.requirePermission("GRADE_ANALYTICS_READ");
        access.requireOpenTeachingOffering(offeringId, "GRADE_ANALYTICS_READ");
        GradeDtos.Statistics statistics = statistics(offeringId);
        Map<String, String> existing = one("grade_analyses", List.of("id"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)));
        String id = existing == null ? UUID.randomUUID().toString() : existing.get("id");
        Instant now = Instant.now();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("offering_id", offeringId);
        values.put("average_score", statistics.average().toPlainString());
        values.put("max_score", statistics.maximum().toPlainString());
        values.put("min_score", statistics.minimum().toPlainString());
        values.put("pass_rate", statistics.passRate().toPlainString());
        try {
            values.put("distribution_json", objectMapper.writeValueAsString(statistics.distribution()));
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ANALYSIS_SERIALIZATION_FAILED",
                    "统计分布无法序列化", exception);
        }
        values.put("analysis_text", request.analysis());
        values.put("generated_at", now.toString());
        values.put("updated_by", principal.username());
        values.put("updated_at", now.toString());
        if (existing == null) {
            values.put("id", id);
            gateway.execute(new MutationCommand(MutationType.INSERT, "grade_analyses", values, List.of()));
        } else {
            gateway.execute(new MutationCommand(MutationType.UPDATE, "grade_analyses", values,
                    List.of(Filter.of("id", FilterOperator.EQ, id))));
        }
        recordPostCommitAudit("GRADE_ANALYSIS_SAVED", "grade_analyses", id,
                "offering=" + offeringId);
        return new GradeDtos.SavedAnalysis(id, offeringId, withSavedAnalysis(statistics, request.analysis()),
                request.analysis(), principal.username(), now);
    }

    private String savedAnalysis(String offeringId) {
        List<Map<String, String>> analyses = rows("grade_analyses", List.of("analysis_text"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)),
                List.of(new Sort("updated_at", SortDirection.DESC)), 0, 1);
        return analyses.isEmpty() ? null : analyses.get(0).get("analysis_text");
    }

    private GradeDtos.Statistics withSavedAnalysis(GradeDtos.Statistics statistics, String analysis) {
        return new GradeDtos.Statistics(statistics.count(), statistics.average(), statistics.maximum(),
                statistics.minimum(), statistics.median(), statistics.passRate(), statistics.standardDeviation(),
                statistics.distribution(), statistics.narrative(), analysis);
    }

    public PageResult<GradeDtos.CourseHistoricalGrade> courseHistory(String courseId, String academicYear,
                                                                     int page, int size) {
        UserPrincipal principal = access.requirePermission("GRADE_HISTORY_READ");
        Map<String, String> teacher = requireOne("teachers", List.of("id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, principal.id())),
                "TEACHER_PROFILE_NOT_FOUND", "教师档案不存在");
        List<Filter> filters = new ArrayList<>();
        filters.add(Filter.of("course_id", FilterOperator.EQ, courseId));
        filters.add(Filter.of("teacher_id", FilterOperator.EQ, teacher.get("id")));
        if (academicYear != null && !academicYear.isBlank()) {
            filters.add(Filter.of("academic_year", FilterOperator.EQ, academicYear));
        }
        long total = count("teacher_course_historical_grades", filters);
        List<GradeDtos.CourseHistoricalGrade> result = rows("teacher_course_historical_grades",
                List.of("id", "score_ciphertext", "score_nonce", "score_integrity", "student_id",
                        "student_no", "student_name", "offering_id", "academic_year", "semester"),
                filters, List.of(new Sort("academic_year", SortDirection.DESC),
                        new Sort("semester", SortDirection.DESC),
                        new Sort("offering_id", SortDirection.ASC),
                        new Sort("student_no", SortDirection.ASC),
                        new Sort("id", SortDirection.ASC)), page, size).stream().map(row -> {
                    GradePayload payload = readPayload(row);
                    BigDecimal visibleFinal = "SUBMITTED".equals(payload.makeupStatus())
                            ? payload.finalScore() : payload.regularScore();
                    return new GradeDtos.CourseHistoricalGrade(row.get("academic_year"),
                            integer(row, "semester"), row.get("offering_id"), row.get("student_id"),
                            row.get("student_no"), row.get("student_name"), payload.regularScore(),
                            payload.makeupRawScore(), payload.makeupEffectiveScore(), visibleFinal);
                }).toList();
        return page(result, page, size, total);
    }

    public PageResult<GradeDtos.GradeHistoryView> history(String offeringId, String gradeId,
                                                          int page, int size) {
        access.requireClosedTeachingOffering(offeringId, "GRADE_HISTORY_READ");
        String requestedGradeId = gradeId == null ? "" : gradeId.strip();
        Set<String> offeringGradeIds = auditedOwnershipQuery("grades",
                requestedGradeId.isEmpty() ? offeringId : requestedGradeId,
                () -> submittedGradeIds(offeringId));
        if (!requestedGradeId.isEmpty() && !offeringGradeIds.contains(requestedGradeId)) {
            auditService.ownershipDenied("grades", requestedGradeId);
            throw new ApiException(HttpStatus.FORBIDDEN, "GRADE_OUTSIDE_OFFERING",
                    "成绩不属于当前已结课授课班或尚未提交");
        }
        Set<String> gradeIds = requestedGradeId.isEmpty() ? offeringGradeIds : Set.of(requestedGradeId);
        if (gradeIds.isEmpty()) {
            return page(List.of(), page, size, 0);
        }
        List<Filter> filters = List.of(new Filter("grade_id", FilterOperator.IN, List.copyOf(gradeIds)));
        long total = count("grade_history", filters);
        List<GradeDtos.GradeHistoryView> items = rows("grade_history", List.of("id", "grade_id", "action",
                        "reason", "scope", "batch_id", "actor_id", "created_at"), filters,
                List.of(new Sort("created_at", SortDirection.DESC)), page, size).stream()
                .map(row -> new GradeDtos.GradeHistoryView(row.get("id"), row.get("grade_id"), row.get("action"),
                        row.get("reason"), row.get("scope"), row.get("batch_id"), row.get("actor_id"),
                        instant(row, "created_at"))).toList();
        return page(items, page, size, total);
    }

    public List<GradeObservation> submittedObservations(String offeringId) {
        return submittedObservations(offeringId, false);
    }

    private List<GradeObservation> submittedObservations(String offeringId, boolean includeArchivedScheme) {
        Map<String, String> scheme = includeArchivedScheme ? schemeForRead(offeringId) : activeScheme(offeringId, null);
        Set<String> enrollmentIds = values(rows("enrollments", List.of("id"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)), List.of(), 0, 500), "id");
        if (enrollmentIds.isEmpty()) {
            return List.of();
        }
        return rows("grades", GRADE_COLUMNS, List.of(
                new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)),
                Filter.of("scheme_id", FilterOperator.EQ, scheme.get("id")),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), List.of(), 0, 500).stream()
                .map(row -> {
                    GradePayload payload = readPayload(row);
                    BigDecimal visibleFinal = "SUBMITTED".equals(payload.makeupStatus())
                            ? payload.finalScore() : payload.regularScore();
                    return new GradeObservation(row.get("id"), row.get("enrollment_id"), payload.regularScore(),
                            payload.makeupRawScore(), payload.makeupEffectiveScore(), payload.finalScore(),
                            visibleFinal, payload.componentScores(), payload.makeupStatus());
                }).toList();
    }

    private boolean changeStatus(String offeringId, GradeDtos.BatchActionRequest request, String permission,
                                 String targetStatus, String historyAction) {
        UserPrincipal principal = access.requirePermission(permission);
        access.requireOpenTeachingOffering(offeringId, permission);
        String semanticFingerprint = SemanticFingerprint.of("teacher.grade-action", offeringId,
                targetStatus, request.examType(), request.gradeIds(), request.reason());
        if (gateway.transactionCompleted(request.idempotencyKey(), semanticFingerprint)) {
            return false;
        }
        String gradeResourceId = ownershipResourceId(offeringId, request.gradeIds());
        List<Map<String, String>> grades = auditedOwnershipQuery("grades", gradeResourceId,
                () -> rows("grades", GRADE_COLUMNS,
                        List.of(new Filter("id", FilterOperator.IN, request.gradeIds())),
                        List.of(), 0, 500));
        if (grades.size() != new LinkedHashSet<>(request.gradeIds()).size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRADE_NOT_FOUND", "部分成绩记录不存在");
        }
        List<MutationCommand> commands = new ArrayList<>();
        Instant now = Instant.now();
        for (Map<String, String> grade : grades) {
            requireEnrollmentInOffering(grade.get("enrollment_id"), offeringId);
            String currentStatus = grade.get("status");
            GradePayload payload = readPayload(grade);
            boolean submitAction = "SUBMITTED".equals(targetStatus);
            boolean makeupAction = request.examType() == GradeDtos.ExamType.RETAKE;
            String nextStatus;
            GradePayload nextPayload = null;
            String mutationReason;
            if (submitAction && !makeupAction) {
                if (!"DRAFT".equals(currentStatus) || payload.regularScore() == null) {
                    throw new ApiException(HttpStatus.CONFLICT, "REGULAR_GRADE_INCOMPLETE",
                            "只有评分项完整的正考草稿可提交");
                }
                nextStatus = "SUBMITTED";
                mutationReason = request.reason();
            } else if (submitAction) {
                if (!"SUBMITTED".equals(currentStatus) || !"DRAFT".equals(payload.makeupStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_STATUS_INVALID", "只有补考草稿可提交");
                }
                nextStatus = "SUBMITTED";
                nextPayload = new GradePayload(payload.componentScores(), payload.regularScore(),
                        payload.makeupRawScore(), payload.makeupEffectiveScore(), payload.finalScore(), "SUBMITTED");
                mutationReason = "MAKEUP_SUBMIT";
            } else if (makeupAction) {
                if (!"SUBMITTED".equals(currentStatus) || !"SUBMITTED".equals(payload.makeupStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_STATUS_INVALID", "只有已提交补考可撤销");
                }
                nextStatus = "SUBMITTED";
                nextPayload = withdrawMakeup(payload);
                mutationReason = "MAKEUP_WITHDRAW";
            } else {
                if (!"SUBMITTED".equals(currentStatus)) {
                    throw new ApiException(HttpStatus.CONFLICT, "GRADE_STATUS_INVALID", "只有已提交正考可撤销");
                }
                if ("SUBMITTED".equals(payload.makeupStatus())) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_MUST_BE_WITHDRAWN_FIRST",
                            "请先撤销补考，再撤销正考");
                }
                nextStatus = "DRAFT";
                if (payload.makeupRawScore() != null || payload.makeupStatus() != null) {
                    nextPayload = clearMakeup(payload);
                }
                mutationReason = "REGULAR_WITHDRAW:" + request.reason();
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put("status", nextStatus);
            values.put("version", Integer.toString(integer(grade, "version") + 1));
            values.put("updated_at", now.toString());
            if (nextPayload != null) {
                GradeCryptoService.ProtectedGrade encrypted = crypto.encrypt(grade.get("id"),
                        writePayload(nextPayload));
                values.put("score_ciphertext", encrypted.ciphertext());
                values.put("score_nonce", encrypted.nonce());
                values.put("score_integrity", encrypted.integrity());
            }
            if (submitAction) {
                values.put("submitted_by", principal.id());
                values.put("submitted_at", now.toString());
            }
            commands.add(new MutationCommand(MutationType.UPDATE, "grades", values, List.of(
                    Filter.of("id", FilterOperator.EQ, grade.get("id")),
                    Filter.of("version", FilterOperator.EQ, grade.get("version")),
                    Filter.of("status", FilterOperator.EQ, currentStatus)),
                    mutationReason, null));
        }
        gateway.transaction(new TransactionRequest(request.idempotencyKey(), semanticFingerprint, commands));
        recordPostCommitAudit("GRADE_BATCH_" + historyAction, "grades", offeringId,
                "count=" + grades.size() + "; reason=" + request.reason());
        return true;
    }

    private List<GradeDtos.GradeView> replayedDraftViews(String offeringId,
                                                         GradeDtos.BatchDraftRequest request) {
        Map<String, GradeDtos.GradeView> byEnrollment = readGradeSheet(offeringId, null, 0, 500, false)
                .items().stream().filter(view -> view.id() != null)
                .collect(Collectors.toMap(GradeDtos.GradeView::enrollmentId, Function.identity()));
        List<GradeDtos.GradeView> replay = new ArrayList<>();
        for (GradeDtos.GradeEntryInput entry : request.entries()) {
            GradeDtos.GradeView view = byEnrollment.get(entry.enrollmentId());
            if (view == null || view.version() <= entry.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENT_RESULT_UNAVAILABLE",
                        "幂等事务已提交，但无法读取对应的成绩结果");
            }
            replay.add(view);
        }
        return List.copyOf(replay);
    }

    private Map<String, String> activeScheme(String offeringId, String requestedSchemeId) {
        List<Filter> filters = new ArrayList<>();
        filters.add(Filter.of("offering_id", FilterOperator.EQ, offeringId));
        filters.add(Filter.of("status", FilterOperator.NE, "ARCHIVED"));
        if (requestedSchemeId != null) {
            filters.add(Filter.of("id", FilterOperator.EQ, requestedSchemeId));
        }
        return requireOne("grading_schemes", List.of("id", "offering_id", "version"), filters,
                "GRADING_SCHEME_NOT_FOUND", "课程评分方案不存在");
    }

    private Map<String, String> schemeForRead(String offeringId) {
        List<Map<String, String>> schemes = rows("grading_schemes",
                List.of("id", "offering_id", "version"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)),
                List.of(new Sort("version", SortDirection.DESC)), 0, 1);
        if (schemes.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRADING_SCHEME_NOT_FOUND", "课程评分方案不存在");
        }
        return schemes.get(0);
    }

    private List<WeightRule> weightRules(String schemeId) {
        return rows("grading_weights", List.of("item_code", "weight", "max_score"),
                List.of(Filter.of("scheme_id", FilterOperator.EQ, schemeId)),
                List.of(new Sort("sort_order", SortDirection.ASC)), 0, 100).stream()
                .map(row -> new WeightRule(row.get("item_code"), decimal(row, "weight"),
                        decimal(row, "max_score"))).toList();
    }

    static GradePayload calculate(GradeDtos.GradeEntryInput entry, List<WeightRule> rules,
                                  GradePayload existing) {
        if (entry.examType() == GradeDtos.ExamType.RETAKE) {
            if (entry.makeupRawScore() == null) {
                if (existing == null || !"DRAFT".equals(existing.makeupStatus())
                        || existing.makeupRawScore() == null) {
                    throw new ApiException(HttpStatus.CONFLICT, "MAKEUP_DRAFT_CLEAR_INVALID",
                            "只有已暂存且未提交的补考成绩可清空");
                }
                return clearMakeupDraft(existing);
            }
            BigDecimal raw = entry.makeupRawScore().setScale(2, RoundingMode.HALF_UP);
            BigDecimal effective = GradePolicy.makeupEffective(existing.regularScore(), raw);
            return new GradePayload(existing.componentScores(), existing.regularScore(), raw, effective,
                    effective, "DRAFT");
        }
        Map<String, BigDecimal> supplied = entry.componentScores() == null ? Map.of() : entry.componentScores();
        Set<String> expected = rules.stream().map(WeightRule::code).collect(Collectors.toSet());
        if (!expected.containsAll(supplied.keySet())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GRADE_COMPONENT_MISMATCH",
                    "成绩包含评分方案之外的分项");
        }
        Map<String, BigDecimal> merged = new LinkedHashMap<>();
        if (existing != null && existing.componentScores() != null) {
            merged.putAll(existing.componentScores());
        }
        merged.putAll(supplied);
        if (!expected.containsAll(merged.keySet())) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_COMPONENT_MISMATCH",
                    "已有草稿与当前评分方案不一致，请刷新后重试");
        }
        BigDecimal raw = BigDecimal.ZERO;
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        for (WeightRule rule : rules) {
            BigDecimal score = merged.get(rule.code());
            if (score == null) {
                continue;
            }
            if (score.compareTo(rule.maximum()) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMPONENT_SCORE_EXCEEDED",
                        rule.code() + " 超过该评分项满分");
            }
            normalized.put(rule.code(), score.setScale(2, RoundingMode.HALF_UP));
            raw = raw.add(score.divide(rule.maximum(), 10, RoundingMode.HALF_UP).multiply(rule.weight()));
        }
        BigDecimal calculated = normalized.keySet().equals(expected)
                ? raw.setScale(2, RoundingMode.HALF_UP) : null;
        return new GradePayload(normalized, calculated, null, null, calculated, null);
    }

    static GradePayload withdrawMakeup(GradePayload payload) {
        return new GradePayload(payload.componentScores(), payload.regularScore(),
                payload.makeupRawScore(), payload.makeupEffectiveScore(), payload.finalScore(), "DRAFT");
    }

    static GradePayload clearMakeup(GradePayload payload) {
        return new GradePayload(payload.componentScores(), payload.regularScore(),
                null, null, payload.regularScore(), null);
    }

    static GradePayload clearMakeupDraft(GradePayload payload) {
        return new GradePayload(payload.componentScores(), payload.regularScore(),
                null, null, payload.regularScore(), "DRAFT");
    }

    private GradeDtos.GradeView gradeView(Map<String, String> grade, Map<String, String> enrollment,
                                          Map<String, String> student) {
        GradePayload payload = readPayload(grade);
        GradeDtos.ExamType displayType = payload.makeupRawScore() == null
                ? GradeDtos.ExamType.REGULAR : GradeDtos.ExamType.RETAKE;
        BigDecimal visibleFinal = "SUBMITTED".equals(payload.makeupStatus())
                ? payload.finalScore() : payload.regularScore();
        return new GradeDtos.GradeView(grade.get("id"), grade.get("enrollment_id"),
                enrollment.get("student_id"), student == null ? null : student.get("student_no"),
                student == null ? null : student.get("name"), grade.get("scheme_id"), payload.componentScores(),
                payload.regularScore(), payload.makeupRawScore(), payload.makeupEffectiveScore(),
                visibleFinal, visibleFinal, displayType, payload.makeupRawScore() != null
                && payload.makeupRawScore().compareTo(BigDecimal.valueOf(60)) > 0, payload.makeupStatus(),
                grade.get("status"),
                integer(grade, "version"), instant(grade, "submitted_at"), instant(grade, "updated_at"));
    }

    private GradeDtos.GradeView draftView(String gradeId, Map<String, String> enrollment,
                                          Map<String, String> student, String schemeId,
                                          GradePayload payload, String status, int version,
                                          Instant submittedAt, Instant updatedAt) {
        GradeDtos.ExamType displayType = payload.makeupRawScore() == null
                ? GradeDtos.ExamType.REGULAR : GradeDtos.ExamType.RETAKE;
        BigDecimal visibleFinal = "SUBMITTED".equals(payload.makeupStatus())
                ? payload.finalScore() : payload.regularScore();
        return new GradeDtos.GradeView(gradeId, enrollment.get("id"), enrollment.get("student_id"),
                student == null ? null : student.get("student_no"), student == null ? null : student.get("name"),
                schemeId, payload.componentScores(), payload.regularScore(), payload.makeupRawScore(),
                payload.makeupEffectiveScore(), visibleFinal, visibleFinal, displayType,
                payload.makeupRawScore() != null
                        && payload.makeupRawScore().compareTo(BigDecimal.valueOf(60)) > 0,
                payload.makeupStatus(), status, version, submittedAt, updatedAt);
    }

    private GradeDtos.GradeView emptyGrade(Map<String, String> enrollment, Map<String, String> student,
                                           String schemeId) {
        return new GradeDtos.GradeView(null, enrollment.get("id"), enrollment.get("student_id"),
                student == null ? null : student.get("student_no"), student == null ? null : student.get("name"),
                schemeId, Map.of(), null, null, null, null, null, GradeDtos.ExamType.REGULAR,
                false, null, "NOT_GRADED", 0, null, null);
    }

    private Map<String, Map<String, String>> students(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rows("students", List.of("id", "student_no", "name"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))), List.of(), 0, 500).stream()
                .collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
    }

    private void requireGradeInOffering(String gradeId, String offeringId) {
        Map<String, String> grade = auditedOwnershipQuery("grades", gradeId,
                () -> requireOne("grades", List.of("enrollment_id"),
                        List.of(Filter.of("id", FilterOperator.EQ, gradeId)),
                        "GRADE_NOT_FOUND", "成绩不存在"));
        requireEnrollmentInOffering(grade.get("enrollment_id"), offeringId);
    }

    private void requireEnrollmentInOffering(String enrollmentId, String offeringId) {
        Map<String, String> enrollment = auditedOwnershipQuery("grades", enrollmentId,
                () -> one("enrollments", List.of("id"), List.of(
                        Filter.of("id", FilterOperator.EQ, enrollmentId),
                        Filter.of("offering_id", FilterOperator.EQ, offeringId))));
        if (enrollment == null) {
            auditService.ownershipDenied("grades", enrollmentId);
            throw new ApiException(HttpStatus.FORBIDDEN, "GRADE_OUTSIDE_OFFERING",
                    "成绩不属于当前授课课程");
        }
    }

    private <T> T auditedOwnershipQuery(String resourceType, String resourceId, Supplier<T> query) {
        try {
            return query.get();
        } catch (ApiException exception) {
            if (ResourceAccessService.isRmiOwnershipDenial(exception)) {
                auditService.ownershipDenied(resourceType, resourceId);
            }
            throw exception;
        }
    }

    private String ownershipResourceId(String offeringId, Collection<String> requestedIds) {
        if (requestedIds.size() == 1) {
            return requestedIds.iterator().next();
        }
        return offeringId + ":batch:" + requestedIds.size();
    }

    private Set<String> submittedGradeIds(String offeringId) {
        Set<String> enrollmentIds = values(rows("enrollments", List.of("id"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)), List.of(), 0, 500), "id");
        if (enrollmentIds.isEmpty()) {
            return Set.of();
        }
        return values(rows("grades", List.of("id"),
                List.of(new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)),
                        Filter.of("status", FilterOperator.EQ, "SUBMITTED")),
                List.of(), 0, 500), "id");
    }

    private Set<String> values(List<Map<String, String>> rows, String column) {
        return rows.stream().map(row -> row.get(column)).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    private void detectAnomalies(String offeringId, List<String> submittedIds) {
        List<GradeObservation> observations = submittedObservations(offeringId);
        if (observations.isEmpty()) {
            return;
        }
        detectStudentHistoryFluctuations(offeringId, observations, submittedIds);
        List<BigDecimal> sorted = observations.stream().map(GradeObservation::finalScore).sorted().toList();
        double mean = sorted.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double stddev = Math.sqrt(sorted.stream().mapToDouble(score -> Math.pow(score.doubleValue() - mean, 2))
                .average().orElse(0));
        BigDecimal p05 = sorted.get((int) Math.floor((sorted.size() - 1) * 0.05));
        BigDecimal p95 = sorted.get((int) Math.ceil((sorted.size() - 1) * 0.95));
        Double historicalMean = historicalClassMean(offeringId);
        if (historicalMean != null && significantClassMeanShift(mean, historicalMean)) {
            alert("CLASS_MEAN_HISTORY_SHIFT", "HIGH", "course_offerings", offeringId,
                    "当前班平均成绩较同课程历史班平均值偏移至少 10 分");
        }
        for (GradeObservation observation : observations) {
            if (!submittedIds.contains(observation.gradeId())) {
                continue;
            }
            if (stddev > 0 && Math.abs(observation.finalScore().doubleValue() - mean) > 3 * stddev) {
                alert("GRADE_THREE_SIGMA", "HIGH", observation.gradeId(), "成绩超出均值正负三倍标准差");
            }
            if (sorted.size() >= 20 && (observation.finalScore().compareTo(p05) <= 0
                    || observation.finalScore().compareTo(p95) >= 0)) {
                alert("GRADE_PERCENTILE_EXTREME", "MEDIUM", observation.gradeId(),
                        "成绩位于课程分布的极端百分位");
            }
            if ("SUBMITTED".equals(observation.makeupStatus()) && observation.makeupRawScore() != null
                    && observation.makeupRawScore().compareTo(BigDecimal.valueOf(60)) > 0) {
                alert("RETAKE_CAP_APPLIED", "LOW", observation.gradeId(),
                        "补考原始计算分超过 60，系统已按规则封顶");
            }
            MakeupScoreAnomaly makeupAnomaly = makeupScoreAnomaly(observation.makeupRawScore());
            if ("SUBMITTED".equals(observation.makeupStatus()) && makeupAnomaly == MakeupScoreAnomaly.LOW) {
                alert("RETAKE_SCORE_UNUSUALLY_LOW", "MEDIUM", observation.gradeId(),
                        "补考卷面分低于 30，建议复核录入与试卷");
            } else if ("SUBMITTED".equals(observation.makeupStatus())
                    && makeupAnomaly == MakeupScoreAnomaly.HIGH) {
                alert("RETAKE_SCORE_UNUSUALLY_HIGH", "MEDIUM", observation.gradeId(),
                        "补考卷面分高于 90，建议复核录入与试卷");
            }
            historyFluctuation(observation);
        }
    }

    void detectStudentHistoryFluctuations(String offeringId, List<GradeObservation> observations,
                                          Collection<String> submittedIds) {
        Set<String> enrollmentIds = observations.stream().map(GradeObservation::enrollmentId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (enrollmentIds.isEmpty()) {
            return;
        }
        Map<String, String> studentByEnrollment = rows("enrollments", List.of("id", "student_id"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(enrollmentIds))),
                List.of(), 0, 500).stream().collect(Collectors.toMap(row -> row.get("id"),
                row -> row.get("student_id"), (left, right) -> left));
        Set<String> studentIds = new LinkedHashSet<>(studentByEnrollment.values());
        if (studentIds.isEmpty()) {
            return;
        }
        Map<String, String> currentOffering = one("course_offerings", List.of("teacher_id"),
                List.of(Filter.of("id", FilterOperator.EQ, offeringId)));
        if (currentOffering == null) {
            return;
        }
        Set<String> historicalOfferingIds = values(rows("course_offerings", List.of("id"), List.of(
                Filter.of("teacher_id", FilterOperator.EQ, currentOffering.get("teacher_id")),
                Filter.of("status", FilterOperator.EQ, "CLOSED")),
                List.of(new Sort("academic_year", SortDirection.DESC)), 0, 500), "id");
        if (historicalOfferingIds.isEmpty()) {
            return;
        }
        List<Map<String, String>> historicalEnrollments = rows("enrollments", List.of("id", "student_id"),
                List.of(new Filter("offering_id", FilterOperator.IN, List.copyOf(historicalOfferingIds)),
                        new Filter("student_id", FilterOperator.IN, List.copyOf(studentIds))),
                List.of(), 0, 500);
        if (historicalEnrollments.isEmpty()) {
            return;
        }
        Map<String, String> historicalStudentByEnrollment = historicalEnrollments.stream()
                .collect(Collectors.toMap(row -> row.get("id"), row -> row.get("student_id"),
                        (left, right) -> left));
        Set<String> historicalEnrollmentIds = historicalStudentByEnrollment.keySet();
        Map<String, List<BigDecimal>> historicalScores = new HashMap<>();
        rows("grades", GRADE_COLUMNS, List.of(
                new Filter("enrollment_id", FilterOperator.IN, List.copyOf(historicalEnrollmentIds)),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), List.of(), 0, 500).forEach(row -> {
            GradePayload payload = readPayload(row);
            BigDecimal visibleFinal = "SUBMITTED".equals(payload.makeupStatus())
                    ? payload.finalScore() : payload.regularScore();
            String studentId = historicalStudentByEnrollment.get(row.get("enrollment_id"));
            if (studentId != null && visibleFinal != null) {
                historicalScores.computeIfAbsent(studentId, ignored -> new ArrayList<>()).add(visibleFinal);
            }
        });
        for (GradeObservation observation : observations) {
            if (!submittedIds.contains(observation.gradeId()) || observation.finalScore() == null) {
                continue;
            }
            String studentId = studentByEnrollment.get(observation.enrollmentId());
            if (historicalScores.getOrDefault(studentId, List.of()).stream()
                    .anyMatch(score -> significantHistoryFluctuation(score, observation.finalScore()))) {
                alert("STUDENT_HISTORY_FLUCTUATION", "HIGH", observation.gradeId(),
                        "本次成绩与该教师可授权查看的学生历史成绩相差超过 20 分");
            }
        }
    }

    private Double historicalClassMean(String offeringId) {
        Map<String, String> current = one("course_offerings", List.of("course_id", "teacher_id"),
                List.of(Filter.of("id", FilterOperator.EQ, offeringId)));
        if (current == null) {
            return null;
        }
        List<Map<String, String>> historical = rows("course_offerings", List.of("id"), List.of(
                Filter.of("course_id", FilterOperator.EQ, current.get("course_id")),
                Filter.of("teacher_id", FilterOperator.EQ, current.get("teacher_id")),
                Filter.of("status", FilterOperator.EQ, "CLOSED")), List.of(), 0, 100);
        return historical.stream().map(row -> submittedObservations(row.get("id"), true))
                .filter(grades -> !grades.isEmpty())
                .mapToDouble(grades -> grades.stream().mapToDouble(
                        grade -> grade.finalScore().doubleValue()).average().orElse(0))
                .average().stream().boxed().findFirst().orElse(null);
    }

    static boolean significantClassMeanShift(double currentMean, double historicalMean) {
        return Double.isFinite(currentMean) && Double.isFinite(historicalMean)
                && Math.abs(currentMean - historicalMean) >= 10.0;
    }

    static MakeupScoreAnomaly makeupScoreAnomaly(BigDecimal rawScore) {
        if (rawScore == null) {
            return MakeupScoreAnomaly.NONE;
        }
        if (rawScore.compareTo(BigDecimal.valueOf(30)) < 0) {
            return MakeupScoreAnomaly.LOW;
        }
        return rawScore.compareTo(BigDecimal.valueOf(90)) > 0
                ? MakeupScoreAnomaly.HIGH : MakeupScoreAnomaly.NONE;
    }

    void historyFluctuation(GradeObservation observation) {
        if (observation.finalScore() == null) {
            return;
        }
        List<Map<String, String>> histories = rows("grade_history", List.of("original_ciphertext",
                        "original_nonce", "original_integrity"),
                List.of(Filter.of("grade_id", FilterOperator.EQ, observation.gradeId())),
                List.of(new Sort("created_at", SortDirection.DESC),
                        new Sort("id", SortDirection.DESC)), 0, 100);
        try {
            for (Map<String, String> history : histories) {
                if (history.get("original_ciphertext") == null || history.get("original_nonce") == null
                        || history.get("original_integrity") == null) {
                    continue;
                }
                GradePayload previous = objectMapper.readValue(crypto.decrypt(observation.gradeId(),
                        history.get("original_ciphertext"), history.get("original_nonce"),
                        history.get("original_integrity")), GradePayload.class);
                if (previous.finalScore() == null
                        || previous.finalScore().compareTo(observation.finalScore()) == 0) {
                    continue;
                }
                if (significantHistoryFluctuation(previous.finalScore(), observation.finalScore())) {
                    alert("GRADE_HISTORY_FLUCTUATION", "HIGH", observation.gradeId(),
                            "本次成绩与上一不同历史版本相差超过 20 分");
                }
                return;
            }
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_HISTORY_INVALID", "历史成绩结构无效", exception);
        }
    }

    static boolean significantHistoryFluctuation(BigDecimal previousScore, BigDecimal currentScore) {
        return previousScore != null && currentScore != null
                && previousScore.subtract(currentScore).abs().compareTo(BigDecimal.valueOf(20)) > 0;
    }

    private void alert(String type, String severity, String gradeId, String message) {
        alert(type, severity, "grades", gradeId, message);
    }

    private void alert(String type, String severity, String relatedTable, String relatedId, String message) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID().toString());
        values.put("type", type);
        values.put("severity", severity);
        values.put("message", message);
        values.put("status", "OPEN");
        values.put("related_table", relatedTable);
        values.put("related_id", relatedId);
        values.put("created_at", Instant.now().toString());
        gateway.executeAsSystem(new MutationCommand(MutationType.INSERT, "alerts", values, List.of()));
    }

    private void recordPostCommitAudit(String operation, String table, String recordKey, String detail) {
        try {
            auditService.record(operation, table, recordKey, true, detail);
        } catch (RuntimeException exception) {
            log.error("Post-commit audit failed for operation {} on {}:{}", operation, table, recordKey,
                    exception);
        }
    }

    private Map<String, Long> distribution(List<BigDecimal> scores) {
        Map<String, Long> result = emptyDistribution();
        for (BigDecimal score : scores) {
            String band = score.compareTo(BigDecimal.valueOf(90)) >= 0 ? "90-100"
                    : score.compareTo(BigDecimal.valueOf(80)) >= 0 ? "80-89"
                    : score.compareTo(BigDecimal.valueOf(70)) >= 0 ? "70-79"
                    : score.compareTo(BigDecimal.valueOf(60)) >= 0 ? "60-69" : "0-59";
            result.put(band, result.get(band) + 1);
        }
        return Map.copyOf(result);
    }

    private Map<String, Long> emptyDistribution() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("90-100", 0L);
        result.put("80-89", 0L);
        result.put("70-79", 0L);
        result.put("60-69", 0L);
        result.put("0-59", 0L);
        return result;
    }

    record WeightRule(String code, BigDecimal weight, BigDecimal maximum) {
    }

    record GradePayload(Map<String, BigDecimal> componentScores, BigDecimal regularScore,
                        BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                        BigDecimal finalScore, String makeupStatus) {
    }

    enum MakeupScoreAnomaly { NONE, LOW, HIGH }

    public record GradeObservation(String gradeId, String enrollmentId, BigDecimal regularScore,
                                   BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                                   BigDecimal storedFinalScore, BigDecimal finalScore,
                                   Map<String, BigDecimal> componentScores, String makeupStatus) {
    }
}
