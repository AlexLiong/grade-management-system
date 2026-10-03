package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CourseService {
  private final RemoteRepository repo;
  private final OrganizationService organizations;

  public CourseService(RemoteRepository repo, OrganizationService organizations) {
    this.repo = repo;
    this.organizations = organizations;
  }

  public Map<String, Object> access(Models.User user, String id, String permission) {
    user.require(permission);
    var course = repo.one("courses", id);
    if (user.role().equals("TEACHER"))
      ApiException.require(user.id().equals(course.get("teacher_id")), 403, "只能访问本人授课课程");
    if (user.role().equals("STUDENT"))
      ApiException.require(
          !repo.find("enrollments", Map.of("course_id", id, "student_id", user.id())).isEmpty(),
          403,
          "未选修该课程");
    return course;
  }

  public List<Map<String, Object>> list(Models.User u) {
    if (u.role().equals("ADMIN"))
      ApiException.require(
          u.permissions().contains("GRADE_ADMIN") || u.permissions().contains("AUDIT"),
          403,
          "无课程查询权限");
    else u.require("QUERY");
    var courses =
        repo.find("courses", u.role().equals("TEACHER") ? Map.of("teacher_id", u.id()) : Map.of());
    if (u.role().equals("STUDENT")) {
      var ids =
          repo.find("enrollments", Map.of("student_id", u.id())).stream()
              .filter(e -> !"DROPPED".equals(e.get("status")))
              .map(e -> e.get("course_id"))
              .toList();
      courses.removeIf(c -> !ids.contains(c.get("id")));
    }
    courses.sort(Comparator.comparing(c -> c.get("term").toString(), Comparator.reverseOrder()));
    return courses.stream().map(this::withOfferingCollege).toList();
  }

  /**
   * 课程目录：选课页面用它列出“可选课程”。教师/学生都可读，只返回启用中的教学班；
   * 前端按课程代码、名称、学期与开设学院筛选，展示的是学院名称而不是编号。
   */
  public List<Map<String, Object>> catalog(Models.User u, String term, String search) {
    // 教师与学生读课程目录用于成绩/选课；教务管理员（GRADE_ADMIN 或 SELECTION_ADMIN）
    // 读它用于课程维护与发布选课，因此这里接受 QUERY 或这两个管理权限之一。
    ApiException.require(
        u.permissions().contains("QUERY")
            || u.permissions().contains("GRADE_ADMIN")
            || u.permissions().contains("SELECTION_ADMIN"),
        403,
        "没有此操作权限");
    var courses = new ArrayList<>(repo.find("courses", Map.of()));
    courses.removeIf(c -> "CANCELLED".equals(c.get("status")));
    if (term != null && !term.isBlank())
      courses.removeIf(c -> !term.equals(c.get("term")));
    if (search != null && !search.isBlank())
      courses.removeIf(c -> !c.get("name").toString().contains(search));
    courses.sort(
        Comparator.comparing((Map<String, Object> c) -> c.get("term").toString())
            .reversed()
            .thenComparing(c -> c.get("code").toString()));
    Map<String, String> teachers = new HashMap<>();
    repo.find("users", Map.of("role", "TEACHER"))
        .forEach(t -> teachers.put(t.get("id").toString(), t.get("name").toString()));
    var result = new ArrayList<Map<String, Object>>();
    for (var c : courses) {
      var row = withOfferingCollege(c);
      row.put("teacherName", teachers.getOrDefault(c.get("teacher_id"), ""));
      row.put("className", classNames(List.of(c)).get(c.get("class_id")));
      result.add(row);
    }
    return result;
  }

  /** 附加开设院系名称与面向班级名称，前端不需要理解内部编号。 */
  public Map<String, Object> withOfferingCollege(Map<String, Object> course) {
    var row = new LinkedHashMap<>(course);
    String collegeId = text(course.get("college_id"));
    row.put(
        "collegeName",
        collegeId == null
            ? null
            : organizations.names(Models.Level.COLLEGE, List.of(collegeId)).get(collegeId));
    return row;
  }

  /** 批量把班级编号映射成显示名称。 */
  public Map<String, String> classNames(List<Map<String, Object>> courses) {
    var ids = new ArrayList<String>();
    for (var c : courses) ids.add(text(c.get("class_id")));
    return organizations.names(Models.Level.CLASS, ids);
  }

  private static String text(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString();
  }

  /**
   * 教学班名单。教师查看所带课程班级的学生，因此每条记录附带学生的学院、专业、班级名称，
   * 以及**该生是否为重修**。
   *
   * <p>重修的口径：该生在本课程**更早学期**修读过**同一课程代码**且没有通过（正考挂科、补考
   * 也未通过），本学期重新修读同一课程号。课程名本身与重修无关，所以「谁在重修」只能由历史
   * 成绩推出来，这里把它算好一并下发，教师端就能直接看到哪些学生是重修。
   */
  public List<Map<String, Object>> roster(Models.User u, String id) {
    access(u, id, u.role().equals("ADMIN") ? "GRADE_ADMIN" : "QUERY");
    var enrollments =
        repo.find(
            "enrollments",
            u.role().equals("STUDENT")
                ? Map.of("course_id", id, "student_id", u.id())
                : Map.of("course_id", id));
    Map<String, Map<String, Object>> users = new HashMap<>();
    repo.find("users", Map.of("role", "STUDENT"))
        .forEach(r -> users.put(r.get("id").toString(), r));
    Map<String, String> colleges =
        organizations.names(Models.Level.COLLEGE, collegeIds(users.values()));
    Map<String, String> majors =
        organizations.names(Models.Level.MAJOR, majorIds(users.values()));
    Map<String, String> classes =
        organizations.names(Models.Level.CLASS, classIds(users.values()));
    // 重修判定：本课程代码在更早学期是否有「已提交且不及格」的成绩。
    var course = repo.findOne("courses", Map.of("id", id));
    String code = course == null ? null : text(course.get("code"));
    var failedBefore = failedCodesBefore(id, code);
    return enrollments.stream()
        .filter(e -> !"DROPPED".equals(e.get("status")))
        .map(
            e -> {
              var r = new LinkedHashMap<String, Object>();
              r.put("id", e.get("student_id"));
              var s = users.get(e.get("student_id"));
              r.put("name", s == null ? "已移除学生" : s.get("name"));
              r.put("username", s == null ? "" : s.get("username"));
              r.put("source", e.get("source"));
              r.put("collegeName", s == null ? null : colleges.get(text(s.get("college_id"))));
              r.put("majorName", s == null ? null : majors.get(text(s.get("major_id"))));
              r.put("className", s == null ? null : classes.get(text(s.get("class_id"))));
              // 该生在本课程更早学期挂过同一课程代码 → 本学期这条记录是重修。
              boolean retake = failedBefore.contains(text(e.get("student_id")));
              r.put("retake", retake);
              r.put("retakeLabel", retake ? "重修" : null);
              return (Map<String, Object>) r;
            })
        .toList();
  }

  /**
   * 找出「在本课程更早学期、同一课程代码上挂过科」的学生。
   *
   * <p>只统计成绩已提交且有效分 &lt; 60 的记录；正在修读（成绩未出/未提交）与已退课都不算。
   */
  private Set<String> failedCodesBefore(String courseId, String code) {
    var result = new HashSet<String>();
    if (courseId == null || code == null) return result;
    var course = repo.findOne("courses", Map.of("id", courseId));
    String term = course == null ? null : text(course.get("term"));
    if (term == null) return result;
    // 同一课程代码的其他教学班（不同学年开设），只保留更早学期。
    var sameCode = new ArrayList<Map<String, Object>>();
    for (var other : repo.find("courses", Map.of("code", code))) {
      String otherTerm = text(other.get("term"));
      if (otherTerm == null || otherTerm.compareTo(term) >= 0) continue;
      sameCode.add(other);
    }
    if (sameCode.isEmpty()) return result;
    var gradeIndex = new HashMap<String, Map<String, Object>>();
    for (var grade : repo.find("grades", Map.of()))
      gradeIndex.put(text(grade.get("course_id")) + "|" + text(grade.get("student_id")), grade);
    for (var other : sameCode) {
      String otherId = text(other.get("id"));
      for (var enrollment : repo.find("enrollments", Map.of("course_id", otherId))) {
        if ("DROPPED".equals(text(enrollment.get("status")))) continue;
        String studentId = text(enrollment.get("student_id"));
        if (studentId == null) continue;
        var grade = gradeIndex.get(otherId + "|" + studentId);
        if (grade == null || !"SUBMITTED".equals(text(grade.get("state")))) continue;
        try {
          Double effective =
              Models.effective(
                  Models.object(grade.get("payload")), Models.object(other.get("weights")));
          if (effective != null && effective < 60) result.add(studentId);
        } catch (RuntimeException ignored) {
          // 成绩损坏时按「不构成重修依据」处理，与本项目其他地方的口径一致。
        }
      }
    }
    return result;
  }

  private static List<String> collegeIds(Collection<Map<String, Object>> users) {
    var ids = new ArrayList<String>();
    for (var s : users) ids.add(text(s.get("college_id")));
    return ids;
  }

  private static List<String> majorIds(Collection<Map<String, Object>> users) {
    var ids = new ArrayList<String>();
    for (var s : users) ids.add(text(s.get("major_id")));
    return ids;
  }

  private static List<String> classIds(Collection<Map<String, Object>> users) {
    var ids = new ArrayList<String>();
    for (var s : users) ids.add(text(s.get("class_id")));
    return ids;
  }

  public void weights(Models.User u, Map<String, Object> body) {
    String id = Models.text(body, "courseId", 100);
    var c = access(u, id, "MAINTAIN");
    ApiException.require(u.role().equals("TEACHER"), 403, "仅教师可设置系数");
    ApiException.require(
        repo.find("grades", Map.of("course_id", id, "state", "SUBMITTED")).isEmpty(),
        409,
        "已提交成绩后不可修改系数，需先撤销");
    Map<String, Object> w = Models.object(body.get("weights"));
    ApiException.require(w.keySet().equals(new HashSet<>(Models.COMPONENTS)), 400, "必须指定全部六项系数");
    double sum = 0;
    for (var v : w.values()) {
      double d = Models.number(v);
      ApiException.require(d >= 0 && d <= 100, 400, "系数必须为 0–100");
      sum += d;
    }
    ApiException.require(Math.abs(sum - 100) < .0001, 400, "系数总和必须为 100%");
    int version = Models.integer(body.get("version"));
    List<Protocol.Operation> ops = new ArrayList<>();
    ops.add(
        update(
            "courses", id, Map.of("weights", Settings.json(w), "version", version + 1), version));
    // Revalidate existing drafts because changing weights may change eligibility for makeup.
    for (var g : repo.find("grades", Map.of("course_id", id))) {
      var scores = Models.object(g.get("payload"));
      Double total = Models.total(scores, w);
      ApiException.require(
          scores.get("makeup") == null || (total != null && total < 60),
          409,
          "新系数使已有补考不再符合条件，请先清除补考成绩");
    }
    repo.mutate(ops, u.id(), "WEIGHTS_UPDATE", id);
  }

  public void saveCourse(Models.User u, Map<String, Object> b) {
    u.require("GRADE_ADMIN");
    String id = b.get("id") == null ? UUID.randomUUID().toString() : Models.text(b, "id", 100);
    String teacher = Models.text(b, "teacherId", 100);
    var t = repo.one("users", teacher);
    ApiException.require(
        t.get("role").equals("TEACHER") && Models.integer(t.get("enabled")) == 1,
        400,
        "授课人必须为启用的教师");
    String term = Models.text(b, "term", 20);
    ApiException.require(term.matches("20\\d{2}-[12]"), 400, "学期格式应为 2026-1");
    double credits = Models.number(b.get("credits"));
    ApiException.require(credits > 0 && credits <= 30, 400, "学分范围错误");
    // 开设院系与面向班级：前端按名称提交，这里归一化成组织编号。
    Object collegeRaw = b.get("collegeId") != null ? b.get("collegeId") : b.get("college");
    String collegeId = organizations.resolveOwn(Models.Level.COLLEGE, collegeRaw);
    ApiException.require(collegeId != null, 400, "必须指定开设院系");
    String classId =
        organizations.resolveOwn(Models.Level.CLASS, b.get("classId") != null ? b.get("classId") : b.get("className"));
    if (classId != null) {
      var klazz = organizations.requireOrganization(Models.Level.CLASS, classId);
      ApiException.require(
          collegeId.equals(klazz.get("college_id")),
          400,
          "面向班级不属于所选开设院系");
    }
    var v =
        new LinkedHashMap<String, Object>(
            Map.of(
                "code",
                Models.text(b, "code", 30),
                "name",
                Models.text(b, "name", 100),
                "term",
                term,
                "teacher_id",
                teacher,
                "credits",
                credits,
                "college_id",
                collegeId));
    if (classId != null) v.put("class_id", classId);
    boolean create = b.get("id") == null;
    int version = create ? 0 : Models.integer(b.get("version"));
    v.put("version", create ? 0 : version + 1);
    if (create) {
      v.put("id", id);
      v.put("weights", Settings.json(defaultWeights()));
      v.put("status", "ACTIVE");
      // 同一学期允许多个教学班使用相同课程代码（不同教师分别开课），这是选课系统
      // “必修课让学生选择某个教师的课”的前提；系统改为在选课环节保证同一学生不会
      // 重复修读同一课程代码，而不是禁止教务开设多个教学班。参见
      // SelectionService 的选课规则 10（同一学期同代码的其他教学班）与规则 11
      // （此前学期已通过不得重选）。
    } else {
      var old = repo.one("courses", id);
      ApiException.require(
          old.get("code").equals(v.get("code")) && old.get("term").equals(term),
          400,
          "已有课程的代码和学期不可更改");
    }
    repo.mutate(
        List.of(create ? insert("courses", v) : update("courses", id, v, version)),
        u.id(),
        "COURSE_SAVE",
        id);
  }

  /**
   * 管理员直接维护教学班名单。选课系统的批量与规则校验见 {@link SelectionService}；
   * 这里保留原有语义，只补充来源标记、状态字段，并把这次代选 / 代退写进选课记录
   * （{@code ADMIN_ASSIGN} / {@code ADMIN_REMOVE}），与按课程批量选课共用同一张流水表。
   */
  public void enroll(Models.User u, Map<String, Object> b) {
    u.require("GRADE_ADMIN");
    String course = Models.text(b, "courseId", 100), student = Models.text(b, "studentId", 100);
    var c = repo.one("courses", course);
    var s = repo.one("users", student);
    boolean remove = Boolean.TRUE.equals(b.get("remove"));
    ApiException.require(
        s.get("role").equals("STUDENT")
            && (remove || Models.integer(s.get("enabled")) == 1),
        400,
        "选课人必须为启用的学生");
    int v = Models.integer(c.get("version"));
    String now = Instant.now().toString();
    List<Protocol.Operation> ops = new ArrayList<>();
    ops.add(update("courses", course, Map.of("version", v + 1), v));
    if (remove) {
      ApiException.require(
          repo.find("grades", Map.of("course_id", course, "student_id", student)).isEmpty(),
          409,
          "已有成绩，不能退选");
      var e = repo.find("enrollments", Map.of("course_id", course, "student_id", student));
      ApiException.require(!e.isEmpty(), 404, "选课不存在");
      ops.add(delete("enrollments", e.get(0).get("id").toString(), null));
    } else
      ops.add(
          insert(
              "enrollments",
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "course_id", course,
                  "student_id", student,
                  "source", "ADMIN",
                  "status", "ACTIVE",
                  "selected_at", now)));
    ops.add(
        enrollmentRecord(c, student, remove ? "ADMIN_REMOVE" : "ADMIN_ASSIGN", u.id(), now));
    repo.mutate(ops, u.id(), "ENROLLMENT_UPDATE", course);
  }

  /** 单人代选 / 代退的选课流水：与按课程批量选课共用分表与动作，教师可在选课记录里查到。 */
  private Protocol.Operation enrollmentRecord(
      Map<String, Object> course,
      String studentId,
      String action,
      String operator,
      String createdAt) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID().toString());
    put(row, "course_id", course.get("id"));
    put(row, "code", course.get("code"));
    put(row, "student_id", studentId);
    put(row, "term", course.get("term"));
    row.put("action", action);
    row.put("reason", "ADMIN_REMOVE".equals(action) ? "教务直接退课" : "教务直接选课");
    put(row, "operator", operator);
    put(row, "created_at", createdAt);
    return insert("enrollment_records", row);
  }

  /** 数据服务不接受空值字段，可选列拿到空值时直接不写。 */
  private static void put(Map<String, Object> row, String key, Object value) {
    if (value != null && !value.toString().isBlank()) row.put(key, value);
  }

  public static Map<String, Object> defaultWeights() {
    return Map.of(
        "regular", 30, "attendance", 0, "homework", 0, "lab", 20, "midterm", 0, "finalExam", 50);
  }
}
