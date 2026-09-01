package edu.chd.practice.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StudentGradeServiceTest {
    @Test
    void listsDistinctCoursesFromOnlyTheCurrentStudentsEnrollmentsIncludingUngradedCourses() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(access.requireStudentProfile()).thenReturn(Map.of("id", "student-1"));
        when(gateway.select(any())).thenAnswer(invocation -> select(invocation.getArgument(0)));
        StudentGradeService service = new StudentGradeService(gateway, access,
                mock(GradeCryptoService.class), new ObjectMapper());

        List<GradeDtos.StudentCourseView> courses = service.courses();

        assertThat(courses).containsExactly(
                new GradeDtos.StudentCourseView("course-1", "CS101", "程序设计"),
                new GradeDtos.StudentCourseView("course-2", "CS201", "数据结构"));
        verify(access).requireStudentProfile();
    }

    @Test
    void returnsEmptyWithoutReadingOfferingsWhenTheCurrentStudentHasNoEnrollments() {
        RemoteDataGateway gateway = mock(RemoteDataGateway.class);
        ResourceAccessService access = mock(ResourceAccessService.class);
        when(access.requireStudentProfile()).thenReturn(Map.of("id", "student-empty"));
        when(gateway.select(any())).thenAnswer(invocation -> {
            SelectRequest request = invocation.getArgument(0);
            assertThat(request.getTable()).isEqualTo("enrollments");
            assertStudentFilter(request.getFilters(), "student-empty");
            return List.of();
        });
        StudentGradeService service = new StudentGradeService(gateway, access,
                mock(GradeCryptoService.class), new ObjectMapper());

        assertThat(service.courses()).isEmpty();
        verify(access).requireStudentProfile();
    }

    private List<Map<String, String>> select(SelectRequest request) {
        return switch (request.getTable()) {
            case "enrollments" -> {
                assertStudentFilter(request.getFilters(), "student-1");
                yield List.of(
                        Map.of("offering_id", "offering-1"),
                        Map.of("offering_id", "offering-2"),
                        Map.of("offering_id", "offering-3"));
            }
            case "course_offerings" -> List.of(
                    Map.of("id", "offering-1", "course_id", "course-2",
                            "academic_year", "2026-2027", "semester", "1"),
                    Map.of("id", "offering-2", "course_id", "course-1",
                            "academic_year", "2026-2027", "semester", "1"),
                    Map.of("id", "offering-3", "course_id", "course-2",
                            "academic_year", "2025-2026", "semester", "2"));
            case "courses" -> List.of(
                    Map.of("id", "course-2", "course_code", "CS201", "name", "数据结构", "credit", "3"),
                    Map.of("id", "course-1", "course_code", "CS101", "name", "程序设计", "credit", "3"));
            default -> throw new AssertionError("Unexpected table " + request.getTable());
        };
    }

    private static void assertStudentFilter(List<Filter> filters, String studentId) {
        assertThat(filters).singleElement().satisfies(filter -> {
            assertThat(filter.getColumn()).isEqualTo("student_id");
            assertThat(filter.getValues()).containsExactly(studentId);
        });
    }
}
