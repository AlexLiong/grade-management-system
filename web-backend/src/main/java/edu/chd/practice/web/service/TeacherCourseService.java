package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TeacherCourseService extends RemoteTableSupport {
    private static final Logger log = LoggerFactory.getLogger(TeacherCourseService.class);
    private final ResourceAccessService access;
    private final AuditService auditService;

    public TeacherCourseService(RemoteDataGateway gateway, ResourceAccessService access,
                                AuditService auditService) {
        super(gateway);
        this.access = access;
        this.auditService = auditService;
    }

    public PageResult<GradeDtos.CourseView> courses(String academicYear, Integer semester,
                                                    String className, String keyword,
                                                    int page, int size) {
        UserPrincipal principal = access.requirePermission("COURSE_READ");
        Map<String, String> teacher = requireOne("teachers", List.of("id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, principal.id())),
                "TEACHER_PROFILE_NOT_FOUND", "教师档案不存在");
        List<Filter> filters = new ArrayList<>();
        filters.add(Filter.of("teacher_id", FilterOperator.EQ, teacher.get("id")));
        if (academicYear != null && !academicYear.isBlank()) {
            filters.add(Filter.of("academic_year", FilterOperator.EQ, academicYear));
        }
        if (semester != null) {
            filters.add(Filter.of("semester", FilterOperator.EQ, semester.toString()));
        }
        if (className != null && !className.isBlank()) {
            filters.add(Filter.of("class_name", FilterOperator.LIKE, "%" + className.strip() + "%"));
        }
        List<Map<String, String>> offerings = rows("course_offerings",
                List.of("id", "course_id", "academic_year", "semester", "class_name", "status"),
                filters, List.of(new Sort("academic_year", SortDirection.DESC),
                        new Sort("semester", SortDirection.DESC)), 0, 500);

        String normalizedKeyword = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<GradeDtos.CourseView> result = new ArrayList<>();
        for (Map<String, String> offering : offerings) {
            Map<String, String> course = one("courses",
                    List.of("id", "course_code", "name", "credit"),
                    List.of(Filter.of("id", FilterOperator.EQ, offering.get("course_id"))));
            if (course == null) {
                continue;
            }
            if (!normalizedKeyword.isEmpty()
                    && !course.get("course_code").toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                    && !course.get("name").toLowerCase(Locale.ROOT).contains(normalizedKeyword)) {
                continue;
            }
            long students = count("enrollments",
                    List.of(Filter.of("offering_id", FilterOperator.EQ, offering.get("id"))));
            result.add(new GradeDtos.CourseView(offering.get("id"), course.get("id"),
                    course.get("course_code"), course.get("name"), decimal(course, "credit"),
                    offering.get("academic_year"), integer(offering, "semester"), offering.get("class_name"),
                    offering.get("status"), students));
        }
        int from = Math.min(page * size, result.size());
        int to = Math.min(from + size, result.size());
        return page(result.subList(from, to), page, size, result.size());
    }

    public PageResult<GradeDtos.HistoryCourseView> historyCourses(String keyword, String academicYear,
                                                                  Integer semester, int page, int size) {
        String teacherId = historyTeacherId();
        List<Filter> filters = new ArrayList<>();
        filters.add(Filter.of("teacher_id", FilterOperator.EQ, teacherId));
        if (keyword != null && !keyword.isBlank()) {
            filters.add(Filter.of("search_text", FilterOperator.CONTAINS,
                    keyword.strip().toLowerCase(Locale.ROOT)));
        }
        if (academicYear != null && !academicYear.isBlank()) {
            filters.add(Filter.of("academic_year", FilterOperator.EQ, academicYear.strip()));
        }
        if (semester != null) {
            filters.add(Filter.of("semester", FilterOperator.EQ, semester.toString()));
        }
        long total = count("teacher_history_courses", filters);
        List<GradeDtos.HistoryCourseView> result = rows("teacher_history_courses",
                List.of("id", "course_id", "course_code", "course_name", "academic_year", "semester",
                        "class_name"), filters,
                List.of(new Sort("academic_year", SortDirection.DESC),
                        new Sort("semester", SortDirection.DESC),
                        new Sort("course_code", SortDirection.ASC),
                        new Sort("id", SortDirection.ASC)), page, size).stream()
                .map(this::historyCourseView)
                .toList();
        return page(result, page, size, total);
    }

    public GradeDtos.HistoryCourseView historyCourse(String offeringId) {
        String teacherId = historyTeacherId();
        Map<String, String> row = requireOne("teacher_history_courses",
                List.of("id", "course_id", "course_code", "course_name", "academic_year", "semester",
                        "class_name"),
                List.of(Filter.of("teacher_id", FilterOperator.EQ, teacherId),
                        Filter.of("id", FilterOperator.EQ, offeringId)),
                "HISTORY_COURSE_NOT_FOUND", "历史开课班不存在或当前账号无权查看");
        return historyCourseView(row);
    }

    private String historyTeacherId() {
        UserPrincipal principal = access.requirePermission("GRADE_HISTORY_READ");
        return requireOne("teachers", List.of("id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, principal.id())),
                "TEACHER_PROFILE_NOT_FOUND", "教师档案不存在").get("id");
    }

    private GradeDtos.HistoryCourseView historyCourseView(Map<String, String> row) {
        return new GradeDtos.HistoryCourseView(row.get("id"), row.get("course_id"),
                row.get("course_code"), row.get("course_name"), row.get("academic_year"),
                integer(row, "semester"), row.get("class_name"));
    }

    public GradeDtos.WeightScheme weights(String offeringId) {
        access.requireTeachingOffering(offeringId, "GRADE_READ");
        Map<String, String> scheme = one("grading_schemes",
                List.of("id", "offering_id", "name", "total_weight", "version", "status"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId),
                        Filter.of("status", FilterOperator.NE, "ARCHIVED")));
        if (scheme == null) {
            return null;
        }
        List<GradeDtos.WeightItem> items = rows("grading_weights",
                List.of("id", "item_code", "item_name", "weight", "max_score", "sort_order"),
                List.of(Filter.of("scheme_id", FilterOperator.EQ, scheme.get("id"))),
                List.of(new Sort("sort_order", SortDirection.ASC)), 0, 100).stream()
                .map(this::weight).toList();
        return new GradeDtos.WeightScheme(scheme.get("id"), scheme.get("offering_id"), scheme.get("name"),
                decimal(scheme, "total_weight"), integer(scheme, "version"), scheme.get("status"), items);
    }

    public GradeDtos.WeightScheme saveWeights(String offeringId, GradeDtos.SaveWeightsRequest request) {
        UserPrincipal principal = access.requirePermission("GRADING_SCHEME_WRITE");
        access.requireOpenTeachingOffering(offeringId, "GRADING_SCHEME_WRITE");
        validateWeightItems(request.items());
        BigDecimal total = request.items().stream().map(GradeDtos.WeightItem::weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(new BigDecimal("100")) != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEIGHT_TOTAL_INVALID", "评分权重之和必须等于 100");
        }
        long duplicateCodes = request.items().stream().map(GradeDtos.WeightItem::itemCode).distinct().count();
        if (duplicateCodes != request.items().size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_WEIGHT_ITEM", "评分项编码不能重复");
        }

        Map<String, String> existing = one("grading_schemes", List.of("id", "version", "status"),
                List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId),
                        Filter.of("status", FilterOperator.NE, "ARCHIVED")));
        String schemeId = existing == null ? UUID.randomUUID().toString() : existing.get("id");
        int nextVersion = 1;
        String schemeStatus = "ACTIVE";
        List<MutationCommand> commands = new ArrayList<>();
        if (existing == null) {
            commands.add(new MutationCommand(MutationType.INSERT, "grading_schemes", Map.of(
                    "id", schemeId, "offering_id", offeringId, "name", request.name(),
                    "total_weight", "100", "version", "1", "status", "ACTIVE"), List.of()));
        } else {
            int currentVersion = integer(existing, "version");
            if (request.expectedVersion() != currentVersion) {
                throw new ApiException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_FAILED", "评分方案已被他人修改");
            }
            nextVersion = currentVersion + 1;
            schemeStatus = existing.get("status");
            requireNoSubmittedGrades(schemeId);
            requirePersistedItemCodesRemain(schemeId, request.items());
            commands.add(new MutationCommand(MutationType.UPDATE, "grading_schemes", Map.of(
                    "name", request.name(), "total_weight", "100",
                    "version", Integer.toString(nextVersion)), List.of(
                    Filter.of("id", FilterOperator.EQ, schemeId),
                    Filter.of("version", FilterOperator.EQ, Integer.toString(currentVersion)))));
            commands.add(new MutationCommand(MutationType.DELETE, "grading_weights", Map.of(),
                    List.of(Filter.of("scheme_id", FilterOperator.EQ, schemeId))));
        }
        List<GradeDtos.WeightItem> savedItems = new ArrayList<>();
        for (GradeDtos.WeightItem item : request.items()) {
            String itemId = UUID.randomUUID().toString();
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", itemId);
            values.put("scheme_id", schemeId);
            values.put("item_code", item.itemCode());
            values.put("item_name", item.itemName());
            values.put("weight", item.weight().stripTrailingZeros().toPlainString());
            values.put("max_score", item.maxScore().stripTrailingZeros().toPlainString());
            values.put("sort_order", Integer.toString(item.sortOrder()));
            commands.add(new MutationCommand(MutationType.INSERT, "grading_weights", values, List.of()));
            savedItems.add(new GradeDtos.WeightItem(itemId, item.itemCode(), item.itemName(), item.weight(),
                    item.maxScore(), item.sortOrder()));
        }
        gateway.transaction(new TransactionRequest("weights:" + offeringId + ":" + UUID.randomUUID(), commands));
        try {
            auditService.record("GRADING_WEIGHTS_SAVED", "grading_schemes", schemeId, true,
                    "offering=" + offeringId + "; actor=" + principal.id());
        } catch (RuntimeException exception) {
            log.error("Post-commit grading scheme audit failed for offering {}", offeringId, exception);
        }
        return new GradeDtos.WeightScheme(schemeId, offeringId, request.name(), new BigDecimal("100"),
                nextVersion, schemeStatus, List.copyOf(savedItems));
    }

    private void validateWeightItems(List<GradeDtos.WeightItem> items) {
        if (items == null || items.isEmpty() || items.size() > 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WEIGHT_ITEMS_INVALID", "评分项数量必须为 1 至 20");
        }
        Set<Integer> sortOrders = new java.util.HashSet<>();
        int previousSortOrder = 0;
        for (GradeDtos.WeightItem item : items) {
            if (item == null || item.itemCode() == null || item.itemCode().isBlank()
                    || !item.itemCode().matches("[A-Z][A-Z0-9_]{0,31}")
                    || item.itemName() == null || item.itemName().isBlank()
                    || item.itemName().length() > 64
                    || item.weight() == null || item.weight().compareTo(new BigDecimal("0.01")) < 0
                    || item.weight().compareTo(new BigDecimal("100")) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WEIGHT_ITEM_INVALID", "评分项字段或权重无效");
            }
            if (item.maxScore() == null || item.maxScore().compareTo(new BigDecimal("100")) != 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WEIGHT_MAX_SCORE_INVALID", "评分项满分必须为 100");
            }
            if (item.sortOrder() < 1 || item.sortOrder() > 100
                    || !sortOrders.add(item.sortOrder()) || item.sortOrder() <= previousSortOrder) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "WEIGHT_SORT_ORDER_INVALID",
                        "评分项排序必须为唯一且严格递增的正整数");
            }
            previousSortOrder = item.sortOrder();
        }
    }

    private void requirePersistedItemCodesRemain(String schemeId, List<GradeDtos.WeightItem> requestedItems) {
        if (count("grades", List.of(Filter.of("scheme_id", FilterOperator.EQ, schemeId))) == 0) {
            return;
        }
        Set<String> persistedCodes = rows("grading_weights", List.of("item_code"),
                List.of(Filter.of("scheme_id", FilterOperator.EQ, schemeId)), List.of(), 0, 100).stream()
                .map(row -> row.get("item_code")).collect(Collectors.toSet());
        Set<String> requestedCodes = requestedItems.stream().map(GradeDtos.WeightItem::itemCode)
                .collect(Collectors.toSet());
        if (!requestedCodes.containsAll(persistedCodes)) {
            throw new ApiException(HttpStatus.CONFLICT, "WEIGHT_ITEMS_LOCKED_BY_GRADES",
                    "已有成绩草稿时不能删除评分项或修改评分项编码");
        }
    }

    private void requireNoSubmittedGrades(String schemeId) {
        if (count("grades", List.of(
                Filter.of("scheme_id", FilterOperator.EQ, schemeId),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED"))) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADING_SCHEME_LOCKED_BY_SUBMITTED_GRADES",
                    "已有成绩提交后不能修改评分方案名称、评分项或权重");
        }
    }

    private GradeDtos.WeightItem weight(Map<String, String> row) {
        return new GradeDtos.WeightItem(row.get("id"), row.get("item_code"), row.get("item_name"),
                decimal(row, "weight"), decimal(row, "max_score"), integer(row, "sort_order"));
    }
}
