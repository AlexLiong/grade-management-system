package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CourseService {
  private final RemoteRepository repo;

  public CourseService(RemoteRepository repo) {
    this.repo = repo;
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
              .map(e -> e.get("course_id"))
              .toList();
      courses.removeIf(c -> !ids.contains(c.get("id")));
    }
    courses.sort(Comparator.comparing(c -> c.get("term").toString(), Comparator.reverseOrder()));
    return courses;
  }

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
    return enrollments.stream()
        .map(
            e -> {
              var r = new LinkedHashMap<String, Object>();
              r.put("id", e.get("student_id"));
              var s = users.get(e.get("student_id"));
              r.put("name", s == null ? "已移除学生" : s.get("name"));
              r.put("username", s == null ? "" : s.get("username"));
              return (Map<String, Object>) r;
            })
        .toList();
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
                credits));
    boolean create = b.get("id") == null;
    int version = create ? 0 : Models.integer(b.get("version"));
    v.put("version", create ? 0 : version + 1);
    if (create) {
      v.put("id", id);
      v.put("weights", Settings.json(defaultWeights()));
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

  public void enroll(Models.User u, Map<String, Object> b) {
    u.require("GRADE_ADMIN");
    String course = Models.text(b, "courseId", 100), student = Models.text(b, "studentId", 100);
    var c = repo.one("courses", course);
    var s = repo.one("users", student);
    ApiException.require(
        s.get("role").equals("STUDENT")
            && (Boolean.TRUE.equals(b.get("remove")) || Models.integer(s.get("enabled")) == 1),
        400,
        "选课人必须为启用的学生");
    int v = Models.integer(c.get("version"));
    List<Protocol.Operation> ops = new ArrayList<>();
    ops.add(update("courses", course, Map.of("version", v + 1), v));
    if (Boolean.TRUE.equals(b.get("remove"))) {
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
                  "id", UUID.randomUUID().toString(), "course_id", course, "student_id", student)));
    repo.mutate(ops, u.id(), "ENROLLMENT_UPDATE", course);
  }

  public static Map<String, Object> defaultWeights() {
    return Map.of(
        "regular", 30, "attendance", 0, "homework", 0, "lab", 20, "midterm", 0, "finalExam", 50);
  }
}
