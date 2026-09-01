package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.dto.AuthDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.CredentialFingerprint;
import edu.chd.practice.web.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AuthService {
    static final String DUMMY_PASSWORD_HASH =
            "$2y$12$jHlBBDmZGuS760OwoyyNd.ntPP2dpZAhQimoEsvw8quJuYapgG4dW";
    private static final List<String> USER_COLUMNS = List.of("id", "username", "password_hash",
            "display_name", "status", "student_id", "teacher_id");
    private final RemoteDataGateway gateway;
    private final PasswordEncoder passwordEncoder;

    public AuthService(RemoteDataGateway gateway, PasswordEncoder passwordEncoder) {
        this.gateway = gateway;
        this.passwordEncoder = passwordEncoder;
    }

    public UserPrincipal authenticate(String username, String password) {
        Map<String, String> user = one(new SelectRequest("users", USER_COLUMNS,
                List.of(Filter.of("username", FilterOperator.EQ, username.strip())), List.of(), 0, 1));
        // Always execute BCrypt to reduce account-enumeration timing differences.
        String storedHash = user == null ? DUMMY_PASSWORD_HASH : user.get("password_hash");
        boolean matches = storedHash != null && passwordEncoder.matches(password, storedHash);
        if (!matches || user == null || !"ACTIVE".equalsIgnoreCase(user.get("status"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }

        return principal(user);
    }

    public UserPrincipal refresh(UserPrincipal tokenPrincipal) {
        String userId = tokenPrincipal.id();
        Map<String, String> user = one(new SelectRequest("users", USER_COLUMNS,
                List.of(Filter.of("id", FilterOperator.EQ, userId)), List.of(), 0, 1));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.get("status"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "账号已停用或登录已失效");
        }
        if (!CredentialFingerprint.matches(tokenPrincipal.credentialFingerprint(), user.get("password_hash"))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_REVOKED", "密码已变更，请重新登录");
        }
        return principal(user);
    }

    private UserPrincipal principal(Map<String, String> user) {
        Set<String> roles = roles(user.get("id"));
        Set<String> humanRoles = Set.of("ADMIN", "TEACHER", "STUDENT");
        if (roles.size() != 1 || !humanRoles.containsAll(roles)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INTERACTIVE_LOGIN_FORBIDDEN",
                    "该账号不能用于交互式登录");
        }
        Set<String> permissions = new LinkedHashSet<>(permissions(roles));
        applyDirectPermissions(user.get("id"), permissions);
        String organizationId = organization(user);
        return new UserPrincipal(user.get("id"), user.get("username"), user.get("display_name"),
                organizationId, roles, Set.copyOf(permissions), true,
                CredentialFingerprint.fromPasswordHash(user.get("password_hash")));
    }

    public AuthDtos.CurrentUser current(UserPrincipal principal) {
        return new AuthDtos.CurrentUser(principal.id(), principal.username(), principal.displayName(),
                principal.organizationId(), primaryRole(principal.roles()), principal.roles(), principal.permissions());
    }

    private String primaryRole(Set<String> roles) {
        for (String candidate : List.of("ADMIN", "TEACHER", "STUDENT")) {
            if (roles.contains(candidate)) {
                return candidate;
            }
        }
        return roles.stream().sorted().findFirst().orElse("USER");
    }

    private Set<String> roles(String userId) {
        List<Map<String, String>> links = gateway.selectAsGateway(new SelectRequest("user_roles", List.of("role_id"),
                List.of(Filter.of("user_id", FilterOperator.EQ, userId)), List.of(), 0, 100));
        if (links.isEmpty()) {
            return Set.of();
        }
        Set<String> roleIds = values(links, "role_id");
        List<Map<String, String>> rows = gateway.selectAsGateway(new SelectRequest("roles", List.of("id", "code"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(roleIds))), List.of(), 0, 100));
        return values(rows, "code");
    }

    private Set<String> permissions(Set<String> roles) {
        if (roles.isEmpty()) {
            return Set.of();
        }
        List<Map<String, String>> roleRows = gateway.selectAsGateway(new SelectRequest("roles", List.of("id"),
                List.of(new Filter("code", FilterOperator.IN, List.copyOf(roles))), List.of(), 0, 100));
        Set<String> roleIds = values(roleRows, "id");
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        List<Map<String, String>> links = gateway.selectAsGateway(new SelectRequest("role_permissions",
                List.of("permission_id"), List.of(new Filter("role_id", FilterOperator.IN, List.copyOf(roleIds))),
                List.of(), 0, 500));
        Set<String> permissionIds = values(links, "permission_id");
        if (permissionIds.isEmpty()) {
            return Set.of();
        }
        List<Map<String, String>> rows = gateway.selectAsGateway(new SelectRequest("permissions", List.of("code"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(permissionIds))), List.of(), 0, 500));
        return values(rows, "code");
    }

    private void applyDirectPermissions(String userId, Set<String> effective) {
        List<Map<String, String>> links = gateway.selectAsGateway(new SelectRequest("user_permissions",
                List.of("permission_id", "granted"), List.of(Filter.of("user_id", FilterOperator.EQ, userId)),
                List.of(), 0, 500));
        Set<String> ids = values(links, "permission_id");
        if (ids.isEmpty()) {
            return;
        }
        Map<String, String> codeById = gateway.selectAsGateway(new SelectRequest("permissions",
                        List.of("id", "code"), List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))),
                        List.of(), 0, 500)).stream()
                .collect(java.util.stream.Collectors.toMap(row -> row.get("id"), row -> row.get("code")));
        for (Map<String, String> link : links) {
            String code = codeById.get(link.get("permission_id"));
            if (code == null) {
                continue;
            }
            if (Boolean.parseBoolean(link.get("granted"))) {
                effective.add(code);
            } else {
                effective.remove(code);
            }
        }
    }

    private String organization(Map<String, String> user) {
        String teacherId = user.get("teacher_id");
        if (teacherId == null || teacherId.isBlank()) {
            return null;
        }
        Map<String, String> teacher = one(new SelectRequest("teachers", List.of("org_id"),
                List.of(Filter.of("id", FilterOperator.EQ, teacherId)), List.of(), 0, 1));
        return teacher == null ? null : teacher.get("org_id");
    }

    private Map<String, String> one(SelectRequest request) {
        List<Map<String, String>> rows = gateway.selectAsGateway(request);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Set<String> values(List<Map<String, String>> rows, String column) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, String> row : rows) {
            if (row.get(column) != null) {
                values.add(row.get(column));
            }
        }
        return Set.copyOf(values);
    }
}
