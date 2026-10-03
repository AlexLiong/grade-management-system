package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 选课系统规则单元测试。
 *
 * <p>数据层用 {@code mock(RemoteRepository.class)} 替身，因此每条测试只验证
 * {@link SelectionService} 自己的规则判断与事务内容（写哪些表、写什么状态、写什么流水）。
 * 重点覆盖：时间窗口、allow_add/allow_drop、选课范围、重复选课、同一课程代码的其他教学班、
 * 此前已通过、挂科重修、学分上限、已有成绩不能发布选课、同一课程不能同时出现在两个进行中的
 * 发布、最低开课人数自动退回、退课时已有成绩、按班级批量选课跳过已选学生。
 */
class SelectionServiceTest {

  private static final String TERM = "2026-1";
  private static final String PUBLISH = "p1";
  private static final String STUDENT_ID = "s1";
  /** 与 course(...) 里的 weights 对应：平时 30% + 实验 20% + 期末 50%，因此分数即有效分。 */
  private static final String WEIGHTS =
      "{\"regular\":30,\"attendance\":0,\"homework\":0,\"lab\":20,\"midterm\":0,\"finalExam\":50}";

  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final OrganizationService organizations = new OrganizationService(repo);
  private final SelectionService service = new SelectionService(repo, organizations);

  private final Models.User student =
      new Models.User(
          STUDENT_ID,
          "student1",
          "张三",
          "STUDENT",
          Set.of("QUERY", "SELECTION_ENROLL"),
          0,
          "C01001",
          "M01001",
          "B01001");
  private final Models.User admin =
      new Models.User(
          "a1",
          "admin",
          "教务处",
          "ADMIN",
          Set.of("GRADE_ADMIN", "USER_ADMIN", "AUDIT", "ORG_ADMIN", "SELECTION_ADMIN"),
          0);

  @BeforeEach
  void stubOrganizationLookups() {
    // 名称翻译是“顺手”的能力，规则测试不关心组织数据，统一桩成空表。
    when(repo.find("colleges", Map.of())).thenReturn(List.of());
    when(repo.find("majors", Map.of())).thenReturn(List.of());
    when(repo.find("classes", Map.of())).thenReturn(List.of());
    when(repo.find("users", Map.of("role", "TEACHER"))).thenReturn(List.of());
  }

  // ---------------------------------------------------------------- 时间窗口

  @Test
  void selectRejectsBeforeWindowStarts() {
    stubPublish(PUBLISH, openPublish("start_time", iso(1), "end_time", iso(2)));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(400, error.status);
    assertEquals("选课尚未开始", error.getMessage());
  }

  @Test
  void selectRejectsAfterWindowEnds() {
    stubPublish(PUBLISH, openPublish("start_time", iso(-2), "end_time", iso(-1)));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(400, error.status);
    assertEquals("选课已结束", error.getMessage());
  }

  @Test
  void looseTimeParsingAcceptsCommonWritings() {
    var local = LocalDateTime.of(2026, 9, 1, 8, 0).atZone(ZoneId.systemDefault()).toInstant();
    assertEquals(Instant.parse("2026-09-01T08:00:00Z"), SelectionService.parseTime("2026-09-01T08:00:00Z"));
    assertEquals(local, SelectionService.parseTime("2026-09-01T08:00"));
    assertEquals(local, SelectionService.parseTime("2026-09-01T08:00:00"));
    assertEquals(local, SelectionService.parseTime("2026-09-01 08:00"));
    var error =
        assertThrows(ApiException.class, () -> SelectionService.parseTime("下周一早上"));
    assertEquals(400, error.status);
    assertEquals("时间格式错误", error.getMessage());
  }

  // ---------------------------------------------------------------- 选课规则

  @Test
  void selectRejectsWhenAddDisabled() {
    stubPublish(PUBLISH, openPublish("allow_add", 0));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(403, error.status);
    assertEquals("本次选课不允许选课", error.getMessage());
  }

  @Test
  void selectRejectsStudentOutsideScope() {
    stubPublish(PUBLISH, openPublish("scope_college_ids", "C01002"));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(403, error.status);
    assertEquals("你不在此次选课范围内", error.getMessage());
  }

  @Test
  void selectRejectsDisabledStudent() {
    stubPublish(PUBLISH, openPublish());
    stubUser(STUDENT_ID, "STUDENT", 0);
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(403, error.status);
    assertEquals("账号已停用", error.getMessage());
  }

