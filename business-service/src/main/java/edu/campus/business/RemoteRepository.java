package edu.campus.business;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class RemoteRepository {
  private final RpcClient rpc = new RpcClient("business");
  public static final Map<String, List<String>> FIELDS =
      Map.of(
          "users",
          List.of(
              "id",
              "username",
              "password",
              "name",
              "role",
              "permissions",
              "department",
              "enabled",
              "version"),
          "courses",
          List.of("id", "code", "name", "term", "teacher_id", "credits", "weights", "version"),
          "grades",
          List.of("id", "course_id", "student_id", "payload", "state", "version"),
          "enrollments",
          List.of("id", "course_id", "student_id"),
          "analyses",
          List.of("id", "course_id", "content", "version"),
          "sessions",
          List.of("id", "user_id", "csrf", "expires"),
          "login_limits",
          List.of("id", "failures", "locked_until"));

  public List<Map<String, Object>> find(String table, Map<String, Object> where) {
    List<String> fields = FIELDS.get(table);
    List<Map<String, Object>> result = new ArrayList<>();
    int offset = 0;
    while (true) {
      var rows =
          rpc.post(
              "data",
              "/internal/select",
              new Protocol.Selection(table, fields, where, "id", offset, 500),
              String[][].class);
      for (String[] row : rows) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < fields.size(); i++) m.put(fields.get(i), row[i]);
        result.add(m);
      }
      if (rows.length < 500) break;
      offset += 500;
      ApiException.require(offset < 100000, 413, "查询结果过大，请缩小范围");
    }
    return result;
  }

  public Map<String, Object> one(String table, String id) {
    var rows = find(table, Map.of("id", id));
    ApiException.require(!rows.isEmpty(), 404, "记录不存在");
    return rows.get(0);
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
