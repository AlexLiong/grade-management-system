package edu.campus.data;

import edu.campus.common.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransactionService {
  private final JdbcTemplate jdbc;
  private final SqlCompiler compiler;
  private final SchemaCatalog catalog;
  private final TransactionTemplate tx;
  private final RpcClient audit = new RpcClient("data");

  public TransactionService(
      JdbcTemplate jdbc,
      SqlCompiler compiler,
      SchemaCatalog catalog,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.compiler = compiler;
    this.catalog = catalog;
    this.tx = new TransactionTemplate(manager);
  }

  public String[][] select(Protocol.Selection s) {
    var q = compiler.select(s);
    // JDBC cursor pagination is portable across the four supported database drivers.
    return jdbc.query(
        q.sql(),
        ps -> {
          ps.setMaxRows(s.offset() + s.limit());
          for (int i = 0; i < q.parameters().size(); i++)
            ps.setObject(i + 1, q.parameters().get(i));
        },
        rs -> {
          List<String[]> rows = new ArrayList<>();
          int n = 0;
          while (rs.next()) {
            if (n++ < s.offset()) continue;
            String[] row = new String[s.fields().size()];
            for (int i = 0; i < row.length; i++) row[i] = rs.getString(i + 1);
            rows.add(row);
          }
          if (s.table().equals("grades") && s.fields().contains("payload")) {
            ApiException.require(
                s.fields()
                    .containsAll(List.of("id", "course_id", "student_id", "state", "version")),
                400,
                "成绩解密需要完整身份及版本字段");
            for (String[] row : rows) {
              String aad =
                  row[s.fields().indexOf("id")]
                      + "|"
                      + row[s.fields().indexOf("course_id")]
                      + "|"
                      + row[s.fields().indexOf("student_id")]
                      + "|"
                      + row[s.fields().indexOf("state")]
                      + "|"
                      + row[s.fields().indexOf("version")];
              int i = s.fields().indexOf("payload");
              row[i] = Crypto.decrypt(Settings.get("DATA_KEY"), aad, row[i]);
            }
          }
          return rows.toArray(String[][]::new);
        });
  }

  private Map<String, Object> row(String table, Object id) {
    var fields = new ArrayList<>(catalog.columns(table).keySet());
    var rows = select(new Protocol.Selection(table, fields, Map.of("id", id), "id", 0, 1));
    Map<String, Object> result = new LinkedHashMap<>();
    if (rows.length > 0)
      for (int i = 0; i < fields.size(); i++) result.put(fields.get(i), rows[0][i]);
    result.remove("password");
    return result;
  }

  public synchronized boolean manipulate(Protocol.Mutation m) {
    ApiException.require(
        m.operations() != null && !m.operations().isEmpty() && m.operations().size() <= 500,
        400,
        "事务操作数量错误");
    boolean sensitive =
        m.operations().stream()
            .anyMatch(o -> !Set.of("sessions", "login_limits").contains(o.table()));
    if (sensitive && !"SEED".equals(m.action())) {
      flush();
      ApiException.require(
          ((Number) status().get("pending")).intValue() == 0, 503, "审计同步尚未完成，暂停敏感写入");
      audit.post("audit", "/internal/check", Map.of(), Map.class);
    }
    try {
      tx.executeWithoutResult(
          status -> {
            List<Map<String, Object>> changes = new ArrayList<>();
            for (var o : m.operations()) {
              ApiException.require(!o.table().equals("audits"), 403, "禁止直接修改审计表");
              Object id = o.type().equals("INSERT") ? o.values().get("id") : o.where().get("id");
              Map<String, Object> before = sensitive ? row(o.table(), id) : Map.of();
              var values =
                  o.values() == null
                      ? new LinkedHashMap<String, Object>()
                      : new LinkedHashMap<>(o.values());
              if (o.table().equals("grades") && !o.type().equals("DELETE")) {
                ApiException.require(values.containsKey("payload"), 400, "成绩更新必须携带完整分数");
                var full = new LinkedHashMap<>(before);
                full.putAll(values);
                full.put("id", id);
                String aad =
                    full.get("id")
                        + "|"
                        + full.get("course_id")
                        + "|"
                        + full.get("student_id")
                        + "|"
                        + full.get("state")
                        + "|"
                        + full.get("version");
                values.put(
                    "payload",
                    Crypto.encrypt(
                        Settings.get("DATA_KEY"), aad, values.get("payload").toString()));
              }
              var compiled =
                  compiler.mutate(
                      new Protocol.Operation(
                          o.type(), o.table(), values, o.where(), o.expectedCount()));
              int count = jdbc.update(compiled.sql(), compiled.parameters().toArray());
              ApiException.require(
                  o.expectedCount() == null || count == o.expectedCount(),
                  409,
                  "数据已被其他操作修改，请刷新后重试");
              if (sensitive)
                changes.add(
                    Map.of(
                        "table",
                        o.table(),
                        "id",
                        id,
                        "before",
                        before,
                        "after",
                        row(o.table(), id)));
            }
            if (sensitive) {
              String id = UUID.randomUUID().toString();
              var event =
                  new Protocol.AuditEvent(
                      id, m.actor(), m.action(), m.resource(), Instant.now().toString(), changes);
              jdbc.update(
                  "INSERT INTO audits (id,payload,delivered) VALUES (?,?,?)",
                  id,
                  Crypto.encrypt(Settings.get("DATA_KEY"), id, Settings.json(event)),
                  0);
            }
          });
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      throw new ApiException(409, "DATA_CONFLICT", "数据冲突或重复记录");
    }
    if (sensitive) flush();
    return true;
  }

  @Scheduled(initialDelay = 8000, fixedDelay = 5000)
  public synchronized void flush() {
    for (var row :
        jdbc.queryForList("SELECT id,payload FROM audits WHERE delivered=0 ORDER BY id")) {
      String id = Objects.toString(row.get("ID"), Objects.toString(row.get("id"), ""));
      try {
        String payload =
            jdbc.queryForObject("SELECT payload FROM audits WHERE id=?", String.class, id);
        var event =
            Settings.JSON.readValue(
                Crypto.decrypt(Settings.get("DATA_KEY"), id, payload), Protocol.AuditEvent.class);
        audit.post("audit", "/internal/append", event, Map.class);
        jdbc.update("UPDATE audits SET delivered=1 WHERE id=?", id);
      } catch (Exception e) {
        System.err.println(
            "Audit outbox pending: "
                + id
                + " ["
                + e.getClass().getSimpleName()
                + "] "
                + e.getMessage());
        break;
      }
    }
  }

  public Map<String, Object> status() {
    return Map.of(
        "pending",
        jdbc.queryForObject("SELECT COUNT(*) FROM audits WHERE delivered=0", Integer.class));
  }
}
