package edu.chd.practice.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.contract.dto.Filter;
import edu.chd.practice.rmi.contract.dto.FilterOperator;
import edu.chd.practice.rmi.contract.dto.SelectRequest;
import edu.chd.practice.web.dto.GradeDtos;
import edu.chd.practice.web.error.ApiException;
import edu.chd.practice.web.crypto.GradeCryptoService;
import edu.chd.practice.web.rmi.RemoteDataGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class StudentGradeService extends RemoteTableSupport {
    private static final List<String> GRADE_COLUMNS = List.of("id", "enrollment_id", "score_ciphertext",
            "score_nonce", "score_integrity", "status");
    private final ResourceAccessService access;
    private final GradeCryptoService crypto;
    private final ObjectMapper objectMapper;

    public StudentGradeService(RemoteDataGateway gateway, ResourceAccessService access,
                               GradeCryptoService crypto, ObjectMapper objectMapper) {
        super(gateway);
        this.access = access;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
    }

    public GradeDtos.StudentOverview grades(String scope) {
        Map<String, String> student = access.requireStudentProfile();
        List<GradeDtos.StudentGradeView> grades = submittedGrades(student.get("id"), scope);
        BigDecimal weighted = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        BigDecimal earned = BigDecimal.ZERO;
        int failures = 0;
        for (GradeDtos.StudentGradeView grade : grades) {
            weighted = weighted.add(grade.score().multiply(grade.credit()));
            credits = credits.add(grade.credit());
            if (grade.score().compareTo(BigDecimal.valueOf(60)) >= 0) {
                earned = earned.add(grade.credit());
            } else {
                failures++;
            }
        }
        BigDecimal average = credits.signum() == 0 ? BigDecimal.ZERO
                : weighted.divide(credits, 2, RoundingMode.HALF_UP);
        return new GradeDtos.StudentOverview(grades, average, earned, failures);
    }

    public GradeDtos.Ranking ranking(String offeringId) {
        Map<String, String> student = access.requireStudentProfile();
        Map<String, String> enrollment = requireOne("enrollments", List.of("id"), List.of(
                Filter.of("offering_id", FilterOperator.EQ, offeringId),
                Filter.of("student_id", FilterOperator.EQ, student.get("id"))),
                "STUDENT_NOT_ENROLLED", "你未选修该课程");
        Set<String> enrollmentIds = values(gateway.selectAsAnalytics(new SelectRequest("enrollments",
                List.of("id"), List.of(Filter.of("offering_id", FilterOperator.EQ, offeringId)),
                List.of(), 0, 500)), "id");
        List<ScoreRecord> scores = gateway.selectAsAnalytics(new SelectRequest("grades", List.of("id",
                        "enrollment_id", "score_ciphertext", "score_nonce", "score_integrity", "status"), List.of(
                new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), List.of(), 0, 500)).stream()
                .map(row -> new ScoreRecord(row.get("enrollment_id"), visibleFinal(row, payload(row)))).toList();
        BigDecimal own = scores.stream().filter(score -> score.enrollmentId().equals(enrollment.get("id")))
                .map(ScoreRecord::score).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "GRADE_NOT_SUBMITTED", "该课程尚无已提交成绩"));
        int rank = 1 + (int) scores.stream().filter(score -> score.score().compareTo(own) > 0).count();
        BigDecimal percentile = BigDecimal.valueOf((scores.size() - rank + 1) * 100.0 / scores.size())
                .setScale(2, RoundingMode.HALF_UP);
        return new GradeDtos.Ranking(offeringId, rank, scores.size(), percentile, own);
    }

    public List<GradeDtos.FailureWarning> warnings() {
        return grades("all").grades().stream()
                .filter(grade -> grade.score().compareTo(BigDecimal.valueOf(60)) < 0)
                .map(grade -> new GradeDtos.FailureWarning(grade.offeringId(), grade.courseCode(),
                        grade.courseName(), grade.score(), "当前成绩未达到 60 分，请及时联系任课教师并准备补考。"))
                .toList();
    }

    public List<GradeDtos.StudentCourseView> courses() {
        Map<String, String> student = access.requireStudentProfile();
        List<Map<String, String>> enrollments = rows("enrollments", List.of("offering_id"),
                List.of(Filter.of("student_id", FilterOperator.EQ, student.get("id"))), List.of(), 0, 500);
        Set<String> offeringIds = values(enrollments, "offering_id");
        if (offeringIds.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> offerings = offerings(offeringIds);
        Set<String> courseIds = values(offerings.values().stream().toList(), "course_id");
        if (courseIds.isEmpty()) {
            return List.of();
        }
        return courses(courseIds).values().stream()
                .map(course -> new GradeDtos.StudentCourseView(course.get("id"),
                        course.get("course_code"), course.get("name")))
                .sorted(Comparator.comparing(GradeDtos.StudentCourseView::code)
                        .thenComparing(GradeDtos.StudentCourseView::id))
                .toList();
    }

    public List<GradeDtos.StudentGradeView> submittedGrades(String studentId, String scope) {
        List<Map<String, String>> enrollments = rows("enrollments", List.of("id", "offering_id"),
                List.of(Filter.of("student_id", FilterOperator.EQ, studentId)), List.of(), 0, 500);
        if (enrollments.isEmpty()) {
            return List.of();
        }
        Map<String, Map<String, String>> enrollmentById = enrollments.stream()
                .collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
        Map<String, Map<String, String>> offerings = offerings(values(enrollments, "offering_id"));
        Map<String, Map<String, String>> courses = courses(values(offerings.values().stream().toList(), "course_id"));
        AcademicTerm current = AcademicTerm.current();
        Set<String> enrollmentIds = values(enrollments, "id");
        List<Map<String, String>> gradeRows = rows("grades", GRADE_COLUMNS, List.of(
                new Filter("enrollment_id", FilterOperator.IN, List.copyOf(enrollmentIds)),
                Filter.of("status", FilterOperator.EQ, "SUBMITTED")), List.of(), 0, 500);
        List<GradeDtos.StudentGradeView> result = new ArrayList<>();
        for (Map<String, String> grade : gradeRows) {
            Map<String, String> enrollment = enrollmentById.get(grade.get("enrollment_id"));
            Map<String, String> offering = offerings.get(enrollment.get("offering_id"));
            if (offering == null) {
                continue;
            }
            if ("current".equalsIgnoreCase(scope) && (!current.academicYear().equals(offering.get("academic_year"))
                    || current.semester() != integer(offering, "semester"))) {
                continue;
            }
            Map<String, String> course = courses.get(offering.get("course_id"));
            if (course == null) {
                continue;
            }
            GradePayload payload = payload(grade);
            boolean makeupVisible = "SUBMITTED".equals(payload.makeupStatus()) && payload.makeupRawScore() != null;
            BigDecimal finalScore = makeupVisible ? payload.finalScore() : payload.regularScore();
            result.add(new GradeDtos.StudentGradeView(grade.get("id"), offering.get("id"), course.get("id"),
                    course.get("course_code"), course.get("name"), decimal(course, "credit"),
                    offering.get("academic_year"), integer(offering, "semester"), payload.regularScore(),
                    makeupVisible ? payload.makeupRawScore() : null,
                    makeupVisible ? payload.makeupEffectiveScore() : null, finalScore, finalScore,
                    makeupVisible && payload.makeupRawScore().compareTo(BigDecimal.valueOf(60)) > 0,
                    grade.get("status")));
        }
        return result.stream().sorted(Comparator.comparing(GradeDtos.StudentGradeView::academicYear).reversed()
                .thenComparing(GradeDtos.StudentGradeView::semester, Comparator.reverseOrder())
                .thenComparing(GradeDtos.StudentGradeView::courseCode)).toList();
    }

    private Map<String, Map<String, String>> offerings(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rows("course_offerings", List.of("id", "course_id", "academic_year", "semester"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))), List.of(), 0, 500).stream()
                .collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
    }

    private Map<String, Map<String, String>> courses(Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return rows("courses", List.of("id", "course_code", "name", "credit"),
                List.of(new Filter("id", FilterOperator.IN, List.copyOf(ids))), List.of(), 0, 500).stream()
                .collect(Collectors.toMap(row -> row.get("id"), Function.identity()));
    }

    private GradePayload payload(Map<String, String> grade) {
        try {
            return objectMapper.readValue(crypto.decrypt(grade.get("id"), grade.get("score_ciphertext"),
                    grade.get("score_nonce"), grade.get("score_integrity")), GradePayload.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "GRADE_PAYLOAD_INVALID", "成绩明文结构无效", exception);
        }
    }

    private Set<String> values(List<Map<String, String>> rows, String column) {
        return rows.stream().map(row -> row.get(column)).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private BigDecimal visibleFinal(Map<String, String> grade, GradePayload payload) {
        return "SUBMITTED".equals(payload.makeupStatus()) && payload.makeupRawScore() != null
                ? payload.finalScore() : payload.regularScore();
    }

    private record GradePayload(Map<String, BigDecimal> componentScores, BigDecimal regularScore,
                                BigDecimal makeupRawScore, BigDecimal makeupEffectiveScore,
                                BigDecimal finalScore, String makeupStatus) {
    }

    private record ScoreRecord(String enrollmentId, BigDecimal score) {
    }

    private record AcademicTerm(String academicYear, int semester) {
        static AcademicTerm current() {
            LocalDate now = LocalDate.now();
            int startYear = now.getMonthValue() >= 8 ? now.getYear() : now.getYear() - 1;
            int semester = now.getMonthValue() >= 2 && now.getMonthValue() <= 7 ? 2 : 1;
            return new AcademicTerm(startYear + "-" + (startYear + 1), semester);
        }
    }
}
