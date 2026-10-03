package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CourseServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final OrganizationService organizations = mock(OrganizationService.class);
  private final CourseService service = new CourseService(repo, organizations);

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

  // ---------------------------------------------------------------- R2 按课程批量选课

  private static final String TERM = "2026-1";

  /** 批量服务里的组织解析要真正查库，因此这里用真实的 OrganizationService + 桩 repo。 */
  private final OrganizationService realOrganizations = new OrganizationService(repo);

  private final SelectionService selections = new SelectionService(repo, realOrganizations);

  private final Models.User gradeAdmin =
      new Models.User("a1", "admin", "教务处", "ADMIN", Set.of("GRADE_ADMIN"), 0);

  @Test
  void batchByCourseAssignsSelectedStudentsAndSkipsAlreadyEnrolled() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubStudent("s2", "李四", "STUDENT", 1);
    stubEnrollments("s1", enrollment("c1", "s1", "ACTIVE"));
    stubEnrollments("s2");

    var result =
        selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1", "s2")));

    assertEquals(Boolean.TRUE, result.get("ok"));
    assertEquals(1, asInt(result.get("added")));
    assertEquals(1, asInt(result.get("skipped")), "已选学生计入 skipped 而不是报错");
    assertEquals(0, asInt(result.get("removed")));
    assertTrue(maps(result.get("failed")).isEmpty());
    // 没有指定班级时不下发班级人数，避免前端显示「班级 0 人」。
    assertFalse(result.containsKey("classStudents"));
    assertEquals(2, asInt(result.get("total")));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    var inserts = opsOn(ops, "enrollments", "INSERT");
    assertEquals(1, inserts.size());
    assertEquals("s2", inserts.get(0).values().get("student_id"));
    assertEquals("ADMIN", inserts.get(0).values().get("source"));
    assertEquals("ACTIVE", inserts.get(0).values().get("status"));
    assertNotNull(inserts.get(0).values().get("selected_at"));

    // 每次写入都产生一条选课记录；被跳过的学生不产生记录。
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("ADMIN_ASSIGN", records.get(0).values().get("action"));
    assertEquals("教务按课程批量选课", records.get(0).values().get("reason"));
    assertEquals("s2", records.get(0).values().get("student_id"));
    assertEquals("CS101", records.get(0).values().get("code"));
    assertEquals(TERM, records.get(0).values().get("term"));
    assertEquals("a1", records.get(0).values().get("operator"));
    assertNotNull(records.get(0).values().get("created_at"));
  }

  @Test
  void batchByCourseAssignsWholeClassByName() {
    stubCourses(courseRow("c1", "CS101"));
    stubClassByName("2023级-软件工程-2301班", "B1");
    when(repo.find("users", Map.of("class_id", "B1")))
        .thenReturn(
            List.of(
                userRow("s1", "张三", "STUDENT", 1),
                userRow("s2", "李四", "STUDENT", 0),
                userRow("t1", "王老师", "TEACHER", 1)));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubStudent("s2", "李四", "STUDENT", 0);
    stubEnrollments("s1");
    stubEnrollments("s2");

    var result =
        selections.batchByCourse(
            gradeAdmin, map("courseId", "c1", "className", "2023级-软件工程-2301班"));

    assertEquals(1, asInt(result.get("added")), "只有启用的学生会被选入");
    assertEquals(2, asInt(result.get("classStudents")), "班级成员只统计学生，教师不算");
    assertEquals(2, asInt(result.get("total")));
    var failed = maps(result.get("failed"));
    assertEquals(1, failed.size(), "停用学生记入 failed 而不是中断整批");
    assertEquals("s2", failed.get(0).get("studentId"));
    assertEquals("李四", failed.get(0).get("name"));
    assertEquals("账号已停用，不能选课", failed.get(0).get("reason"));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals(1, opsOn(ops, "enrollments", "INSERT").size());
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("s1", records.get(0).values().get("student_id"));
  }

  @Test
  void batchByCourseUnionsStudentIdsAndClassStudents() {
    stubCourses(courseRow("c1", "CS101"));
    stubClassById("B1");
    when(repo.find("users", Map.of("class_id", "B1")))
        .thenReturn(
            List.of(userRow("s1", "张三", "STUDENT", 1), userRow("s2", "李四", "STUDENT", 1)));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubStudent("s2", "李四", "STUDENT", 1);
    stubStudent("s3", "王五", "STUDENT", 1);
    stubEnrollments("s1");
    stubEnrollments("s2");
    stubEnrollments("s3");

    var result =
        selections.batchByCourse(
            gradeAdmin, map("courseId", "c1", "classId", "B1", "studentIds", List.of("s1", "s3")));

    assertEquals(3, asInt(result.get("added")), "s1 同时出现在名单与班级里，只能处理一次");
    assertEquals(2, asInt(result.get("classStudents")));
    assertEquals(3, asInt(result.get("total")));
    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals(3, opsOn(ops, "enrollments", "INSERT").size());
    assertEquals(3, opsOn(ops, "enrollment_records", "INSERT").size());
  }

  @Test
  void batchByCourseRequiresStudentsOrClass() {
    stubCourses(courseRow("c1", "CS101"));
    var error =
        assertThrows(
            ApiException.class,
            () -> selections.batchByCourse(gradeAdmin, map("courseId", "c1")));
    assertEquals(400, error.status);
    assertEquals("必须指定学生名单或班级", error.getMessage());
  }

  @Test
  void batchByCourseRejectsUnknownCourseAndUnknownClass() {
    stubCourses(courseRow("c1", "CS101"));
    assertEquals(
        404,
        assertThrows(
                ApiException.class,
                () ->
                    selections.batchByCourse(
                        gradeAdmin, map("courseId", "nope", "studentIds", List.of("s1"))))
            .status);
    when(repo.findOne("classes", Map.of("id", "不存在的班级"))).thenReturn(null);
    when(repo.findOne("classes", Map.of("name", "不存在的班级"))).thenReturn(null);
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    selections.batchByCourse(
                        gradeAdmin, map("courseId", "c1", "className", "不存在的班级")))
            .status);
  }

  @Test
  void batchByCourseRemoveFailsGradedStudentsButRemovesTheRest() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubStudent("s2", "李四", "STUDENT", 1);
    stubStudent("s3", "王五", "STUDENT", 1);
    stubEnrollments("s1", enrollment("c1", "s1", "ACTIVE"));
    stubEnrollments("s2", enrollment("c1", "s2", "ACTIVE"));
    stubEnrollments("s3");
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", "s1")))
        .thenReturn(List.of(map("id", "g1", "state", "DRAFT")));

    var result =
        selections.batchByCourse(
            gradeAdmin,
            map("courseId", "c1", "studentIds", List.of("s1", "s2", "s3"), "remove", true));

    assertEquals(1, asInt(result.get("removed")));
    assertEquals(1, asInt(result.get("skipped")), "没有 ACTIVE 记录的学生计入 skipped");
    var failed = maps(result.get("failed"));
    assertEquals(1, failed.size(), "有成绩的学生进 failed，其余学生照常退课");
    assertEquals("s1", failed.get(0).get("studentId"));
    assertEquals("张三", failed.get(0).get("name"));
    assertEquals("教师已录入成绩，不能退课", failed.get(0).get("reason"));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    var updates = opsOn(ops, "enrollments", "UPDATE");
    assertEquals(1, updates.size());
    assertEquals("DROPPED", updates.get(0).values().get("status"));
    assertEquals(Map.of("id", "e-c1-s2"), updates.get(0).where());
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("ADMIN_REMOVE", records.get(0).values().get("action"));
    assertEquals("教务按课程批量退课", records.get(0).values().get("reason"));
    assertEquals(TERM, records.get(0).values().get("term"));
  }

  @Test
  void batchByCourseReselectReusesDroppedRowWithUpdate() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubEnrollments("s1", enrollment("c1", "s1", "DROPPED"));

    var result =
        selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1")));

    assertEquals(1, asInt(result.get("added")));
    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertTrue(
        opsOn(ops, "enrollments", "INSERT").isEmpty(),
        "唯一索引是 (course_id,student_id)：退课后的重选必须 UPDATE 复用 DROPPED 行");
    var updates = opsOn(ops, "enrollments", "UPDATE");
    assertEquals(1, updates.size());
    assertEquals(Map.of("id", "e-c1-s1"), updates.get(0).where());
    assertEquals("ACTIVE", updates.get(0).values().get("status"));
    assertEquals("ADMIN", updates.get(0).values().get("source"));
    assertNotNull(updates.get(0).values().get("selected_at"));
    assertEquals(1, opsOn(ops, "enrollment_records", "INSERT").size());
  }

  @Test
  void batchByCourseRejectsMoreThanMaxStudents() {
    stubCourses(courseRow("c1", "CS101"));
    var ids = new ArrayList<String>();
    for (int i = 0; i <= Models.MAX_BATCH_STUDENTS; i++) ids.add("s" + i);
    var error =
        assertThrows(
            ApiException.class,
            () -> selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", ids)));
    assertEquals(400, error.status);
  }

  @Test
  void batchByCourseResolvesPublishIdAutomatically() {
    stubCourses(courseRow("c1", "CS101"));
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(
            List.of(
                map("id", "p9", "term", "2025-2", "status", "OPEN", "course_ids", "c1"),
                map("id", "p8", "term", TERM, "status", "OPEN", "course_ids", "c9"),
                map("id", "p1", "term", TERM, "status", "OPEN", "course_ids", "c2,c1")));
    when(repo.find("course_selections", Map.of("status", "CLOSED"))).thenReturn(List.of());
    stubStudent("s1", "张三", "STUDENT", 1);
    stubEnrollments("s1");

    selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1")));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals("p1", opsOn(ops, "enrollments", "INSERT").get(0).values().get("publish_id"));
    assertEquals(
        "p1", opsOn(ops, "enrollment_records", "INSERT").get(0).values().get("publish_id"));
  }

  @Test
  void batchByCourseFallsBackToClosedPublish() {
    stubCourses(courseRow("c1", "CS101"));
    when(repo.find("course_selections", Map.of("status", "OPEN"))).thenReturn(List.of());
    when(repo.find("course_selections", Map.of("status", "CLOSED")))
        .thenReturn(
            List.of(map("id", "p2", "term", TERM, "status", "CLOSED", "course_ids", "c1")));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubEnrollments("s1");

    selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1")));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals("p2", opsOn(ops, "enrollments", "INSERT").get(0).values().get("publish_id"));
  }

  @Test
  void batchByCourseHonoursExplicitPublishId() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubEnrollments("s1");

    selections.batchByCourse(
        gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1"), "publishId", "p7"));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals("p7", opsOn(ops, "enrollments", "INSERT").get(0).values().get("publish_id"));
    assertEquals(
        "p7", opsOn(ops, "enrollment_records", "INSERT").get(0).values().get("publish_id"));
  }

  @Test
  void batchByCourseLeavesPublishIdEmptyWhenNoPublishMatches() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 1);
    stubEnrollments("s1");

    selections.batchByCourse(gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1")));

    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertFalse(opsOn(ops, "enrollments", "INSERT").get(0).values().containsKey("publish_id"));
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertFalse(records.get(0).values().containsKey("publish_id"));
    assertEquals(TERM, records.get(0).values().get("term"), "所属批次找不到时仍写课程学期");
  }

  @Test
  void batchByCourseRequiresGradeAdmin() {
    var teacher = new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY", "ENTRY"), 0);
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () -> selections.batchByCourse(teacher, map("courseId", "c1")))
            .status);
  }

  @Test
  void batchByCourseRejectsNonStudentAndUnknownStudent() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("t1", "王老师", "TEACHER", 1);

    var result =
        selections.batchByCourse(
            gradeAdmin, map("courseId", "c1", "studentIds", List.of("t1", "ghost")));

    assertEquals(0, asInt(result.get("added")));
    var failed = maps(result.get("failed"));
    assertEquals(2, failed.size());
    assertEquals("t1", failed.get(0).get("studentId"));
    assertEquals("王老师", failed.get(0).get("name"));
    assertEquals("仅学生可选课", failed.get(0).get("reason"));
    assertEquals("ghost", failed.get(1).get("studentId"));
    assertEquals("学生不存在", failed.get(1).get("reason"));
    assertFalse(failed.get(1).containsKey("name"));
    // 全部失败时没有任何写入：既不写选课行，也不写流水。
    verify(repo, never()).mutate(any(), anyString(), anyString(), anyString());
  }

  @Test
  void batchByCourseRemoveAllowsDisabledStudent() {
    stubCourses(courseRow("c1", "CS101"));
    stubStudent("s1", "张三", "STUDENT", 0);
    stubEnrollments("s1", enrollment("c1", "s1", "ACTIVE"));

    var result =
        selections.batchByCourse(
            gradeAdmin, map("courseId", "c1", "studentIds", List.of("s1"), "remove", true));

    assertEquals(1, asInt(result.get("removed")), "教务可以给停用账号退课");
    assertTrue(maps(result.get("failed")).isEmpty());
    var ops = capturedOps("ENROLLMENT_BATCH", "c1");
    assertEquals(1, opsOn(ops, "enrollments", "UPDATE").size());
    assertEquals(
        "ADMIN_REMOVE",
        opsOn(ops, "enrollment_records", "INSERT").get(0).values().get("action"));
  }

  // ---------------------------------------------------------------- R2 单人代选 / 代退也写选课记录

  @Test
  void enrollWritesAdminAssignRecordAndSelectedAt() {
    when(repo.one("courses", "c1"))
        .thenReturn(map("id", "c1", "code", "CS101", "term", TERM, "version", 0));
    when(repo.one("users", "s1")).thenReturn(map("id", "s1", "role", "STUDENT", "enabled", 1));

    service.enroll(gradeAdmin, map("courseId", "c1", "studentId", "s1"));

    var ops = capturedOps("ENROLLMENT_UPDATE", "c1");
    var inserts = opsOn(ops, "enrollments", "INSERT");
    assertEquals(1, inserts.size());
    assertEquals("ADMIN", inserts.get(0).values().get("source"));
    assertEquals("ACTIVE", inserts.get(0).values().get("status"));
    assertNotNull(inserts.get(0).values().get("selected_at"));
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("ADMIN_ASSIGN", records.get(0).values().get("action"));
    assertEquals("教务直接选课", records.get(0).values().get("reason"));
    assertEquals("s1", records.get(0).values().get("student_id"));
    assertEquals("CS101", records.get(0).values().get("code"));
    assertEquals(TERM, records.get(0).values().get("term"));
    assertEquals("a1", records.get(0).values().get("operator"));
  }

  @Test
  void enrollRemoveWritesAdminRemoveRecord() {
    when(repo.one("courses", "c1"))
        .thenReturn(map("id", "c1", "code", "CS101", "term", TERM, "version", 2));
    when(repo.one("users", "s1")).thenReturn(map("id", "s1", "role", "STUDENT", "enabled", 1));
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", "s1"))).thenReturn(List.of());
    when(repo.find("enrollments", Map.of("course_id", "c1", "student_id", "s1")))
        .thenReturn(List.of(enrollment("c1", "s1", "ACTIVE")));

    service.enroll(gradeAdmin, map("courseId", "c1", "studentId", "s1", "remove", true));

    var ops = capturedOps("ENROLLMENT_UPDATE", "c1");
    assertEquals(1, opsOn(ops, "enrollments", "DELETE").size());
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("ADMIN_REMOVE", records.get(0).values().get("action"));
    assertEquals("教务直接退课", records.get(0).values().get("reason"));
    assertEquals(TERM, records.get(0).values().get("term"));
  }

  // ---------------------------------------------------------------- R2 路由

  @Test
  void coreRoutesForwardsEnrollmentBatchToSelectionService() {
    var selectionStub = mock(SelectionService.class);
    var routes =
        new CoreRoutes(
            mock(AuthService.class),
            mock(CourseService.class),
            mock(GradeService.class),
            mock(AnalyticsService.class),
            mock(AdminService.class),
            mock(OrganizationService.class),
            mock(RemoteRepository.class),
            selectionStub);
    assertTrue(routes.handles().contains("/enrollments/batch"));

    var body = map("courseId", "c1", "studentIds", List.of("s1"));
    when(selectionStub.batchByCourse(gradeAdmin, body))
        .thenReturn(map("ok", true, "added", 1, "skipped", 0, "removed", 0, "failed", List.of()));
    var response =
        (Map<?, ?>)
            routes.dispatch(
                new Routes.Request("/enrollments/batch", gradeAdmin, true, body, Map.of(), null, null));

    assertEquals(1, asInt(response.get("added")));
    verify(selectionStub).batchByCourse(gradeAdmin, body);
  }

  // ---------------------------------------------------------------- R4 教师端名单的重修标注

  /**
   * 课程系数：与 {@code CourseService.defaultWeights()} 一样必须给全六项（{@code weights()} 也校验
   * 「必须指定全部六项系数」）——{@link Models#total} 对缺失的系数项会直接抛异常，缺项会被重修判定
   * 当成「没有成绩依据」而静默不算挂科。
   */
  private static final String WEIGHTS =
      "{\"regular\":30,\"attendance\":0,\"homework\":0,\"lab\":20,\"midterm\":0,\"finalExam\":50}";

  /** 当前课程 c2（CS102 / 2024-1）的名单里 s1、s2 都曾在更早学年 c1（CS102 / 2023-1）修读同一课程号。 */
  private void stubRosterScenario(Map<String, Object> earlierGrade) {
    var current = termCourse("c2", "CS102", "2024-1");
    var earlier = termCourse("c1", "CS102", "2023-1");
    when(repo.one("courses", "c2")).thenReturn(current);
    when(repo.findOne("courses", Map.of("id", "c2"))).thenReturn(current);
    when(repo.find("courses", Map.of("code", "CS102"))).thenReturn(List.of(earlier, current));
    when(repo.find("enrollments", Map.of("course_id", "c2")))
        .thenReturn(List.of(rosterEnrollment("c2", "s1"), rosterEnrollment("c2", "s2")));
    when(repo.find("enrollments", Map.of("course_id", "c1")))
        .thenReturn(List.of(rosterEnrollment("c1", "s1"), rosterEnrollment("c1", "s2")));
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(
            List.of(
                map("id", "s1", "name", "张三", "username", "s1", "role", "STUDENT"),
                map("id", "s2", "name", "李四", "username", "s2", "role", "STUDENT")));
    when(repo.find("grades", Map.of()))
        .thenReturn(earlierGrade == null ? List.of() : List.of(earlierGrade));
  }

  @Test
  void rosterMarksStudentsRetakingFailedCourseCode() {
    // 更早学年同代码：平时 40、实验 40、期末 45 → 有效分 42.5 < 60，属于挂科，本学期这门课就是重修。
    stubRosterScenario(gradeRow("c1", "s1", "SUBMITTED", 40, 40, 45));

    var roster = service.roster(gradeAdmin, "c2");

    var retaking = byStudentId(roster, "s1");
    assertEquals(Boolean.TRUE, retaking.get("retake"), "更早学期同代码挂过科 → 本学期是重修");
    assertEquals("重修", retaking.get("retakeLabel"));
    assertEquals("张三", retaking.get("name"));
    var plain = byStudentId(roster, "s2");
    assertEquals(Boolean.FALSE, plain.get("retake"), "没有挂科史的学生不标注重修");
    assertNull(plain.get("retakeLabel"));
  }

  @Test
  void rosterDoesNotMarkRetakeWhenEarlierTermPassed() {
    // 更早学年同代码已通过（有效分 87.5 ≥ 60）→ 不能标成重修。
    stubRosterScenario(gradeRow("c1", "s1", "SUBMITTED", 85, 85, 90));

    var roster = service.roster(gradeAdmin, "c2");

    assertEquals(Boolean.FALSE, byStudentId(roster, "s1").get("retake"));
    assertNull(byStudentId(roster, "s1").get("retakeLabel"));
  }

  @Test
  void rosterDoesNotMarkRetakeWhenEarlierGradeIsDraft() {
    // 成绩还没提交：正在修读，不是重修。
    stubRosterScenario(gradeRow("c1", "s1", "DRAFT", 40, 40, 45));

    var roster = service.roster(gradeAdmin, "c2");

    assertEquals(Boolean.FALSE, byStudentId(roster, "s1").get("retake"));
    assertNull(byStudentId(roster, "s1").get("retakeLabel"));
  }

  @Test
  void rosterDoesNotMarkRetakeForOtherCourseCode() {
    var current = termCourse("c2", "CS102", "2024-1");
    var other = termCourse("c9", "CS103", "2023-1");
    when(repo.one("courses", "c2")).thenReturn(current);
    when(repo.findOne("courses", Map.of("id", "c2"))).thenReturn(current);
    // 同一课程代码只有本学期这一个教学班：更早学年挂的是另一门课程代码，不构成本课程的重修。
    when(repo.find("courses", Map.of("code", "CS102"))).thenReturn(List.of(current));
    when(repo.find("courses", Map.of("code", "CS103"))).thenReturn(List.of(other));
    when(repo.find("enrollments", Map.of("course_id", "c2")))
        .thenReturn(List.of(rosterEnrollment("c2", "s1")));
    when(repo.find("enrollments", Map.of("course_id", "c9")))
        .thenReturn(List.of(rosterEnrollment("c9", "s1")));
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(
            List.of(map("id", "s1", "name", "张三", "username", "s1", "role", "STUDENT")));
    when(repo.find("grades", Map.of()))
        .thenReturn(List.of(gradeRow("c9", "s1", "SUBMITTED", 30, 30, 30)));

    var roster = service.roster(gradeAdmin, "c2");

    assertEquals(
        Boolean.FALSE, byStudentId(roster, "s1").get("retake"), "挂的是另一门课程代码，不是重修");
    assertNull(byStudentId(roster, "s1").get("retakeLabel"));
  }

  // ---------------------------------------------------------------- 夹具

  private static Map<String, Object> map(Object... pairs) {
    var row = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) row.put(pairs[i].toString(), pairs[i + 1]);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> courseRow(String id, String code) {
    return map(
        "id", id,
        "code", code,
        "name", "课程" + code,
        "term", TERM,
        "credits", 3.0,
        "teacher_id", "t1",
        "college_id", "C01001",
        "class_id", "B1",
        "status", "ACTIVE",
        "version", 0);
  }

  /** 指定学期的课程行：重修样本要跨学年，因此学期不能写死。 */
  private static Map<String, Object> termCourse(String id, String code, String term) {
    return map(
        "id", id,
        "code", code,
        "name", "课程" + code,
        "term", term,
        "credits", 3.0,
        "teacher_id", "t1",
        "college_id", "C01001",
        "class_id", "B1",
        "status", "ACTIVE",
        "weights", WEIGHTS,
        "version", 0);
  }

  private static Map<String, Object> userRow(String id, String name, String role, int enabled) {
    return map("id", id, "name", name, "role", role, "enabled", enabled, "class_id", "B1");
  }

  /**
   * 成绩行：weights = 平时 30% + 实验 20% + 期末 50%，有效分 = 平时×0.3 + 实验×0.2 + 期末×0.5。
   * 例如 (40, 40, 45) → 42.5 分（挂科），(85, 85, 90) → 87.5 分（通过）。
   */
  private static Map<String, Object> gradeRow(
      String courseId,
      String studentId,
      String state,
      double regular,
      double lab,
      double finalExam) {
    return map(
        "id", "g-" + courseId + "-" + studentId,
        "course_id", courseId,
        "student_id", studentId,
        "state", state,
        "payload",
            Settings.json(
                map("regular", regular, "lab", lab, "finalExam", finalExam)),
        "version", 0);
  }

  /** 教学班名单里的一条选课记录（roster 按 course_id 查询）。 */
  private static Map<String, Object> rosterEnrollment(String courseId, String studentId) {
    return map(
        "id", "e-" + courseId + "-" + studentId,
        "course_id", courseId,
        "student_id", studentId,
        "source", "SELECTION",
        "publish_id", "p0",
        "selected_at", "2026-02-01T08:00:00Z",
        "status", "ACTIVE");
  }

  private static Map<String, Object> byStudentId(List<Map<String, Object>> rows, String id) {
    for (var row : rows) if (id.equals(row.get("id"))) return row;
    throw new AssertionError("名单里没有学生 " + id + "：" + rows);
  }

  private static Map<String, Object> enrollment(String courseId, String studentId, String status) {
    return map(
        "id", "e-" + courseId + "-" + studentId,
        "course_id", courseId,
        "student_id", studentId,
        "source", "SELECTION",
        "publish_id", "p0",
        "selected_at", "2026-02-01T08:00:00Z",
        "status", status);
  }

  private static int asInt(Object value) {
    return ((Number) value).intValue();
  }

  @SafeVarargs
  private final void stubCourses(Map<String, Object>... courses) {
    when(repo.find("courses", Map.of())).thenReturn(List.of(courses));
  }

  private void stubStudent(String id, String name, String role, int enabled) {
    when(repo.find("users", Map.of("id", id)))
        .thenReturn(List.of(userRow(id, name, role, enabled)));
  }

  @SafeVarargs
  private final void stubEnrollments(String studentId, Map<String, Object>... rows) {
    when(repo.find("enrollments", Map.of("student_id", studentId))).thenReturn(List.of(rows));
  }

  private void stubClassById(String classId) {
    when(repo.findOne("classes", Map.of("id", classId)))
        .thenReturn(map("id", classId, "name", "班级" + classId));
  }

  /**
   * 班级名称 → 班级行。
   *
   * <p>{@link OrganizationService#resolveOwn} 先按编号查、再按名称查；而被 mock 的 repo 对返回 Map
   * 的方法默认给空 Map（不是 null），因此必须显式让“按编号查”返回 null，“按名称查”这一支才生效。
   */
  private void stubClassByName(String name, String classId) {
    when(repo.findOne("classes", Map.of("id", name))).thenReturn(null);
    when(repo.findOne("classes", Map.of("name", name)))
        .thenReturn(map("id", classId, "name", name));
  }

  @SuppressWarnings("unchecked")
  private List<Protocol.Operation> capturedOps(String action, String resource) {
    var captor = ArgumentCaptor.forClass(List.class);
    verify(repo).mutate(captor.capture(), anyString(), eq(action), eq(resource));
    return (List<Protocol.Operation>) captor.getValue();
  }

  private static List<Protocol.Operation> opsOn(
      List<Protocol.Operation> ops, String table, String type) {
    var result = new ArrayList<Protocol.Operation>();
    for (var op : ops) if (op.table().equals(table) && op.type().equals(type)) result.add(op);
    return result;
  }
}
