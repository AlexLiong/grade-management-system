package edu.chd.practice.web.service;

import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.rmi.contract.dto.Sort;
import edu.chd.practice.rmi.contract.dto.SortDirection;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import edu.chd.practice.web.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeacherCourseServiceHistoryTest {
    @Test
    void filtersAndPagesTheCurrentTeachersClosedOfferingsWithAnExactTotal() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        UserPrincipal principal = new UserPrincipal("user-history", "history", "History Teacher", null,
                Set.of("TEACHER"), Set.of("GRADE_HISTORY_READ"), true);
        when(access.requirePermission("GRADE_HISTORY_READ")).thenReturn(principal);
        when(gateway.select(any())).thenAnswer(invocation -> select(invocation.getArgument(0)));
        when(gateway.count(any())).thenAnswer(invocation -> count(invocation.getArgument(0)));
        TeacherCourseService service = new TeacherCourseService(gateway, access, mock(AuditService.class));

        PageResult<GradeDtos.HistoryCourseView> courses = service.historyCourses(
                " CS_100% ", "2025-2026", 2, 40, 25);

        assertThat(courses.items()).containsExactly(new GradeDtos.HistoryCourseView(
                "offering-closed", "course-1", "CS_100%", "程序设计", "2025-2026", 2, "计科一班"));
        assertThat(courses.page()).isEqualTo(40);
        assertThat(courses.size()).isEqualTo(25);
        assertThat(courses.total()).isEqualTo(1001);
        assertThat(courses.totalPages()).isEqualTo(41);
        verify(access).requirePermission("GRADE_HISTORY_READ");
    }

    @Test
    void loadsOneClosedOfferingByIdWithoutDependingOnTheCurrentCatalogPage() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        UserPrincipal principal = new UserPrincipal("user-history", "history", "History Teacher", null,
                Set.of("TEACHER"), Set.of("GRADE_HISTORY_READ"), true);
        when(access.requirePermission("GRADE_HISTORY_READ")).thenReturn(principal);
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            if ("teachers".equals(request.getTable())) {
                return List.of(Map.of("id", "teacher-1"));
            }
            assertThat(request.getTable()).isEqualTo("teacher_history_courses");
            assertThat(request.getFilters()).extracting(Filter::getColumn)
                    .containsExactly("teacher_id", "id");
            assertThat(request.getFilters()).extracting(Filter::getValues)
                    .containsExactly(List.of("teacher-1"), List.of("offering-closed"));
            return List.of(Map.of("id", "offering-closed", "course_id", "course-1",
                    "course_code", "CS101", "course_name", "程序设计",
                    "academic_year", "2026-2027", "semester", "1", "class_name", "计科一班"));
        });
        TeacherCourseService service = new TeacherCourseService(gateway, access, mock(AuditService.class));

        GradeDtos.HistoryCourseView course = service.historyCourse("offering-closed");

        assertThat(course.offeringId()).isEqualTo("offering-closed");
        assertThat(course.courseId()).isEqualTo("course-1");
    }

    private List<Map<String, String>> select(SelectRequest request) {
        return switch (request.getTable()) {
            case "teachers" -> List.of(Map.of("id", "teacher-1"));
            case "teacher_history_courses" -> {
                assertHistoryFilters(request);
                assertThat(request.getPage()).isEqualTo(40);
                assertThat(request.getPageSize()).isEqualTo(25);
                assertThat(request.getSorts()).extracting(Sort::getColumn)
                        .containsExactly("academic_year", "semester", "course_code", "id");
                assertThat(request.getSorts()).extracting(Sort::getDirection)
                        .containsExactly(SortDirection.DESC, SortDirection.DESC,
                                SortDirection.ASC, SortDirection.ASC);
                yield List.of(Map.of("id", "offering-closed", "course_id", "course-1",
                        "course_code", "CS_100%", "course_name", "程序设计",
                        "academic_year", "2025-2026", "semester", "2", "class_name", "计科一班"));
            }
            default -> throw new AssertionError("Unexpected table " + request.getTable());
        };
    }

    private long count(SelectRequest request) {
        assertThat(request.getTable()).isEqualTo("teacher_history_courses");
        assertHistoryFilters(request);
        return 1001L;
    }

    private void assertHistoryFilters(SelectRequest request) {
        assertThat(request.getFilters()).extracting(Filter::getColumn)
                .containsExactly("teacher_id", "search_text", "academic_year", "semester");
        assertThat(request.getFilters()).extracting(Filter::getOperator)
                .containsExactly(FilterOperator.EQ, FilterOperator.CONTAINS,
                        FilterOperator.EQ, FilterOperator.EQ);
        assertThat(request.getFilters()).extracting(Filter::getValues)
                .containsExactly(List.of("teacher-1"), List.of("cs_100%"),
                        List.of("2025-2026"), List.of("2"));
    }
}
