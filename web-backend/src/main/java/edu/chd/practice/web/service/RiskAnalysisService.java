package edu.chd.practice.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.analytics.ExplainableDecisionTree;
import edu.chd.practice.web.analytics.MultipleLinearRegression;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RiskAnalysisService extends RemoteTableSupport {
    private final ResourceAccessService access;
    private final GradeCryptoService crypto;
    private final ObjectMapper objectMapper;

    public RiskAnalysisService(RemoteDataGateway gateway, ResourceAccessService access,
                               GradeCryptoService crypto, ObjectMapper objectMapper) {
        super(gateway);
        this.access = access;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    public GradeDtos.RiskAssessment forSelf(String courseId, BigDecimal usualScore, BigDecimal labScore) {
        access.requirePermission("RISK_SELF_ANALYZE");
        Map<String, String> student = access.requireStudentProfile();
        access.requireStudentEnrolledInCourse(student.get("id"), courseId);
        ModelBundle model = train(courseId);
        GradeDtos.Prediction prediction = predict(model,
                new GradeDtos.PredictionInput(student.get("id"), usualScore, labScore));
        return new GradeDtos.RiskAssessment(student.get("id"), courseId, model.academicYears().size(),
                prediction.predictedFinalExam(), prediction.failureProbability(), prediction.riskLevel(),
                regressionExplanation(model), prediction.decisionPath(), Instant.now());
    }

    public GradeDtos.RiskAssessment forTeacher(String offeringId, String studentId) {
        Map<String, String> offering = access.requireTeachingOffering(offeringId, "RISK_ANALYZE");
        access.requireStudentEnrolledInOffering(studentId, offeringId);
        GradeDtos.PredictionInput input = currentInput(studentId, offeringId);
        ModelBundle model = train(offering.get("course_id"));
        GradeDtos.Prediction prediction = predict(model, input);
        return new GradeDtos.RiskAssessment(studentId, offering.get("course_id"), model.academicYears().size(),
                prediction.predictedFinalExam(), prediction.failureProbability(), prediction.riskLevel(),
                regressionExplanation(model), prediction.decisionPath(), Instant.now());
    }

    public GradeDtos.PredictionBatchResult forTeacher(String offeringId,
                                                       GradeDtos.PredictionBatchRequest request) {
        Map<String, String> offering = access.requireTeachingOffering(offeringId, "RISK_ANALYZE");
        for (GradeDtos.PredictionInput input : request.students()) {
            if (input.studentId() == null || input.studentId().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "STUDENT_ID_REQUIRED", "整班预测必须提供学生ID");
            }
            access.requireStudentEnrolledInOffering(input.studentId(), offeringId);
        }
        ModelBundle model = train(offering.get("course_id"));
        return result(model, request.students());
    }

    private GradeDtos.PredictionBatchResult result(ModelBundle model, List<GradeDtos.PredictionInput> inputs) {
        Map<String, BigDecimal> coefficients = new LinkedHashMap<>();
        double[] rawCoefficients = model.regression().coefficients();
        for (int index = 0; index < rawCoefficients.length; index++) {
            coefficients.put(model.regression().featureNames().get(index), decimal(rawCoefficients[index]));
        }
        GradeDtos.RegressionSummary regression = new GradeDtos.RegressionSummary(coefficients,
                decimal(model.regression().intercept()), decimal(model.regression().rSquared()),
                decimal(model.regression().residualStandardError()), model.regression().sampleCount(),
                model.academicYears());
        return new GradeDtos.PredictionBatchResult(regression, node(model.tree().root(), model.tree().featureNames()),
                inputs.stream().map(input -> predict(model, input)).toList(), Instant.now(), false);
    }

    private GradeDtos.Prediction predict(ModelBundle model, GradeDtos.PredictionInput input) {
        double usual = input.usualScore().doubleValue();
        double lab = input.labScore().doubleValue();
        MultipleLinearRegression.Prediction regression = model.regression().predict(usual, lab);
        ExplainableDecisionTree.Decision decision = model.tree().predict(usual, lab);
        double value = clamp(regression.value());
        double probability = decision.failureProbability() * 100;
        String level = RiskLevelPolicy.level(value, probability);
        List<String> decisionPath = new ArrayList<>(decision.path());
        if (significantlyBelowHistoricalFinalMean(value, model.historicalFinalExamMean())) {
            decisionPath.add("预测期末成绩较历史样本期末均值低至少 10 分，建议关注学习风险。");
        }
        return new GradeDtos.Prediction(input.studentId(), decimal(value), decimal(clamp(regression.intervalLow())),
                decimal(clamp(regression.intervalHigh())), decimal(probability), level, List.copyOf(decisionPath));
    }

    private ModelBundle train(String courseId) {
        if (courseId == null || courseId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "COURSE_ID_REQUIRED", "模型训练必须指定课程");
        }
        List<Map<String, String>> offeringRows = analytics("course_offerings",
                List.of("id", "academic_year"),
                List.of(Filter.of("course_id", FilterOperator.EQ, courseId)), 500);
        Set<String> academicYears = values(offeringRows, "academic_year");
        if (academicYears.size() < 3) {
            throw insufficient("同一课程必须覆盖至少三个不同学年");
        }
        Set<String> offeringIds = values(offeringRows, "id");
        List<Map<String, String>> enrollmentRows = analytics("enrollments", List.of("id", "offering_id"),
                List.of(new Filter("offering_id", FilterOperator.IN, List.copyOf(offeringIds))), 500);
        if (enrollmentRows.isEmpty()) {
            throw insufficient("历史课程没有可用选课样本");
        }
        Map<String, String> offeringByEnrollment = enrollmentRows.stream()
                .collect(Collectors.toMap(row -> row.get("id"), row -> row.get("offering_id")));
        Map<String, String> yearByOffering = offeringRows.stream()
                .collect(Collectors.toMap(row -> row.get("id"), row -> row.get("academic_year")));
        List<Map<String, String>> grades = analytics("grades", List.of("id", "enrollment_id",
                        "score_ciphertext", "score_nonce", "score_integrity"), List.of(
                        new Filter("enrollment_id", FilterOperator.IN, List.copyOf(offeringByEnrollment.keySet())),
                        Filter.of("status", FilterOperator.EQ, "SUBMITTED")), 500);
        List<TrainingSample> samples = new ArrayList<>();
        for (Map<String, String> grade : grades) {
            GradePayload payload = payload(grade);
            Double usual = component(payload.componentScores(), Component.USUAL);
            Double lab = component(payload.componentScores(), Component.LAB);
            Double finalExam = component(payload.componentScores(), Component.FINAL_EXAM);
            if (usual != null && lab != null && finalExam != null) {
                String offeringId = offeringByEnrollment.get(grade.get("enrollment_id"));
                samples.add(new TrainingSample(usual, lab, finalExam, yearByOffering.get(offeringId)));
            }
        }
        Set<String> usableYears = samples.stream().map(TrainingSample::academicYear)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (usableYears.size() < 3 || samples.size() < 6) {
            throw insufficient("有效平时/实验/期末样本需至少六条并覆盖三个学年");
        }
        double[][] features = samples.stream().map(sample -> new double[]{sample.usual(), sample.lab()})
                .toArray(double[][]::new);
        double[] targets = samples.stream().mapToDouble(TrainingSample::finalExam).toArray();
        boolean[] failures = new boolean[samples.size()];
        for (int index = 0; index < samples.size(); index++) {
            failures[index] = samples.get(index).finalExam() < 60;
        }
        MultipleLinearRegression.Model regression;
        ExplainableDecisionTree.Model tree;
        try {
            regression = MultipleLinearRegression.fit(List.of("usualScore", "labScore"), features, targets);
            tree = ExplainableDecisionTree.fit(List.of("usualScore", "labScore"), features, failures, 3, 2);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_TRAINING_FAILED",
                    "历史样本无法形成稳定模型: " + exception.getMessage(), exception);
        }
        double historicalFinalExamMean = samples.stream().mapToDouble(TrainingSample::finalExam)
                .average().orElseThrow();
        return new ModelBundle(regression, tree, Set.copyOf(usableYears), historicalFinalExamMean);
    }

    private GradeDtos.PredictionInput currentInput(String studentId, String offeringId) {
        Map<String, String> enrollment = requireOne("enrollments", List.of("id"), List.of(
                Filter.of("student_id", FilterOperator.EQ, studentId),
                Filter.of("offering_id", FilterOperator.EQ, offeringId)),
                "STUDENT_NOT_ENROLLED", "学生未选修该课程");
        Map<String, String> grade = requireOne("grades", List.of("id", "score_ciphertext", "score_nonce",
                        "score_integrity"), List.of(Filter.of("enrollment_id", FilterOperator.EQ,
                        enrollment.get("id"))), "CURRENT_COMPONENTS_NOT_FOUND", "学生尚无当前评分项数据");
        GradePayload payload = payload(grade);
        Double usual = component(payload.componentScores(), Component.USUAL);
        Double lab = component(payload.componentScores(), Component.LAB);
        if (usual == null || lab == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENT_COMPONENTS_NOT_FOUND",
                    "当前成绩缺少平时或实验评分项");
        }
        return new GradeDtos.PredictionInput(studentId, decimal(usual), decimal(lab));
    }

    private List<Map<String, String>> analytics(String table, List<String> columns,
                                                List<Filter> filters, int size) {
        return gateway.selectAsAnalytics(new SelectRequest(table, columns, filters, List.of(), 0, size));
    }

    private GradePayload payload(Map<String, String> grade) {
        try {
            return objectMapper.readValue(crypto.decrypt(grade.get("id"), grade.get("score_ciphertext"),
                    grade.get("score_nonce"), grade.get("score_integrity")), GradePayload.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_PAYLOAD_INVALID", "历史成绩结构无效", exception);
        }
    }

    private Double component(Map<String, BigDecimal> components, Component component) {
        if (components == null) {
            return null;
        }
        for (Map.Entry<String, BigDecimal> entry : components.entrySet()) {
            String normalized = entry.getKey().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\p{IsHan}]", "");
            if (component.matches(normalized)) {
                return entry.getValue().doubleValue();
            }
        }
        return null;
    }

    private GradeDtos.DecisionTreeNode node(ExplainableDecisionTree.Node node, List<String> featureNames) {
        return new GradeDtos.DecisionTreeNode(node.leaf(), node.leaf() ? null : featureNames.get(node.featureIndex()),
                node.leaf() ? null : decimal(node.threshold()), decimal(node.failureProbability() * 100),
                node.samples(), node.failures(), node.left() == null ? null : node(node.left(), featureNames),
                node.right() == null ? null : node(node.right(), featureNames));
    }

    private List<String> regressionExplanation(ModelBundle model) {
        return List.of(
                "模型使用同一课程 " + model.academicYears().size() + " 个学年的历史成绩，结果未持久化。",
                "期末预测 = " + decimal(model.regression().coefficients()[0]) + " * 平时 + "
                        + decimal(model.regression().coefficients()[1]) + " * 实验 + "
                        + decimal(model.regression().intercept()) + "。",
                "R²=" + decimal(model.regression().rSquared()) + "，95%预测区间已随结果返回。"
        );
    }

    private Set<String> values(List<Map<String, String>> rows, String column) {
        return rows.stream().map(row -> row.get(column)).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private double clamp(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private ApiException insufficient(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_RISK_SAMPLES",
                "预警分析样本不足: " + detail);
    }

    static boolean significantlyBelowHistoricalFinalMean(double predictedFinalExam,
                                                          double historicalFinalExamMean) {
        return Double.isFinite(predictedFinalExam) && Double.isFinite(historicalFinalExamMean)
                && historicalFinalExamMean - predictedFinalExam >= 10.0;
    }

    private enum Component {
        USUAL(List.of("USUAL", "PROCESS", "CONTINUOUS", "DAILY", "平时")),
        LAB(List.of("LAB", "EXPERIMENT", "PRACTICAL", "实验")),
        FINAL_EXAM(List.of("FINAL", "EXAM", "期末"));

        private final List<String> aliases;

        Component(List<String> aliases) {
            this.aliases = aliases;
        }

        boolean matches(String value) {
            return aliases.stream().anyMatch(value::contains);
        }
    }

    private record TrainingSample(double usual, double lab, double finalExam, String academicYear) {
    }

    private record ModelBundle(MultipleLinearRegression.Model regression,
                               ExplainableDecisionTree.Model tree, Set<String> academicYears,
                               double historicalFinalExamMean) {
    }

    private record GradePayload(Map<String, BigDecimal> componentScores, BigDecimal regularScore,
                                BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                                BigDecimal finalScore, String makeupStatus) {
    }
}
