package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.service.AdminIdentityService;
import edu.chd.practice.web.service.AdminSecurityService;
import edu.chd.practice.web.service.GradeReversionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final AdminIdentityService identityService;
    private final AdminSecurityService securityService;
    private final GradeReversionService reversionService;

    public AdminController(AdminIdentityService identityService, AdminSecurityService securityService,
                           GradeReversionService reversionService) {
        this.identityService = identityService;
        this.securityService = securityService;
        this.reversionService = reversionService;
    }

    @GetMapping("/users")
    public ApiResponse<PageResult<AdminDtos.UserView>> users(@RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(required = false) String role,
                                                             @RequestParam(required = false) String organizationId,
                                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(identityService.users(keyword, status, role, organizationId, page, size));
    }

    @PostMapping("/users")
    public ApiResponse<AdminDtos.UserView> createUser(@Valid @RequestBody AdminDtos.CreateUserRequest request) {
        return ApiResponse.ok(identityService.createUser(request));
    }

    @PutMapping("/users/{id}")
    public ApiResponse<AdminDtos.UserView> updateUser(@PathVariable String id,
                                                      @Valid @RequestBody AdminDtos.UpdateUserRequest request) {
        return ApiResponse.ok(identityService.updateUser(id, request));
    }

    @DeleteMapping("/users/{id}")
    public ApiResponse<Void> disableUser(@PathVariable String id) {
        identityService.disableUser(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/organizations")
    public ApiResponse<List<AdminDtos.OrganizationView>> organizations() {
        return ApiResponse.ok(identityService.organizations());
    }

    @PostMapping("/organizations")
    public ApiResponse<AdminDtos.OrganizationView> createOrganization(
            @Valid @RequestBody AdminDtos.OrganizationRequest request) {
        return ApiResponse.ok(identityService.createOrganization(request));
    }

    @PutMapping("/organizations/{id}")
    public ApiResponse<AdminDtos.OrganizationView> updateOrganization(@PathVariable String id,
            @Valid @RequestBody AdminDtos.OrganizationRequest request) {
        return ApiResponse.ok(identityService.updateOrganization(id, request));
    }

    @DeleteMapping("/organizations/{id}")
    public ApiResponse<Void> deleteOrganization(@PathVariable String id) {
        identityService.deleteOrganization(id);
        return ApiResponse.ok(null);
    }

    @GetMapping("/roles")
    public ApiResponse<List<AdminDtos.RolePermissions>> roles() {
        return ApiResponse.ok(identityService.roles());
    }

    @GetMapping("/permissions")
    public ApiResponse<List<AdminDtos.PermissionView>> permissions() {
        return ApiResponse.ok(identityService.permissions());
    }

    @PutMapping("/roles/{id}/permissions")
    public ApiResponse<AdminDtos.RolePermissions> updateRolePermissions(@PathVariable String id,
            @Valid @RequestBody AdminDtos.UpdateRolePermissionsRequest request) {
        return ApiResponse.ok(identityService.updateRolePermissions(id, request));
    }

    @PutMapping("/users/{id}/permissions")
    public ApiResponse<AdminDtos.UserPermissionOverrides> updateUserPermissions(@PathVariable String id,
            @Valid @RequestBody AdminDtos.UserPermissionOverrides request) {
        return ApiResponse.ok(identityService.updateUserPermissions(id, request));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<PageResult<AdminDtos.AuditLogView>> auditLogs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(securityService.auditLogs(actor, operation, from, to, page, size));
    }

    @GetMapping("/alerts")
    public ApiResponse<PageResult<AdminDtos.AlertView>> alerts(@RequestParam(required = false) String status,
                                                               @RequestParam(required = false) String severity,
                                                               @RequestParam(defaultValue = "0") @Min(0) int page,
                                                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(securityService.alerts(status, severity, page, size));
    }

    @PatchMapping("/alerts/{id}/resolve")
    public ApiResponse<AdminDtos.AlertView> resolveAlert(@PathVariable String id) {
        return ApiResponse.ok(securityService.resolveAlert(id));
    }

    @PostMapping("/integrity/verify")
    public ApiResponse<AdminDtos.IntegrityView> verifyIntegrity() {
        return ApiResponse.ok(securityService.verifyIntegrity());
    }

    @GetMapping("/grades")
    public ApiResponse<PageResult<AdminDtos.AdminGradeView>> grades(
            @RequestParam(required = false) String course,
            @RequestParam(required = false) String student,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(reversionService.grades(course, student, status, page, size));
    }

    @GetMapping("/recovery-evidence")
    public ApiResponse<List<AdminDtos.RecoveryEvidence>> recoveryEvidence(@RequestParam String gradeId,
                                                                          @RequestParam(defaultValue = "20")
                                                                          @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(securityService.recoveryEvidence(gradeId, limit));
    }

    @PostMapping("/recovery-evidence/{sequence}/preview")
    public ApiResponse<AdminDtos.RecoverySnapshotView> previewRecovery(@PathVariable long sequence,
            @Valid @RequestBody AdminDtos.RestoreOriginalRequest request) {
        return ApiResponse.ok(securityService.previewRecovery(sequence, request.reason()));
    }

    @PostMapping("/grades/revert-small")
    public ApiResponse<Void> smallReversion(@Valid @RequestBody AdminDtos.SmallReversionRequest request) {
        reversionService.smallReversion(request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/reversion-requests")
    public ApiResponse<PageResult<AdminDtos.ReversionView>> reversionRequests(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(reversionService.list(status, page, size));
    }

    @GetMapping("/reversion-requests/{id}")
    public ApiResponse<AdminDtos.ReversionView> reversionRequest(@PathVariable String id) {
        return ApiResponse.ok(reversionService.get(id));
    }

    @PostMapping("/reversion-requests")
    public ApiResponse<AdminDtos.ReversionView> createReversion(
            @Valid @RequestBody AdminDtos.CreateReversionRequest request) {
        return ApiResponse.ok(reversionService.create(request));
    }

    @PostMapping("/reversion-requests/{id}/approve")
    public ApiResponse<AdminDtos.ReversionView> approve(@PathVariable String id,
            @Valid @RequestBody AdminDtos.ReviewRequest request) {
        return ApiResponse.ok(reversionService.approve(id, request));
    }

    @PostMapping("/reversion-requests/{id}/reject")
    public ApiResponse<AdminDtos.ReversionView> reject(@PathVariable String id,
            @Valid @RequestBody AdminDtos.ReviewRequest request) {
        return ApiResponse.ok(reversionService.reject(id, request));
    }
}
