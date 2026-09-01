package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
public class AdminIdentityService extends RemoteTableSupport {
    private static final Set<String> ASSIGNABLE_ROLES = Set.of("ADMIN", "TEACHER", "STUDENT");
    private static final Set<String> CORE_ADMIN_PERMISSIONS = Set.of("USER_MANAGE", "PERMISSION_MANAGE");
    private static final Map<String, Set<String>> PERMISSION_DEPENDENCIES = Map.ofEntries(
            Map.entry("GRADING_SCHEME_WRITE", Set.of("COURSE_READ", "GRADE_READ")),
            Map.entry("GRADE_DRAFT_WRITE", Set.of("COURSE_READ", "GRADE_READ")),
            Map.entry("GRADE_SUBMIT", Set.of("COURSE_READ", "GRADE_READ")),
            Map.entry("GRADE_WITHDRAW", Set.of("COURSE_READ", "GRADE_READ")),
            Map.entry("GRADE_ANALYTICS_READ", Set.of("COURSE_READ", "GRADE_READ")),
            Map.entry("RISK_ANALYZE", Set.of("COURSE_READ", "GRADE_READ", "GRADE_ANALYTICS_READ")),
            Map.entry("RISK_SELF_ANALYZE", Set.of("GRADE_SELF_READ")),
            Map.entry("GRADE_REVERT_SMALL", Set.of("GRADE_READ"))
    );
    private static final List<String> USER_COLUMNS = List.of("id", "username", "display_name", "email",
            "status", "student_id", "teacher_id", "created_at", "updated_at");
    private final ResourceAccessService access;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminIdentityService(RemoteDataGateway gateway, ResourceAccessService access,
                                PasswordEncoder passwordEncoder, AuditService auditService) {
        super(gateway);
        this.access = access;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    public PageResult<AdminDtos.UserView> users(String keyword, String status, String role,
                                                String organizationId, int page, int size) {
        UserPrincipal actor = access.requirePermission("USER_MANAGE");
        boolean includeOrganizationName = actor.hasPermission("ORG_MANAGE");
        boolean includePermissionOverrides = actor.hasPermission("PERMISSION_MANAGE");
        String normalizedRole = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        if (!normalizedRole.isEmpty() && !ASSIGNABLE_ROLES.contains(normalizedRole)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ASSIGNABLE", "用户筛选角色无效");
        }
        List<Filter> filters = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            filters.add(Filter.of("status", FilterOperator.EQ, status));
        }
        List<Map<String, String>> all = rows("users", USER_COLUMNS, filters,
                List.of(new Sort("created_at", SortDirection.DESC)), 0, 500);
        String normalized = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        List<AdminDtos.UserView> views = all.stream().filter(row -> normalized.isEmpty()
                        || row.get("username").toLowerCase(Locale.ROOT).contains(normalized)
                        || row.get("display_name").toLowerCase(Locale.ROOT).contains(normalized))
                .map(row -> userView(row, includeOrganizationName, includePermissionOverrides))
                .filter(view -> isInteractive(view.roles()))
                .filter(view -> normalizedRole.isEmpty() || view.roles().contains(normalizedRole))
                .filter(view -> organizationId == null || organizationId.isBlank()
                        || organizationId.equals(view.organizationId()))
                .toList();
        int from = Math.min(page * size, views.size());
        int to = Math.min(from + size, views.size());
        return page(views.subList(from, to), page, size, views.size());
    }

