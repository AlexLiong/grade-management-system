package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.MutationCommand;
import edu.chd.practice.rmi.contract.dto.MutationType;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.TransactionRequest;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminIdentityServiceSecurityTest {
    private RemoteDataGateway gateway;
    private ResourceAccessService access;
    private AuditService audit;
    private PasswordEncoder passwordEncoder;
    private AdminIdentityService service;
    private UserPrincipal actor;

    @BeforeEach
    void setUp() {
        gateway = mock(RemoteDataGateway.class);
        access = mock(ResourceAccessService.class);
        audit = mock(AuditService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new AdminIdentityService(gateway, access, passwordEncoder, audit);
        actor = new UserPrincipal("actor", "admin", "Admin", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE", "PERMISSION_MANAGE"), true);
        when(access.requirePermission("USER_MANAGE")).thenReturn(actor);
        when(access.requirePermission("PERMISSION_MANAGE")).thenReturn(actor);
    }

    @Test
    void creatingUserRequiresPermissionManagementBeforeReadingOrWritingData() {
        when(access.requirePermission("PERMISSION_MANAGE")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));
        AdminDtos.CreateUserRequest request = new AdminDtos.CreateUserRequest(
                "admin03", "password1", "Admin 3", "admin3@example.com",
                Set.of("ADMIN"), null, null, null, null, null, null);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PERMISSION_REQUIRED");

        verify(access).requirePermission("USER_MANAGE");
        verify(access).requirePermission("PERMISSION_MANAGE");
        verifyNoInteractions(gateway);
    }

    @Test
    void creatingUserRejectsMultipleInteractiveRolesBeforeReadingOrWritingData() {
        AdminDtos.CreateUserRequest request = new AdminDtos.CreateUserRequest(
                "mixed-user", "password1", "Mixed", null,
                Set.of("TEACHER", "STUDENT"), "20260001", "T001", "org-1", null, null, null);

        assertThatThrownBy(() -> service.createUser(request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ROLE_ASSIGNMENT_INVALID"));

        verifyNoInteractions(gateway);
    }

    @Test
    void createUserAndDenyOverridesShareOneFailClosedTransaction() {
        when(passwordEncoder.encode("password1")).thenReturn("hash");
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of();
                case "roles" -> List.of(Map.of("id", "role-admin", "code", "ADMIN"));
                case "role_permissions" -> List.of();
                case "permissions" -> List.of(Map.of("id", "p-submit", "code", "GRADE_SUBMIT"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        ApiException failure = new ApiException(HttpStatus.CONFLICT,
                "REMOTE_TRANSACTION_REJECTED", "transaction failed");
        doThrow(failure).when(gateway).transaction(any());
        AdminDtos.CreateUserRequest request = new AdminDtos.CreateUserRequest(
                "restricted-admin", "password1", "Restricted", null, Set.of("ADMIN"),
                null, null, null, null, null, Map.of("GRADE_SUBMIT", false));

        assertThatThrownBy(() -> service.createUser(request)).isSameAs(failure);

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands())
                .extracting(MutationCommand::getTable)
                .containsExactly("users", "user_roles", "user_permissions");
        assertThat(transaction.getValue().getCommands().get(2).getValues())
                .containsEntry("granted", "false")
                .containsEntry("permission_id", "p-submit");
    }

    @Test
    void limitedUserManagerCanListUsersWithoutOrganizationOrOverrideReads() {
        UserPrincipal limitedActor = new UserPrincipal("limited", "limited-admin", "Limited", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true);
        when(access.requirePermission("USER_MANAGE")).thenReturn(limitedActor);
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(teacherUserRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-teacher"));
                case "roles" -> List.of(Map.of("code", "TEACHER"));
                case "teachers" -> List.of(Map.of(
                        "id", "teacher-1", "teacher_no", "T001", "org_id", "org-1"));
                default -> throw new AssertionError("Restricted listing must not read " + request.getTable());
            };
        });

        AdminDtos.UserView view = service.users(null, null, null, null, 0, 20).items().get(0);

        assertThat(view.roles()).containsExactly("TEACHER");
        assertThat(view.organizationId()).isEqualTo("org-1");
        assertThat(view.organizationName()).isNull();
        assertThat(view.permissionOverrides()).isEmpty();
    }

    @Test
    void unchangedNonAdminRolesDoNotRequirePermissionManagementOrRewriteRoleLinks() {
        stubInteractiveStudentUser();
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated", "updated@example.com", "ACTIVE", Set.of("STUDENT"), null, null, null);

        service.updateUser("target", request);

        verify(access, never()).requirePermission("PERMISSION_MANAGE");
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands())
                .extracting(MutationCommand::getTable)
                .containsExactly("users", "students");
    }

    @Test
    void updatingStudentClassAndMajorUsesTheSameTransactionAsTheAccountUpdate() {
        stubInteractiveStudentUser();
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated", "updated@example.com", "ACTIVE", Set.of("STUDENT"), null, null,
                "计算2602", "网络空间安全", null);

        service.updateUser("target", request);

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands()).hasSize(2);
        assertThat(transaction.getValue().getCommands().get(1).getTable()).isEqualTo("students");
        assertThat(transaction.getValue().getCommands().get(1).getValues())
                .containsEntry("name", "Updated")
                .containsEntry("class_name", "计算2602")
                .containsEntry("major", "网络空间安全");
    }

    @Test
    void resettingPasswordWritesAReplacementHashInTheAccountTransaction() {
        stubInteractiveStudentUser();
        when(passwordEncoder.encode("new-password")).thenReturn("replacement-bcrypt-hash");
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Target", "target@example.com", "ACTIVE", Set.of("STUDENT"),
                "new-password", null, null);

        service.updateUser("target", request);

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands().get(0).getTable()).isEqualTo("users");
        assertThat(transaction.getValue().getCommands().get(0).getValues())
                .containsEntry("password_hash", "replacement-bcrypt-hash");
    }

    @Test
    void updateUserAndDenyOverridesShareOneFailClosedTransaction() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(studentUserRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-student"));
                case "roles" -> List.of(Map.of("id", "role-student", "code", "STUDENT"));
                case "role_permissions" -> List.of();
                case "user_permissions" -> List.of();
                case "permissions" -> List.of(Map.of(
                        "id", "p-risk", "code", "RISK_SELF_ANALYZE"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
        ApiException failure = new ApiException(HttpStatus.CONFLICT,
                "REMOTE_TRANSACTION_REJECTED", "transaction failed");
        doThrow(failure).when(gateway).transaction(any());
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated", "updated@example.com", "ACTIVE", Set.of("STUDENT"), null, null,
                Map.of("RISK_SELF_ANALYZE", false));

        assertThatThrownBy(() -> service.updateUser("target", request)).isSameAs(failure);

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands())
                .extracting(MutationCommand::getTable)
                .containsExactly("users", "students", "user_permissions");
        assertThat(transaction.getValue().getCommands().get(2).getValues())
                .containsEntry("granted", "false");
        verify(access).requirePermission("PERMISSION_MANAGE");
    }

    @Test
    void changedRolesRequirePermissionManagementBeforeProfileMutation() {
        stubInteractiveAdminUser();
        when(access.requirePermission("PERMISSION_MANAGE")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated", null, "ACTIVE", Set.of("TEACHER"), null, "org-1", null);

        assertThatThrownBy(() -> service.updateUser("target", request))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PERMISSION_REQUIRED");

        verify(access).requirePermission("PERMISSION_MANAGE");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void limitedUserManagerCanUpdateTeacherWithoutReadingOrChangingOrganization() {
        UserPrincipal limitedActor = new UserPrincipal("limited", "limited-admin", "Limited", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true);
        when(access.requirePermission("USER_MANAGE")).thenReturn(limitedActor);
        stubInteractiveTeacherUser();
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated Teacher", "teacher@example.com", "ACTIVE", Set.of("TEACHER"), null, null, null);

        AdminDtos.UserView updated = service.updateUser("teacher-user", request);

        assertThat(updated.organizationId()).isEqualTo("org-1");
        assertThat(updated.organizationName()).isNull();
        verify(access, never()).requirePermission("ORG_MANAGE");
        verify(access, never()).requirePermission("PERMISSION_MANAGE");
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands().get(1).getValues())
                .containsEntry("name", "Updated Teacher")
                .doesNotContainKey("org_id");
    }

    @Test
    void changingTeacherOrganizationRequiresOrganizationManagementBeforeMutation() {
        UserPrincipal limitedActor = new UserPrincipal("limited", "limited-admin", "Limited", null,
                Set.of("ADMIN"), Set.of("USER_MANAGE"), true);
        when(access.requirePermission("USER_MANAGE")).thenReturn(limitedActor);
        when(access.requirePermission("ORG_MANAGE")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));
        stubInteractiveTeacherUser();
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Updated Teacher", null, "ACTIVE", Set.of("TEACHER"), null, "org-2", null);

        assertThatThrownBy(() -> service.updateUser("teacher-user", request))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERMISSION_REQUIRED"));

        verify(access).requirePermission("ORG_MANAGE");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void resettingExistingAdminPasswordRequiresPermissionManagement() {
        stubInteractiveAdminUser();
        when(access.requirePermission("PERMISSION_MANAGE")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));
        AdminDtos.UpdateUserRequest request = new AdminDtos.UpdateUserRequest(
                "Target", "target@example.com", "ACTIVE", Set.of("ADMIN"), "new-password", null, null);

        assertThatThrownBy(() -> service.updateUser("target", request))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PERMISSION_REQUIRED");

        verify(access).requirePermission("PERMISSION_MANAGE");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void disablingExistingAdminRequiresPermissionManagement() {
        stubInteractiveAdminUser();
        when(access.requirePermission("PERMISSION_MANAGE")).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing"));

        assertThatThrownBy(() -> service.disableUser("target"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("PERMISSION_REQUIRED");

        verify(access).requirePermission("PERMISSION_MANAGE");
        verify(gateway, never()).execute(any());
    }

    @Test
    void rolePermissionUpdateAddsBeforeRemovingOnlyChangedLinks() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "roles" -> List.of(Map.of("id", "role-admin", "code", "ADMIN"));
                case "role_permissions" -> List.of(
                        Map.of("permission_id", "p-audit"), Map.of("permission_id", "p-manage"),
                        Map.of("permission_id", "p-user"));
                case "permissions" -> request.getColumns().contains("id")
                        ? List.of(
                                Map.of("id", "p-audit", "code", "AUDIT_READ"),
                                Map.of("id", "p-grade", "code", "GRADE_READ"),
                                Map.of("id", "p-manage", "code", "PERMISSION_MANAGE"),
                                Map.of("id", "p-user", "code", "USER_MANAGE"))
                        : List.of(Map.of("code", "AUDIT_READ"), Map.of("code", "PERMISSION_MANAGE"),
                                Map.of("code", "USER_MANAGE"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        service.updateRolePermissions("role-admin", new AdminDtos.UpdateRolePermissionsRequest(
                Set.of("USER_MANAGE", "PERMISSION_MANAGE", "GRADE_READ")));

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        List<MutationCommand> commands = transaction.getValue().getCommands();
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0).getType()).isEqualTo(MutationType.INSERT);
        assertThat(commands.get(0).getValues()).containsEntry("permission_id", "p-grade");
        assertThat(commands.get(1).getType()).isEqualTo(MutationType.DELETE);
        assertThat(commands.get(1).getFilters()).anySatisfy(filter -> {
            assertThat(filter.getColumn()).isEqualTo("permission_id");
            assertThat(filter.getValues()).containsExactly("p-audit");
        });
    }

    @Test
    void emptyRolePermissionReplacementIsValidAndDeletesEveryExistingLink() {
        AdminDtos.UpdateRolePermissionsRequest request =
                new AdminDtos.UpdateRolePermissionsRequest(Set.of());
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertThat(validatorFactory.getValidator().validate(request)).isEmpty();
            assertThat(validatorFactory.getValidator().validate(
                    new AdminDtos.UpdateRolePermissionsRequest(null))).isNotEmpty();
        }
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest select = invocation.getArgument(0);
            return switch (select.getTable()) {
                case "roles" -> List.of(Map.of("id", "role-student", "code", "STUDENT"));
                case "role_permissions" -> List.of(
                        Map.of("permission_id", "p-self"), Map.of("permission_id", "p-risk"));
                case "permissions" -> select.getColumns().contains("id")
                        ? List.of(
                                Map.of("id", "p-self", "code", "GRADE_SELF_READ"),
                                Map.of("id", "p-risk", "code", "RISK_SELF_ANALYZE"))
                        : List.of(
                                Map.of("code", "GRADE_SELF_READ"),
                                Map.of("code", "RISK_SELF_ANALYZE"));
                default -> throw new AssertionError("Unexpected table " + select.getTable());
            };
        });

        AdminDtos.RolePermissions result = service.updateRolePermissions("role-student", request);

        assertThat(result.permissions()).isEmpty();
        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        assertThat(transaction.getValue().getCommands()).singleElement().satisfies(command -> {
            assertThat(command.getType()).isEqualTo(MutationType.DELETE);
            assertThat(command.getTable()).isEqualTo("role_permissions");
            assertThat(command.getValues()).isEmpty();
            assertThat(command.getFilters()).anySatisfy(filter -> {
                assertThat(filter.getColumn()).isEqualTo("permission_id");
                assertThat(filter.getValues()).containsExactlyInAnyOrder("p-self", "p-risk");
            });
        });
    }

    @Test
    void rolePermissionReplacementRejectsMissingDependenciesBeforeWriting() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("roles".equals(request.getTable())) {
                return List.of(Map.of("id", "role-teacher", "code", "TEACHER"));
            }
            throw new AssertionError("Unexpected table " + request.getTable());
        });

        assertThatThrownBy(() -> service.updateRolePermissions("role-teacher",
                new AdminDtos.UpdateRolePermissionsRequest(Set.of("GRADE_SUBMIT"))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERMISSION_DEPENDENCY_REQUIRED"));

        verify(gateway, never()).transaction(any());
    }

    @Test
    void userOverridesRejectMissingDependenciesBeforeWriting() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(teacherUserRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-teacher"));
                case "roles" -> List.of(Map.of("id", "role-teacher", "code", "TEACHER"));
                case "role_permissions" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        assertThatThrownBy(() -> service.updateUserPermissions("teacher-user",
                new AdminDtos.UserPermissionOverrides(Map.of("GRADE_SUBMIT", true))))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERMISSION_DEPENDENCY_REQUIRED"));

        verify(gateway, never()).transaction(any());
    }

    @Test
    void directOverridesAreDifferentialAndPermissionRevocationRunsLast() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(userRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-teacher"));
                case "roles" -> List.of(Map.of("id", "role-teacher", "code", "TEACHER"));
                case "role_permissions" -> List.of();
                case "user_permissions" -> List.of(
                        Map.of("permission_id", "p-manage", "granted", "true"),
                        Map.of("permission_id", "p-user", "granted", "true"));
                case "permissions" -> List.of(
                        Map.of("id", "p-grade", "code", "GRADE_READ"),
                        Map.of("id", "p-manage", "code", "PERMISSION_MANAGE"),
                        Map.of("id", "p-user", "code", "USER_MANAGE"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        service.updateUserPermissions("target", new AdminDtos.UserPermissionOverrides(Map.of(
                "GRADE_READ", true, "USER_MANAGE", false)));

        ArgumentCaptor<TransactionRequest> transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(gateway).transaction(transaction.capture());
        List<MutationCommand> commands = transaction.getValue().getCommands();
        assertThat(commands).hasSize(3);
        assertThat(commands.get(0).getType()).isEqualTo(MutationType.INSERT);
        assertThat(commands.get(1).getType()).isEqualTo(MutationType.UPDATE);
        assertThat(commands.get(2).getType()).isEqualTo(MutationType.DELETE);
        assertThat(commands.get(2).getFilters()).anySatisfy(filter -> {
            assertThat(filter.getColumn()).isEqualTo("permission_id");
            assertThat(filter.getValues()).containsExactly("p-manage");
        });
    }

    private void stubInteractiveAdminUser() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(userRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-admin"));
                case "roles" -> List.of(Map.of("id", "role-admin", "code", "ADMIN"));
                case "user_permissions" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
    }

    private void stubInteractiveStudentUser() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(studentUserRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-student"));
                case "roles" -> List.of(Map.of("id", "role-student", "code", "STUDENT"));
                case "students" -> List.of(Map.of(
                        "id", "student-1", "student_no", "20260001",
                        "class_name", "计算2601", "major", "计算机科学"));
                case "user_permissions" -> List.of();
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
    }

    private void stubInteractiveTeacherUser() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "users" -> List.of(teacherUserRow());
                case "user_roles" -> List.of(Map.of("role_id", "role-teacher"));
                case "roles" -> List.of(Map.of("id", "role-teacher", "code", "TEACHER"));
                case "teachers" -> List.of(Map.of(
                        "id", "teacher-1", "teacher_no", "T001", "org_id", "org-1"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });
    }

    private Map<String, String> userRow() {
        return Map.of(
                "id", "target", "username", "target-admin", "display_name", "Target",
                "email", "target@example.com", "status", "ACTIVE",
                "created_at", Instant.parse("2026-08-31T00:00:00Z").toString(),
                "updated_at", Instant.parse("2026-08-31T00:00:00Z").toString());
    }

    private Map<String, String> studentUserRow() {
        return Map.of(
                "id", "target", "username", "target-student", "display_name", "Target",
                "email", "target@example.com", "status", "ACTIVE", "student_id", "student-1",
                "created_at", Instant.parse("2026-08-31T00:00:00Z").toString(),
                "updated_at", Instant.parse("2026-08-31T00:00:00Z").toString());
    }

    private Map<String, String> teacherUserRow() {
        return Map.of(
                "id", "teacher-user", "username", "teacher", "display_name", "Teacher",
                "email", "teacher@example.com", "status", "ACTIVE", "teacher_id", "teacher-1",
                "created_at", Instant.parse("2026-08-31T00:00:00Z").toString(),
                "updated_at", Instant.parse("2026-08-31T00:00:00Z").toString());
    }
}
