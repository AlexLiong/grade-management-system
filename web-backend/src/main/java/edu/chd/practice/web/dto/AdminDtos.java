package edu.chd.practice.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AdminDtos {
    private AdminDtos() {
    }

    public record UserView(String id, String username, String displayName, String email, String status,
                           Set<String> roles, String studentId, String studentNo, String teacherId,
                           String teacherNo, String organizationId, String organizationName,
                           String className, String major, Map<String, Boolean> permissionOverrides,
                           Instant createdAt, Instant updatedAt) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,32}") String username,
            @NotBlank @Size(min = 8, max = 128) String password,
            @NotBlank @Size(max = 80) String displayName,
            @Email @Size(max = 120) String email,
            @NotEmpty @Size(max = 1) Set<@NotBlank String> roleCodes,
            String studentNo,
            String teacherNo,
            String organizationId,
            String className,
            String major,
            Map<@NotBlank String, @NotNull Boolean> permissionOverrides
    ) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 80) String displayName,
                                    @Email @Size(max = 120) String email,
                                    @NotBlank @Pattern(regexp = "ACTIVE|DISABLED|LOCKED") String status,
                                    @NotEmpty @Size(max = 1) Set<@NotBlank String> roleCodes,
                                    @Size(min = 8, max = 128) String newPassword,
                                    @Size(max = 64) String organizationId,
                                    @Size(max = 80) String className,
                                    @Size(max = 80) String major,
                                    Map<@NotBlank String, @NotNull Boolean> permissionOverrides) {
        public UpdateUserRequest(String displayName, String email, String status, Set<String> roleCodes,
                                 String newPassword, String organizationId,
                                 Map<String, Boolean> permissionOverrides) {
            this(displayName, email, status, roleCodes, newPassword, organizationId,
                    null, null, permissionOverrides);
        }
    }

    public record OrganizationView(String id, String code, String name, String parentId) {
    }

    public record OrganizationRequest(@NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,24}") String code,
                                      @NotBlank @Size(max = 100) String name,
                                      String parentId) {
    }

    public record RolePermissions(String roleId, String roleCode, Set<String> permissions) {
    }

    public record PermissionView(String code, String name) {
    }

    public record UpdateRolePermissionsRequest(@NotNull Set<@NotBlank String> permissions) {
    }

    public record UserPermissionOverrides(@NotNull Map<@NotBlank String, @NotNull Boolean> overrides) {
    }

    public enum ReversionScope { SMALL_BATCH, LARGE_BATCH, ORIGINAL_RESTORE }
    public enum ApprovalDecision { APPROVE, REJECT }

    public record CreateReversionRequest(@NotBlank @Size(max = 40000) String targetFilter,
                                         @jakarta.validation.constraints.NotNull ReversionScope scope,
                                         @NotBlank @Size(min = 5, max = 500) String reason,
                                         @NotBlank @Size(max = 80) String idempotencyKey) {
    }

    public record ReviewRequest(@NotBlank @Size(min = 2, max = 300) String comment) {
    }

    public record ReversionView(String id, String requestNo, ReversionScope scope, String targetFilter,
                                String reason, String status, String requestedBy, Instant requestedAt,
                                Instant approvedAt, Instant executedAt, String reviewer,
                                String reviewComment, Instant reviewedAt) {
    }

    public record AuditLogView(String id, String requestId, String actor, String operation,
                               String tableName, String recordKey, boolean success, String detail,
                               Instant createdAt) {
    }

    public record AlertView(String id, String type, String severity, String message, String status,
                            String relatedTable, String relatedId, Instant createdAt, Instant resolvedAt) {
    }

    public record IntegrityView(boolean valid, long checkedEntries, Long firstInvalidSequence,
                                String message) {
    }

    public record AdminGradeView(String id, String enrollmentId, String status, int version,
                                 String studentId, String studentNo, String studentName,
                                 String offeringId, String courseId, String courseCode, String courseName,
                                 String academicYear, int semester, String className,
                                 Instant submittedAt, Instant updatedAt) {
    }

    public record RestoreOriginalRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record SmallReversionRequest(@NotEmpty @Size(max = 10) List<@NotBlank String> gradeIds,
                                        @NotBlank @Size(min = 5, max = 500) String reason,
                                        @NotBlank @Size(max = 80) String idempotencyKey) {
    }

    public record RecoveryEvidence(long sequence, String eventType, String aggregateType,
                                   String aggregateId, String previousHash, String entryHash,
                                   Instant createdAt, String actor) {
    }

    public record RecoverySnapshotView(long sequence, String eventType, String aggregateId,
                                       java.util.Map<String, String> originalValues, Instant createdAt) {
    }
}