  @Test
  void selectRejectsDuplicateEnrollment() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID, enrollment("c1", STUDENT_ID, "ACTIVE", PUBLISH));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(409, error.status);
    assertEquals("已选修该课程", error.getMessage());
  }

  @Test
  void selectRejectsOtherTeachingClassWithSameCourseCode() {
    stubPublish(PUBLISH, openPublish());
    // 两个教学班课程代码相同、id 与授课教师不同：本学期只能选其中一个。
    stubCourses(
        course("c1", "CS101", "数据结构", TERM, 3, "t1"),
        course("c2", "CS101", "数据结构（二班）", TERM, 3, "t2"));
    stubEnrollments(STUDENT_ID, enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(409, error.status);
    assertEquals("本学期已选择同一课程代码的其他教学班", error.getMessage());
  }

  @Test
  void selectRejectsCourseAlreadyPassedInEarlierTerm() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(
        course("c0", "CS101", "数据结构", "2025-2", 3, "t2"),
        course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID, enrollment("c0", STUDENT_ID, "ACTIVE", "p0"));
    stubGrades("c0", STUDENT_ID, grade("c0", "SUBMITTED", scores(85)));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(409, error.status);
    assertEquals("该课程此前已通过，不能重复修读", error.getMessage());
  }

  @Test
  void selectRejectsRetakeWhenRetakeDisabled() {
    stubPublish(PUBLISH, openPublish("allow_retake", 0));
    stubCourses(
        course("c0", "CS101", "数据结构", "2025-2", 3, "t2"),
        course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID, enrollment("c0", STUDENT_ID, "ACTIVE", "p0"));
    stubGrades("c0", STUDENT_ID, grade("c0", "SUBMITTED", scores(45)));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(403, error.status);
    assertEquals("本次选课不允许重修", error.getMessage());
  }

  @Test
  void selectAllowsRetakeWhenRetakeEnabled() {
    stubPublish(PUBLISH, openPublish("allow_retake", 1));
    stubCourses(
        course("c0", "CS101", "数据结构", "2025-2", 3, "t2"),
        course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID, enrollment("c0", STUDENT_ID, "ACTIVE", "p0"));
    stubGrades("c0", STUDENT_ID, grade("c0", "SUBMITTED", scores(45)));
    var result = service.select(student, selectBody());
    assertEquals(Boolean.TRUE, result.get("ok"));
    var ops = capturedOps("SELECTION_SELECT");
    assertEquals(1, opsOn(ops, "enrollments", "INSERT").size());
  }

  @Test
  void selectRejectsWhenCreditsExceeded() {
    stubPublish(PUBLISH, openPublish("course_ids", "c1,c2", "max_credits", 6));
    stubCourses(
        course("c1", "CS101", "数据结构", TERM, 3, "t1"),
        course("c2", "CS102", "操作系统", TERM, 4, "t2"));
    stubEnrollments(STUDENT_ID, enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    var error = assertThrows(ApiException.class, () -> service.select(student, selectBody()));
    assertEquals(409, error.status);
    assertTrue(error.getMessage().contains("超出学分上限"), error.getMessage());
  }

  @Test
  void selectWritesEnrollmentAndAuditRecord() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID);
    var result = service.select(student, selectBody());
    assertEquals(Boolean.TRUE, result.get("ok"));
    var ops = capturedOps("SELECTION_SELECT");
    var enrollments = opsOn(ops, "enrollments", "INSERT");
    assertEquals(1, enrollments.size());
    assertEquals("ACTIVE", enrollments.get(0).values().get("status"));
    assertEquals("SELECTION", enrollments.get(0).values().get("source"));
    assertEquals(STUDENT_ID, enrollments.get(0).values().get("student_id"));
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("SELECT", records.get(0).values().get("action"));
    assertEquals("CS101", records.get(0).values().get("code"));
    assertEquals(TERM, records.get(0).values().get("term"));
  }

  @Test
  void selectReusesDroppedEnrollmentRowToKeepUniqueIndex() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubEnrollments(STUDENT_ID, enrollment("c1", STUDENT_ID, "DROPPED", "p0"));
    service.select(student, selectBody());
    var ops = capturedOps("SELECTION_SELECT");
    assertEquals(0, opsOn(ops, "enrollments", "INSERT").size());
    var updates = opsOn(ops, "enrollments", "UPDATE");
    assertEquals(1, updates.size());
    assertEquals("ACTIVE", updates.get(0).values().get("status"));
  }

  // ---------------------------------------------------------------- 发布

  @Test
  void saveRejectsCourseWithAnyGrades() {
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    when(repo.find("grades", Map.of("course_id", "c1")))
        .thenReturn(List.of(map("id", "g1", "state", "DRAFT")));
    var error = assertThrows(ApiException.class, () -> service.save(admin, saveBody()));
    assertEquals(409, error.status);
    assertEquals("该课程已有教师录入成绩，不能发布选课", error.getMessage());
  }

  @Test
  void saveRejectsCourseAlreadyInAnotherOpenPublish() {
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    when(repo.find("grades", Map.of("course_id", "c1"))).thenReturn(List.of());
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(List.of(openPublish("id", "p2")));
    var error = assertThrows(ApiException.class, () -> service.save(admin, saveBody()));
    assertEquals(409, error.status);
    assertEquals("该课程已存在于其他进行中的选课发布", error.getMessage());
  }

  @Test
  void saveAcceptsCourseNamesAndCreatesOpenPublish() {
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    when(repo.find("grades", Map.of("course_id", "c1"))).thenReturn(List.of());
    when(repo.find("course_selections", Map.of("status", "OPEN"))).thenReturn(List.of());
    var body = saveBody();
    body.remove("courseIds"); // 前端也可以只提交课程名称
    body.put("courseNames", List.of("数据结构"));
    var result = service.save(admin, body);
    assertEquals(Boolean.TRUE, result.get("ok"));
    assertNotNull(result.get("id"));
    var ops = capturedOps("SELECTION_PUBLISH");
    assertEquals(1, ops.size());
    assertEquals("INSERT", ops.get(0).type());
    assertEquals("c1", ops.get(0).values().get("course_ids"));
    assertEquals("OPEN", ops.get(0).values().get("status"));
    assertEquals(0, ops.get(0).values().get("version"));
  }

  @Test
  void saveValidatesTermTimeAndMinimumEnrollment() {
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> service.save(admin, saveBody("term", "2026-3"))).status);
    assertEquals(
        "时间格式错误",
        assertThrows(ApiException.class, () -> service.save(admin, saveBody("startTime", "明天")))
            .getMessage());
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> service.save(admin, saveBody("startTime", iso(1), "endTime", iso(-1))))
            .status);
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> service.save(admin, saveBody("minEnroll", 0))).status);
  }

  @Test
  void closeOnlyAllowedFromOpenStatus() {
    stubPublish(PUBLISH, openPublish("status", "CLOSED"));
    var error =
        assertThrows(ApiException.class, () -> service.close(admin, map("id", PUBLISH)));
    assertEquals(409, error.status);
    assertEquals("选课未开放", error.getMessage());

    stubPublish(PUBLISH, openPublish("version", 2));
    assertEquals(Boolean.TRUE, service.close(admin, map("id", PUBLISH)).get("ok"));
    var ops = capturedOps("SELECTION_CLOSE");
    assertEquals("CLOSED", ops.get(0).values().get("status"));
    assertEquals(3, ops.get(0).values().get("version"));
  }

  @Test
  void cancelRefundsEveryActiveEnrollment() {
    stubPublish(PUBLISH, openPublish("version", 1));
    when(repo.find("enrollments", Map.of("publish_id", PUBLISH, "status", "ACTIVE")))
        .thenReturn(List.of(enrollment("c1", "s1", "ACTIVE", PUBLISH)));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    var result = service.cancel(admin, map("id", PUBLISH, "reason", "系统升级"));
    assertEquals(1, ((Number) result.get("refunded")).intValue());
    var ops = capturedOps("SELECTION_CANCEL");
    assertEquals("CANCELLED", opsOn(ops, "course_selections", "UPDATE").get(0).values().get("status"));
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("AUTO_REFUND", records.get(0).values().get("action"));
    assertTrue(records.get(0).values().get("reason").toString().contains("系统升级"));
  }

  // ---------------------------------------------------------------- 结算与定时任务

  @Test
  void settleRefundsCoursesBelowMinimumAndClosesPublish() {
    stubPublish(PUBLISH, openPublish("course_ids", "c1,c2", "min_enroll", 5, "version", 3));
    when(repo.find("enrollments", Map.of("publish_id", PUBLISH, "status", "ACTIVE")))
        .thenReturn(
            List.of(
                enrollment("c1", "s1", "ACTIVE", PUBLISH),
                enrollment("c1", "s2", "ACTIVE", PUBLISH),
                enrollment("c2", "s3", "ACTIVE", PUBLISH)));
    stubCourses(
        course("c1", "CS101", "数据结构", TERM, 3, "t1"),
        course("c2", "CS102", "操作系统", TERM, 3, "t2"));
    var result = service.settle(admin, map("id", PUBLISH));
    assertEquals(Boolean.TRUE, result.get("ok"));
    assertEquals(3, ((Number) result.get("refunded")).intValue());
    assertEquals(2, ((List<?>) result.get("cancelled")).size());
    var ops = capturedOps("SELECTION_SETTLE");
    assertEquals(3, opsOn(ops, "enrollments", "UPDATE").size());
    assertEquals("DROPPED", opsOn(ops, "enrollments", "UPDATE").get(0).values().get("status"));
    var refunds = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(3, refunds.size());
    assertEquals("AUTO_REFUND", refunds.get(0).values().get("action"));
    assertTrue(refunds.get(0).values().get("reason").toString().contains("未达到最低开课人数"));
    assertTrue(refunds.get(0).values().get("reason").toString().contains("已自动退回"));
    var courseOps = opsOn(ops, "courses", "UPDATE");
    assertEquals(2, courseOps.size());
    assertEquals("CANCELLED", courseOps.get(0).values().get("status"));
    var publishOps = opsOn(ops, "course_selections", "UPDATE");
    assertEquals(1, publishOps.size());
    assertEquals("CLOSED", publishOps.get(0).values().get("status"));
    assertEquals(4, publishOps.get(0).values().get("version"));
  }

  @Test
  void settleOnlyAllowedWhenPublishIsOpen() {
    stubPublish(PUBLISH, openPublish("status", "CANCELLED"));
    var error = assertThrows(ApiException.class, () -> service.settle(admin, map("id", PUBLISH)));
    assertEquals(409, error.status);
    assertEquals("选课未开放", error.getMessage());
  }

  @Test
  void scheduledTaskSettlesOnlyExpiredPublishes() {
    var expired = openPublish("end_time", iso(-1));
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(List.of(expired, openPublish("id", "p2", "end_time", iso(1))));
    stubPublish(PUBLISH, expired);
    when(repo.find("enrollments", Map.of("publish_id", PUBLISH, "status", "ACTIVE")))
        .thenReturn(List.of());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    service.autoSettleExpired();
    verify(repo).mutate(anyList(), eq("SYSTEM"), eq("SELECTION_SETTLE"), eq(PUBLISH));
    verify(repo, never()).mutate(anyList(), anyString(), eq("SELECTION_SETTLE"), eq("p2"));
  }

  @Test
  void scheduledTaskNeverThrowsWhenDataServiceFails() {
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenThrow(new IllegalStateException("data-service unavailable"));
    assertDoesNotThrow(() -> service.autoSettleExpired());
  }

  // ---------------------------------------------------------------- 退课与批量

  @Test
  void dropRejectsWhenDropDisabled() {
    stubPublish(PUBLISH, openPublish("allow_drop", 0));
    var error = assertThrows(ApiException.class, () -> service.drop(student, dropBody()));
    assertEquals(403, error.status);
    assertEquals("本次选课不允许退课", error.getMessage());
  }

  @Test
  void dropRejectsCourseWithGrades() {
    stubPublish(PUBLISH, openPublish());
    when(repo.find("enrollments", Map.of("course_id", "c1", "student_id", STUDENT_ID, "status", "ACTIVE")))
        .thenReturn(List.of(enrollment("c1", STUDENT_ID, "ACTIVE", PUBLISH)));
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", STUDENT_ID)))
        .thenReturn(List.of(grade("c1", "SUBMITTED", scores(80))));
    var error = assertThrows(ApiException.class, () -> service.drop(student, dropBody()));
    assertEquals(409, error.status);
    assertEquals("教师已录入成绩，不能退课", error.getMessage());
  }

  @Test
  void dropRejectsMissingEnrollment() {
    stubPublish(PUBLISH, openPublish());
    var error = assertThrows(ApiException.class, () -> service.drop(student, dropBody()));
    assertEquals(404, error.status);
    assertEquals("没有该课程的选课记录", error.getMessage());
  }

  @Test
  void dropMarksEnrollmentDroppedAndWritesRecord() {
    stubPublish(PUBLISH, openPublish());
    when(repo.find("enrollments", Map.of("course_id", "c1", "student_id", STUDENT_ID, "status", "ACTIVE")))
        .thenReturn(List.of(enrollment("c1", STUDENT_ID, "ACTIVE", PUBLISH)));
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", STUDENT_ID))).thenReturn(List.of());
    when(repo.find("courses", Map.of("id", "c1")))
        .thenReturn(List.of(course("c1", "CS101", "数据结构", TERM, 3, "t1")));
    assertEquals(Boolean.TRUE, service.drop(student, dropBody()).get("ok"));
    var ops = capturedOps("SELECTION_DROP");
    assertEquals("DROPPED", opsOn(ops, "enrollments", "UPDATE").get(0).values().get("status"));
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("DROP", records.get(0).values().get("action"));
  }

  @Test
  void dropRejectsStudentDroppingSomeoneElse() {
    stubPublish(PUBLISH, openPublish());
    var body = dropBody();
    body.put("studentId", "s2");
    var error = assertThrows(ApiException.class, () -> service.drop(student, body));
    assertEquals(403, error.status);
    assertEquals("只能退选本人的课程", error.getMessage());
  }

  @Test
  void batchByClassSkipsStudentsAlreadySelected() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubClassByName("软件工程2301班", map("id", "B01001", "name", "软件工程2301班", "grade_year", "2023"));
    when(repo.find("users", Map.of("class_id", "B01001")))
        .thenReturn(
            List.of(
                map("id", "s1", "role", "STUDENT", "enabled", 1, "name", "张三"),
                map("id", "s2", "role", "STUDENT", "enabled", 1, "name", "李四")));
    stubUser("s1", "STUDENT", 1);
    stubUser("s2", "STUDENT", 1);
    stubEnrollments("s1", enrollment("c1", "s1", "ACTIVE", PUBLISH));
    stubEnrollments("s2");
    var body = map("publishId", PUBLISH, "courseId", "c1", "className", "软件工程2301班");
    var result = service.batch(admin, body);
    assertEquals(1, ((Number) result.get("added")).intValue());
    assertEquals(1, ((Number) result.get("skipped")).intValue());
    assertEquals(0, ((Number) result.get("removed")).intValue());
    assertTrue(((List<?>) result.get("failed")).isEmpty());
    var ops = capturedOps("SELECTION_BATCH");
    assertEquals(1, opsOn(ops, "enrollments", "INSERT").size());
    var records = opsOn(ops, "enrollment_records", "INSERT");
    assertEquals(1, records.size());
    assertEquals("ADMIN_ASSIGN", records.get(0).values().get("action"));
    assertEquals("s2", records.get(0).values().get("student_id"));
  }

  @Test
  void batchRemovesStudentsByExplicitIds() {
    stubPublish(PUBLISH, openPublish());
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubUser("s2", "STUDENT", 1);
    stubEnrollments("s2", enrollment("c1", "s2", "ACTIVE", PUBLISH));
    when(repo.find("grades", Map.of("course_id", "c1", "student_id", "s2"))).thenReturn(List.of());
    var body =
        map("publishId", PUBLISH, "courseId", "c1", "studentIds", List.of("s2"), "remove", true);
    var result = service.batch(admin, body);
    assertEquals(1, ((Number) result.get("removed")).intValue());
    assertEquals(0, ((Number) result.get("added")).intValue());
    var ops = capturedOps("SELECTION_BATCH");
    assertEquals("DROPPED", opsOn(ops, "enrollments", "UPDATE").get(0).values().get("status"));
    assertEquals(
        "ADMIN_REMOVE", opsOn(ops, "enrollment_records", "INSERT").get(0).values().get("action"));
  }

  @Test
  void batchRecordsPerStudentFailuresWithoutAbortingOthers() {
    stubPublish(PUBLISH, openPublish("max_credits", 2));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    stubUser("s1", "STUDENT", 1);
    stubUser("s2", "STUDENT", 1);
    stubEnrollments("s1");
    stubEnrollments("s2");
    var body = map("publishId", PUBLISH, "courseId", "c1", "studentIds", List.of("s1", "s2"));
    var result = service.batch(admin, body);
    assertEquals(0, ((Number) result.get("added")).intValue());
    assertEquals(2, ((List<?>) result.get("failed")).size());
    assertTrue(
        ((Map<?, ?>) ((List<?>) result.get("failed")).get(0))
            .get("reason")
            .toString()
            .contains("超出学分上限"));
  }

  // ---------------------------------------------------------------- 查询

  @Test
  void availableMarksEligibilityWithSameRulesAsSelect() {
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(List.of(openPublish("course_ids", "c1,c2")));
    stubCourses(
        course("c1", "CS101", "数据结构", TERM, 3, "t1"),
        course("c2", "CS102", "操作系统", TERM, 3, "t2"));
    stubEnrollments(STUDENT_ID, enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    when(repo.find("enrollments", Map.of()))
        .thenReturn(
            List.of(
                enrollment("c1", "s9", "ACTIVE", PUBLISH),
                enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH)));
    var batches = service.available(student);
    assertEquals(1, batches.size());
    var courses = maps(batches.get(0).get("courses"));
    assertEquals(2, courses.size());
    assertEquals(Boolean.TRUE, courses.get(0).get("eligible"));
    assertEquals(Boolean.FALSE, courses.get(0).get("selected"));
    assertEquals(1, ((Number) courses.get(0).get("enrolled")).intValue());
    assertEquals(Boolean.FALSE, courses.get(1).get("eligible"));
    assertEquals("已选修该课程", courses.get(1).get("reason"));
  }

  @Test
  void availableHidesPublishesOutsideWindowOrScope() {
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(
            List.of(
                openPublish("id", "future", "start_time", iso(2), "end_time", iso(3)),
                openPublish("id", "other", "scope_major_ids", "M01999"),
                openPublish("id", "ok")));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    when(repo.find("enrollments", Map.of())).thenReturn(List.of());
    var batches = service.available(student);
    assertEquals(1, batches.size());
    assertEquals("ok", batches.get(0).get("id"));
  }

  @Test
  void myListsOnlyActiveEnrollmentsWithCourseNames() {
    stubCourses(
        course("c1", "CS101", "数据结构", TERM, 3, "t1"),
        course("c2", "CS102", "操作系统", TERM, 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", PUBLISH),
        enrollment("c2", STUDENT_ID, "DROPPED", PUBLISH));
    var rows = service.my(student);
    assertEquals(1, rows.size());
    assertEquals("CS101", rows.get(0).get("code"));
    assertEquals("数据结构", rows.get(0).get("name"));
    assertEquals("SELECTION", rows.get(0).get("source"));
    assertEquals(PUBLISH, rows.get(0).get("publishId"));
  }

  @Test
  void recordsAreFilteredAndEnrichedWithNames() {
    when(repo.find("enrollment_records", Map.of("publish_id", PUBLISH)))
        .thenReturn(
            List.of(
                map(
                    "id", "r1", "publish_id", PUBLISH, "course_id", "c1", "code", "CS101",
                    "student_id", STUDENT_ID, "term", TERM, "action", "SELECT", "reason", "",
                    "operator", STUDENT_ID, "created_at", "2026-02-01T08:00:00Z")));
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(List.of(map("id", STUDENT_ID, "name", "张三", "role", "STUDENT")));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    var page = service.records(admin, query("publishId", PUBLISH));
    assertEquals(1, ((Number) page.get("total")).intValue());
    var item = maps(page.get("items")).get(0);
    assertEquals("张三", item.get("studentName"));
    assertEquals("数据结构", item.get("courseName"));
    assertEquals("选课", item.get("actionName"));
  }

  @Test
  void listReturnsTranslatedNamesAndCounters() {
    when(repo.find("course_selections", Map.of()))
        .thenReturn(List.of(openPublish("scope_college_ids", "C01001")));
    stubCourses(course("c1", "CS101", "数据结构", TERM, 3, "t1"));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(map("id", "C01001", "name", "信息工程学院")));
    when(repo.find("users", Map.of("role", "TEACHER")))
        .thenReturn(List.of(map("id", "t1", "name", "王老师", "role", "TEACHER")));
    when(repo.find("enrollments", Map.of()))
        .thenReturn(List.of(enrollment("c1", "s1", "ACTIVE", PUBLISH)));
    var page = service.list(admin, Map.of());
    assertEquals(1, ((Number) page.get("total")).intValue());
    var item = maps(page.get("items")).get(0);
    assertEquals("进行中", item.get("statusName"));
    assertEquals(1, ((Number) item.get("courseCount")).intValue());
    assertEquals(1, ((Number) item.get("selectedCount")).intValue());
    assertEquals(List.of("信息工程学院"), item.get("scopeCollegeNames"));
    assertEquals("信息工程学院", item.get("scopeLabel"));
    assertEquals("王老师", maps(item.get("courses")).get(0).get("teacherName"));
  }

  // ---------------------------------------------------------------- 重修状态（R4）

  /**
   * 重修 = 同一课程号在**更早学期**修读且没有通过（成绩已提交、有效分 &lt; 60），本学期重新修读。
   *
   * <p>重修不体现在课程名上：学生选的就是下一学年重新开设的普通课程，判断依据只有历史成绩，
   * 因此前端要用 {@code retake} / {@code retakeLabel} 展示「重修」。
   */
  @Test
  void myMarksRetakeForSameCodeFailedInEarlierTerm() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "SUBMITTED", scores(45)));

    var rows = service.my(student);

    var earlier = byTerm(rows, "2023-1");
    assertEquals(Boolean.FALSE, earlier.get("retake"), "挂科的当学期自己不是重修");
    assertNull(earlier.get("retakeLabel"));
    var retaking = byTerm(rows, "2024-1");
    assertEquals(Boolean.TRUE, retaking.get("retake"));
    assertEquals("重修", retaking.get("retakeLabel"));
    // 课程名本身跟重修无关：同一课程号在下一学年重新开设，名字仍是普通课程名。
    assertEquals("操作系统", retaking.get("name"));
  }

  @Test
  void myMarksRetakeWhenMakeupAlsoFailed() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    // 正考 40、补考 55：补考也未通过（有效分 55 < 60）→ 本学期这门课是重修。
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "SUBMITTED", scoresWithMakeup(40, 55)));

    var rows = service.my(student);

    assertEquals(Boolean.TRUE, byTerm(rows, "2024-1").get("retake"));
    assertEquals("重修", byTerm(rows, "2024-1").get("retakeLabel"));
  }

  @Test
  void myDoesNotMarkRetakeWhenMakeupPassed() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    // 正考挂科但补考及格：有效分被封顶到 60 → 已通过，不需要重修。
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "SUBMITTED", scoresWithMakeup(40, 70)));

    var rows = service.my(student);

    assertEquals(Boolean.FALSE, byTerm(rows, "2024-1").get("retake"));
    assertNull(byTerm(rows, "2024-1").get("retakeLabel"));
  }

  @Test
  void myDoesNotMarkRetakeWhenEarlierTermPassed() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "SUBMITTED", scores(85)));

    var rows = service.my(student);

    assertEquals(
        Boolean.FALSE, byTerm(rows, "2024-1").get("retake"), "已通过的课程不能误标成重修");
    assertNull(byTerm(rows, "2024-1").get("retakeLabel"));
  }

  @Test
  void myDoesNotMarkRetakeWhenEarlierGradeIsDraft() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    // 成绩还没提交（教师正在录入）：不能把「正在修读」误判成挂科重修。
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "DRAFT", scores(45)));

    var rows = service.my(student);

    assertEquals(Boolean.FALSE, byTerm(rows, "2024-1").get("retake"));
    assertNull(byTerm(rows, "2024-1").get("retakeLabel"));
  }

  @Test
  void myDoesNotMarkRetakeWhenEarlierCourseHasNoGrade() {
    stubCourses(
        course("c1", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    stubGradeIndex();

    var rows = service.my(student);

    assertEquals(Boolean.FALSE, byTerm(rows, "2024-1").get("retake"));
    assertNull(byTerm(rows, "2024-1").get("retakeLabel"));
  }

  @Test
  void myDoesNotMarkRetakeForDifferentCourseCode() {
    stubCourses(
        course("c1", "CS103", "编译原理", "2023-1", 3, "t1"),
        course("c2", "CS102", "操作系统", "2024-1", 3, "t2"));
    stubEnrollments(
        STUDENT_ID,
        enrollment("c1", STUDENT_ID, "ACTIVE", "p0"),
        enrollment("c2", STUDENT_ID, "ACTIVE", PUBLISH));
    stubGradeIndex(gradeFor("c1", STUDENT_ID, "SUBMITTED", scores(45)));

    var rows = service.my(student);

    assertEquals(
        Boolean.FALSE, byTerm(rows, "2024-1").get("retake"), "挂的是另一门课程代码，不是重修");
    assertEquals(Boolean.FALSE, byTerm(rows, "2023-1").get("retake"));
  }

  @Test
  void availableMarksRetakeOnCoursesWithFailedCodeHistory() {
    when(repo.find("course_selections", Map.of("status", "OPEN")))
        .thenReturn(List.of(openPublish("course_ids", "c3,c4", "allow_retake", 1)));
    stubCourses(
        course("c0", "CS102", "操作系统", "2023-1", 3, "t1"),
        course("c3", "CS102", "操作系统", TERM, 3, "t1"),
        course("c4", "CS103", "编译原理", TERM, 3, "t2"));
    stubEnrollments(STUDENT_ID, enrollment("c0", STUDENT_ID, "ACTIVE", "p0"));
    stubGradeIndex(gradeFor("c0", STUDENT_ID, "SUBMITTED", scores(45)));
    stubGrades("c0", STUDENT_ID, grade("c0", "SUBMITTED", scores(45)));
    when(repo.find("enrollments", Map.of())).thenReturn(List.of());

    var batches = service.available(student);

    assertEquals(1, batches.size());
    var courses = maps(batches.get(0).get("courses"));
    var retaking = byId(courses, "c3");
    assertEquals(Boolean.TRUE, retaking.get("retake"));
    assertEquals("重修", retaking.get("retakeLabel"));
    assertEquals(Boolean.TRUE, retaking.get("eligible"), "允许重修时这门课可选");
    var plain = byId(courses, "c4");
    assertEquals(Boolean.FALSE, plain.get("retake"), "没有挂科史的课程不是重修");
    assertNull(plain.get("retakeLabel"));
    assertEquals(Boolean.TRUE, plain.get("eligible"));
  }

  @Test
  void listCountsRetakeStudentsPerCourse() {
    when(repo.find("course_selections", Map.of()))
        .thenReturn(List.of(openPublish("course_ids", "c1,c2")));
    stubCourses(
        course("c0", "CS102", "操作系统", "2023-1", 3, "t2"),
        course("c1", "CS102", "操作系统", TERM, 3, "t1"),
        course("c2", "CS103", "编译原理", TERM, 3, "t2"));
    when(repo.find("enrollments", Map.of()))
        .thenReturn(
            List.of(
                enrollment("c1", "s1", "ACTIVE", PUBLISH),
                enrollment("c1", "s2", "ACTIVE", PUBLISH),
                enrollment("c2", "s3", "ACTIVE", PUBLISH),
                enrollment("c0", "s1", "ACTIVE", "p0")));
    stubGradeIndex(gradeFor("c0", "s1", "SUBMITTED", scores(45)));

    var page = service.list(admin, Map.of());

    var item = maps(page.get("items")).get(0);
    var courses = maps(item.get("courses"));
    assertEquals(1, ((Number) byId(courses, "c1").get("retakeCount")).intValue(), "只有 s1 是重修");
    assertEquals(0, ((Number) byId(courses, "c2").get("retakeCount")).intValue());
  }

  // ---------------------------------------------------------------- 权限与路由

  @Test
  void adminPathsRejectStudentsAndStudentPathsRejectTeachers() {
    assertEquals(403, assertThrows(ApiException.class, () -> service.list(student, Map.of())).status);
    assertEquals(403, assertThrows(ApiException.class, () -> service.save(student, saveBody())).status);
    assertEquals(403, assertThrows(ApiException.class, () -> service.batch(student, map())).status);
    var teacher =
        new Models.User("t1", "teacher1", "李老师", "TEACHER", Set.of("QUERY", "ENTRY"), 0);
    assertEquals(403, assertThrows(ApiException.class, () -> service.select(teacher, selectBody())).status);
    assertEquals(403, assertThrows(ApiException.class, () -> service.my(teacher)).status);
    assertEquals(403, assertThrows(ApiException.class, () -> service.available(teacher)).status);
  }

  @Test
  void routesCoverEveryContractPathAndDispatch() {
    var stub = mock(SelectionService.class);
    var routes = new SelectionRoutes(stub);
    for (String path :
        List.of(
            "/selections",
            "/selections/available",
            "/selections/my",
            "/selections/records",
            "/selections/save",
            "/selections/close",
            "/selections/cancel",
            "/selections/settle",
            "/selections/select",
            "/selections/drop",
            "/selections/batch"))
      assertTrue(routes.handles().contains(path), "缺少路径 " + path);
    assertEquals(11, routes.handles().size());

    when(stub.available(student)).thenReturn(List.of());
    assertNotNull(
        routes.dispatch(
            new Routes.Request("/selections/available", student, false, Map.of(), Map.of(), null, null)));

    when(stub.select(student, selectBody())).thenReturn(Map.of("ok", true));
    var response =
        (Map<?, ?>)
            routes.dispatch(
                new Routes.Request("/selections/select", student, true, selectBody(), Map.of(), null, null));
    assertEquals(Boolean.TRUE, response.get("ok"));

    assertEquals(
        404,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        new Routes.Request("/selections/unknown", student, false, Map.of(), Map.of(), null, null)))
            .status);
  }

  // ---------------------------------------------------------------- 夹具

  private static Map<String, Object> map(Object... pairs) {
    var row = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) row.put(pairs[i].toString(), pairs[i + 1]);
    return row;
  }

  private static Map<String, String> query(String... pairs) {
    var row = new LinkedHashMap<String, String>();
    for (int i = 0; i + 1 < pairs.length; i += 2) row.put(pairs[i], pairs[i + 1]);
    return row;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    return (List<Map<String, Object>>) value;
  }

  private static Map<String, Object> byId(List<Map<String, Object>> rows, String id) {
    for (var row : rows) if (id.equals(row.get("id"))) return row;
    throw new AssertionError("没有 id 为 " + id + " 的记录：" + rows);
  }

  private static Map<String, Object> byTerm(List<Map<String, Object>> rows, String term) {
    for (var row : rows) if (term.equals(row.get("term"))) return row;
    throw new AssertionError("没有学期为 " + term + " 的记录：" + rows);
  }

  private static Map<String, Object> course(
      String id, String code, String name, String term, double credits, String teacherId) {
    return map(
        "id", id,
        "code", code,
        "name", name,
        "term", term,
        "credits", credits,
        "teacher_id", teacherId,
        "college_id", "C01001",
        "class_id", "B01001",
        "status", "ACTIVE",
        "weights", WEIGHTS,
        "version", 0);
  }

  /** 默认：进行中、窗口包含当前时间、允许选课与退课、不限学分、最低 1 人。 */
  private static Map<String, Object> openPublish(Object... overrides) {
    var publish =
        map(
            "id", PUBLISH,
            "name", "2026-1 学期选课",
            "term", TERM,
            "course_ids", "c1",
            "scope_college_ids", "",
            "scope_major_ids", "",
            "scope_class_ids", "",
            "start_time", iso(-1),
            "end_time", iso(1),
            "min_enroll", 1,
            "max_credits", 0,
            "allow_add", 1,
            "allow_drop", 1,
            "allow_retake", 0,
            "status", "OPEN",
            "published_by", "a1",
            "published_at", "2026-01-01T00:00:00Z",
            "note", "",
            "version", 0);
    for (int i = 0; i + 1 < overrides.length; i += 2)
      publish.put(overrides[i].toString(), overrides[i + 1]);
    return publish;
  }

  private static String iso(int daysFromNow) {
    return LocalDateTime.now()
        .plusDays(daysFromNow)
        .withNano(0)
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private static Map<String, Object> enrollment(
      String courseId, String studentId, String status, String publishId) {
    return map(
        "id", "e-" + courseId + "-" + studentId,
        "course_id", courseId,
        "student_id", studentId,
        "source", "SELECTION",
        "publish_id", publishId,
        "selected_at", "2026-02-01T08:00:00Z",
        "status", status);
  }

  private static Map<String, Object> grade(String courseId, String state, Map<String, Object> scores) {
    return map(
        "id", "g-" + courseId,
        "course_id", courseId,
        "student_id", STUDENT_ID,
        "state", state,
        "payload", Settings.json(scores),
        "version", 0);
  }

  /** 指定学生的成绩行：重修判定要按「课程 + 学生」建索引，不能固定挂在 STUDENT_ID 上。 */
  private static Map<String, Object> gradeFor(
      String courseId, String studentId, String state, Map<String, Object> scores) {
    return map(
        "id", "g-" + courseId + "-" + studentId,
        "course_id", courseId,
        "student_id", studentId,
        "state", state,
        "payload", Settings.json(scores),
        "version", 0);
  }

  /** 权重之外的分数项为 0，因此总分等于给定分数，便于断言“已通过 / 挂科”。 */
  private static Map<String, Object> scores(double value) {
    return map(
        "regular", value,
        "attendance", 0,
        "homework", 0,
        "lab", value,
        "midterm", 0,
        "finalExam", value);
  }

  /** 带补考成绩的分数项：有效分 = max(正考总分, min(60, 补考))。 */
  private static Map<String, Object> scoresWithMakeup(double value, double makeup) {
    var scores = new LinkedHashMap<>(scores(value));
    scores.put("makeup", makeup);
    return scores;
  }

  private static Map<String, Object> selectBody() {
    return map("publishId", PUBLISH, "courseId", "c1");
  }

  private static Map<String, Object> dropBody() {
    return map("publishId", PUBLISH, "courseId", "c1");
  }

  private static Map<String, Object> saveBody(Object... overrides) {
    var body =
        map(
            "name", "2026-1 学期选课",
            "term", TERM,
            "courseIds", List.of("c1"),
            "scopeCollegeIds", List.of(),
            "scopeMajorIds", List.of(),
            "scopeClassIds", List.of(),
            "startTime", iso(-1),
            "endTime", iso(1),
            "minEnroll", 1,
            "maxCredits", 0,
            "allowAdd", true,
            "allowDrop", true,
            "allowRetake", false,
            "note", "");
    for (int i = 0; i + 1 < overrides.length; i += 2)
      body.put(overrides[i].toString(), overrides[i + 1]);
    return body;
  }

  private void stubPublish(String id, Map<String, Object> publish) {
    when(repo.find("course_selections", Map.of("id", id))).thenReturn(List.of(publish));
  }

  @SafeVarargs
  private void stubCourses(Map<String, Object>... courses) {
    when(repo.find("courses", Map.of())).thenReturn(List.of(courses));
  }

  @SafeVarargs
  private void stubEnrollments(String studentId, Map<String, Object>... enrollments) {
    when(repo.find("enrollments", Map.of("student_id", studentId)))
        .thenReturn(List.of(enrollments));
  }

  @SafeVarargs
  private void stubGrades(String courseId, String studentId, Map<String, Object>... grades) {
    when(repo.find("grades", Map.of("course_id", courseId, "student_id", studentId)))
        .thenReturn(List.of(grades));
  }

  /**
   * 重修判定（failedCodes）一次性读 grades 全表建索引，桩的是 {@code repo.find("grades", Map.of())}，
   * 与逐门课查询的 {@link #stubGrades} 不是同一个键：两个都要桩才能同时覆盖规则校验与重修标注。
   */
  @SafeVarargs
  private final void stubGradeIndex(Map<String, Object>... grades) {
    when(repo.find("grades", Map.of())).thenReturn(List.of(grades));
  }

  private void stubUser(String id, String role, int enabled) {
    when(repo.find("users", Map.of("id", id)))
        .thenReturn(
            List.of(map("id", id, "name", "学生" + id, "role", role, "enabled", enabled)));
  }

  /**
   * 班级名称 → 班级行。
   *
   * <p>{@link OrganizationService#resolveOwn} 先按编号查、再按名称查；而 Mockito 对返回 Map 的
   * 被 mock 具体方法默认返回空 Map（不是 null），因此必须显式让“按编号查”返回 null，
   * “按名称查”这一支才会生效。
   */
  private void stubClassByName(String name, Map<String, Object> klazz) {
    when(repo.findOne("classes", Map.of("id", name))).thenReturn(null);
    when(repo.findOne("classes", Map.of("name", name))).thenReturn(klazz);
  }

  @SuppressWarnings("unchecked")
  private List<Protocol.Operation> capturedOps(String action) {
    var captor = ArgumentCaptor.forClass(List.class);
    verify(repo).mutate(captor.capture(), anyString(), eq(action), anyString());
    return (List<Protocol.Operation>) captor.getValue();
  }

  private static List<Protocol.Operation> opsOn(
      List<Protocol.Operation> ops, String table, String type) {
    var result = new ArrayList<Protocol.Operation>();
    for (var op : ops) if (op.table().equals(table) && op.type().equals(type)) result.add(op);
    return result;
  }
}
