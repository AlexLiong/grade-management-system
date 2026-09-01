package edu.chd.practice.rmi.server.security;

import edu.chd.practice.rmi.contract.RemoteServiceException;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.InvocationContext;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.server.sql.SchemaRegistry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class AuthorizationService {
    private static final Pattern SAFE_RESOURCE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final List<String> GATEWAY_USER_COLUMNS = List.of(
            "id", "username", "password_hash", "display_name", "status", "student_id", "teacher_id");
    private static final Set<String> INTERACTIVE_ROLES = Set.of("ADMIN", "TEACHER", "STUDENT");
    private static final Set<String> STUDENT_REFERENCE_TABLES = Set.of(
            "orgs", "teachers", "courses", "course_offerings", "grading_schemes",
            "grading_weights", "grade_analyses");
    private static final Map<String, Set<String>> ANALYTICS_COLUMNS = Map.of(
            "course_offerings", Set.of("id", "course_id", "teacher_id", "academic_year"),
            "enrollments", Set.of("id", "offering_id"),
            "grades", Set.of("id", "enrollment_id", "score_ciphertext", "score_nonce",
                    "score_integrity", "status"));

    private final JdbcTemplate jdbc;
    private final SchemaRegistry registry;

    public AuthorizationService(JdbcTemplate jdbc, SchemaRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    public Set<String> authorizeRead(SelectRequest request, InvocationContext context)
            throws RemoteServiceException {
        SchemaRegistry.TableDefinition table = requireTable(request.getTable());
        Set<String> roles = effectiveRoles(context);
        if (roles.contains("GATEWAY")) {
            authorizeGatewayRead(request);
            return roles;
        }
        requireIntersection(roles, table.readRoles(), "read", table.name());
        if (Set.of("teacher_history_courses", "teacher_course_historical_grades")
                .contains(request.getTable())) {
            requirePermission(context, "GRADE_HISTORY_READ");
            requireTeacherOwnedOfferings(request.getFilters(), identity(context.getPrincipal()).teacherId());
            return roles;
        }
        if (roles.contains("ADMIN")) {
            authorizeAdminRead(request, context);
            return roles;
        }
        if (roles.contains("AUDITOR")) return roles;
        if (roles.contains("ANALYTICS")) {
            authorizeAnalyticsRead(request);
            return roles;
        }
        if (roles.contains("STUDENT")) authorizeStudentRead(request, context);
        if (roles.contains("TEACHER")) authorizeTeacherRead(request, context);
        return roles;
    }

    public Set<String> authorizeWrite(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        SchemaRegistry.TableDefinition table = requireTable(command.getTable());
        Set<String> roles = effectiveRoles(context);
        requireIntersection(roles, table.writeRoles(), "write", table.name());
        if (roles.contains("SYSTEM")) {
            if (command.getType() != MutationType.INSERT
                    || !("audit_logs".equals(command.getTable()) || "alerts".equals(command.getTable()))) {
                throw denied("SYSTEM may only insert audit or alert records");
            }
            return roles;
        }
        if (roles.contains("ADMIN")) {
            authorizeAdminWrite(command, context);
            return roles;
        }
        if (roles.contains("TEACHER")) authorizeTeacherWrite(command, context);
        if (roles.contains("STUDENT")) authorizeStudentWrite(command, context);
        return roles;
    }

    public void requireIntegrityAccess(InvocationContext context) throws RemoteServiceException {
        Set<String> roles = effectiveRoles(context);
        if (!roles.contains("ADMIN") && !roles.contains("AUDITOR")) {
            throw denied("Integrity evidence requires ADMIN or AUDITOR");
        }
    }

    public void requirePermission(InvocationContext context, String permission) throws RemoteServiceException {
        if (!hasPermission(context, permission)) throw denied("Missing permission: " + permission);
    }

    public void requireAnyPermission(InvocationContext context, String... permissions) throws RemoteServiceException {
        for (String permission : permissions) {
            if (hasPermission(context, permission)) return;
        }
        throw denied("Missing every required alternative permission");
    }

    private boolean hasPermission(InvocationContext context, String permission) throws RemoteServiceException {
        effectiveRoles(context);
        Boolean explicit = jdbc.query("""
                SELECT up.granted FROM user_permissions up JOIN users u ON u.id=up.user_id
                JOIN permissions p ON p.id=up.permission_id
                WHERE u.username=? AND p.code=? AND u.status='ACTIVE'
                """, resultSet -> resultSet.next() ? resultSet.getBoolean(1) : null,
                context.getPrincipal(), permission);
        if (explicit != null) {
            return explicit;
        }
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id
                JOIN role_permissions rp ON rp.role_id=ur.role_id
                JOIN permissions p ON p.id=rp.permission_id
                WHERE u.username=? AND u.status='ACTIVE' AND p.code=?
                """, Long.class, context.getPrincipal(), permission);
        return count != null && count > 0;
    }

    public Set<String> effectiveRoles(InvocationContext context) throws RemoteServiceException {
        List<String> databaseRoles = jdbc.query("""
                SELECT r.code FROM roles r
                JOIN user_roles ur ON ur.role_id = r.id
                JOIN users u ON u.id = ur.user_id
                WHERE u.username = ? AND u.status = 'ACTIVE'
                """, (rs, row) -> normalize(rs.getString(1)), context.getPrincipal());
        Set<String> asserted = new HashSet<>();
        context.getRoles().forEach(role -> asserted.add(normalize(role)));
        databaseRoles.removeIf(role -> !asserted.contains(role));
        if (databaseRoles.isEmpty()) throw denied("No active database role matches the signed invocation roles");
        return Set.copyOf(databaseRoles);
    }

    private void authorizeStudentRead(SelectRequest request, InvocationContext context)
            throws RemoteServiceException {
        requirePermission(context, "GRADE_SELF_READ");
        if (STUDENT_REFERENCE_TABLES.contains(request.getTable())) return;
        Identity identity = identity(context.getPrincipal());
        switch (request.getTable()) {
            case "students" -> requireEitherOwnedFilter(request.getFilters(), "id", identity.studentId(),
                    "user_id", identity.userId());
            case "enrollments" -> requireOwnedFilter(request.getFilters(), "student_id", identity.studentId());
            case "grades" -> requireStudentOwnedGrades(request.getFilters(), identity.studentId());
            case "grade_exceptions" -> requireStudentOwnedEnrollments(request.getFilters(), identity.studentId());
            default -> throw denied("Students cannot read " + request.getTable());
        }
    }

    private void authorizeAnalyticsRead(SelectRequest request) throws RemoteServiceException {
        Set<String> allowed = ANALYTICS_COLUMNS.get(request.getTable());
        if (allowed == null || request.getColumns().isEmpty()
                || request.getColumns().stream().anyMatch(column -> !allowed.contains(column))) {
            throw denied("ANALYTICS requested a table or column outside its narrow projection");
        }
        String requiredFilter = switch (request.getTable()) {
            case "course_offerings" -> "course_id";
            case "enrollments" -> "offering_id";
            case "grades" -> "enrollment_id";
            default -> throw denied("ANALYTICS table is not allowed");
        };
        if (ids(request.getFilters(), requiredFilter).isEmpty()) {
            throw denied("ANALYTICS request lacks required chain filter " + requiredFilter);
        }
    }

    private void authorizeGatewayRead(SelectRequest request) throws RemoteServiceException {
        switch (request.getTable()) {
            case "users" -> {
                requireGatewayColumns(request, GATEWAY_USER_COLUMNS);
                requireGatewayFilter(request, Set.of("id", "username"), FilterOperator.EQ, 1);
                requireGatewayWindow(request, 1);
            }
            case "user_roles" -> {
                requireGatewayColumns(request, List.of("role_id"));
                requireGatewayFilter(request, Set.of("user_id"), FilterOperator.EQ, 1);
                requireGatewayWindow(request, 100);
            }
            case "roles" -> {
                if (request.getColumns().equals(List.of("id", "code"))) {
                    requireGatewayFilter(request, Set.of("id"), FilterOperator.IN, 100);
                } else if (request.getColumns().equals(List.of("id"))) {
                    Filter filter = requireGatewayFilter(request, Set.of("code"), FilterOperator.IN, 3);
                    if (filter.getValues().stream().map(AuthorizationService::normalize)
                            .anyMatch(role -> !INTERACTIVE_ROLES.contains(role))) {
                        throw denied("GATEWAY may resolve permissions only for interactive roles");
                    }
                } else {
                    throw denied("GATEWAY requested columns outside the authentication projection for roles");
                }
                requireGatewayWindow(request, 100);
            }
            case "role_permissions" -> {
                requireGatewayColumns(request, List.of("permission_id"));
                requireGatewayFilter(request, Set.of("role_id"), FilterOperator.IN, 100);
                requireGatewayWindow(request, 500);
            }
            case "permissions" -> {
                if (!request.getColumns().equals(List.of("code"))
                        && !request.getColumns().equals(List.of("id", "code"))) {
                    throw denied("GATEWAY requested columns outside the authentication projection for permissions");
                }
                requireGatewayFilter(request, Set.of("id"), FilterOperator.IN, 500);
                requireGatewayWindow(request, 500);
            }
            case "user_permissions" -> {
                requireGatewayColumns(request, List.of("permission_id", "granted"));
                requireGatewayFilter(request, Set.of("user_id"), FilterOperator.EQ, 1);
                requireGatewayWindow(request, 500);
            }
            case "teachers" -> {
                requireGatewayColumns(request, List.of("org_id"));
                requireGatewayFilter(request, Set.of("id"), FilterOperator.EQ, 1);
                requireGatewayWindow(request, 1);
            }
            default -> throw denied("GATEWAY may only read authentication metadata");
        }
    }

    private static void requireGatewayColumns(SelectRequest request, List<String> expected)
            throws RemoteServiceException {
        if (!request.getColumns().equals(expected)) {
            throw denied("GATEWAY requested columns outside the authentication projection for "
                    + request.getTable());
        }
    }

    private static Filter requireGatewayFilter(SelectRequest request, Set<String> columns,
                                               FilterOperator operator, int maxValues)
            throws RemoteServiceException {
        if (request.getFilters().size() != 1) {
            throw denied("GATEWAY authentication reads require exactly one identifying filter");
        }
        Filter filter = request.getFilters().get(0);
        if (!columns.contains(filter.getColumn()) || filter.getOperator() != operator
                || filter.getValues().isEmpty() || filter.getValues().size() > maxValues
                || filter.getValues().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw denied("GATEWAY authentication read has an invalid identifying filter");
        }
        return filter;
    }

    private static void requireGatewayWindow(SelectRequest request, int maxPageSize)
            throws RemoteServiceException {
        if (request.getPage() != 0 || request.getPageSize() < 1 || request.getPageSize() > maxPageSize
                || !request.getSorts().isEmpty()) {
            throw denied("GATEWAY authentication read has an invalid query window");
        }
    }

    private void authorizeTeacherRead(SelectRequest request, InvocationContext context)
            throws RemoteServiceException {
        Identity identity = identity(context.getPrincipal());
        switch (request.getTable()) {
            case "courses" -> {
                if (hasPermission(context, "COURSE_READ")) {
                    return;
                }
                requirePermission(context, "GRADE_HISTORY_READ");
                requireTeacherOwnedClosedCourses(request.getFilters(), identity.teacherId());
            }
            case "grading_weights" -> requirePermission(context, "COURSE_READ");
            case "teachers", "course_offerings", "grading_schemes" ->
                    requireTeacherReadOrClosedHistory(request, context, identity.teacherId(), "COURSE_READ");
            case "students", "enrollments", "grades" ->
                    requireTeacherReadOrClosedHistory(request, context, identity.teacherId(), "GRADE_READ");
            case "grade_exceptions" -> requirePermission(context, "GRADE_READ");
            case "grade_analyses" -> requirePermission(context, "GRADE_ANALYTICS_READ");
            case "grade_history" -> requirePermission(context, "GRADE_HISTORY_READ");
            default -> { }
        }
        switch (request.getTable()) {
            case "teachers" -> requireEitherOwnedFilter(request.getFilters(), "id", identity.teacherId(),
                    "user_id", identity.userId());
            case "students" -> requireTeacherOwnedStudents(request.getFilters(), identity.teacherId());
            case "course_offerings" -> requireTeacherOwnedOfferings(request.getFilters(), identity.teacherId());
            case "enrollments" -> requireTeacherOwnedOfferings(request.getFilters(), identity.teacherId());
            case "grades" -> requireTeacherOwnedGrades(request.getFilters(), identity.teacherId());
            case "grading_schemes" -> requireTeacherOwnedSchemes(request.getFilters(), identity.teacherId());
            case "grade_analyses" -> requireTeacherOwnedAnalyses(request.getFilters(), identity.teacherId());
            case "grading_weights" -> requireTeacherOwnedSchemes(request.getFilters(), identity.teacherId());
            case "grade_history" -> requireTeacherOwnedHistory(request.getFilters(), identity.teacherId());
            case "grade_exceptions" -> requireTeacherOwnedExceptions(request.getFilters(), identity.teacherId());
            default -> { /* Reference and administrative table permissions are still controlled above. */ }
        }
    }

    private void requireTeacherReadOrClosedHistory(SelectRequest request, InvocationContext context,
                                                   String teacherId, String regularPermission)
            throws RemoteServiceException {
        if (hasPermission(context, regularPermission)) {
            return;
        }
        requirePermission(context, "GRADE_HISTORY_READ");
        switch (request.getTable()) {
            case "teachers" -> { /* The ownership filter below still limits this to the caller's profile. */ }
            case "course_offerings" -> requireFilterValue(request.getFilters(), "status", "CLOSED");
            case "grading_schemes", "enrollments" -> {
                List<String> offeringIds = ids(request.getFilters(), "offering_id");
                if (offeringIds.isEmpty()
                        || !offeringIds.stream().allMatch(id -> isClosedTeacherOffering(id, teacherId))) {
                    throw denied("Historical request must be constrained to the teacher's closed offerings");
                }
            }
            case "grades" -> {
                List<String> enrollmentIds = ids(request.getFilters(), "enrollment_id");
                if (!hasFilterValue(request.getFilters(), "status", "SUBMITTED")
                        || enrollmentIds.isEmpty()
                        || !enrollmentIds.stream().allMatch(id -> isClosedTeacherEnrollment(id, teacherId))) {
                    throw denied("Historical grade request must target submitted grades from closed offerings");
                }
            }
            case "students" -> {
                List<String> studentIds = ids(request.getFilters(), "id");
                if (studentIds.isEmpty() || !studentIds.stream().allMatch(studentId -> jdbc.queryForObject("""
                        SELECT COUNT(*) FROM enrollments e JOIN course_offerings o ON o.id=e.offering_id
                        WHERE e.student_id=? AND o.teacher_id=? AND o.status='CLOSED'
                        """, Long.class, studentId, teacherId) > 0L)) {
                    throw denied("Historical student request must be constrained to a closed owned offering");
                }
            }
            default -> throw denied("Historical permission cannot read " + request.getTable());
        }
    }

    private void authorizeTeacherWrite(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        String teacherId = identity(context.getPrincipal()).teacherId();
        switch (command.getTable()) {
            case "grades" -> {
                if (command.getType() == MutationType.INSERT) {
                    String enrollmentId = requireSafeId(command.getValues().get("enrollment_id"));
                    String schemeId = requireSafeId(command.getValues().get("scheme_id"));
                    if (!isTeacherGradeRelation(enrollmentId, schemeId, teacherId)) {
                        throw denied("Grade enrollment and scheme must belong to the same teacher offering");
                    }
                    if (!"DRAFT".equals(command.getValues().getOrDefault("status", "DRAFT"))) {
                        throw denied("Teachers must create grades as DRAFT");
                    }
                    requirePermission(context, "GRADE_DRAFT_WRITE");
                } else {
                    requireTeacherOwnedGrades(command.getFilters(), teacherId);
                    String nextStatus = command.getValues().get("status");
                    boolean makeupUpdate = isMakeupReason(command.getReason());
                    boolean regularWithdrawal = isRegularWithdrawalReason(command.getReason());
                    boolean withdrawal = command.getType() == MutationType.UPDATE
                            && "DRAFT".equals(nextStatus)
                            && hasFilterValue(command.getFilters(), "status", "SUBMITTED");
                    if (makeupUpdate) {
                        requireMakeupEnvelope(command, context);
                    } else if (regularWithdrawal) {
                        requireRegularWithdrawalEnvelope(command, context);
                    } else if (withdrawal) {
                        throw denied("Regular withdrawal requires the dedicated withdrawal envelope");
                    } else {
                        if (command.getType() != MutationType.UPDATE
                                || !hasExactlyOneEqualityFilter(command.getFilters(), "id", null)
                                || !hasExactlyOneEqualityFilter(command.getFilters(), "version", null)
                                || !hasExactlyOneEqualityFilter(command.getFilters(), "status", "DRAFT")) {
                            throw denied("Draft grade updates require one id, version, and DRAFT filter");
                        }
                        requireOptimisticVersionAdvance(command);
                        requirePermission(context, "SUBMITTED".equals(nextStatus)
                                ? "GRADE_SUBMIT" : "GRADE_DRAFT_WRITE");
                    }
                    if (nextStatus != null && !Set.of("DRAFT", "SUBMITTED").contains(nextStatus)) {
                        throw denied("Teachers may only edit or submit DRAFT grades");
                    }
                }
            }
            case "enrollments" -> {
                if (command.getType() == MutationType.INSERT) {
                    requireValueOwned(command, "offering_id", id -> isTeacherOffering(id, teacherId));
                } else requireTeacherOwnedOfferings(command.getFilters(), teacherId);
            }
            case "grading_schemes" -> {
                requirePermission(context, "GRADING_SCHEME_WRITE");
                if (command.getType() == MutationType.INSERT) {
                    requireValueOwned(command, "offering_id", id -> isTeacherOffering(id, teacherId));
                } else requireTeacherOwnedSchemes(command.getFilters(), teacherId);
                requireSchemeIsMutable(command);
            }
            case "grade_analyses" -> {
                requirePermission(context, "GRADE_ANALYTICS_READ");
                if (command.getType() == MutationType.INSERT) {
                    requireValueOwned(command, "offering_id", id -> isTeacherOffering(id, teacherId));
                } else requireTeacherOwnedAnalyses(command.getFilters(), teacherId);
            }
            case "grading_weights" -> {
                requirePermission(context, "GRADING_SCHEME_WRITE");
                if (command.getType() == MutationType.INSERT) {
                    requireValueOwned(command, "scheme_id", id -> isTeacherScheme(id, teacherId));
                } else requireTeacherOwnedSchemes(command.getFilters(), teacherId);
                requireWeightsAreMutable(command);
            }
            case "grade_exceptions" -> {
                requirePermission(context, "GRADE_READ");
                if (command.getType() == MutationType.INSERT) {
                    requireValueOwned(command, "enrollment_id", id -> isTeacherEnrollment(id, teacherId));
                } else {
                    requireTeacherOwnedExceptions(command.getFilters(), teacherId);
                    String replacementEnrollment = command.getValues().get("enrollment_id");
                    if (replacementEnrollment != null && !isTeacherEnrollment(
                            requireSafeId(replacementEnrollment), teacherId)) {
                        throw denied("Grade exception cannot be moved outside the teacher's offering");
                    }
                }
            }
            default -> throw denied("Teachers cannot write " + command.getTable());
        }
    }

    private void authorizeAdminRead(SelectRequest request, InvocationContext context)
            throws RemoteServiceException {
        switch (request.getTable()) {
            case "users", "teachers", "user_roles" ->
                    requirePermission(context, "USER_MANAGE");
            case "students" -> requireAnyPermission(context, "USER_MANAGE", "GRADE_READ");
            case "roles" -> requireAnyPermission(context, "USER_MANAGE", "PERMISSION_MANAGE");
            case "permissions", "role_permissions", "user_permissions" ->
                    requirePermission(context, "PERMISSION_MANAGE");
            case "orgs" -> requireAnyPermission(context, "USER_MANAGE", "ORG_MANAGE");
            case "courses", "course_offerings" ->
                    requireAnyPermission(context, "COURSE_READ", "GRADE_READ");
            case "grading_schemes", "grading_weights" ->
                    requirePermission(context, "COURSE_READ");
            case "enrollments", "grades", "grade_exceptions" ->
                    requirePermission(context, "GRADE_READ");
            case "grade_analyses" -> requirePermission(context, "GRADE_ANALYTICS_READ");
            case "audit_logs" -> requirePermission(context, "AUDIT_READ");
            case "alerts" -> requireAnyPermission(context, "AUDIT_READ", "ALERT_MANAGE");
            case "grade_history" -> requirePermission(context, "GRADE_HISTORY_READ");
            case "reversion_requests" -> requireAnyPermission(context,
                    "GRADE_REVERT_REQUEST", "GRADE_REVERT_APPROVE");
            case "high_risk_approvals" -> requireAnyPermission(context,
                    "GRADE_REVERT_REQUEST", "GRADE_REVERT_APPROVE", "AUDIT_READ");
            case "idempotency_records" -> requirePermission(context, "AUDIT_READ");
            default -> { /* Domain table role checks are sufficient for ordinary reads. */ }
        }
    }

    private void authorizeAdminWrite(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        switch (command.getTable()) {
            case "users" -> {
                requirePermission(context, "USER_MANAGE");
                if (userWriteNeedsPermissionManagement(command)) {
                    requirePermission(context, "PERMISSION_MANAGE");
                }
                return;
            }
            case "students", "teachers" -> {
                requirePermission(context, "USER_MANAGE");
                return;
            }
            case "user_roles" -> {
                requirePermission(context, "PERMISSION_MANAGE");
                requireExclusiveHumanRole(command);
                return;
            }
            case "roles", "permissions", "role_permissions", "user_permissions" -> {
                requirePermission(context, "PERMISSION_MANAGE");
                return;
            }
            case "orgs" -> {
                requirePermission(context, "ORG_MANAGE");
                return;
            }
            case "courses", "course_offerings" -> {
                requirePermission(context, "ORG_MANAGE");
                return;
            }
            case "enrollments" -> {
                requirePermission(context, "USER_MANAGE");
                return;
            }
            case "grading_schemes", "grading_weights" -> {
                requirePermission(context, "GRADING_SCHEME_WRITE");
                return;
            }
            case "grade_analyses" -> {
                requirePermission(context, "GRADE_ANALYTICS_READ");
                return;
            }
            case "grade_exceptions" -> {
                requirePermission(context, "GRADE_READ");
                return;
            }
            case "alerts" -> {
                requirePermission(context, "ALERT_MANAGE");
                return;
            }
            case "reversion_requests" -> {
                requirePermission(context, command.getType() == MutationType.INSERT
                        ? "GRADE_REVERT_REQUEST" : "GRADE_REVERT_APPROVE");
                return;
            }
            case "high_risk_approvals" -> {
                requirePermission(context, "GRADE_REVERT_APPROVE");
                return;
            }
            default -> { }
        }
        if (!"grades".equals(command.getTable())) return;
        if (command.getType() == MutationType.INSERT) {
            requirePermission(context, "GRADE_DRAFT_WRITE");
            return;
        }
        if (command.getType() == MutationType.DELETE) {
            requirePermission(context, "GRADE_REVERT_APPROVE");
            return;
        }

        boolean encryptedUpdate = containsEncryptedGradeValue(command);
        if (isMakeupReason(command.getReason())) {
            requireMakeupEnvelope(command, context);
            return;
        }
        if (isAdminSmallReversionReason(command.getReason())) {
            requireAdminSmallReversionEnvelope(command, context);
            return;
        }
        if (isRegularWithdrawalReason(command.getReason())) {
            requireRegularWithdrawalEnvelope(command, context);
            return;
        }
        if ("DRAFT".equals(command.getValues().get("status"))
                && hasFilterValue(command.getFilters(), "status", "SUBMITTED")) {
            throw denied("Admin grade reversion requires the dedicated small-reversion envelope");
        }
        if (encryptedUpdate && !hasFilterValue(command.getFilters(), "status", "DRAFT")) {
            throw denied("Encrypted updates outside a dedicated transition must target a DRAFT grade");
        }
        requirePermission(context, "SUBMITTED".equals(command.getValues().get("status"))
                ? "GRADE_SUBMIT" : "GRADE_DRAFT_WRITE");
    }

    private void authorizeStudentWrite(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        if (!"grade_exceptions".equals(command.getTable()) || command.getType() != MutationType.INSERT) {
            throw denied("Students may only submit grade exceptions");
        }
        String studentId = identity(context.getPrincipal()).studentId();
        requireValueOwned(command, "enrollment_id", id -> isStudentEnrollment(id, studentId));
        String reporter = command.getValues().get("reported_by");
        if (!context.getPrincipal().equals(reporter)) throw denied("reported_by must match the caller");
    }

    private void requireStudentOwnedGrades(List<Filter> filters, String studentId) throws RemoteServiceException {
        List<String> enrollmentIds = ids(filters, "enrollment_id");
        List<String> gradeIds = ids(filters, "id");
        if (!enrollmentIds.isEmpty() && enrollmentIds.stream().allMatch(id -> isStudentEnrollment(id, studentId))) return;
        if (!gradeIds.isEmpty() && gradeIds.stream().allMatch(id -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM grades g JOIN enrollments e ON e.id=g.enrollment_id
                WHERE g.id=? AND e.student_id=?
                """, Long.class, id, studentId) == 1L)) return;
        throw denied("Grade query must be constrained to the student's enrollments");
    }

    private void requireTeacherOwnedGrades(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> enrollmentIds = ids(filters, "enrollment_id");
        List<String> gradeIds = ids(filters, "id");
        List<String> schemeIds = ids(filters, "scheme_id");
        if (!enrollmentIds.isEmpty() && enrollmentIds.stream().allMatch(id -> isTeacherEnrollment(id, teacherId))) return;
        if (!schemeIds.isEmpty() && schemeIds.stream().allMatch(id -> isTeacherScheme(id, teacherId))) return;
        if (!gradeIds.isEmpty() && gradeIds.stream().allMatch(id -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM grades g JOIN enrollments e ON e.id=g.enrollment_id
                JOIN course_offerings o ON o.id=e.offering_id WHERE g.id=? AND o.teacher_id=?
                """, Long.class, id, teacherId) == 1L)) return;
        throw denied("Grade request must be constrained to the teacher's offerings");
    }

    private void requireTeacherOwnedClosedCourses(List<Filter> filters, String teacherId)
            throws RemoteServiceException {
        List<String> courseIds = ids(filters, "id");
        if (courseIds.isEmpty() || !courseIds.stream().allMatch(courseId -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_offerings
                WHERE course_id=? AND teacher_id=? AND status='CLOSED'
                """, Long.class, courseId, teacherId) > 0L)) {
            throw denied("History course query must be constrained to the teacher's closed offerings");
        }
    }

    private void requireStudentOwnedEnrollments(List<Filter> filters, String studentId) throws RemoteServiceException {
        List<String> enrollmentIds = ids(filters, "enrollment_id");
        if (enrollmentIds.isEmpty() || !enrollmentIds.stream().allMatch(id -> isStudentEnrollment(id, studentId))) {
            throw denied("Request must be constrained to the student's enrollments");
        }
    }

    private void requireTeacherOwnedOfferings(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> teacherIds = ids(filters, "teacher_id");
        if (teacherIds.size() == 1 && Objects.equals(teacherIds.get(0), teacherId)) return;
        List<String> byOffering = ids(filters, "offering_id");
        List<String> offeringIds = byOffering.isEmpty() ? ids(filters, "id") : byOffering;
        if (offeringIds.isEmpty() || !offeringIds.stream().allMatch(id -> isTeacherOffering(id, teacherId))) {
            throw denied("Request must be constrained to the teacher's offerings");
        }
    }

    private void requireTeacherOwnedSchemes(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> offeringIds = ids(filters, "offering_id");
        if (!offeringIds.isEmpty() && offeringIds.stream().allMatch(id -> isTeacherOffering(id, teacherId))) {
            return;
        }
        List<String> byScheme = ids(filters, "scheme_id");
        List<String> schemeIds = byScheme.isEmpty() ? ids(filters, "id") : byScheme;
        if (schemeIds.isEmpty() || !schemeIds.stream().allMatch(id -> isTeacherScheme(id, teacherId))) {
            throw denied("Request must be constrained to the teacher's grading schemes");
        }
    }

    private void requireTeacherOwnedAnalyses(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> offeringIds = ids(filters, "offering_id");
        if (!offeringIds.isEmpty() && offeringIds.stream().allMatch(id -> isTeacherOffering(id, teacherId))) return;
        List<String> analysisIds = ids(filters, "id");
        if (!analysisIds.isEmpty() && analysisIds.stream().allMatch(id -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM grade_analyses ga JOIN course_offerings o ON o.id=ga.offering_id
                WHERE ga.id=? AND o.teacher_id=?
                """, Long.class, id, teacherId) == 1L)) return;
        throw denied("Request must be constrained to the teacher's grade analyses");
    }

    private void requireTeacherOwnedStudents(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> studentIds = ids(filters, "id");
        if (studentIds.isEmpty() || !studentIds.stream().allMatch(studentId -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrollments e JOIN course_offerings o ON o.id=e.offering_id
                WHERE e.student_id=? AND o.teacher_id=?
                """, Long.class, studentId, teacherId) > 0L)) {
            throw denied("Student query must be constrained to students enrolled with the teacher");
        }
    }

    private void requireTeacherOwnedHistory(List<Filter> filters, String teacherId) throws RemoteServiceException {
        List<String> gradeIds = ids(filters, "grade_id");
        if (gradeIds.isEmpty() || !gradeIds.stream().allMatch(gradeId -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM grades g JOIN enrollments e ON e.id=g.enrollment_id
                JOIN course_offerings o ON o.id=e.offering_id
                WHERE g.id=? AND o.teacher_id=? AND o.status='CLOSED'
                """, Long.class, gradeId, teacherId) == 1L)) {
            throw denied("History query must be constrained to the teacher's closed offerings");
        }
    }

    private void requireTeacherOwnedExceptions(List<Filter> filters, String teacherId)
            throws RemoteServiceException {
        List<String> enrollmentIds = ids(filters, "enrollment_id");
        if (!enrollmentIds.isEmpty()
                && enrollmentIds.stream().allMatch(id -> isTeacherEnrollment(id, teacherId))) return;
        List<String> exceptionIds = ids(filters, "id");
        if (!exceptionIds.isEmpty() && exceptionIds.stream().allMatch(id -> jdbc.queryForObject("""
                SELECT COUNT(*) FROM grade_exceptions ge JOIN enrollments e ON e.id=ge.enrollment_id
                JOIN course_offerings o ON o.id=e.offering_id WHERE ge.id=? AND o.teacher_id=?
                """, Long.class, id, teacherId) == 1L)) return;
        throw denied("Grade exception request must be constrained to the teacher's offerings");
    }

    private void requireOwnedFilter(List<Filter> filters, String column, String expected) throws RemoteServiceException {
        List<String> values = ids(filters, column);
        if (values.size() != 1 || !Objects.equals(values.get(0), expected)) {
            throw denied("Missing required ownership filter " + column);
        }
    }

    private void requireEitherOwnedFilter(List<Filter> filters, String firstColumn, String firstValue,
                                          String secondColumn, String secondValue) throws RemoteServiceException {
        List<String> first = ids(filters, firstColumn);
        List<String> second = ids(filters, secondColumn);
        if ((first.size() == 1 && Objects.equals(first.get(0), firstValue))
                || (second.size() == 1 && Objects.equals(second.get(0), secondValue))) return;
        throw denied("Missing required profile ownership filter");
    }

    private void requireFilterValue(List<Filter> filters, String column, String expected)
            throws RemoteServiceException {
        if (!hasFilterValue(filters, column, expected)) {
            throw denied("Mutation requires filter " + column + " = " + expected);
        }
    }

    private boolean hasFilterValue(List<Filter> filters, String column, String expected) {
        return filters.stream().anyMatch(filter -> column.equals(filter.getColumn())
                && filter.getOperator() == FilterOperator.EQ && filter.getValues().size() == 1
                && expected.equals(filter.getValues().get(0)));
    }

    private void requireMakeupEnvelope(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        if (command.getType() != MutationType.UPDATE
                || !"SUBMITTED".equals(command.getValues().get("status"))
                || !hasExactlyOneEqualityFilter(command.getFilters(), "status", "SUBMITTED")
                || !hasExactlyOneEqualityFilter(command.getFilters(), "id", null)
                || !hasExactlyOneEqualityFilter(command.getFilters(), "version", null)) {
            throw denied("Makeup update requires one id plus version and SUBMITTED filters");
        }
        Set<String> allowedValues = Set.of("score_ciphertext", "score_nonce", "score_integrity",
                "status", "version", "updated_at");
        if (!command.getValues().keySet().containsAll(
                Set.of("score_ciphertext", "score_nonce", "score_integrity"))
                || command.getValues().keySet().stream().anyMatch(key -> !allowedValues.contains(key))) {
            throw denied("Makeup update may only replace the encrypted payload and optimistic metadata");
        }
        requireOptimisticVersionAdvance(command);
        String permission = switch (command.getReason()) {
            case "MAKEUP_DRAFT", "MAKEUP_CLEAR" -> "GRADE_DRAFT_WRITE";
            case "MAKEUP_SUBMIT" -> "GRADE_SUBMIT";
            case "MAKEUP_WITHDRAW" -> "GRADE_WITHDRAW";
            default -> throw denied("Unsupported makeup transition");
        };
        requirePermission(context, permission);
    }

    private void requireAdminSmallReversionEnvelope(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        if (command.getType() != MutationType.UPDATE
                || !"DRAFT".equals(command.getValues().get("status"))
                || !hasExactlyOneEqualityFilter(command.getFilters(), "status", "SUBMITTED")
                || !hasExactlyOneEqualityFilter(command.getFilters(), "id", null)
                || !hasExactlyOneEqualityFilter(command.getFilters(), "version", null)) {
            throw denied("Admin small reversion requires one submitted grade and an optimistic version filter");
        }
        Set<String> allowedValues = Set.of("score_ciphertext", "score_nonce", "score_integrity",
                "status", "version", "updated_at");
        long encryptedValues = command.getValues().keySet().stream()
                .filter(Set.of("score_ciphertext", "score_nonce", "score_integrity")::contains).count();
        if ((encryptedValues != 0 && encryptedValues != 3)
                || command.getValues().keySet().stream().anyMatch(key -> !allowedValues.contains(key))) {
            throw denied("Admin small reversion may only clear the signed makeup payload and update metadata");
        }
        requireOptimisticVersionAdvance(command);
        requirePermission(context, "GRADE_REVERT_SMALL");
    }

    private void requireRegularWithdrawalEnvelope(MutationCommand command, InvocationContext context)
            throws RemoteServiceException {
        if (command.getType() != MutationType.UPDATE
                || !"DRAFT".equals(command.getValues().get("status"))
                || !hasExactlyOneEqualityFilter(command.getFilters(), "status", "SUBMITTED")
                || !hasExactlyOneEqualityFilter(command.getFilters(), "id", null)
                || !hasExactlyOneEqualityFilter(command.getFilters(), "version", null)) {
            throw denied("Regular withdrawal requires one submitted grade and an optimistic version filter");
        }
        Set<String> allowedValues = Set.of("score_ciphertext", "score_nonce", "score_integrity",
                "status", "version", "updated_at");
        long encryptedValues = command.getValues().keySet().stream()
                .filter(Set.of("score_ciphertext", "score_nonce", "score_integrity")::contains).count();
        if ((encryptedValues != 0 && encryptedValues != 3)
                || command.getValues().keySet().stream().anyMatch(key -> !allowedValues.contains(key))) {
            throw denied("Regular withdrawal may only clear the signed makeup payload and update metadata");
        }
        requireOptimisticVersionAdvance(command);
        requirePermission(context, "GRADE_WITHDRAW");
    }

    private void requireOptimisticVersionAdvance(MutationCommand command) throws RemoteServiceException {
        String current = equalityFilterValue(command.getFilters(), "version");
        String next = command.getValues().get("version");
        try {
            if (current == null || next == null || Integer.parseInt(next) != Math.addExact(Integer.parseInt(current), 1)) {
                throw denied("Grade mutation must increment the optimistic version by one");
            }
        } catch (NumberFormatException | ArithmeticException exception) {
            throw denied("Grade mutation contains an invalid optimistic version");
        }
    }

    private boolean userWriteNeedsPermissionManagement(MutationCommand command) {
        if ((command.getType() != MutationType.UPDATE && command.getType() != MutationType.DELETE)
                || !hasExactlyOneEqualityFilter(command.getFilters(), "id", null)) return true;
        String userId = equalityFilterValue(command.getFilters(), "id");
        Long adminRoles = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=? AND r.code='ADMIN'
                """, Long.class, userId);
        return adminRoles == null || adminRoles > 0;
    }

    private void requireExclusiveHumanRole(MutationCommand command) throws RemoteServiceException {
        if (command.getType() != MutationType.INSERT) return;
        String userId = requireSafeId(command.getValues().get("user_id"));
        String roleId = requireSafeId(command.getValues().get("role_id"));
        List<String> targetRoles = jdbc.query("SELECT code FROM roles WHERE id=?",
                (rs, row) -> normalize(rs.getString(1)), roleId);
        if (targetRoles.size() != 1) throw denied("Assigned role does not exist");
        String targetRole = targetRoles.get(0);
        if (!Set.of("ADMIN", "TEACHER", "STUDENT").contains(targetRole)) return;
        Long conflicting = jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                WHERE ur.user_id=? AND r.code IN ('ADMIN','TEACHER','STUDENT') AND r.code<>?
                """, Long.class, userId, targetRole);
        if (conflicting != null && conflicting > 0) {
            throw denied("A person may have only one of ADMIN, TEACHER, or STUDENT roles");
        }
    }

    private boolean hasExactlyOneEqualityFilter(List<Filter> filters, String column, String expected) {
        List<Filter> matches = filters.stream().filter(filter -> column.equals(filter.getColumn())).toList();
        return matches.size() == 1 && matches.get(0).getOperator() == FilterOperator.EQ
                && matches.get(0).getValues().size() == 1
                && (expected == null || expected.equals(matches.get(0).getValues().get(0)));
    }

    private String equalityFilterValue(List<Filter> filters, String column) {
        return filters.stream().filter(filter -> column.equals(filter.getColumn())
                        && filter.getOperator() == FilterOperator.EQ && filter.getValues().size() == 1)
                .map(filter -> filter.getValues().get(0)).findFirst().orElse(null);
    }

    private static boolean isMakeupReason(String reason) {
        return reason != null && Set.of("MAKEUP_DRAFT", "MAKEUP_CLEAR", "MAKEUP_SUBMIT", "MAKEUP_WITHDRAW")
                .contains(reason);
    }

    private static boolean isAdminSmallReversionReason(String reason) {
        return reason != null && reason.startsWith("ADMIN_SMALL_REVERSION:");
    }

    private static boolean isRegularWithdrawalReason(String reason) {
        return reason != null && reason.startsWith("REGULAR_WITHDRAW:");
    }

    private static boolean containsEncryptedGradeValue(MutationCommand command) {
        return command.getValues().keySet().stream().anyMatch(
                Set.of("score_ciphertext", "score_nonce", "score_integrity")::contains);
    }

    private void requireSchemeIsMutable(MutationCommand command) throws RemoteServiceException {
        String offeringId = idFromValuesOrFilters(command, "offering_id");
        String schemeId = idFromValuesOrFilters(command, "id");
        long submitted;
        if (offeringId != null) {
            submitted = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM grades g JOIN grading_schemes s ON s.id=g.scheme_id
                    WHERE s.offering_id=? AND g.status='SUBMITTED'
                    """, Long.class, offeringId);
        } else if (schemeId != null) {
            submitted = jdbc.queryForObject("SELECT COUNT(*) FROM grades WHERE scheme_id=? AND status='SUBMITTED'",
                    Long.class, schemeId);
        } else throw denied("Scheme mutation requires an offering_id or id constraint");
        if (submitted > 0) throw denied("A grading scheme with submitted grades is immutable");
    }

    private void requireWeightsAreMutable(MutationCommand command) throws RemoteServiceException {
        String schemeId = idFromValuesOrFilters(command, "scheme_id");
        if (schemeId == null) {
            String weightId = idFromValuesOrFilters(command, "id");
            if (weightId != null) {
                try {
                    schemeId = jdbc.queryForObject("SELECT scheme_id FROM grading_weights WHERE id=?", String.class, weightId);
                } catch (EmptyResultDataAccessException exception) {
                    throw denied("Grading weight does not exist");
                }
            }
        }
        if (schemeId == null) throw denied("Weight mutation requires a scheme constraint");
        long submitted = jdbc.queryForObject("SELECT COUNT(*) FROM grades WHERE scheme_id=? AND status='SUBMITTED'",
                Long.class, schemeId);
        if (submitted > 0) throw denied("Weights for a scheme with submitted grades are immutable");
    }

    private String idFromValuesOrFilters(MutationCommand command, String column) throws RemoteServiceException {
        String fromValues = command.getValues().get(column);
        if (fromValues != null) return requireSafeId(fromValues);
        List<String> values = ids(command.getFilters(), column);
        return values.size() == 1 ? values.get(0) : null;
    }

    private List<String> ids(List<Filter> filters, String column) throws RemoteServiceException {
        for (Filter filter : filters) {
            if (column.equals(filter.getColumn())
                    && (filter.getOperator() == FilterOperator.EQ || filter.getOperator() == FilterOperator.IN)) {
                for (String value : filter.getValues()) requireSafeId(value);
                return filter.getValues();
            }
        }
        return List.of();
    }

    private void requireValueOwned(MutationCommand command, String field, OwnershipCheck check)
            throws RemoteServiceException {
        String value = command.getValues().get(field);
        if (value == null || !check.owned(requireSafeId(value))) throw denied("Resource ownership check failed");
    }

    private boolean isStudentEnrollment(String enrollmentId, String studentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE id=? AND student_id=?",
                Long.class, enrollmentId, studentId) == 1L;
    }

    private boolean isTeacherEnrollment(String enrollmentId, String teacherId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrollments e JOIN course_offerings o ON o.id=e.offering_id
                WHERE e.id=? AND o.teacher_id=?
                """, Long.class, enrollmentId, teacherId) == 1L;
    }

    private boolean isTeacherGradeRelation(String enrollmentId, String schemeId, String teacherId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrollments e
                JOIN course_offerings o ON o.id=e.offering_id
                JOIN grading_schemes s ON s.offering_id=o.id
                WHERE e.id=? AND s.id=? AND o.teacher_id=?
                """, Long.class, enrollmentId, schemeId, teacherId) == 1L;
    }

    private boolean isTeacherOffering(String offeringId, String teacherId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM course_offerings WHERE id=? AND teacher_id=?",
                Long.class, offeringId, teacherId) == 1L;
    }

    private boolean isClosedTeacherOffering(String offeringId, String teacherId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM course_offerings WHERE id=? AND teacher_id=? AND status='CLOSED'
                """, Long.class, offeringId, teacherId) == 1L;
    }

    private boolean isClosedTeacherEnrollment(String enrollmentId, String teacherId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM enrollments e JOIN course_offerings o ON o.id=e.offering_id
                WHERE e.id=? AND o.teacher_id=? AND o.status='CLOSED'
                """, Long.class, enrollmentId, teacherId) == 1L;
    }

    private boolean isTeacherScheme(String schemeId, String teacherId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM grading_schemes s JOIN course_offerings o ON o.id=s.offering_id
                WHERE s.id=? AND o.teacher_id=?
                """, Long.class, schemeId, teacherId) == 1L;
    }

    private Identity identity(String username) throws RemoteServiceException {
        try {
            return jdbc.queryForObject("SELECT id, student_id, teacher_id FROM users WHERE username=? AND status='ACTIVE'",
                    (rs, row) -> new Identity(rs.getString(1), rs.getString(2), rs.getString(3)), username);
        } catch (EmptyResultDataAccessException exception) {
            throw denied("Active caller identity was not found");
        }
    }

    private SchemaRegistry.TableDefinition requireTable(String name) throws RemoteServiceException {
        try {
            return registry.requireTable(name);
        } catch (IllegalArgumentException exception) {
            throw new RemoteServiceException("SCHEMA_NOT_ALLOWED", exception.getMessage());
        }
    }

    private static void requireIntersection(Set<String> actual, Set<String> allowed, String action, String table)
            throws RemoteServiceException {
        if (actual.stream().noneMatch(allowed::contains)) throw denied("Caller cannot " + action + " " + table);
    }

    private static String normalize(String role) {
        String normalized = role.toUpperCase(Locale.ROOT);
        return normalized.startsWith("ROLE_") ? normalized.substring(5) : normalized;
    }

    private static RemoteServiceException denied(String message) {
        return new RemoteServiceException("ACCESS_DENIED", message);
    }

    private static String requireSafeId(String value) throws RemoteServiceException {
        if (value == null || !SAFE_RESOURCE_ID.matcher(value).matches()) throw denied("Invalid resource identifier");
        return value;
    }

    private record Identity(String userId, String studentId, String teacherId) { }
    private interface OwnershipCheck { boolean owned(String id); }
}
