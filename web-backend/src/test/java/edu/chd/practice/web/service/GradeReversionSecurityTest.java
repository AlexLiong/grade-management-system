package edu.chd.practice.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.AdminDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GradeReversionSecurityTest {
    private RemoteDataGateway gateway;
    private ResourceAccessService access;
    private GradeReversionService service;
    private UserPrincipal approver;

    @BeforeEach
    void setUp() {
        gateway = mock(RemoteDataGateway.class);
        access = mock(ResourceAccessService.class);
        service = new GradeReversionService(gateway, access, mock(AuditService.class),
                mock(GradeCryptoService.class), new ObjectMapper());
        approver = new UserPrincipal("admin-2", "admin02", "Admin 02", null,
                Set.of("ADMIN"), Set.of("GRADE_REVERT_APPROVE"), true);
        when(access.requirePermission("GRADE_REVERT_APPROVE")).thenReturn(approver);
    }

    @Test
    void smallReversionRequiresGradeReadBeforeReadingOrWritingGrades() {
        when(access.requirePermission("GRADE_REVERT_SMALL")).thenReturn(approver);
        deny("GRADE_READ");

        assertPermissionDenied(() -> service.smallReversion(new AdminDtos.SmallReversionRequest(
                List.of("grade-1"), "成绩录入错误", "small:permission-test")));

        verify(access).requirePermission("GRADE_REVERT_SMALL");
        verify(access).requirePermission("GRADE_READ");
        verifyNoInteractions(gateway);
    }

    @Test
    void completedSmallReversionReplaySkipsCurrentDraftStatusValidation() {
        when(access.requirePermission("GRADE_REVERT_SMALL")).thenReturn(approver);
        when(access.requirePermission("GRADE_READ")).thenReturn(approver);
        when(gateway.transactionCompleted(eq("small:replay"), anyString())).thenReturn(true);

        service.smallReversion(new AdminDtos.SmallReversionRequest(
                List.of("grade-1"), "成绩录入错误", "small:replay"));

        verify(gateway, never()).select(any());
        verify(gateway, never()).transaction(any());
    }

    @Test
    void completedHighRiskRequestReplayFindsTheDeterministicOriginalRequest() {
        when(access.requirePermission("GRADE_REVERT_REQUEST")).thenReturn(approver);
        when(gateway.transactionCompleted(eq("request:replay"), anyString())).thenReturn(true);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("reversion_requests".equals(request.getTable())) {
                return List.of(Map.of("id", "deterministic-id", "request_no", "REV-1", "scope", "LARGE",
                        "target_filter", "1[2:id2:IN1[7:grade-1]]", "reason", "批量成绩录入错误",
                        "status", "PENDING", "requested_by", "admin02",
                        "requested_at", "2026-08-31T10:00:00Z"));
            }
            if ("high_risk_approvals".equals(request.getTable())) {
                return List.of();
            }
            throw new AssertionError("Unexpected table " + request.getTable());
        });

        AdminDtos.ReversionView replay = service.create(new AdminDtos.CreateReversionRequest(
                "gradeIds:grade-1", AdminDtos.ReversionScope.LARGE_BATCH,
                "批量成绩录入错误", "request:replay"));

        assertThat(replay.requestNo()).isEqualTo("REV-1");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void gradeLookupRequiresReadPermissionBeforeQueryingRmi() {
        deny("GRADE_READ");

        assertPermissionDenied(() -> service.grades(null, null, "SUBMITTED", 0, 20));

        verify(access).requirePermission("GRADE_READ");
        verifyNoInteractions(gateway);
    }

    @Test
    void gradeLookupReturnsSelectableIdsWithCourseAndStudentMetadata() {
        when(access.requirePermission("GRADE_READ")).thenReturn(approver);
        when(gateway.count(any(SelectRequest.class))).thenReturn(1L);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "grades" -> List.of(Map.of(
                        "id", "grade-1", "enrollment_id", "enrollment-1", "status", "SUBMITTED",
                        "version", "3", "updated_at", "2026-08-31T10:00:00Z"));
                case "enrollments" -> List.of(Map.of(
                        "id", "enrollment-1", "offering_id", "offering-1", "student_id", "student-1"));
                case "students" -> List.of(Map.of(
                        "id", "student-1", "student_no", "20260001", "name", "张同学"));
                case "course_offerings" -> List.of(Map.of(
                        "id", "offering-1", "course_id", "course-1", "academic_year", "2025-2026",
                        "semester", "2", "class_name", "软件一班"));
                case "courses" -> List.of(Map.of(
                        "id", "course-1", "course_code", "SEC101", "name", "网络软件与安全"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        var result = service.grades(null, null, "SUBMITTED", 0, 20);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(grade -> {
            assertThat(grade.id()).isEqualTo("grade-1");
            assertThat(grade.studentNo()).isEqualTo("20260001");
            assertThat(grade.studentName()).isEqualTo("张同学");
            assertThat(grade.courseCode()).isEqualTo("SEC101");
            assertThat(grade.courseName()).isEqualTo("网络软件与安全");
            assertThat(grade.status()).isEqualTo("SUBMITTED");
        });
    }

    @Test
    void reversionListIncludesPersistedReviewerCommentAndReviewTime() {
        when(gateway.count(any(SelectRequest.class))).thenReturn(1L);
        when(gateway.select(any(SelectRequest.class))).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "reversion_requests" -> List.of(Map.of(
                        "id", "request-1", "request_no", "REV-1", "scope", "LARGE",
                        "target_filter", "target", "reason", "修正错误成绩", "status", "REJECTED",
                        "requested_by", "admin01", "requested_at", "2026-08-31T09:00:00Z"));
                case "high_risk_approvals" -> List.of(Map.of(
                        "reversion_request_id", "request-1", "approver", "admin02",
                        "decision", "REJECTED", "comment", "目标范围有误",
                        "created_at", "2026-08-31T10:00:00Z"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        var result = service.list("REJECTED", 0, 20);

        assertThat(result.items()).singleElement().satisfies(request -> {
            assertThat(request.reviewer()).isEqualTo("admin02");
            assertThat(request.reviewComment()).isEqualTo("目标范围有误");
            assertThat(request.reviewedAt()).hasToString("2026-08-31T10:00:00Z");
        });
    }

    @Test
    void largeApprovalRequiresGradeReadBeforePersistingApproval() {
        stubRequest("PENDING", "1[2:id2:IN1[7:grade-1]]");
        deny("GRADE_READ");

        assertPermissionDenied(() -> service.approve("request-1", new AdminDtos.ReviewRequest("同意执行")));

        verify(access).requirePermission("GRADE_READ");
        verify(gateway, never()).transaction(any());
    }

    @Test
    void recoveryApprovalRequiresRestorePermissionBeforePersistingApproval() {
        stubRequest("PENDING", "RECOVERY:12");
        deny("GRADE_RESTORE_ORIGINAL");

        assertPermissionDenied(() -> service.approve("request-1", new AdminDtos.ReviewRequest("同意恢复")));

        verify(access).requirePermission("GRADE_RESTORE_ORIGINAL");
        verify(gateway, never()).transaction(any());
        verify(gateway, never()).restoreGrade(any());
    }

    @Test
    void approvedRetryRechecksExecutionPermissionBeforeStartingATransaction() {
        stubRequest("APPROVED", "1[2:id2:IN1[7:grade-1]]");
        deny("GRADE_READ");

        assertPermissionDenied(() -> service.approve("request-1", new AdminDtos.ReviewRequest("重试执行")));

        verify(access).requirePermission("GRADE_READ");
        verify(gateway, never()).transaction(any());
    }

    private void stubRequest(String status, String targetFilter) {
        when(gateway.select(any(SelectRequest.class))).thenReturn(List.of(Map.of(
                "id", "request-1",
                "status", status,
                "requested_by", "admin01",
                "target_filter", targetFilter,
                "reason", "修正错误成绩"
        )));
    }

    private void deny(String permission) {
        when(access.requirePermission(permission)).thenThrow(new ApiException(
                HttpStatus.FORBIDDEN, "PERMISSION_REQUIRED", "missing " + permission));
    }

    private void assertPermissionDenied(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("PERMISSION_REQUIRED"));
    }
}
