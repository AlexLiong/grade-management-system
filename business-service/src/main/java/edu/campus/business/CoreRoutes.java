package edu.campus.business;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 既有的成绩、课程、统计、人员与审计路径。迁移到路由注册表后行为保持不变。 */
@Component
public class CoreRoutes implements Routes {
  private static final Set<String> PATHS =
      Set.of(
          "/me",
          "/courses",
          "/courses/catalog",
          "/roster",
          "/grades",
          "/statistics",
          "/transcript",
          "/users",
          "/users/teachers",
          "/audit",
          "/integrity",
          "/status",
          "/logout",
          "/password",
          "/grades/save",
          "/grades/transition",
          "/weights",
          "/analysis",
          "/predict",
          "/users/save",
          "/courses/save",
          "/enrollments",
          "/enrollments/batch",
          "/audit/review",
          "/audit/classify");

  private final AuthService auth;
  private final CourseService courses;
  private final GradeService grades;
  private final AnalyticsService analytics;
  private final AdminService admin;
  private final OrganizationService organizations;
  private final SelectionService selections;
  private final RemoteRepository repo;

  public CoreRoutes(
      AuthService auth,
      CourseService courses,
      GradeService grades,
      AnalyticsService analytics,
      AdminService admin,
      OrganizationService organizations,
      RemoteRepository repo,
      SelectionService selections) {
    this.auth = auth;
    this.courses = courses;
    this.grades = grades;
    this.analytics = analytics;
    this.admin = admin;
    this.organizations = organizations;
    this.repo = repo;
    this.selections = selections;
  }

  @Override
  public Set<String> handles() {
    return PATHS;
  }

  @Override
  public Object dispatch(Request request) {
    Models.User u = request.user();
    Map<String, Object> body = request.body();
    Map<String, String> query = request.query();
    String path = request.path();
    if (!request.write())
      return switch (path) {
        case "/me" -> me(u);
        case "/courses" ->
            ApiController.page(
                courses.list(u).stream()
                    .filter(
                        c ->
                            query.getOrDefault("term", "").isBlank()
                                || c.get("term").equals(query.get("term")))
                    .filter(
                        c -> c.get("name").toString().contains(query.getOrDefault("search", "")))
                    .toList(),
                query);
        case "/courses/catalog" ->
            ApiController.page(
                courses.catalog(
                    u, query.getOrDefault("term", ""), query.getOrDefault("search", "")),
                query);
        case "/roster" -> courses.roster(u, query.get("courseId"));
        case "/grades" -> ApiController.page(grades.list(u, query.get("courseId")), query);
        case "/statistics" -> analytics.statistics(u, query.get("courseId"));
        case "/transcript" -> grades.transcript(u);
        case "/users" -> ApiController.page(admin.users(u), query);
        case "/users/teachers" -> admin.teachers(u);
        case "/audit" -> {
          u.require("AUDIT");
          yield repo.ledger();
        }
        case "/integrity" -> admin.integrity(u);
        case "/status" -> {
          ApiException.require(u.role().equals("ADMIN"), 403, "仅管理员可查看状态");
          yield repo.status();
        }
        default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
      };
    return switch (path) {
      case "/logout" -> {
        auth.logout(request.servletRequest(), request.servletResponse(), u);
        yield Map.of("ok", true);
      }
      case "/password" -> {
        auth.changePassword(u, body);
        yield Map.of("ok", true);
      }
      case "/grades/save" -> grades.save(u, body);
      case "/grades/transition" -> {
        grades.transition(u, body);
        yield Map.of("ok", true);
      }
      case "/weights" -> {
        courses.weights(u, body);
        yield Map.of("ok", true);
      }
      case "/analysis" -> {
        analytics.analysis(u, body);
        yield Map.of("ok", true);
      }
      case "/predict" -> analytics.predict(u, Models.text(body, "courseId", 100));
      case "/users/save" -> {
        admin.saveUser(u, body);
        yield Map.of("ok", true);
      }
      case "/courses/save" -> {
        courses.saveCourse(u, body);
        yield Map.of("ok", true);
      }
      case "/enrollments" -> {
        courses.enroll(u, body);
        yield Map.of("ok", true);
      }
      case "/enrollments/batch" -> selections.batchByCourse(u, body);
      case "/audit/review" -> {
        admin.review(u, body);
        yield Map.of("ok", true);
      }
      case "/audit/classify" -> {
        u.require("AUDIT");
        yield repo.logModel();
      }
      default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
    };
  }

  /**
   * 当前用户。除会话信息外附带学院、专业、班级名称：前端一律按名称展示与操作，
   * 不要求浏览器理解内部编号。
   */
  private Map<String, Object> me(Models.User u) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", u.id());
    row.put("username", u.username());
    row.put("name", u.name());
    row.put("role", u.role());
    row.put("permissions", u.permissions());
    row.put("version", u.version());
    row.put("collegeId", u.collegeId());
    row.put("majorId", u.majorId());
    row.put("classId", u.classId());
    row.put("collegeName", nameOf(Models.Level.COLLEGE, u.collegeId()));
    row.put("majorName", nameOf(Models.Level.MAJOR, u.majorId()));
    row.put("className", nameOf(Models.Level.CLASS, u.classId()));
    return row;
  }

  private String nameOf(Models.Level level, String id) {
    if (id == null || id.isBlank()) return null;
    return organizations.names(level, List.of(id)).get(id);
  }
}
