package edu.chd.practice.web.controller;

import edu.chd.practice.web.api.ApiResponse;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.service.RiskAnalysisService;
import edu.chd.practice.web.service.StudentGradeService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/student")
public class StudentController {
    private final StudentGradeService gradeService;
    private final RiskAnalysisService riskAnalysisService;

    public StudentController(StudentGradeService gradeService, RiskAnalysisService riskAnalysisService) {
        this.gradeService = gradeService;
        this.riskAnalysisService = riskAnalysisService;
    }

    @GetMapping("/grades")
    public ApiResponse<GradeDtos.StudentOverview> grades(
            @RequestParam(defaultValue = "current") @Pattern(regexp = "current|all") String scope) {
        return ApiResponse.ok(gradeService.grades(scope));
    }

    @GetMapping("/overview")
    public ApiResponse<GradeDtos.StudentOverview> overview() {
        return ApiResponse.ok(gradeService.grades("current"));
    }

    @GetMapping("/ranking")
    public ApiResponse<GradeDtos.Ranking> ranking(@RequestParam String offeringId) {
        return ApiResponse.ok(gradeService.ranking(offeringId));
    }

    @GetMapping("/warnings")
    public ApiResponse<List<GradeDtos.FailureWarning>> warnings() {
        return ApiResponse.ok(gradeService.warnings());
    }

    @GetMapping("/courses")
    public ApiResponse<List<GradeDtos.StudentCourseView>> courses() {
        return ApiResponse.ok(gradeService.courses());
    }

    @GetMapping("/risk")
    public ApiResponse<GradeDtos.RiskAssessment> risk(
            @RequestParam String courseId,
            @RequestParam @DecimalMin("0") @DecimalMax("100") BigDecimal usualScore,
            @RequestParam @DecimalMin("0") @DecimalMax("100") BigDecimal labScore) {
        return ApiResponse.ok(riskAnalysisService.forSelf(courseId, usualScore, labScore));
    }
}
