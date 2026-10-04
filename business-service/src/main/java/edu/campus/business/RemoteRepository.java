package edu.campus.business;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RemoteRepository {
  private final RpcClient rpc = new RpcClient("business");
  public static final Map<String, List<String>> FIELDS =
      Map.ofEntries(
          Map.entry(
              "users",
              List.of(
                  "id",
                  "username",
                  "password",
                  "name",
                  "role",
                  "permissions",
                  "department",
                  "college_id",
                  "major_id",
                  "class_id",
                  "enabled",
                  "version")),
          Map.entry(
              "courses",
              List.of(
                  "id",
                  "code",
                  "name",
                  "term",
                  "teacher_id",
                  "credits",
                  "weights",
                  "college_id",
                  "class_id",
                  "status",
                  "version")),
          Map.entry("grades", List.of("id", "course_id", "student_id", "payload", "state", "version")),
          Map.entry(
              "enrollments",
              List.of(
                  "id", "course_id", "student_id", "source", "publish_id", "selected_at", "status")),
          Map.entry("analyses", List.of("id", "course_id", "content", "version")),
          Map.entry("sessions", List.of("id", "user_id", "csrf", "expires")),
          Map.entry("login_limits", List.of("id", "failures", "locked_until")),
          Map.entry(
              "colleges", List.of("id", "name", "code", "short_name", "description", "enabled", "version")),
          Map.entry(
              "majors",
              List.of("id", "college_id", "name", "code", "degree", "years", "enabled", "version")),
          Map.entry(
              "classes",
              List.of(
                  "id",
                  "major_id",
                  "college_id",
                  "name",
                  "grade_year",
                  "code",
                  "enabled",
                  "version")),
          Map.entry(
              "course_selections",
              List.of(
                  "id",
                  "name",
                  "term",
                  "course_ids",
                  "scope_college_ids",
                  "scope_major_ids",
                  "scope_class_ids",
                  "start_time",
                  "end_time",
                  "min_enroll",
                  "max_credits",
                  "allow_add",
                  "allow_drop",
                  "allow_retake",
                  "status",
                  "published_by",
                  "published_at",
                  "note",
                  "version")),
          Map.entry(
              "enrollment_records",
              List.of(
                  "id",
                  "publish_id",
                  "course_id",
                  "code",
                  "student_id",
                  "term",
                  "action",
                  "reason",
                  "operator",
                  "created_at")));

  /**
   * 是否打印单次查询耗时。用 {@code -Dcampus.trace.repo=true} 打开：一次页面加载往往包含十几次
   * {@code repo.find}，逐个猜哪个慢很低效，打开后按行看耗时最直接。
   */
  private static final boolean TRACE = Boolean.getBoolean("campus.trace.repo");

  /**
   * 单次查询的行数上限。
   *
   * <p>分页是为了不让一次 RPC 拉回整张表，但页太小会成倍放大开销：data-service 用
   * {@code setMaxRows(offset + limit)} 实现游标分页，**每一页都要从头重扫**，而成绩行还带
   * 加密 payload（要逐行 AES 解密）。实测 1445 条成绩按 500/页要 3 页 ≈ 500 ms，按 2000/页
   * 只需 1 页。这里取 2000：既让常见表一次读完，又仍然给超大数据集留了分页兜底。
   */
  private static final int PAGE = 2000;

  public List<Map<String, Object>> find(String table, Map<String, Object> where) {
    long started = TRACE ? System.nanoTime() : 0;
    try {
      return findPage(table, where);
    } finally {
      if (TRACE) {
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("[repo] " + table + " " + where + " -> " + ms + " ms");
      }
    }
  }

  private List<Map<String, Object>> findPage(String table, Map<String, Object> where) {
    List<String> fields = FIELDS.get(table);
    List<Map<String, Object>> result = new ArrayList<>();
    int offset = 0;
    while (true) {
      var rows =
          rpc.post(
              "data",
              "/internal/select",
              new Protocol.Selection(table, fields, where, "id", offset, PAGE),
              String[][].class);
      for (String[] row : rows) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.size(); i++) m.put(fields.get(i), row[i]);
        result.add(m);
      }
      if (rows.length < PAGE) break;
      offset += PAGE;
      ApiException.require(offset < 100000, 413, "查询结果过大，请缩小范围");
    }
    return result;
  }

  public Map<String, Object> one(String table, String id) {
    var rows = find(table, Map.of("id", id));
    ApiException.require(!rows.isEmpty(), 404, "记录不存在");
    return rows.get(0);
  }

  /** 可空查询：不存在时返回 null，用于“先查后建”的业务流程。 */
  public Map<String, Object> findOne(String table, Map<String, Object> where) {
    var rows = find(table, where);
    return rows.isEmpty() ? null : rows.get(0);
  }

  public void mutate(List<Protocol.Operation> ops, String actor, String action, String resource) {
    rpc.post(
        "data",
        "/internal/manipulate",
        new Protocol.Mutation(ops, actor, action, resource, UUID.randomUUID().toString()),
        Boolean.class);
  }

  public static Protocol.Operation insert(String table, Map<String, Object> data) {
    return new Protocol.Operation("INSERT", table, data, Map.of(), 1);
  }

  public static Protocol.Operation update(
      String table, String id, Map<String, Object> values, Object version) {
    return new Protocol.Operation(
        "UPDATE",
        table,
        values,
        version == null ? Map.of("id", id) : Map.of("id", id, "version", version),
        1);
  }

  public static Protocol.Operation delete(String table, String id, Object version) {
    return new Protocol.Operation(
        "DELETE",
        table,
        Map.of(),
        version == null ? Map.of("id", id) : Map.of("id", id, "version", version),
        1);
  }

  public Map<String, Object> status() {
    return rpc.post("data", "/internal/status", Map.of(), Map.class);
  }

  public Map<String, Object> ledger() {
    return rpc.post("audit", "/internal/ledger", Map.of(), Map.class);
  }

  public Map<String, Object> logModel() {
    return rpc.post("audit", "/internal/classify", Map.of(), Map.class);
  }

  public void securityEvent(String actor, String action, String resource) {
    rpc.post(
        "audit",
        "/internal/append",
        new Protocol.AuditEvent(
            UUID.randomUUID().toString(),
            actor,
            action,
            resource,
            java.time.Instant.now().toString(),
            List.of()),
        Map.class);
  }
}