    public AdminDtos.UserView createUser(AdminDtos.CreateUserRequest request) {
        UserPrincipal actor = access.requirePermission("USER_MANAGE");
        access.requirePermission("PERMISSION_MANAGE");
        requireSingleInteractiveRole(request.roleCodes());
        if (request.roleCodes().contains("TEACHER")) {
            access.requirePermission("ORG_MANAGE");
        }
        if (one("users", List.of("id"),
                List.of(Filter.of("username", FilterOperator.EQ, request.username()))) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        Map<String, String> roles = roleIds(request.roleCodes());
        Map<String, Boolean> requestedOverrides = request.permissionOverrides() == null
                ? Map.of() : request.permissionOverrides();
        requireCoreAdminOverrides(request.roleCodes(), requestedOverrides, actor, userIdPlaceholder());
        if (!requestedOverrides.isEmpty()) {
            requirePermissionDependencies(effectivePermissions(roles.values(), requestedOverrides));
        }
        String userId = UUID.randomUUID().toString();
        String studentId = request.roleCodes().contains("STUDENT") ? UUID.randomUUID().toString() : null;
        String teacherId = request.roleCodes().contains("TEACHER") ? UUID.randomUUID().toString() : null;
        validateProfile(request, studentId, teacherId);
        Instant now = Instant.now();
        Map<String, String> userValues = new LinkedHashMap<>();
        userValues.put("id", userId);
        userValues.put("username", request.username());
        userValues.put("password_hash", passwordEncoder.encode(request.password()));
        userValues.put("display_name", request.displayName());
        userValues.put("email", blank(request.email()));
        userValues.put("status", "ACTIVE");
        userValues.put("student_id", studentId);
        userValues.put("teacher_id", teacherId);
        userValues.put("created_at", now.toString());
        userValues.put("updated_at", now.toString());
        List<MutationCommand> commands = new ArrayList<>();
        commands.add(new MutationCommand(MutationType.INSERT, "users", userValues, List.of()));
        for (String roleId : roles.values()) {
            commands.add(new MutationCommand(MutationType.INSERT, "user_roles", Map.of(
                    "user_id", userId, "role_id", roleId), List.of()));
        }
        commands.addAll(permissionOverrideCommands(userId, Map.of(), requestedOverrides, actor, now));
        if (studentId != null) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", studentId);
            values.put("student_no", request.studentNo());
            values.put("user_id", userId);
            values.put("name", request.displayName());
            values.put("gender", "UNKNOWN");
            values.put("admission_year", Integer.toString(java.time.Year.now().getValue()));
            values.put("class_name", blank(request.className()));
            values.put("major", blank(request.major()));
            values.put("status", "ACTIVE");
            commands.add(new MutationCommand(MutationType.INSERT, "students", values, List.of()));
        }
        if (teacherId != null) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("id", teacherId);
            values.put("teacher_no", request.teacherNo());
            values.put("user_id", userId);
            values.put("name", request.displayName());
            values.put("title", "LECTURER");
            values.put("org_id", request.organizationId());
            values.put("status", "ACTIVE");
            commands.add(new MutationCommand(MutationType.INSERT, "teachers", values, List.of()));
        }
        gateway.transaction(new TransactionRequest("create-user:" + userId, commands));
        auditService.record("USER_CREATED", "users", userId, true, "username=" + request.username());
        return userView(requireOne("users", USER_COLUMNS,
                        List.of(Filter.of("id", FilterOperator.EQ, userId)), "USER_NOT_FOUND", "用户不存在"),
                actor.hasPermission("ORG_MANAGE"), true);
    }

    public AdminDtos.UserView updateUser(String userId, AdminDtos.UpdateUserRequest request) {
        UserPrincipal actor = access.requirePermission("USER_MANAGE");
        requireSingleInteractiveRole(request.roleCodes());
        Map<String, String> current = requireOne("users", USER_COLUMNS,
                List.of(Filter.of("id", FilterOperator.EQ, userId)), "USER_NOT_FOUND", "用户不存在");
        Set<String> currentRoles = rolesForUser(userId);
        if (!isInteractive(currentRoles)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SERVICE_IDENTITY_IMMUTABLE",
                    "内部服务或审计主体不可通过用户管理接口修改");
        }
        boolean rolesChanged = !currentRoles.equals(request.roleCodes());
        if (rolesChanged || currentRoles.contains("ADMIN") || request.permissionOverrides() != null) {
            access.requirePermission("PERMISSION_MANAGE");
        }
        if (actor.id().equals(userId) && !"ACTIVE".equals(request.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_DISABLE_FORBIDDEN", "不能停用或锁定当前登录账号");
        }
        if (actor.id().equals(userId) && currentRoles.contains("ADMIN")
                && !request.roleCodes().contains("ADMIN")) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN",
                    "不能移除当前登录账号的管理员角色");
        }
        requireCoreAdminOverrides(request.roleCodes(), request.permissionOverrides(), actor, userId);
        if (rolesChanged || request.permissionOverrides() != null) {
            Map<String, Boolean> targetOverrides = request.permissionOverrides() == null
                    ? permissionOverridesForUser(userId) : request.permissionOverrides();
            requirePermissionDependencies(effectivePermissions(
                    roleIds(request.roleCodes()).values(), targetOverrides));
        }
        if (currentRoles.contains("ADMIN")
                && (!request.roleCodes().contains("ADMIN") || !"ACTIVE".equals(request.status()))) {
            requireAnotherCapableAdmin(userId);
        }
        validateProfileTransition(current, request.roleCodes());
        Map<String, String> teacher = profile("teachers", current.get("teacher_id"),
                List.of("id", "org_id"));
        String organizationId = teacher == null ? null : teacher.get("org_id");
        boolean organizationChanged = teacher != null && hasValue(request.organizationId())
                && !java.util.Objects.equals(organizationId, request.organizationId());
        if (organizationChanged) {
            access.requirePermission("ORG_MANAGE");
            requireOrganization(request.organizationId());
            organizationId = request.organizationId();
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("display_name", request.displayName());
        values.put("email", blank(request.email()));
        values.put("status", request.status());
        values.put("updated_at", Instant.now().toString());
        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            values.put("password_hash", passwordEncoder.encode(request.newPassword()));
        }
        List<MutationCommand> commands = new ArrayList<>();
        commands.add(new MutationCommand(MutationType.UPDATE, "users", values,
                List.of(Filter.of("id", FilterOperator.EQ, userId))));
        if (hasValue(current.get("student_id"))) {
            Map<String, String> studentValues = new LinkedHashMap<>();
            studentValues.put("name", request.displayName());
            if (request.className() != null) {
                studentValues.put("class_name", blank(request.className()));
            }
            if (request.major() != null) {
                studentValues.put("major", blank(request.major()));
            }
            commands.add(new MutationCommand(MutationType.UPDATE, "students", studentValues,
                    List.of(Filter.of("id", FilterOperator.EQ, current.get("student_id")))));
        }
        if (teacher != null) {
            Map<String, String> teacherValues = new LinkedHashMap<>();
            teacherValues.put("name", request.displayName());
            if (organizationChanged) {
                teacherValues.put("org_id", organizationId);
            }
            commands.add(new MutationCommand(MutationType.UPDATE, "teachers", teacherValues,
                    List.of(Filter.of("id", FilterOperator.EQ, teacher.get("id")))));
        }
        if (rolesChanged) {
            Set<String> allRoleCodes = union(currentRoles, request.roleCodes());
            Map<String, String> roleIds = roleIds(allRoleCodes);
            difference(request.roleCodes(), currentRoles).forEach(code -> commands.add(
                    new MutationCommand(MutationType.INSERT, "user_roles",
                            Map.of("user_id", userId, "role_id", roleIds.get(code)), List.of())));
            List<String> removedRoleIds = difference(currentRoles, request.roleCodes()).stream()
                    .map(roleIds::get).toList();
            if (!removedRoleIds.isEmpty()) {
                commands.add(new MutationCommand(MutationType.DELETE, "user_roles", Map.of(), List.of(
                        Filter.of("user_id", FilterOperator.EQ, userId),
                        new Filter("role_id", FilterOperator.IN, removedRoleIds))));
            }
        }
        if (request.permissionOverrides() != null) {
            commands.addAll(permissionOverrideCommands(userId, permissionOverridesForUser(userId),
                    request.permissionOverrides(), actor, Instant.now()));
        }
        gateway.transaction(new TransactionRequest("update-user:" + userId + ":" + UUID.randomUUID(), commands));
        auditService.record("USER_UPDATED", "users", userId, true, "roles=" + request.roleCodes());
        return userView(requireOne("users", USER_COLUMNS,
                        List.of(Filter.of("id", FilterOperator.EQ, userId)), "USER_NOT_FOUND", "用户不存在"),
                actor.hasPermission("ORG_MANAGE"), actor.hasPermission("PERMISSION_MANAGE"));
    }

    public void disableUser(String userId) {
        UserPrincipal principal = access.requirePermission("USER_MANAGE");
        if (principal.id().equals(userId)) {
            throw new ApiException(HttpStatus.CONFLICT, "SELF_DISABLE_FORBIDDEN", "不能禁用当前登录账号");
        }
        requireOne("users", List.of("id"), List.of(Filter.of("id", FilterOperator.EQ, userId)),
                "USER_NOT_FOUND", "用户不存在");
        Set<String> targetRoles = rolesForUser(userId);
        if (!isInteractive(targetRoles)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SERVICE_IDENTITY_IMMUTABLE",
                    "内部服务或审计主体不可通过用户管理接口禁用");
        }
        if (targetRoles.contains("ADMIN")) {
            access.requirePermission("PERMISSION_MANAGE");
            requireAnotherCapableAdmin(userId);
        }
        gateway.execute(new MutationCommand(MutationType.UPDATE, "users", Map.of(
                "status", "DISABLED", "updated_at", Instant.now().toString()),
                List.of(Filter.of("id", FilterOperator.EQ, userId))));
        auditService.record("USER_DISABLED", "users", userId, true, "soft delete");
    }

    public List<AdminDtos.OrganizationView> organizations() {
        access.requirePermission("ORG_MANAGE");
        return rows("orgs", List.of("id", "code", "name", "parent_id"), List.of(),
                List.of(new Sort("code", SortDirection.ASC)), 0, 500).stream().map(this::organization).toList();
    }

    public AdminDtos.OrganizationView createOrganization(AdminDtos.OrganizationRequest request) {
        access.requirePermission("ORG_MANAGE");
        validateParent(request.parentId(), null);
        String id = UUID.randomUUID().toString();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("code", request.code());
        values.put("name", request.name());
        values.put("parent_id", nullable(request.parentId()));
        gateway.execute(new MutationCommand(MutationType.INSERT, "orgs", values, List.of()));
        auditService.record("ORGANIZATION_CREATED", "orgs", id, true, "code=" + request.code());
        return new AdminDtos.OrganizationView(id, request.code(), request.name(), request.parentId());
    }

    public AdminDtos.OrganizationView updateOrganization(String id, AdminDtos.OrganizationRequest request) {
        access.requirePermission("ORG_MANAGE");
        requireOne("orgs", List.of("id"), List.of(Filter.of("id", FilterOperator.EQ, id)),
                "ORGANIZATION_NOT_FOUND", "组织不存在");
        validateParent(request.parentId(), id);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("code", request.code());
        values.put("name", request.name());
        values.put("parent_id", nullable(request.parentId()));
        gateway.execute(new MutationCommand(MutationType.UPDATE, "orgs", values,
                List.of(Filter.of("id", FilterOperator.EQ, id))));
        auditService.record("ORGANIZATION_UPDATED", "orgs", id, true, "code=" + request.code());
        return new AdminDtos.OrganizationView(id, request.code(), request.name(), request.parentId());
    }

    public void deleteOrganization(String id) {
        access.requirePermission("ORG_MANAGE");
        requireOne("orgs", List.of("id"), List.of(Filter.of("id", FilterOperator.EQ, id)),
                "ORGANIZATION_NOT_FOUND", "组织不存在");
        if (count("orgs", List.of(Filter.of("parent_id", FilterOperator.EQ, id))) > 0
                || count("teachers", List.of(Filter.of("org_id", FilterOperator.EQ, id))) > 0
                || count("courses", List.of(Filter.of("org_id", FilterOperator.EQ, id))) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANIZATION_IN_USE", "组织仍有关联记录，不能删除");
        }
        gateway.execute(new MutationCommand(MutationType.DELETE, "orgs", Map.of(),
                List.of(Filter.of("id", FilterOperator.EQ, id))));
        auditService.record("ORGANIZATION_DELETED", "orgs", id, true, "");
    }

    public List<AdminDtos.RolePermissions> roles() {
        access.requirePermission("PERMISSION_MANAGE");
        return rows("roles", List.of("id", "code"),
                List.of(new Filter("code", FilterOperator.IN, List.copyOf(ASSIGNABLE_ROLES))),
                List.of(new Sort("code", SortDirection.ASC)), 0, 100).stream()
                .map(row -> new AdminDtos.RolePermissions(row.get("id"), row.get("code"),
                        permissionCodesForRole(row.get("id")))).toList();
    }

    public List<AdminDtos.PermissionView> permissions() {
        access.requirePermission("PERMISSION_MANAGE");
        return rows("permissions", List.of("code", "name"), List.of(),
                List.of(new Sort("code", SortDirection.ASC)), 0, 500).stream()
                .map(row -> new AdminDtos.PermissionView(row.get("code"), row.get("name")))
                .toList();
    }

    public AdminDtos.RolePermissions updateRolePermissions(String roleId,
                                                            AdminDtos.UpdateRolePermissionsRequest request) {
        access.requirePermission("PERMISSION_MANAGE");
        Map<String, String> role = requireOne("roles", List.of("id", "code"),
                List.of(Filter.of("id", FilterOperator.EQ, roleId)), "ROLE_NOT_FOUND", "角色不存在");
        if (!ASSIGNABLE_ROLES.contains(role.get("code"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INTERNAL_ROLE_IMMUTABLE", "内部服务角色不可通过 Web 修改");
        }
        if ("ADMIN".equals(role.get("code"))
                && !request.permissions().containsAll(CORE_ADMIN_PERMISSIONS)) {
            throw new ApiException(HttpStatus.CONFLICT, "CORE_ADMIN_PERMISSIONS_REQUIRED",
                    "管理员角色必须保留用户管理和权限管理能力");
        }
        requirePermissionDependencies(request.permissions());
        Set<String> current = permissionCodesForRole(roleId);
        Map<String, String> ids = permissionIds(union(current, request.permissions()));
        List<MutationCommand> commands = new ArrayList<>();
        difference(request.permissions(), current).forEach(code -> commands.add(new MutationCommand(
                MutationType.INSERT, "role_permissions",
                Map.of("role_id", roleId, "permission_id", ids.get(code)), List.of())));
        List<String> removedPermissionIds = difference(current, request.permissions()).stream()
                .map(ids::get).toList();
        if (!removedPermissionIds.isEmpty()) {
            commands.add(new MutationCommand(MutationType.DELETE, "role_permissions", Map.of(), List.of(
                    Filter.of("role_id", FilterOperator.EQ, roleId),
                    new Filter("permission_id", FilterOperator.IN, removedPermissionIds))));
        }
        if (!commands.isEmpty()) {
            gateway.transaction(new TransactionRequest(
                    "role-permissions:" + roleId + ":" + UUID.randomUUID(), commands));
        }
        auditService.record("ROLE_PERMISSIONS_UPDATED", "roles", roleId, true,
                "permissions=" + request.permissions());
        return new AdminDtos.RolePermissions(roleId, role.get("code"), request.permissions());
    }

    public AdminDtos.UserPermissionOverrides updateUserPermissions(
            String userId, AdminDtos.UserPermissionOverrides request) {
        UserPrincipal actor = access.requirePermission("PERMISSION_MANAGE");
        Map<String, String> user = requireOne("users", List.of("id", "username"),
                List.of(Filter.of("id", FilterOperator.EQ, userId)), "USER_NOT_FOUND", "用户不存在");
        Set<String> targetRoles = rolesForUser(userId);
        if (!isInteractive(targetRoles)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "SERVICE_PERMISSIONS_IMMUTABLE",
                    "不能通过 Web API 修改内部服务或审计主体权限");
        }
        requireCoreAdminOverrides(targetRoles, request.overrides(), actor, userId);
        requirePermissionDependencies(effectivePermissions(
                roleIds(targetRoles).values(), request.overrides()));
        List<MutationCommand> commands = permissionOverrideCommands(userId,
                permissionOverridesForUser(userId), request.overrides(), actor, Instant.now());
        if (!commands.isEmpty()) {
            gateway.transaction(new TransactionRequest(
                    "user-permissions:" + userId + ":" + UUID.randomUUID(), commands));
        }
        auditService.record("USER_PERMISSION_OVERRIDES_UPDATED", "users", userId, true,
                "overrides=" + request.overrides().keySet());
        return request;
    }

    private void requireCoreAdminOverrides(Set<String> roles, Map<String, Boolean> overrides,
                                           UserPrincipal actor, String userId) {
        if (!roles.contains("ADMIN") || overrides == null) {
            return;
        }
        boolean deniesCore = CORE_ADMIN_PERMISSIONS.stream()
                .anyMatch(code -> Boolean.FALSE.equals(overrides.get(code)));
        if (deniesCore) {
            String code = actor.id().equals(userId)
                    ? "SELF_CORE_PERMISSION_REVOCATION" : "CORE_ADMIN_PERMISSIONS_REQUIRED";
            throw new ApiException(HttpStatus.CONFLICT, code,
                    "管理员不能显式拒绝用户管理或权限管理能力");
        }
    }

    private void requireAnotherCapableAdmin(String excludedUserId) {
        Map<String, String> adminRole = requireOne("roles", List.of("id"),
                List.of(Filter.of("code", FilterOperator.EQ, "ADMIN")),
                "ROLE_NOT_FOUND", "管理员角色不存在");
        Set<String> adminIds = values(rows("user_roles", List.of("user_id"), List.of(
                Filter.of("role_id", FilterOperator.EQ, adminRole.get("id"))),
                List.of(), 0, 500), "user_id");
        List<String> candidates = adminIds.stream().filter(id -> !id.equals(excludedUserId)).toList();
        if (candidates.isEmpty()) {
            throw lastCapableAdmin();
        }
        Set<String> defaults = permissionCodesForRole(adminRole.get("id"));
        List<Map<String, String>> active = rows("users", List.of("id"), List.of(
                new Filter("id", FilterOperator.IN, candidates),
                Filter.of("status", FilterOperator.EQ, "ACTIVE")), List.of(), 0, 500);
        boolean capable = active.stream().map(row -> row.get("id")).anyMatch(userId -> {
            Map<String, Boolean> overrides = permissionOverridesForUser(userId);
            return CORE_ADMIN_PERMISSIONS.stream().allMatch(code ->
                    Boolean.TRUE.equals(overrides.get(code))
                            || (!Boolean.FALSE.equals(overrides.get(code)) && defaults.contains(code)));
        });
        if (!capable) {
            throw lastCapableAdmin();
        }
    }

    private ApiException lastCapableAdmin() {
        return new ApiException(HttpStatus.CONFLICT, "LAST_CAPABLE_ADMIN_REQUIRED",
                "系统必须至少保留一名启用且具备用户管理和权限管理能力的管理员");
    }

    private String userIdPlaceholder() {
        return "<new-user>";
    }

    private List<MutationCommand> permissionOverrideCommands(
            String userId, Map<String, Boolean> current, Map<String, Boolean> requested,
            UserPrincipal actor, Instant now) {
        Set<String> allCodes = union(current.keySet(), requested.keySet());
        Map<String, String> ids = permissionIds(allCodes);
        List<MutationCommand> commands = new ArrayList<>();
        List<String> changedCodes = allCodes.stream()
                .filter(code -> !java.util.Objects.equals(current.get(code), requested.get(code))
                        || current.containsKey(code) != requested.containsKey(code))
                .sorted(java.util.Comparator.comparing((String code) -> "PERMISSION_MANAGE".equals(code)))
                .toList();
        for (String code : changedCodes) {
            String permissionId = ids.get(code);
            if (!requested.containsKey(code)) {
                commands.add(new MutationCommand(MutationType.DELETE, "user_permissions", Map.of(), List.of(
                        Filter.of("user_id", FilterOperator.EQ, userId),
                        Filter.of("permission_id", FilterOperator.EQ, permissionId))));
            } else if (!current.containsKey(code)) {
                commands.add(new MutationCommand(MutationType.INSERT, "user_permissions", Map.of(
                        "user_id", userId, "permission_id", permissionId,
                        "granted", requested.get(code).toString(), "granted_by", actor.username(),
                        "created_at", now.toString()), List.of()));
            } else {
                commands.add(new MutationCommand(MutationType.UPDATE, "user_permissions", Map.of(
                        "granted", requested.get(code).toString(), "granted_by", actor.username(),
                        "created_at", now.toString()), List.of(
                        Filter.of("user_id", FilterOperator.EQ, userId),
                        Filter.of("permission_id", FilterOperator.EQ, permissionId))));
            }
        }
        return commands;
    }

    private AdminDtos.UserView userView(Map<String, String> user, boolean includeOrganizationName,
                                        boolean includePermissionOverrides) {
        Map<String, String> student = profile("students", user.get("student_id"),
                List.of("id", "student_no", "class_name", "major"));
        Map<String, String> teacher = profile("teachers", user.get("teacher_id"),
                List.of("id", "teacher_no", "org_id"));
        String organizationId = teacher == null ? null : teacher.get("org_id");
        Map<String, String> organization = includeOrganizationName
                ? profile("orgs", organizationId, List.of("id", "name")) : null;
        return new AdminDtos.UserView(user.get("id"), user.get("username"), user.get("display_name"),
                user.get("email"), user.get("status"), rolesForUser(user.get("id")),
                student == null ? null : student.get("id"), student == null ? null : student.get("student_no"),
                teacher == null ? null : teacher.get("id"), teacher == null ? null : teacher.get("teacher_no"),
                organizationId, organization == null ? null : organization.get("name"),
                student == null ? null : student.get("class_name"), student == null ? null : student.get("major"),
                includePermissionOverrides ? permissionOverridesForUser(user.get("id")) : Map.of(),
                instant(user, "created_at"), instant(user, "updated_at"));
    }

    private Map<String, String> profile(String table, String id, List<String> columns) {
        if (!hasValue(id)) {
            return null;
        }
        return one(table, columns, List.of(Filter.of("id", FilterOperator.EQ, id)));
    }

    private Map<String, Boolean> permissionOverridesForUser(String userId) {
        List<Map<String, String>> overrides = rows("user_permissions", List.of("permission_id", "granted"),
                List.of(Filter.of("user_id", FilterOperator.EQ, userId)), List.of(), 0, 500);
        if (overrides.isEmpty()) {
            return Map.of();
        }
        Set<String> permissionIds = values(overrides, "permission_id");
        Map<String, String> codes = rows("permissions", List.of("id", "code"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(permissionIds))),
                List.of(new Sort("code", SortDirection.ASC)), 0, 500).stream()
                .collect(Collectors.toMap(row -> row.get("id"), row -> row.get("code")));
        Map<String, Boolean> result = new LinkedHashMap<>();
        overrides.stream().sorted(java.util.Comparator.comparing(row -> codes.get(row.get("permission_id"))))
                .forEach(row -> result.put(codes.get(row.get("permission_id")),
                        Boolean.parseBoolean(row.get("granted"))));
        return Map.copyOf(result);
    }

    private Set<String> rolesForUser(String userId) {
        Set<String> roleIds = values(rows("user_roles", List.of("role_id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, userId)), List.of(), 0, 100), "role_id");
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return values(rows("roles", List.of("code"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(roleIds))), List.of(), 0, 100), "code");
    }

    private Map<String, String> roleIds(Set<String> codes) {
        if (!ASSIGNABLE_ROLES.containsAll(codes)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_ASSIGNABLE", "只能分配管理员、教师或学生角色");
        }
        List<Map<String, String>> rows = rows("roles", List.of("id", "code"),
                List.of(new Filter("code", FilterOperator.IN, List.copyOf(codes))), List.of(), 0, 100);
        if (rows.size() != codes.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_NOT_FOUND", "包含不存在的角色编码");
        }
        return rows.stream().collect(Collectors.toMap(row -> row.get("code"), row -> row.get("id")));
    }

    private Map<String, String> permissionIds(Set<String> codes) {
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<Map<String, String>> rows = rows("permissions", List.of("id", "code"),
                List.of(new Filter("code", FilterOperator.IN, List.copyOf(codes))), List.of(), 0, 500);
        if (rows.size() != codes.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERMISSION_NOT_FOUND", "包含不存在的权限编码");
        }
        return rows.stream().collect(Collectors.toMap(row -> row.get("code"), row -> row.get("id")));
    }

    private Set<String> effectivePermissions(java.util.Collection<String> roleIds,
                                             Map<String, Boolean> overrides) {
        Set<String> effective = new LinkedHashSet<>();
        for (String roleId : roleIds) {
            effective.addAll(permissionCodesForRole(roleId));
        }
        overrides.forEach((code, granted) -> {
            if (Boolean.TRUE.equals(granted)) effective.add(code);
            else effective.remove(code);
        });
        return effective;
    }

    private void requirePermissionDependencies(Set<String> effective) {
        List<String> missing = new ArrayList<>();
        PERMISSION_DEPENDENCIES.forEach((permission, dependencies) -> {
            if (!effective.contains(permission)) return;
            dependencies.stream().filter(dependency -> !effective.contains(dependency))
                    .sorted().forEach(dependency -> missing.add(permission + " -> " + dependency));
        });
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PERMISSION_DEPENDENCY_REQUIRED",
                    "权限依赖不完整: " + String.join(", ", missing));
        }
    }

    static Set<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(code -> !right.contains(code)).sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.addAll(right);
        return result.stream().sorted().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> permissionCodesForRole(String roleId) {
        Set<String> ids = values(rows("role_permissions", List.of("permission_id"),
                List.of(Filter.of("role_id", FilterOperator.EQ, roleId)), List.of(), 0, 500), "permission_id");
        if (ids.isEmpty()) {
            return Set.of();
        }
        return values(rows("permissions", List.of("code"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))), List.of(), 0, 500), "code");
    }

    private void validateProfile(AdminDtos.CreateUserRequest request, String studentId, String teacherId) {
        if (studentId != null && (request.studentNo() == null || request.studentNo().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STUDENT_NO_REQUIRED", "学生账号必须提供学号");
        }
        if (teacherId != null && (request.teacherNo() == null || request.teacherNo().isBlank()
                || request.organizationId() == null || request.organizationId().isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEACHER_PROFILE_REQUIRED", "教师账号必须提供工号和组织");
        }
        if (teacherId != null) {
            requireOrganization(request.organizationId());
        }
    }

    private void validateProfileTransition(Map<String, String> user, Set<String> requestedRoles) {
        Set<String> requiredProfileRoles = new LinkedHashSet<>();
        if (hasValue(user.get("student_id"))) {
            requiredProfileRoles.add("STUDENT");
        }
        if (hasValue(user.get("teacher_id"))) {
            requiredProfileRoles.add("TEACHER");
        }
        Set<String> requestedProfileRoles = requestedRoles.stream()
                .filter(role -> role.equals("STUDENT") || role.equals("TEACHER"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!requiredProfileRoles.equals(requestedProfileRoles)) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_ROLE_CHANGE_REQUIRES_MIGRATION",
                    "学生或教师角色变更需要先执行档案迁移");
        }
    }

    private void requireOrganization(String organizationId) {
        if (!hasValue(organizationId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEACHER_ORGANIZATION_REQUIRED",
                    "教师账号必须选择所属组织");
        }
        requireOne("orgs", List.of("id"), List.of(Filter.of("id", FilterOperator.EQ, organizationId)),
                "ORGANIZATION_NOT_FOUND", "所属组织不存在");
    }

    private boolean isInteractive(Set<String> roles) {
        return roles.size() == 1 && ASSIGNABLE_ROLES.containsAll(roles);
    }

    private void requireSingleInteractiveRole(Set<String> roles) {
        if (roles == null || roles.size() != 1 || !ASSIGNABLE_ROLES.containsAll(roles)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ROLE_ASSIGNMENT_INVALID",
                    "交互式用户必须且只能分配一个管理员、教师或学生角色");
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private void validateParent(String parentId, String ownId) {
        if (parentId == null || parentId.isBlank()) {
            return;
        }
        Set<String> visited = new LinkedHashSet<>();
        String cursor = parentId;
        while (hasValue(cursor)) {
            if (cursor.equals(ownId) || !visited.add(cursor)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ORGANIZATION_CYCLE", "组织层级不能形成循环");
            }
            Map<String, String> parent = requireOne("orgs", List.of("id", "parent_id"),
                    List.of(Filter.of("id", FilterOperator.EQ, cursor)),
                    "PARENT_ORGANIZATION_NOT_FOUND", "上级组织不存在");
            cursor = parent.get("parent_id");
        }
    }

    private AdminDtos.OrganizationView organization(Map<String, String> row) {
        return new AdminDtos.OrganizationView(row.get("id"), row.get("code"), row.get("name"), row.get("parent_id"));
    }

    private Set<String> values(List<Map<String, String>> rows, String column) {
        return rows.stream().map(row -> row.get(column)).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String blank(String value) {
        return value == null ? "" : value.strip();
    }

    private String nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
