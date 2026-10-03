package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 组织管理路径：学院—专业—班级三级组织的查询、维护与人员归属调整。
 *
 * <p>只负责 HTTP 语义（路径、查询参数、请求体字段）与统一事务的组装；编号生成、重名校验、
 * 级联删除与权限判断都委托给 {@link OrganizationService}，行为与既有服务保持一致。前端一律
 * 按名称操作：请求体中的 college/major 既接受编号也接受名称，响应统一附带可读名称。
 *
 * <p>组织对外的编号就是主键 {@code id}（{@code C01001}、{@code M01001}、{@code B01001}…）：
 * 新建时由后端通过 {@link OrganizationService#nextId} 自动生成，修改时保持不变。接口只把它作为
 * 普通字段随列表/下拉返回，前端只读展示、不提供编号输入框，也不接受前端提交编号。
 *
 * <p>所有写操作都组装成 {@link Protocol.Operation} 后交给 {@link RemoteRepository#mutate}
 * 一次性提交，因而自动进入审计账本，不绕过统一事务直接写库。
 */
@Component
public class OrganizeRoutes implements Routes {
  private static final Set<String> PATHS =
      Set.of(
          "/organizations",
          "/organizations/options",
          "/organizations/impact",
          "/organizations/members",
          "/organizations/students",
          "/organizations/save",
          "/organizations/delete",
          "/organizations/assign");

  private final OrganizationService organizations;
  private final RemoteRepository repo;

  public OrganizeRoutes(OrganizationService organizations, RemoteRepository repo) {
    this.organizations = organizations;
    this.repo = repo;
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
        case "/organizations" -> list(u, query);
        case "/organizations/options" -> options();
        case "/organizations/impact" -> impact(u, query);
        case "/organizations/members" -> members(u, query);
        case "/organizations/students" -> students(u, query);
        default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
      };
    return switch (path) {
      case "/organizations/save" -> save(u, body);
      case "/organizations/delete" -> remove(u, body);
      case "/organizations/assign" -> assign(u, body);
      default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
    };
  }

  // ---------------------------------------------------------------- 查询

  /** 分页查询某一层组织，附带上层名称与下级、学生数量，供组织管理页面直接展示。 */
  private Object list(Models.User u, Map<String, String> query) {
    Models.Level level = level(query.get("level"));
    var rows = organizations.list(u, level);
    String collegeId =
        level == Models.Level.COLLEGE
            ? null
            : organizations.resolveOwn(Models.Level.COLLEGE, first(query, "collegeId", "college"));
    String majorId =
        level == Models.Level.CLASS
            ? organizations.resolveOwn(Models.Level.MAJOR, first(query, "majorId", "major"))
            : null;
    var collegeNames = organizations.names(Models.Level.COLLEGE, valuesOf(rows, "college_id"));
    var majorNames = organizations.names(Models.Level.MAJOR, valuesOf(rows, "major_id"));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      String id = text(row.get("id"));
      if (collegeId != null && !collegeId.equals(text(row.get("college_id")))) continue;
      if (majorId != null && !majorId.equals(text(row.get("major_id")))) continue;
      var copy = new LinkedHashMap<>(row);
      // 数据库列名是 snake_case；这里补 camelCase 别名，让前端各页面用同一套字段名，
      // 不必在不同接口之间切换写法（选课接口同样输出双拼写）。
      copy.put("shortName", row.get("short_name"));
      copy.put("short_name", row.get("short_name"));
      copy.put("gradeYear", row.get("grade_year"));
      copy.put("grade_year", row.get("grade_year"));
      // 组织编号就是主键 id，随行数据原样输出；库表里若残留 code 列也不再对外暴露该键。
      copy.remove("code");
      // 需求：班级不设辅导员，列表一律不再输出该字段（旧数据里可能残留）。
      copy.remove("counselor");
      switch (level) {
        case COLLEGE -> {
          copy.put("majorCount", repo.find("majors", Map.of("college_id", id)).size());
          copy.put("classCount", repo.find("classes", Map.of("college_id", id)).size());
          copy.put("studentCount", studentCount(level, id));
        }
        case MAJOR -> {
          copy.put("collegeName", collegeNames.get(text(row.get("college_id"))));
          copy.put("classCount", repo.find("classes", Map.of("major_id", id)).size());
          copy.put("studentCount", studentCount(level, id));
        }
        case CLASS -> {
          copy.put("collegeName", collegeNames.get(text(row.get("college_id"))));
          copy.put("majorName", majorNames.get(text(row.get("major_id"))));
          copy.put("studentCount", studentCount(level, id));
        }
      }
      items.add(copy);
    }
    return ApiController.page(items, query);
  }

  /**
   * 下拉框数据：任何已登录用户都可读，一次性返回三级组织，前端按名称选择。
   *
   * <p>不按启用状态过滤，停用的组织仍需在历史数据里显示名称。
   */
  private Object options() {
    var colleges = organizations.listAll(Models.Level.COLLEGE);
    var majors = organizations.listAll(Models.Level.MAJOR);
    var classes = organizations.listAll(Models.Level.CLASS);
    Map<String, String> collegeNames = plainNames(colleges);
    Map<String, String> majorNames = plainNames(majors);
    var collegeItems = new ArrayList<Map<String, Object>>();
    for (var row : colleges) {
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("name", row.get("name"));
      item.put("shortName", row.get("short_name"));
      collegeItems.add(item);
    }
    var majorItems = new ArrayList<Map<String, Object>>();
    for (var row : majors) {
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("name", row.get("name"));
      item.put("collegeId", row.get("college_id"));
      item.put("collegeName", collegeNames.get(text(row.get("college_id"))));
      majorItems.add(item);
    }
    var classItems = new ArrayList<Map<String, Object>>();
    for (var row : classes) {
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("name", row.get("name"));
      item.put("collegeId", row.get("college_id"));
      item.put("collegeName", collegeNames.get(text(row.get("college_id"))));
      item.put("majorId", row.get("major_id"));
      item.put("majorName", majorNames.get(text(row.get("major_id"))));
      item.put("gradeYear", row.get("grade_year"));
      // 编号就是上面的 id；班级不设辅导员，options 同样不输出 counselor。
      classItems.add(item);
    }
    return Map.of("colleges", collegeItems, "majors", majorItems, "classes", classItems);
  }

  /** 删除影响评估：返回该组织下的下级、人员与课程数量，供前端提示级联后果。 */
  private Object impact(Models.User u, Map<String, String> query) {
    u.require("ORG_ADMIN");
    Models.Level level = level(query.get("level"));
    return organizations.impact(level, text(query.get("id")));
  }

  /** 某一层组织下的账号：附带组织名称与展示用归属，供组织管理页面查看成员。 */
  private Object members(Models.User u, Map<String, String> query) {
    u.require("ORG_ADMIN");
    Models.Level level = level(query.get("level"));
    String id = text(query.get("id"));
    organizations.requireOrganization(level, id);
    var rows = repo.find("users", Map.of(fieldOf(level), id));
    var collegeNames = organizations.names(Models.Level.COLLEGE, valuesOf(rows, "college_id"));
    var majorNames = organizations.names(Models.Level.MAJOR, valuesOf(rows, "major_id"));
    var classNames = organizations.names(Models.Level.CLASS, valuesOf(rows, "class_id"));
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      var copy = Models.publicUser(row);
      String collegeName = collegeNames.get(text(row.get("college_id")));
      String majorName = majorNames.get(text(row.get("major_id")));
      String className = classNames.get(text(row.get("class_id")));
      copy.put("collegeName", collegeName);
      copy.put("majorName", majorName);
      copy.put("className", className);
      copy.put(
          "department", AdminService.department(row.get("role"), collegeName, majorName, className));
      items.add(copy);
    }
    return ApiController.page(items, query);
  }

  /**
   * 组织管理页面自带的学生名册：只持有 ORG_ADMIN 的管理员也能按班级、专业、学院筛选学生，
   * 不必依赖需要 USER_ADMIN 的 {@code /users}，从而能独立完成「批量调入学生」。
   *
   * <p>只返回启用的学生；{@code className} 与 {@code classNameRaw} 都是班级的规范名称
   * （“2023级-软件工程-2301班”），前端按名称提交时可直接使用。
   */
  private Object students(Models.User u, Map<String, String> query) {
    u.require("ORG_ADMIN");
    String collegeId =
        organizations.resolveOwn(Models.Level.COLLEGE, first(query, "collegeId", "college"));
    String majorId = organizations.resolveOwn(Models.Level.MAJOR, first(query, "majorId", "major"));
    String classId =
        organizations.resolveOwn(Models.Level.CLASS, first(query, "classId", "className", "class"));
    boolean unassigned = Models.flag(query.get("unassigned"), false);
    String search = text(query.get("search"));
    String needle = search == null ? null : search.toLowerCase(Locale.ROOT);
    var rows = new ArrayList<Map<String, Object>>();
    for (var row : repo.find("users", Map.of("role", "STUDENT"))) {
      if (!"STUDENT".equals(text(row.get("role"))) || intOf(row.get("enabled"), 0) != 1) continue;
      String rowClass = text(row.get("class_id"));
      if (collegeId != null && !collegeId.equals(text(row.get("college_id")))) continue;
      if (majorId != null && !majorId.equals(text(row.get("major_id")))) continue;
      if (unassigned) {
        if (rowClass != null) continue;
      } else if (classId != null && !classId.equals(rowClass)) continue;
      if (needle != null && !matches(row, needle)) continue;
      rows.add(row);
    }
    // 分页要稳定：先按班级，再按编号。
    rows.sort(
        Comparator.comparing((Map<String, Object> r) -> Objects.toString(r.get("class_id"), ""))
            .thenComparing(r -> Objects.toString(r.get("id"), "")));
    var collegeNames = organizations.names(Models.Level.COLLEGE, valuesOf(rows, "college_id"));
    var majorNames = organizations.names(Models.Level.MAJOR, valuesOf(rows, "major_id"));
    Map<String, String> classNames = new HashMap<>();
    Map<String, String> classRawNames = new HashMap<>();
    for (var row : organizations.listAll(Models.Level.CLASS)) {
      String id = text(row.get("id"));
      if (id == null) continue;
      classNames.put(id, OrganizationService.displayName(Models.Level.CLASS, row));
      classRawNames.put(id, Objects.toString(row.get("name"), ""));
    }
    var items = new ArrayList<Map<String, Object>>();
    for (var row : rows) {
      String college = text(row.get("college_id"));
      String major = text(row.get("major_id"));
      String klass = text(row.get("class_id"));
      String collegeName = collegeNames.get(college);
      String majorName = majorNames.get(major);
      String classNameRaw = classRawNames.get(klass);
      var item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("username", row.get("username"));
      item.put("name", row.get("name"));
      item.put("role", row.get("role"));
      item.put("enabled", row.get("enabled"));
      item.put("collegeId", row.get("college_id"));
      item.put("collegeName", collegeName);
      item.put("majorId", row.get("major_id"));
      item.put("majorName", majorName);
      item.put("classId", row.get("class_id"));
      item.put("className", classNames.get(klass));
      item.put("classNameRaw", classNameRaw);
      item.put(
          "department",
          AdminService.department(row.get("role"), collegeName, majorName, classNameRaw));
      items.add(item);
    }
    return ApiController.page(items, query);
  }

  // ---------------------------------------------------------------- 维护

  /**
   * 新建或修改组织对象；请求体字段名与库表列名不同，这里做一次映射。
   *
   * <p>组织编号就是主键 {@code id}：新建时由 {@link OrganizationService#save} 生成，
   * 修改时沿用库里原值，前端只读展示、不提供输入，请求体里的 {@code id} 只用于定位被修改的对象。
   */
  private Object save(Models.User u, Map<String, Object> body) {
    u.require("ORG_ADMIN");
    Models.Level level = level(body.get("level"));
    boolean create = text(body.get("id")) == null;
    // 先解析上级：新建编号要用上级序号，且上级必须存在。
    String parentId = level.isRoot() ? null : organizations.resolveParent(level, body);
    String id = organizations.save(u, level, body);
    Map<String, Object> old = create ? null : organizations.requireOrganization(level, id);
    var values = new LinkedHashMap<String, Object>();
    switch (level) {
      case COLLEGE -> {
        values.put("name", Models.text(body, "name", 100));
        values.put("short_name", keep(body, old, "shortName", "short_name", 50));
        values.put("description", keep(body, old, "description", "description", 500));
      }
      case MAJOR -> {
        values.put("college_id", parentId);
        values.put("name", Models.text(body, "name", 100));
        values.put("degree", keep(body, old, "degree", "degree", 50));
        values.put("years", years(body, old));
      }
      case CLASS -> {
        // 班级同时记所属专业与学院，学院取自专业，保证三级归属只有一份真相。
        var major = organizations.requireOrganization(Models.Level.MAJOR, parentId);
        values.put("major_id", parentId);
        values.put("college_id", Objects.toString(major.get("college_id"), ""));
        values.put("name", Models.text(body, "name", 100));
        values.put("grade_year", keep(body, old, "gradeYear", "grade_year", 20));
        // 需求：不设辅导员，classes 表也没有该列，这里不再写入 counselor。
      }
    }
    values.put(
        "enabled",
        Models.flagInt(body.get("enabled"), old == null || intOf(old.get("enabled"), 1) == 1));
    int version = old == null ? 0 : intOf(old.get("version"), 0);
    values.put("version", create ? 0 : version + 1);
    List<Protocol.Operation> ops = new ArrayList<>();
    if (create) {
      values.put("id", id);
      ops.add(insert(level.table, values));
    } else {
      ops.add(update(level.table, id, values, version));
    }
    repo.mutate(ops, u.id(), "ORG_SAVE", id);
    // 编号即主键 id：返回给前端用于提示「已新增 XXX（编号 C01005）」。
    return Map.of("ok", true, "id", id);
  }

  /** 删除组织对象；force 表示确认级联删除下级，仍受课程与人员引用约束。 */
  private Object remove(Models.User u, Map<String, Object> body) {
    u.require("ORG_ADMIN");
    Models.Level level = level(body.get("level"));
    String id = Models.text(body, "id", 100);
    boolean force = Models.flag(body.get("force"), false);
    var ops = organizations.deleteOps(u, level, id, force);
    repo.mutate(ops, u.id(), "ORG_DELETE", id);
    return Map.of("ok", true);
  }

  /**
   * 批量调整人员归属：学生只能进入班级，教师只能进入专业。
   *
   * <p>两种指定方式：{@code studentIds}/{@code teacherIds} 显式列出账号，或用 {@code classId}
   * 把某个班级的全部学生整体迁移。所有写入合并为一次事务并进入审计账本。
   */
  private Object assign(Models.User u, Map<String, Object> body) {
    u.require("ORG_ADMIN");
    Models.Level level = level(body.get("level"));
    String targetId = text(body.get("id"));
    Map<String, Object> target = organizations.requireOrganization(level, targetId);
    var studentIds = idList(body.get("studentIds"));
    var teacherIds = idList(body.get("teacherIds"));
    String classId = text(first(body, "classId", "class"));
    ApiException.require(
        !studentIds.isEmpty() || !teacherIds.isEmpty() || classId != null,
        400,
        "请指定要调整的学生或教师");
    ApiException.require(
        (studentIds.isEmpty() && classId == null) || level == Models.Level.CLASS,
        400,
        "学生只能调整到班级");
    ApiException.require(
        teacherIds.isEmpty() || level == Models.Level.MAJOR, 400, "教师只能调整到专业");
    var students = new ArrayList<Map<String, Object>>();
    for (String studentId : studentIds) students.add(requireRole(studentId, "STUDENT", "学生"));
    if (classId != null) {
      organizations.requireOrganization(Models.Level.CLASS, classId);
      students.addAll(repo.find("users", Map.of("class_id", classId, "role", "STUDENT")));
    }
    var teachers = new ArrayList<Map<String, Object>>();
    for (String teacherId : teacherIds) teachers.add(requireRole(teacherId, "TEACHER", "教师"));
    ApiException.require(
        students.size() + teachers.size() <= Models.MAX_BATCH_STUDENTS,
        400,
        "单次调整的账号数量过多");
    List<Protocol.Operation> ops = new ArrayList<>();
    if (level == Models.Level.CLASS) {
      String collegeId = text(target.get("college_id"));
      String majorId = text(target.get("major_id"));
      String department =
          AdminService.department(
              "STUDENT",
              nameOf(Models.Level.COLLEGE, collegeId),
              nameOf(Models.Level.MAJOR, majorId),
              OrganizationService.displayName(Models.Level.CLASS, target));
      for (var student : students)
        ops.add(
            update(
                "users",
                text(student.get("id")),
                placement(student, collegeId, majorId, targetId, department),
                intOf(student.get("version"), 0)));
    } else {
      String collegeId = text(target.get("college_id"));
      String department =
          AdminService.department(
              "TEACHER", nameOf(Models.Level.COLLEGE, collegeId), text(target.get("name")), null);
      for (var teacher : teachers)
        ops.add(
            update(
                "users",
                text(teacher.get("id")),
                placement(teacher, collegeId, targetId, null, department),
                intOf(teacher.get("version"), 0)));
    }
    if (!ops.isEmpty()) repo.mutate(ops, u.id(), "ORG_ASSIGN", targetId);
    return Map.of("ok", true, "moved", ops.size());
  }

  /** 人员归属字段：三级引用与展示用描述一起更新，version 递增。 */
  private static Map<String, Object> placement(
      Map<String, Object> row,
      String collegeId,
      String majorId,
      String classId,
      String department) {
    var values = new LinkedHashMap<String, Object>();
    values.put("college_id", collegeId == null ? "" : collegeId);
    values.put("major_id", majorId == null ? "" : majorId);
    if (classId != null) values.put("class_id", classId);
    values.put("department", department);
    values.put("version", intOf(row.get("version"), 0) + 1);
    return values;
  }

  /**
   * 校验账号角色；账号不存在时由仓库层返回 404。
   *
   * <p>管理员（{@code ADMIN}）不属于任何组织，无论请求把它列在学生还是教师里都直接拒绝。
   */
  private Map<String, Object> requireRole(String id, String role, String label) {
    var row = repo.one("users", id);
    ApiException.require(
        !"ADMIN".equals(text(row.get("role"))), 400, "管理员不归属学院/专业/班级");
    ApiException.require(role.equals(text(row.get("role"))), 400, "只能调整" + label + "账号");
    return row;
  }

  // ---------------------------------------------------------------- 工具

  private String nameOf(Models.Level level, String id) {
    if (id == null || id.isBlank()) return null;
    return organizations.names(level, List.of(id)).get(id);
  }

  /** 该组织下的学生数量；教师不计入，与页面上的“学生数”一致。 */
  private int studentCount(Models.Level level, String id) {
    if (id == null) return 0;
    return repo.find("users", Map.of(fieldOf(level), id, "role", "STUDENT")).size();
  }

  /** 用户表里对应层级的归属字段。 */
  private static String fieldOf(Models.Level level) {
    return switch (level) {
      case COLLEGE -> "college_id";
      case MAJOR -> "major_id";
      case CLASS -> "class_id";
    };
  }

  /** 层级参数：只接受 COLLEGE/MAJOR/CLASS。 */
  private static Models.Level level(Object raw) {
    ApiException.require(raw != null && !raw.toString().isBlank(), 400, "缺少组织层级 level");
    try {
      return Models.Level.valueOf(raw.toString().strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ApiException(400, "REQUEST_REJECTED", "组织层级无效：" + raw);
    }
  }

  /** 取第一个非空字段：前端可能用编号键，也可能用名称键。 */
  private static Object first(Map<String, ?> source, String... keys) {
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null && !value.toString().isBlank()) return value;
    }
    return null;
  }

  /**
   * 可空字段的落库值：未提供时保留旧值，新建时写空串。
   *
   * <p>库层拒绝 null 字段值，因此这里始终给出字符串。
   */
  private static Object keep(
      Map<String, Object> body, Map<String, Object> old, String key, String column, int max) {
    String value = Models.optionalText(body, key, max);
    if (value != null) return value;
    return old == null ? "" : Objects.toString(old.get(column), "");
  }

  /** 学制年限：未提供时保留旧值，新建默认 4 年。 */
  private static int years(Map<String, Object> body, Map<String, Object> old) {
    Object raw = body.get("years");
    int years =
        raw == null || raw.toString().isBlank()
            ? intOf(old == null ? null : old.get("years"), 4)
            : Models.integer(raw);
    ApiException.require(years >= 1 && years <= 10, 400, "学制年限无效");
    return years;
  }

  private static int intOf(Object value, int fallback) {
    if (value == null || value.toString().isBlank()) return fallback;
    try {
      return Integer.parseInt(value.toString().strip());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static List<String> idList(Object raw) {
    var result = new ArrayList<String>();
    if (raw == null) return result;
    if (raw instanceof Collection<?> collection) {
      for (Object value : collection)
        if (value != null && !value.toString().isBlank()) result.add(value.toString().strip());
    } else {
      result.addAll(OrganizationService.split(raw));
    }
    return result;
  }

  private static List<String> valuesOf(List<Map<String, Object>> rows, String field) {
    var list = new ArrayList<String>();
    for (var row : rows) list.add(text(row.get(field)));
    return list;
  }

  /** 姓名或账号的模糊匹配，大小写不敏感。 */
  private static boolean matches(Map<String, Object> row, String needle) {
    return Objects.toString(row.get("name"), "").toLowerCase(Locale.ROOT).contains(needle)
        || Objects.toString(row.get("username"), "").toLowerCase(Locale.ROOT).contains(needle);
  }

  private static Map<String, String> plainNames(List<Map<String, Object>> rows) {
    Map<String, String> result = new HashMap<>();
    for (var row : rows) {
      String id = text(row.get("id"));
      if (id != null) result.put(id, text(row.get("name")));
    }
    return result;
  }

  private static String text(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString();
  }
}
