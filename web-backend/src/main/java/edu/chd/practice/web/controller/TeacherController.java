package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.api.PageResult;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.service.RiskAnalysisService;
import edu.chd.practice.web.service.TeacherCourseService;
import edu.chd.practice.web.service.TeacherGradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/teacher")
public class TeacherController {
    private final TeacherCourseService courseService;
    private final TeacherGradeService gradeService;
    private final RiskAnalysisService riskAnalysisService;

    public TeacherController(TeacherCourseService courseService, TeacherGradeService gradeService,
                             RiskAnalysisService riskAnalysisService) {
        this.courseService = courseService;
        this.gradeService = gradeService;
        this.riskAnalysisService = riskAnalysisService;
    }

    @GetMapping("/courses")
    public ApiResponse<PageResult<GradeDtos.CourseView>> courses(
            @RequestParam(required = false) String academicYear,
            @RequestParam(required = false) @Min(1) @Max(2) Integer semester,
            @RequestParam(required = false) String className,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(courseService.courses(academicYear, semester, className, keyword, page, size));
    }

    @GetMapping("/history-courses")
    public ApiResponse<PageResult<GradeDtos.HistoryCourseView>> historyCourses(
            @RequestParam(required = false) @Size(max = 160) String keyword,
            @RequestParam(required = false) @Size(max = 16) String academicYear,
            @RequestParam(required = false) @Min(1) @Max(2) Integer semester,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(courseService.historyCourses(keyword, academicYear, semester, page, size));
    }

    @GetMapping("/history-courses/{offeringId}")
    public ApiResponse<GradeDtos.HistoryCourseView> historyCourse(@PathVariable String offeringId) {
        return ApiResponse.ok(courseService.historyCourse(offeringId));
    }

    @GetMapping("/courses/{offeringId}/weights")
    public ApiResponse<GradeDtos.WeightScheme> weights(@PathVariable String offeringId) {
        return ApiResponse.ok(courseService.weights(offeringId));
    }

    @PutMapping("/courses/{offeringId}/weights")
    public ApiResponse<GradeDtos.WeightScheme> saveWeights(@PathVariable String offeringId,
                                                            @Valid @RequestBody GradeDtos.SaveWeightsRequest request) {
        return ApiResponse.ok(courseService.saveWeights(offeringId, request));
    }

    @GetMapping("/courses/{offeringId}/grade-sheet")
    public ApiResponse<PageResult<GradeDtos.GradeView>> gradeSheet(
            @PathVariable String offeringId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(gradeService.gradeSheet(offeringId, status, page, size));
    }

    @PostMapping("/courses/{offeringId}/grades/draft")
    public ApiResponse<List<GradeDtos.GradeView>> saveDrafts(
            @PathVariable String offeringId,
            @Valid @RequestBody GradeDtos.BatchDraftRequest request) {
        return ApiResponse.ok(gradeService.saveDrafts(offeringId, request));
    }

    @PostMapping("/courses/{offeringId}/grades/submit")
    public ApiResponse<Void> submit(@PathVariable String offeringId,
                                    @Valid @RequestBody GradeDtos.BatchActionRequest request) {
        gradeService.submit(offeringId, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/courses/{offeringId}/grades/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable String offeringId,
                                      @Valid @RequestBody GradeDtos.BatchActionRequest request) {
        gradeService.withdraw(offeringId, request);
        return ApiResponse.ok(null);
    }

    @GetMapping("/courses/{offeringId}/statistics")
    public ApiResponse<GradeDtos.Statistics> statistics(@PathVariable String offeringId) {
        return ApiResponse.ok(gradeService.statistics(offeringId));
    }

    @PutMapping("/courses/{offeringId}/statistics")
    public ApiResponse<GradeDtos.SavedAnalysis> saveAnalysis(@PathVariable String offeringId,
            @Valid @RequestBody GradeDtos.AnalysisNoteRequest request) {
        return ApiResponse.ok(gradeService.saveAnalysis(offeringId, request));
    }

    @GetMapping("/course-history")
    public ApiResponse<PageResult<GradeDtos.CourseHistoricalGrade>> courseHistory(
            @RequestParam String courseId,
            @RequestParam(required = false) String academicYear,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(gradeService.courseHistory(courseId, academicYear, page, size));
    }

    @GetMapping("/courses/{offeringId}/history")
    public ApiResponse<PageResult<GradeDtos.GradeHistoryView>> history(
            @PathVariable String offeringId,
            @RequestParam(required = false) String gradeId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.ok(gradeService.history(offeringId, gradeId, page, size));
    }

    @GetMapping("/courses/{offeringId}/risk/{studentId}")
    public ApiResponse<GradeDtos.RiskAssessment> studentRisk(@PathVariable String offeringId,
                                                              @PathVariable String studentId) {
        return ApiResponse.ok(riskAnalysisService.forTeacher(offeringId, studentId));
    }

    @PostMapping("/courses/{offeringId}/predictions")
    public ApiResponse<GradeDtos.PredictionBatchResult> predictions(
            @PathVariable String offeringId,
            @Valid @RequestBody GradeDtos.PredictionBatchRequest request) {
        return ApiResponse.ok(riskAnalysisService.forTeacher(offeringId, request));
    }
}
