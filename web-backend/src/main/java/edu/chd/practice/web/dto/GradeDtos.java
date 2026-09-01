package edu.chd.practice.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class GradeDtos {
    private GradeDtos() {
    }

    public record CourseView(String offeringId, String courseId, String courseCode, String courseName,
                             BigDecimal credit, String academicYear, int semester, String className,
                             String status, long enrolledStudents) {
    }

    public record HistoryCourseView(String offeringId, String courseId, String courseCode, String courseName,
                                    String academicYear, int semester, String className) {
    }

    public record WeightItem(String id,
                             @NotBlank @Size(max = 32) @Pattern(regexp = "[A-Z][A-Z0-9_]{0,31}") String itemCode,
                             @NotBlank @Size(max = 64) String itemName,
                             @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal weight,
                             @NotNull @DecimalMin("100") @DecimalMax("100") BigDecimal maxScore,
                             @Min(1) @Max(100) int sortOrder) {
    }

    public record WeightScheme(String id, String offeringId, String name, BigDecimal totalWeight,
                               int version, String status, List<WeightItem> items) {
    }

    public record SaveWeightsRequest(@NotBlank @Size(max = 64) String name,
                                     @NotEmpty @Size(max = 20) List<@Valid WeightItem> items,
                                     @Min(0) int expectedVersion) {
    }

    public record GradeEntryInput(@NotBlank String enrollmentId,
                                  Map<@NotBlank String,
                                          @DecimalMin("0") @DecimalMax("100") BigDecimal> componentScores,
                                  @DecimalMin("0") @DecimalMax("100") BigDecimal makeupRawScore,
                                  @NotNull ExamType examType,
                                  @Min(0) int expectedVersion) {
    }

    public enum ExamType { REGULAR, RETAKE }

    public record BatchDraftRequest(@NotBlank String schemeId,
                                    @NotEmpty @Size(max = 200) List<@Valid GradeEntryInput> entries,
                                    @NotBlank @Size(max = 80) String idempotencyKey) {
    }

    public record BatchActionRequest(@NotEmpty @Size(max = 200) List<@NotBlank String> gradeIds,
                                     @NotNull ExamType examType,
                                     @NotBlank @Size(min = 3, max = 300) String reason,
                                     @NotBlank @Size(max = 80) String idempotencyKey) {
    }

    public record GradeView(String id, String enrollmentId, String studentId, String studentNo,
                            String studentName, String schemeId, Map<String, BigDecimal> componentScores,
                            BigDecimal regularScore, BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                            BigDecimal finalScore, BigDecimal score, ExamType examType,
                            boolean cappedAtSixty, String makeupStatus, String status,
                            int version, Instant submittedAt, Instant updatedAt) {
    }

    public record GradeHistoryView(String id, String gradeId, String action, String reason, String scope,
                                   String batchId, String actorId, Instant createdAt) {
    }

    public record Statistics(long count, BigDecimal average, BigDecimal maximum, BigDecimal minimum,
                             BigDecimal median, BigDecimal passRate, BigDecimal standardDeviation,
                             Map<String, Long> distribution, String narrative, String savedAnalysis) {
    }

    public record AnalysisNoteRequest(@NotBlank @Size(max = 3000) String analysis) {
    }

    public record SavedAnalysis(String id, String offeringId, Statistics statistics, String analysis,
                                String updatedBy, Instant updatedAt) {
    }

    public record CourseHistoricalGrade(String academicYear, int semester, String offeringId,
                                        String studentId, String studentNo, String studentName,
                                        BigDecimal regularScore, BigDecimal makeupRawScore,
                                        BigDecimal makeupEffectiveScore, BigDecimal finalScore) {
    }

    public record StudentGradeView(String gradeId, String offeringId, String courseId,
                                   String courseCode, String courseName,
                                   BigDecimal credit, String academicYear, int semester,
                                   BigDecimal regularScore, BigDecimal makeupRawScore,
                                   BigDecimal makeupEffectiveScore, BigDecimal finalScore, BigDecimal score,
                                   boolean cappedAtSixty, String status) {
    }

    public record StudentOverview(List<StudentGradeView> grades, BigDecimal weightedAverage,
                                  BigDecimal earnedCredits, int failedCourses) {
    }

    public record StudentCourseView(String id, String code, String name) {
    }

    public record Ranking(String offeringId, int rank, int participants, BigDecimal percentile,
                          BigDecimal score) {
    }

    public record FailureWarning(String offeringId, String courseCode, String courseName,
                                 BigDecimal score, String message) {
    }

    public record RiskAssessment(String studentId, String courseId, int sampleYears,
                                 BigDecimal predictedScore, BigDecimal failureProbability,
                                 String level, List<String> linearRegressionExplanation,
                                 List<String> decisionPath, Instant generatedAt) {
    }

    public record PredictionInput(String studentId,
                                  @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal usualScore,
                                  @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal labScore) {
    }

    public record PredictionBatchRequest(@NotEmpty @Size(max = 200)
                                         List<@Valid PredictionInput> students) {
    }

    public record Prediction(String studentId, BigDecimal predictedFinalExam,
                             BigDecimal intervalLow, BigDecimal intervalHigh,
                             BigDecimal failureProbability, String riskLevel,
                             List<String> decisionPath) {
    }

    public record RegressionSummary(Map<String, BigDecimal> coefficients, BigDecimal intercept,
                                    BigDecimal rSquared, BigDecimal residualStandardError,
                                    int sampleCount, java.util.Set<String> academicYears) {
    }

    public record DecisionTreeNode(boolean leaf, String feature, BigDecimal threshold,
                                   BigDecimal failureProbability, int samples, int failures,
                                   DecisionTreeNode left, DecisionTreeNode right) {
    }

    public record PredictionBatchResult(RegressionSummary regression, DecisionTreeNode decisionTree,
                                        List<Prediction> predictions, Instant generatedAt,
                                        boolean persisted) {
    }
}
