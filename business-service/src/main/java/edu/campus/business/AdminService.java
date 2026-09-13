package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AdminService {
  private final RemoteRepository repo;

  public AdminService(RemoteRepository repo) {
    this.repo = repo;
  }

  public List<Map<String, Object>> users(Models.User u) {
    u.require("USER_ADMIN");
    return repo.find("users", Map.of()).stream().map(Models::publicUser).toList();
  }

  @SuppressWarnings("unchecked")
  public void saveUser(Models.User u, Map<String, Object> b) {
    u.require("USER_ADMIN");
    boolean create = b.get("id") == null;
    String id = create ? UUID.randomUUID().toString() : Models.text(b, "id", 100),
        role = Models.text(b, "role", 20);
    ApiException.require(Models.PERMISSIONS.containsKey(role), 400, "角色无效");
    var perms = new TreeSet<>((List<String>) b.get("permissions"));
    ApiException.require(Models.PERMISSIONS.get(role).containsAll(perms), 400, "权限不属于该角色");
    var username = Models.text(b, "username", 60);
    ApiException.require(username.matches("[A-Za-z0-9_.-]{3,60}"), 400, "账号只支持字母、数字、点、下划线、连字符");
    int enabled = Models.integer(b.get("enabled"));
    ApiException.require(enabled == 0 || enabled == 1, 400, "账号状态错误");
    var v =
        new LinkedHashMap<String, Object>(
            Map.of(
                "username",
                username,
                "name",
                Models.text(b, "name", 80),
                "role",
                role,
                "permissions",
                String.join(",", perms),
                "department",
                Models.text(b, "department", 100),
                "enabled",
                enabled));
    List<Protocol.Operation> ops = new ArrayList<>();
    if (create) {
      String password = Models.text(b, "password", 128);
      demoPassword(username).ifPresentOrElse(AuthService::demoValidatePassword, () -> AuthService.validatePassword(password));
      v.put("id", id);
      v.put("password", AuthService.PASSWORDS.encode(password));
      v.put("version", 0);
      ops.add(insert("users", v));
    } else {
      var old = repo.one("users", id);
      ApiException.require(old.get("role").equals(role), 400, "已有账号不可变更角色，请新建对应角色账号");
      if (id.equals(u.id()))
        ApiException.require(
            enabled == 1 && perms.contains("USER_ADMIN"), 400, "不能停用自己或撤销自己的人员管理权限");
      int version = Models.integer(b.get("version"));
      v.put("version", version + 1);
      if (b.get("password") != null && !b.get("password").toString().isBlank()) {
        String password = Models.text(b, "password", 128);
        demoPassword(username).ifPresentOrElse(AuthService::demoValidatePassword, () -> AuthService.validatePassword(password));
        v.put("password", AuthService.PASSWORDS.encode(password));
      }
      ops.add(update("users", id, v, version));
      repo.find("sessions", Map.of("user_id", id))
          .forEach(s -> ops.add(delete("sessions", s.get("id").toString(), null)));
    }
    repo.mutate(ops, u.id(), "USER_SAVE", id);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> integrity(Models.User u) {
    u.require("AUDIT");
    var ledger = repo.ledger();
    Map<String, Map<String, Object>> originals = new LinkedHashMap<>();
    for (var event : (List<Map<String, Object>>) ledger.get("events"))
      for (var change : (List<Map<String, Object>>) event.get("changes"))
        if (change.get("table").equals("grades"))
          originals.put(change.get("id").toString(), (Map<String, Object>) change.get("after"));
    List<Map<String, Object>> issues = new ArrayList<>();
    int checked = 0;
    for (var entry : originals.entrySet()) {
      try {
        var current = repo.find("grades", Map.of("id", entry.getKey()));
        var expected = entry.getValue();
        if (expected.isEmpty()) {
          if (!current.isEmpty())
            issues.add(
                Map.of(
                    "id",
                    entry.getKey(),
                    "issue",
                    "DELETED_RECORD_REAPPEARED",
                    "original",
                    expected));
        } else if (current.isEmpty() || !current.get(0).equals(expected))
          issues.add(
              Map.of(
                  "id",
                  entry.getKey(),
                  "issue",
                  current.isEmpty() ? "MISSING" : "MISMATCH",
                  "original",
                  Models.grade(expected)));
      } catch (ApiException e) {
        if (e.status != 409) throw e;
        issues.add(
            Map.of(
                "id",
                entry.getKey(),
                "issue",
                "CIPHERTEXT_TAMPERED",
                "original",
                entry.getValue().isEmpty() ? Map.of() : Models.grade(entry.getValue())));
      }
      checked++;
    }
    // Query identifiers without decrypting payloads to detect rows fabricated directly in the
    // database.
    var rpc = new RpcClient("business");
    var ids =
        rpc.post(
            "data",
            "/internal/select",
            new Protocol.Selection("grades", List.of("id"), Map.of(), "id", 0, 10000),
            String[][].class);
    for (var row : ids)
      if (!originals.containsKey(row[0]))
        issues.add(Map.of("id", row[0], "issue", "UNAUDITED_ROW", "original", Map.of()));
    return Map.of(
        "verified",
        issues.isEmpty(),
        "checked",
        checked,
        "issues",
        issues,
        "head",
        ledger.get("head"),
        "outbox",
        repo.status());
  }

  public void review(Models.User u, Map<String, Object> b) {
    u.require("AUDIT");
    repo.securityEvent(
        u.id(),
        "ADMIN_REVIEW",
        Models.text(b, "resource", 200) + ":" + Models.text(b, "comment", 1000));
  }

  private static final Set<String> DEMO_USERNAMES =
      Set.of("admin", "t1101", "t1102",
          "20231530", "20231531", "20231532", "20231533", "20231534",
          "20231535", "20231536", "20231537", "20231538", "20231539",
          "20231540", "20231541");

  static Optional<String> demoPassword(String username) {
    return DEMO_USERNAMES.contains(username) ? Optional.of(username) : Optional.empty();
  }
}
