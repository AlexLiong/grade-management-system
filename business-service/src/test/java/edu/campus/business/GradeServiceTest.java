package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GradeServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final CourseService courses = mock(CourseService.class);
  private final AnalyticsService analytics = mock(AnalyticsService.class);
  private final GradeService service = new GradeService(repo, courses, analytics);

  @Test
  void saveRequiresTeacherRole() {
    var student =
        new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    ApiException ex = assertThrows(ApiException.class, () -> service.save(student, Map.of()));
    assertEquals(400, ex.status);
  }

  @Test
  void saveRequiresAccessToCourse() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    when(courses.access(teacher, "c1", "ENTRY")).thenThrow(new ApiException(403, "X", "forbidden"));
    assertThrows(ApiException.class, () -> service.save(teacher, Map.of("courseId", "c1", "grades", List.of())));
  }

  @Test
  void saveRejectsEmptyGradesList() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    when(courses.access(teacher, "c1", "ENTRY")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    ApiException ex =
        assertThrows(ApiException.class, () -> service.save(teacher, Map.of("courseId", "c1", "courseVersion", 0, "grades", List.of())));
    assertEquals(400, ex.status);
  }

  @Test
  void saveRejectsTooManyGrades() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    when(courses.access(teacher, "c1", "ENTRY")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    var grades = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 301; i++)
      grades.add(Map.of("studentId", "s" + i, "scores", Map.of("regular", 50, "lab", 50, "finalExam", 60)));
    ApiException ex =
        assertThrows(ApiException.class, () -> service.save(teacher, Map.of("courseId", "c1", "courseVersion", 0, "grades", grades)));
    assertEquals(400, ex.status);
  }

  @Test
  void saveRequiresStudentsToBeEnrolled() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    when(courses.access(teacher, "c1", "ENTRY")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    when(repo.find("enrollments", Map.of("course_id", "c1"))).thenReturn(List.of());
    var grades = List.of(Map.of("studentId", "s1", "scores", Map.of("regular", 50, "lab", 50, "finalExam", 60)));
    ApiException ex =
        assertThrows(ApiException.class, () -> service.save(teacher, Map.of("courseId", "c1", "courseVersion", 0, "grades", grades)));
    assertEquals(400, ex.status);
  }

  @Test
  void saveRejectsDuplicateStudents() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    when(courses.access(teacher, "c1", "ENTRY")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    when(repo.find("enrollments", Map.of("course_id", "c1"))).thenReturn(List.of(Map.of("student_id", "s1")));
    var grades =
        List.of(
            Map.of("studentId", "s1", "scores", Map.of("regular", 50, "lab", 50, "finalExam", 60)),
            Map.of("studentId", "s1", "scores", Map.of("regular", 50, "lab", 50, "finalExam", 60)));
    ApiException ex =
        assertThrows(ApiException.class, () -> service.save(teacher, Map.of("courseId", "c1", "courseVersion", 0, "grades", grades)));
    assertEquals(400, ex.status);
  }

  @Test
  void transitionRejectsUnknownAction() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("MAINTAIN"), 0);
    when(courses.access(teacher, "c1", "MAINTAIN")).thenReturn(Map.of("id", "c1"));
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.transition(teacher, Map.of("courseId", "c1", "action", "INVALID")));
    assertEquals(400, ex.status);
  }

  @Test
  void transitionSubmitRequiresAllStudents() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("MAINTAIN"), 0);
    when(courses.access(teacher, "c1", "MAINTAIN")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    when(repo.find("grades", Map.of("course_id", "c1"))).thenReturn(List.of());
    when(repo.find("enrollments", Map.of("course_id", "c1"))).thenReturn(List.of(Map.of("id", "e1", "student_id", "s1")));
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                service.transition(
                    teacher, Map.of("courseId", "c1", "courseVersion", 0, "action", "SUBMIT")));
    assertEquals(409, ex.status);
  }

  @Test
  void transitionDeleteAllRequiresConfirmationMatch() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("GRADE_ADMIN"), 0);
    when(courses.access(admin, "c1", "GRADE_ADMIN")).thenReturn(Map.of("id", "c1"));
    when(repo.find("grades", Map.of("course_id", "c1"))).thenReturn(List.of());
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                service.transition(
                    admin, Map.of("courseId", "c1", "courseVersion", 0, "action", "DELETE_ALL", "confirmation", "wrong-id")));
    assertEquals(400, ex.status);
  }

  @Test
  void transcriptRestrictedToStudents() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY"), 0);
    assertThrows(ApiException.class, () -> service.transcript(teacher));
  }

  @Test
  void listFiltersStudentToSubmittedOnly() {
    var student =
        new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    when(courses.access(student, "c1", "QUERY")).thenReturn(Map.of("id", "c1", "weights", Settings.json(CourseService.defaultWeights())));
    // Return only SUBMITTED grades for student
    when(repo.find(eq("grades"), anyMap()))
        .thenReturn(
            List.of(
                Map.of("id", "g1", "student_id", "s1", "payload", Settings.json(Map.of("regular", 50, "lab", 50, "finalExam", 60)), "state", "SUBMITTED", "version", 0)));
    var result = service.list(student, "c1");
    assertEquals(1, result.size());
    assertEquals("SUBMITTED", result.get(0).get("state"));
  }

  // ------------------------------------------------- 学业记录里的重修状态（R5）

  /** 学业记录：同一课程代码在「挂科学期」与「重修学期」各一条，只有后一条标注重修。 */
  @Test
  void transcriptMarksLaterAttemptOfSameCodeAsRetake() {
    var student = new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    Map<String, Object> failed =
        Map.of("id", "c-fail", "code", "CS102", "name", "程序设计基础", "term", "2023-1",
            "credits", "4.00", "weights", Settings.json(CourseService.defaultWeights()));
    Map<String, Object> passed =
        Map.of("id", "c-pass", "code", "CS102", "name", "程序设计基础", "term", "2024-1",
            "credits", "4.00", "weights", Settings.json(CourseService.defaultWeights()));
    when(courses.list(student)).thenReturn(List.of(failed, passed));
    when(repo.find(eq("grades"), anyMap()))
        .thenAnswer(inv -> {
          String courseId = inv.getArgument(1, Map.class).get("course_id").toString();
          // 2023-1 挂科（加权 47.5，补考 52 也不及格）；2024-1 重修通过（补考 87 封顶 60）
          Map<String, Object> payload = "c-fail".equals(courseId)
              ? Map.of("regular", 45, "lab", 50, "finalExam", 48, "makeup", 52)
              : Map.of("regular", 50, "lab", 55, "finalExam", 52, "makeup", 87);
          Map<String, Object> grade =
              Map.of("id", "g-" + courseId, "course_id", courseId, "student_id", "s1",
                  "payload", Settings.json(payload), "state", "SUBMITTED", "version", 0);
          return List.of(grade);
        });

    var rows = service.transcript(student);
    assertEquals(2, rows.size(), "同一课程代码的两个学期各一条记录");
    var failRow = rows.stream().filter(r -> "2023-1".equals(r.get("term"))).findFirst().orElseThrow();
    var passRow = rows.stream().filter(r -> "2024-1".equals(r.get("term"))).findFirst().orElseThrow();
    assertEquals(Boolean.TRUE, failRow.get("failed"));
    assertEquals(Boolean.FALSE, passRow.get("failed"));
    assertEquals(Boolean.FALSE, failRow.get("retake"), "第一次修读不是重修");
    assertEquals(Boolean.TRUE, passRow.get("retake"), "更早学期挂过同代码 → 后一次是重修");
    assertEquals("重修", passRow.get("retakeLabel"));
    assertNull(failRow.get("retakeLabel"));
    // 重修不体现在课程名上：两行课程名完全一致
    assertEquals(failRow.get("name"), passRow.get("name"));
    assertFalse(failRow.get("name").toString().contains("重修"));
  }

  /** 只修读一次的课程不能被标成重修。 */
  @Test
  void transcriptDoesNotMarkSingleAttemptAsRetake() {
    var student = new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    Map<String, Object> only =
        Map.of("id", "c1", "code", "CS101", "name", "计算机导论", "term", "2023-1",
            "credits", "3.00", "weights", Settings.json(CourseService.defaultWeights()));
    when(courses.list(student)).thenReturn(List.of(only));
    Map<String, Object> grade =
        Map.of("id", "g1", "course_id", "c1", "student_id", "s1",
            "payload", Settings.json(Map.of("regular", 80, "lab", 80, "finalExam", 85)),
            "state", "SUBMITTED", "version", 0);
    when(repo.find(eq("grades"), anyMap())).thenReturn(List.of(grade));

    var rows = service.transcript(student);
    assertEquals(1, rows.size());
    assertEquals(Boolean.FALSE, rows.get(0).get("retake"));
    assertNull(rows.get(0).get("retakeLabel"));
  }

  /** 学业记录按学期倒序返回，便于「我的成绩」先看到最近的修读。 */
  @Test
  void transcriptSortsByTermDescending() {
    var student = new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    Map<String, Object> older =
        Map.of("id", "c-old", "code", "CS101", "name", "计算机导论", "term", "2023-1",
            "credits", "3.00", "weights", Settings.json(CourseService.defaultWeights()));
    Map<String, Object> newer =
        Map.of("id", "c-new", "code", "CS102", "name", "程序设计基础", "term", "2024-1",
            "credits", "4.00", "weights", Settings.json(CourseService.defaultWeights()));
    when(courses.list(student)).thenReturn(List.of(older, newer));
    when(repo.find(eq("grades"), anyMap()))
        .thenAnswer(inv -> {
          Map<String, Object> grade =
              Map.of("id", "g", "course_id", inv.getArgument(1, Map.class).get("course_id"),
                  "student_id", "s1",
                  "payload", Settings.json(Map.of("regular", 80, "lab", 80, "finalExam", 85)),
                  "state", "SUBMITTED", "version", 0);
          return List.of(grade);
        });

    var rows = service.transcript(student);
    assertEquals("2024-1", rows.get(0).get("term"));
    assertEquals("2023-1", rows.get(1).get("term"));
  }
}
