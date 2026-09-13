package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CourseServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final CourseService service = new CourseService(repo);

  @Test
  void defaultWeightsAreEqualDivision() {
    var w = CourseService.defaultWeights();
    assertEquals(30, w.get("regular"));
    assertEquals(0, w.get("attendance"));
    assertEquals(0, w.get("homework"));
    assertEquals(20, w.get("lab"));
    assertEquals(0, w.get("midterm"));
    assertEquals(50, w.get("finalExam"));
  }

  @Test
  void accessAdminCanReadAnyCourse() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("GRADE_ADMIN", "USER_ADMIN", "AUDIT"), 0);
    Map<String, Object> course = new LinkedHashMap<>();
    course.put("id", "c1");
    course.put("teacher_id", "t1");
    when(repo.one("courses", "c1")).thenReturn(course);
    assertDoesNotThrow(() -> service.access(admin, "c1", "GRADE_ADMIN"));
  }

  @Test
  void accessTeacherRestrictedToOwnCourses() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY", "ENTRY"), 0);
    Map<String, Object> course = new LinkedHashMap<>();
    course.put("id", "other");
    course.put("teacher_id", "t2");
    when(repo.one("courses", "other")).thenReturn(course);
    ApiException ex =
        assertThrows(ApiException.class, () -> service.access(teacher, "other", "ENTRY"));
    assertEquals(403, ex.status);
  }

  @Test
  void accessStudentMustBeEnrolled() {
    var student =
        new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    Map<String, Object> course = new LinkedHashMap<>();
    course.put("id", "c1");
    when(repo.one("courses", "c1")).thenReturn(course);
    when(repo.find("enrollments", Map.of("course_id", "c1", "student_id", "s1")))
        .thenReturn(Collections.emptyList());
    assertThrows(ApiException.class, () -> service.access(student, "c1", "QUERY"));
  }

  @Test
  void listAdminReturnsAllCourses() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("GRADE_ADMIN", "USER_ADMIN", "AUDIT"), 0);
    List<Map<String, Object>> courses = new ArrayList<>();
    Map<String, Object> c1 = new LinkedHashMap<>();
    c1.put("id", "c1");
    c1.put("term", "2026-1");
    c1.put("teacher_id", "t1");
    Map<String, Object> c2 = new LinkedHashMap<>();
    c2.put("id", "c2");
    c2.put("term", "2025-2");
    c2.put("teacher_id", "t2");
    courses.add(c1);
    courses.add(c2);
    when(repo.find("courses", Map.of())).thenReturn(courses);
    var result = service.list(admin);
    assertEquals(2, result.size());
  }

  @Test
  void listTeacherReturnsOnlyOwnCourses() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY", "ENTRY"), 0);
    List<Map<String, Object>> courses = new ArrayList<>();
    Map<String, Object> c1 = new LinkedHashMap<>();
    c1.put("id", "c1");
    c1.put("term", "2026-1");
    courses.add(c1);
    when(repo.find("courses", Map.of("teacher_id", "t1"))).thenReturn(courses);
    var result = service.list(teacher);
    assertEquals(1, result.size());
  }

  @Test
  void listStudentFilteredByEnrollment() {
    var student =
        new Models.User("s1", "student1", "S1", "STUDENT", Set.of("QUERY"), 0);
    List<Map<String, Object>> allCourses = new ArrayList<>();
    Map<String, Object> c1 = new LinkedHashMap<>();
    c1.put("id", "c1");
    c1.put("term", "2026-1");
    Map<String, Object> c2 = new LinkedHashMap<>();
    c2.put("id", "c2");
    c2.put("term", "2026-1");
    allCourses.add(c1);
    allCourses.add(c2);
    when(repo.find("courses", Map.of())).thenReturn(allCourses);
    List<Map<String, Object>> enrollments = new ArrayList<>();
    Map<String, Object> e1 = new LinkedHashMap<>();
    e1.put("course_id", "c1");
    enrollments.add(e1);
    when(repo.find("enrollments", Map.of("student_id", "s1"))).thenReturn(enrollments);
    var result = service.list(student);
    assertEquals(1, result.size());
    assertEquals("c1", result.get(0).get("id"));
  }

  @Test
  void rosterReturnsEnrolledStudents() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY"), 0);
    Map<String, Object> course = new LinkedHashMap<>();
    course.put("id", "c1");
    course.put("teacher_id", "t1");
    when(repo.one("courses", "c1")).thenReturn(course);
    List<Map<String, Object>> enrollments = new ArrayList<>();
    Map<String, Object> e1 = new LinkedHashMap<>();
    e1.put("student_id", "s1");
    Map<String, Object> e2 = new LinkedHashMap<>();
    e2.put("student_id", "s2");
    enrollments.add(e1);
    enrollments.add(e2);
    when(repo.find("enrollments", Map.of("course_id", "c1"))).thenReturn(enrollments);
    List<Map<String, Object>> students = new ArrayList<>();
    Map<String, Object> s1 = new LinkedHashMap<>();
    s1.put("id", "s1");
    s1.put("name", "Alice");
    s1.put("username", "alice");
    Map<String, Object> s2 = new LinkedHashMap<>();
    s2.put("id", "s2");
    s2.put("name", "Bob");
    s2.put("username", "bob");
    students.add(s1);
    students.add(s2);
    when(repo.find("users", Map.of("role", "STUDENT"))).thenReturn(students);
    var result = service.roster(teacher, "c1");
    assertEquals(2, result.size());
  }

  @Test
  void saveCourseRequiresGradeAdmin() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    assertThrows(ApiException.class, () -> service.saveCourse(teacher, Map.of("code", "CS101")));
  }

  @Test
  void saveCourseValidatesTermFormat() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("GRADE_ADMIN"), 0);
    // Mock teacher lookup to avoid NPE
    Map<String, Object> teacher = new LinkedHashMap<>();
    teacher.put("id", "t1");
    teacher.put("role", "TEACHER");
    teacher.put("enabled", 1);
    when(repo.one("users", "t1")).thenReturn(teacher);
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                service.saveCourse(
                    admin,
                    Map.of(
                        "code",
                        "CS101",
                        "teacherId",
                        "t1",
                        "term",
                        "bad-term",
                        "credits",
                        3.0)));
    assertEquals(400, ex.status);
  }

  @Test
  void enrollRequiresGradeAdmin() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("ENTRY"), 0);
    assertThrows(ApiException.class, () -> service.enroll(teacher, Map.of("courseId", "c1", "studentId", "s1")));
  }

  @Test
  void enrollRejectsRemovingStudentWithGrades() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("GRADE_ADMIN"), 0);
    Map<String, Object> course = new LinkedHashMap<>();
    course.put("id", "c1");
    course.put("version", 0);
    when(repo.one("courses", "c1")).thenReturn(course);
    Map<String, Object> student = new LinkedHashMap<>();
    student.put("id", "s1");
    student.put("role", "STUDENT");
    student.put("enabled", 1);
    when(repo.one("users", "s1")).thenReturn(student);
    List<Map<String, Object>> grades = new ArrayList<>();
    Map<String, Object> g1 = new LinkedHashMap<>();
    g1.put("id", "g1");
    grades.add(g1);
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", "s1"))).thenReturn(grades);
    ApiException ex =
        assertThrows(
            ApiException.class,
            () ->
                service.enroll(
                    admin, Map.of("courseId", "c1", "studentId", "s1", "remove", true)));
    assertEquals(409, ex.status);
  }
}
