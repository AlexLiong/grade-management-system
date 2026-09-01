package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceAccessServiceTest {
    private RemoteDataGateway gateway;
    private AuditService audit;
    private ResourceAccessService service;

    @BeforeEach
    void setUp() {
        gateway = mock(RemoteDataGateway.class);
        audit = mock(AuditService.class);
        service = new ResourceAccessService(gateway, audit);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void auditsRmiOwnershipDenialForTeachingOfferingAndRethrowsOriginalError() {
        authenticateTeacher("GRADE_READ");
        ApiException denial = rmiAccessDeniedForOffering();

        assertThatThrownBy(() -> service.requireTeachingOffering("offering-foreign", "GRADE_READ"))
                .isSameAs(denial);

        verify(audit).ownershipDenied("course_offerings", "offering-foreign");
    }

    @Test
    void auditsRmiOwnershipDenialBeforeOpenOfferingStatusCheck() {
        authenticateTeacher("GRADE_DRAFT_WRITE");
        ApiException denial = rmiAccessDeniedForOffering();

        assertThatThrownBy(() -> service.requireOpenTeachingOffering(
                "offering-foreign", "GRADE_DRAFT_WRITE"))
                .isSameAs(denial);

        verify(audit).ownershipDenied("course_offerings", "offering-foreign");
    }

    @Test
    void doesNotAuditOfferingLookupFailuresThatAreNotRmiAccessDenials() {
        authenticateTeacher("GRADE_READ");
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("teachers".equals(request.getTable())) {
                return List.of(Map.of("id", "teacher-1", "org_id", "org-1"));
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RMI_UNAVAILABLE", "RMI unavailable");
        });

        assertThatThrownBy(() -> service.requireTeachingOffering("offering-1", "GRADE_READ"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RMI_UNAVAILABLE"));

        verify(audit, never()).ownershipDenied(any(), any());
    }

    @Test
    void closedHistoryOwnershipLookupSuppliesTheClosedStatusFilter() {
        authenticateTeacher("GRADE_HISTORY_READ");
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("teachers".equals(request.getTable())) {
                return List.of(Map.of("id", "teacher-1", "org_id", "org-1"));
            }
            if ("course_offerings".equals(request.getTable())) {
                assertThat(request.getFilters()).extracting(Filter::getColumn)
                        .containsExactly("id", "status");
                assertThat(request.getFilters().get(1).getValues()).containsExactly("CLOSED");
                return List.of(Map.of("id", "offering-closed", "course_id", "course-1",
                        "teacher_id", "teacher-1", "academic_year", "2025-2026", "semester", "2",
                        "class_name", "一班", "status", "CLOSED"));
            }
            throw new AssertionError("Unexpected table " + request.getTable());
        });

        assertThatCode(() -> service.requireClosedTeachingOffering(
                "offering-closed", "GRADE_HISTORY_READ")).doesNotThrowAnyException();
    }

    @Test
    void rejectsRiskAnalysisForCourseTheStudentNeverTook() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return "enrollments".equals(request.getTable())
                    ? List.of(Map.of("offering_id", "offering-1")) : List.of();
        });

        assertThatThrownBy(() -> service.requireStudentEnrolledInCourse("student-1", "course-2"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("STUDENT_NOT_ENROLLED");
                });
        verify(audit).ownershipDenied("courses", "course-2:student-1");
    }

    @Test
    void acceptsRiskAnalysisForAPreviouslyEnrolledCourse() {
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            return switch (request.getTable()) {
                case "enrollments" -> List.of(Map.of("offering_id", "offering-1"));
                case "course_offerings" -> List.of(Map.of("id", "offering-1"));
                default -> throw new AssertionError("Unexpected table " + request.getTable());
            };
        });

        assertThatCode(() -> service.requireStudentEnrolledInCourse("student-1", "course-1"))
                .doesNotThrowAnyException();
    }

    private ApiException rmiAccessDeniedForOffering() {
        ApiException denial = new ApiException(HttpStatus.FORBIDDEN, "RMI_ACCESS_DENIED", "access denied");
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("teachers".equals(request.getTable())) {
                return List.of(Map.of("id", "teacher-1", "org_id", "org-1"));
            }
            if ("course_offerings".equals(request.getTable())) {
                throw denial;
            }
            throw new AssertionError("Unexpected table " + request.getTable());
        });
        return denial;
    }

    private void authenticateTeacher(String permission) {
        UserPrincipal principal = new UserPrincipal("user-1", "teacher", "Teacher", "org-1",
                Set.of("TEACHER"), Set.of(permission), true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities()));
    }
}
