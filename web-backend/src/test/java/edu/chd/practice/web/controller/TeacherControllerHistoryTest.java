package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.service.RiskAnalysisService;
import edu.chd.practice.web.service.TeacherCourseService;
import edu.chd.practice.web.service.TeacherGradeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeacherControllerHistoryTest {
    @Test
    void returnsThePagedHistoryContractAndForwardsEveryFilter() {
        TeacherCourseService courseService = mock(TeacherCourseService.class);
        TeacherController controller = new TeacherController(courseService,
                mock(TeacherGradeService.class), mock(RiskAnalysisService.class));
        PageResult<GradeDtos.HistoryCourseView> expected = PageResult.of(List.of(
                new GradeDtos.HistoryCourseView("offering-1", "course-1", "CS101", "程序设计",
                        "2025-2026", 2, "一班")), 3, 10, 42);
        when(courseService.historyCourses("程序", "2025-2026", 2, 3, 10)).thenReturn(expected);

        ApiResponse<PageResult<GradeDtos.HistoryCourseView>> response = controller.historyCourses(
                "程序", "2025-2026", 2, 3, 10);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isSameAs(expected);
        verify(courseService).historyCourses("程序", "2025-2026", 2, 3, 10);
    }
}
