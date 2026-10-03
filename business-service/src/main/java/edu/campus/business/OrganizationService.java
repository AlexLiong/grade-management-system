package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 学院—专业—班级三级组织。
 *
 * <p>组织对象在库里以独立编号（业务主键）互相引用，前端一律按名称操作：请求体里出现的
 * 组织字段既接受编号也接受名称，{@link #resolveOwn} 统一归一化成编号；返回给前端的数据
 * 通过 {@link #enrichOwn}/{@link #names} 附加可读名称。这样既满足“编号作主键”，也满足
 * “前端以名称操作”。
 *
 * <p>编号规则为「层前缀 + 2 位号段 + 3 位本级序号」：学院用自己在学院序列中的号段（首位学院
 * {@code C01001}、第二所 {@code C02001}），专业与班级沿用所属上级编号的第 2–3 位号段
 * （如 {@code M01002}、{@code B01003}）。同级序号自动递增，删除后不复用。
 *
 * <p>这个编号同时就是组织的业务主键 {@code id}，也是界面上展示的唯一编号：新建时由
 * {@link #nextId} 自动生成，一经生成不可修改，前端只读展示、不提供编号输入。
 */
@Service
public class OrganizationService {
  private final RemoteRepository repo;

  public OrganizationService(RemoteRepository repo) {
    this.repo = repo;
  }

  // ---------------------------------------------------------------- 查询

  public List<Map<String, Object>> list(Models.User u, Models.Level level) {
    u.require("ORG_ADMIN");
    return listAll(level);
  }

  /** 无鉴权查询，供其他服务内部渲染名称使用。 */
  public List<Map<String, Object>> listAll(Models.Level level) {
    var rows = new ArrayList<>(repo.find(level.table, Map.of()));
    rows.sort(Comparator.comparing(r -> Objects.toString(r.get("id"), "")));
    return rows;
  }

  public Map<String, Object> requireOrganization(Models.Level level, String id) {
    ApiException.require(id != null && !id.isBlank(), 400, "缺少" + level.label + "编号");
    var row = repo.findOne(level.table, Map.of("id", id));
    ApiException.require(row != null, 400, "指定的" + level.label + "不存在");
    return row;
  }

  /**
   * 把请求体中的组织字段归一化成编号。
   *
   * <p>接受三种写法：组织编号、组织名称、以及 {@code id} 字段直接给的编号；名称为空时
   * 返回 null（表示不设置该层级）。
   */
  public String resolveOwn(Models.Level level, Object raw) {
    if (raw == null) return null;
    String value = raw.toString().strip();
    if (value.isEmpty()) return null;
    var byId = repo.findOne(level.table, Map.of("id", value));
    if (byId != null) return value;
    var byName = repo.findOne(level.table, Map.of("name", value));
    ApiException.require(byName != null, 400, "指定的" + level.label + "不存在：" + value);
    return byName.get("id").toString();
  }

  /** 批量查名称表：id -> 显示名称。 */
  public Map<String, String> names(Models.Level level, Collection<String> ids) {
    var wanted = new HashSet<String>();
    for (String id : ids) if (id != null && !id.isBlank()) wanted.add(id);
    Map<String, String> result = new HashMap<>();
    if (wanted.isEmpty()) return result;
    for (var row : repo.find(level.table, Map.of()))
      if (wanted.contains(Objects.toString(row.get("id"), "")))
        result.put(row.get("id").toString(), displayName(level, row));
    return result;
  }

  /**
   * 组织显示名：班级按需求采用「XXXX级-XX专业-XX班」的形式，名称本身就已经是完整形式，
   * 因此直接返回 {@code name}；学院与专业同样返回各自的名称。
   *
   * <p>库里仍分别保存 {@code grade_year} 与专业归属，便于按年级/专业统计，不重复存真相。
   */
  public static String displayName(Models.Level level, Map<String, Object> row) {
    return Objects.toString(row.get("name"), "");
  }

  /** 班级的规范名称：{@code 年级 + "级-" + 专业名 + "-" + 班号}，如「2023级-软件工程-2301班」。 */
  public static String className(String gradeYear, String majorName, String number) {
    return gradeYear + "级-" + majorName + "-" + number;
  }

  /**
   * 给一行数据补上组织名称。
   *
   * @param mapping 字段名 -> 组织层级，例如 {@code college_id -> COLLEGE}
   */
  public Map<String, Object> enrichOwn(
      Map<String, Object> row, Map<String, Models.Level> mapping) {
    var copy = new LinkedHashMap<>(row);
    for (var entry : mapping.entrySet()) {
      Object id = row.get(entry.getKey());
      if (id == null || id.toString().isBlank()) {
        copy.put(nameField(entry.getKey()), null);
        continue;
      }
      var org = repo.findOne(entry.getValue().table, Map.of("id", id.toString()));
      copy.put(
          nameField(entry.getKey()),
          org == null ? null : displayName(entry.getValue(), org));
    }
    return copy;
  }

  /** {@code college_id -> collegeName}、{@code class_id -> className}。 */
  public static String nameField(String idField) {
    String base = idField.endsWith("_id") ? idField.substring(0, idField.length() - 3) : idField;
    int underscore = base.lastIndexOf('_');
    String camel =
        underscore < 0
            ? base
            : base.substring(0, underscore)
                + Character.toUpperCase(base.charAt(underscore + 1))
                + base.substring(underscore + 2);
    return camel + "Name";
  }

  // ---------------------------------------------------------------- 维护

  /**
   * 新建或修改组织对象。敏感操作由调用方以统一事务写入，因此这里只做校验与字段整理。
   *
   * <p>显示编号就是主键 {@code id}：新建时由这里通过 {@link #nextId} 生成，修改时沿用库中原值，
   * 请求体里的 {@code id} 只用于定位被修改的对象，不会成为新的编号。
   *
   * @param values 归一化后的待写字段
   * @return 主键编号
   */
  public String save(Models.User u, Models.Level level, Map<String, Object> b) {
    u.require("ORG_ADMIN");
    boolean create = b.get("id") == null || b.get("id").toString().isBlank();
    String name = Models.text(b, "name", 100);
    var parentId = level.isRoot() ? null : resolveParent(level, b);
    if (level == Models.Level.CLASS) checkClassConsistency(b);
    var duplicate = repo.findOne(level.table, Map.of("name", name));
    ApiException.require(
        duplicate == null
            || (!create && duplicate.get("id").toString().equals(b.get("id").toString())),
        400,
        "同名" + level.label + "已存在：" + name);
    if (create) return nextId(level, parentId);
    requireOrganization(level, b.get("id").toString());
    return b.get("id").toString();
  }

  /**
   * 班级必须与所选学院、专业三者一致：前端同时给出学院与专业时，专业必须属于该学院，
   * 否则会出现「班级算在 A 学院、专业却属于 B 学院」的脏数据。
   *
   * <p>只校验请求体里真正给出的层级；某一层未给出时由 {@link #resolveParent} 决定归属。
   */
  private void checkClassConsistency(Map<String, Object> b) {
    String majorId = resolveParent(Models.Level.CLASS, b);
    String collegeId = resolveOwn(Models.Level.COLLEGE, b.get("collegeId") != null ? b.get("collegeId") : b.get("college"));
    if (collegeId == null) return;
    var major = requireOrganization(Models.Level.MAJOR, majorId);
    ApiException.require(
        collegeId.equals(major.get("college_id")),
        400,
        "所选专业「"
            + major.get("name")
            + "」不属于该学院，请检查学院与专业的对应关系");
  }

  /** 上级编号：学院没有上级，专业必须有学院，班级必须有专业。 */
  public String resolveParent(Models.Level level, Map<String, Object> b) {
    if (level.isRoot()) return null;
    var parent = level.parent();
    String base = parent.name().toLowerCase();
    Object raw = b.get(base + "Id");
    if (raw == null || raw.toString().isBlank()) raw = b.get(base);
    ApiException.require(
        raw != null && !raw.toString().isBlank(), 400, "必须指定所属" + parent.label);
    return resolveOwn(parent, raw);
  }

  /**
   * 生成同级递增编号：{@code 前缀 + 2 位上级序号 + 3 位本级序号}，例如 {@code C01001}、{@code M01002}。
   *
   * <p>这个编号既是主键 {@code id}，也是界面上展示的组织编号（{@code C01001}/{@code M01001}/
   * {@code B01001}…），前端只读展示、不提供编号输入。
   *
   * <p>第 2–3 位是「上级对象自己已占用的序号」：
   *
   * <ul>
   *   <li>学院：取现有学院第 2–3 位的最大数值 + 1，本级序号从 001 开始。这样新建的学院不会
   *       与任何已有学院撞号，也不会占用已被下级号段使用的两位序号；
   *   <li>专业 / 班级：直接沿用上级编号的第 2–3 位，本级序号在同一个号段内递增。
   * </ul>
   *
   * <p>编号一经生成即作为主键使用，删除后不复用。
   */
  public String nextId(Models.Level level, String parentId) {
    if (level.isRoot()) {
      int maxSeq = 0;
      for (var row : repo.find(level.table, Map.of())) {
        String seq = sequenceOf(Objects.toString(row.get("id"), ""));
        if (seq != null) maxSeq = Math.max(maxSeq, Integer.parseInt(seq));
      }
      ApiException.require(maxSeq < 99, 409, level.label + "编号已用尽");
      return level.prefix + String.format("%02d", maxSeq + 1) + "001";
    }
    String parentSeq = parentId == null ? null : sequenceOf(parentId);
    ApiException.require(
        parentSeq != null, 400, "缺少所属" + level.parent().label + "，无法生成编号");
    int max = 0;
    for (var row : repo.find(level.table, Map.of())) {
      String id = Objects.toString(row.get("id"), "");
      if (!parentSeq.equals(sequenceOf(id))) continue;
      max = Math.max(max, itemOf(id));
    }
    ApiException.require(max < 999, 409, level.label + "编号已用尽");
    return level.prefix + parentSeq + String.format("%03d", max + 1);
  }

  /** 取编号的第 2–3 位序号；不符合「字母 + 5 位数字」时返回 null。 */
  private static String sequenceOf(String id) {
    if (id == null || id.length() != 6) return null;
    String digits = id.substring(1);
    for (int i = 0; i < digits.length(); i++) if (!Character.isDigit(digits.charAt(i))) return null;
    return id.substring(1, 3);
  }

  /** 取编号末 3 位本级序号，非数字时返回 0。 */
  private static int itemOf(String id) {
    try {
      return Integer.parseInt(id.substring(3));
    } catch (RuntimeException e) {
      return 0;
    }
  }

  // ---------------------------------------------------------------- 删除

  /** 删除前统计下级与关联人员，供前端提示；不修改数据。 */
  public Map<String, Object> impact(Models.Level level, String id) {
    requireOrganization(level, id);
    var details = new LinkedHashMap<String, Object>();
    int total = 0;
    if (level == Models.Level.COLLEGE) {
      var majors = repo.find("majors", Map.of("college_id", id));
      var classes = repo.find("classes", Map.of("college_id", id));
      details.put("majors", majors.size());
      details.put("classes", classes.size());
      total += majors.size() + classes.size();
    } else if (level == Models.Level.MAJOR) {
      var classes = repo.find("classes", Map.of("major_id", id));
      details.put("classes", classes.size());
      total += classes.size();
    } else {
      var students = repo.find("users", Map.of("class_id", id));
      details.put("students", students.size());
      total += students.size();
    }
    details.put("courses", affiliatedCourses(level, id).size());
    details.put("total", total);
    return details;
  }

  /**
   * 与组织对象关联的课程：学院用开设院系，班级除开设院系外还包含指定给该班的教学班。
   * 仅统计仍在校验范围内的记录，用于阻止“删掉还有课程的学院”。
   */
  private List<Map<String, Object>> affiliatedCourses(Models.Level level, String id) {
    if (level == Models.Level.COLLEGE) return repo.find("courses", Map.of("college_id", id));
    if (level == Models.Level.CLASS) {
      var byClass = new ArrayList<>(repo.find("courses", Map.of("class_id", id)));
      return byClass;
    }
    var collegeIds =
        repo.find("colleges", Map.of()).stream()
            .map(c -> c.get("id").toString())
            .filter(cid -> repo.find("majors", Map.of("college_id", cid)).stream()
                .anyMatch(m -> m.get("id").equals(id)))
            .toList();
    var result = new ArrayList<Map<String, Object>>();
    for (String collegeId : collegeIds) result.addAll(repo.find("courses", Map.of("college_id", collegeId)));
    return result;
  }

  /**
   * 删除组织对象。
   *
   * @param force true 表示连同下级一起删除；下级仍有人员或课程时始终拒绝，避免产生悬挂引用
   */
  public List<Protocol.Operation> deleteOps(Models.User u, Models.Level level, String id, boolean force) {
    u.require("ORG_ADMIN");
    requireOrganization(level, id);
    var ops = new ArrayList<Protocol.Operation>();
    var children = childLevel(level) == null ? List.<Map<String, Object>>of() : childrenOf(level, id);
    var courses = affiliatedCourses(level, id);
    ApiException.require(
        courses.isEmpty(), 409, "该" + level.label + "下仍有课程，不能删除");
    if (!children.isEmpty()) {
      ApiException.require(force, 409, "该" + level.label + "下仍有" + children.size() + "个下级，需确认级联删除");
      for (var child : children) doDelete(u, ops, childLevel(level), child.get("id").toString());
    }
    checkPeople(level, id);
    ops.add(delete(level.table, id, null));
    return ops;
  }

  private void doDelete(
      Models.User u, List<Protocol.Operation> ops, Models.Level level, String id) {
    var courses = affiliatedCourses(level, id);
    ApiException.require(courses.isEmpty(), 409, "下级" + level.label + "仍有课程，不能删除");
    var children = childLevel(level) == null ? List.<Map<String, Object>>of() : childrenOf(level, id);
    for (var child : children) doDelete(u, ops, childLevel(level), child.get("id").toString());
    checkPeople(level, id);
    ops.add(delete(level.table, id, null));
  }

  private static Models.Level childLevel(Models.Level level) {
    return switch (level) {
      case COLLEGE -> Models.Level.MAJOR;
      case MAJOR -> Models.Level.CLASS;
      case CLASS -> null;
    };
  }

  private List<Map<String, Object>> childrenOf(Models.Level level, String id) {
    return switch (level) {
      case COLLEGE -> {
        var all = new ArrayList<Map<String, Object>>();
        all.addAll(repo.find("majors", Map.of("college_id", id)));
        all.addAll(repo.find("classes", Map.of("college_id", id)));
        yield all;
      }
      case MAJOR -> repo.find("classes", Map.of("major_id", id));
      case CLASS -> List.of();
    };
  }

  /** 组织对象仍被人员引用时不允许删除，保持成绩与审计引用稳定。 */
  private void checkPeople(Models.Level level, String id) {
    String field =
        switch (level) {
          case COLLEGE -> "college_id";
          case MAJOR -> "major_id";
          case CLASS -> "class_id";
        };
    ApiException.require(
        repo.find("users", Map.of(field, id)).isEmpty(),
        409,
        "该" + level.label + "下仍有账号，请先调整人员的组织归属");
  }

  /** 选课范围匹配：给定学生是否落在范围集合内；范围为空表示不限。 */
  public static boolean inScope(
      Map<String, Object> publish, Map<String, Object> student) {
    if (!matchesScope(publish.get("scope_college_ids"), student.get("college_id"))) return false;
    if (!matchesScope(publish.get("scope_major_ids"), student.get("major_id"))) return false;
    if (!matchesScope(publish.get("scope_class_ids"), student.get("class_id"))) return false;
    return true;
  }

  private static boolean matchesScope(Object scope, Object value) {
    var ids = split(scope);
    if (ids.isEmpty()) return true;
    return value != null && ids.contains(value.toString());
  }

  /** 逗号分隔集合的解析，空值得到空列表。 */
  public static List<String> split(Object raw) {
    if (raw == null) return List.of();
    var result = new ArrayList<String>();
    for (String part : raw.toString().split(","))
      if (!part.isBlank()) result.add(part.strip());
    return result;
  }
}
