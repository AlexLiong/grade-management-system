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
}
