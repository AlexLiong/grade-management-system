package edu.campus.data;

import edu.campus.common.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class SqlCompiler {
  public record Statement(String sql, List<Object> parameters) {}

  private final SchemaCatalog catalog;

  public SqlCompiler(SchemaCatalog catalog) {
    this.catalog = catalog;
  }

  public Object value(String table, String field, Object value) {
    String type = catalog.columns(table).get(field);
    ApiException.require(type != null, 400, "不允许的数据字段");
    ApiException.require(value != null, 400, "字段不能为空");
    return switch (type) {
      case "I" -> Integer.valueOf(value.toString());
      case "D" -> new BigDecimal(value.toString());
      default -> value.toString();
    };
  }

  private String where(String table, Map<String, Object> where, List<Object> args) {
    if (where == null || where.isEmpty()) return "";
    List<String> parts = new ArrayList<>();
    where.forEach(
        (k, v) -> {
          args.add(value(table, k, v));
          parts.add(k + " = ?");
        });
    return " WHERE " + String.join(" AND ", parts);
  }

  public Statement select(Protocol.Selection s) {
    var cols = catalog.columns(s.table());
    ApiException.require(
        s.fields() != null && !s.fields().isEmpty() && cols.keySet().containsAll(s.fields()),
        400,
        "查询字段无效");
    ApiException.require(s.limit() > 0 && s.limit() <= 10000 && s.offset() >= 0, 400, "分页参数错误");
    List<Object> args = new ArrayList<>();
    String sql =
        "SELECT "
            + String.join(",", s.fields())
            + " FROM "
            + s.table()
            + where(s.table(), s.where(), args);
    if (s.orderBy() != null && !s.orderBy().isBlank()) {
      ApiException.require(cols.containsKey(s.orderBy()), 400, "排序字段无效");
      sql += " ORDER BY " + s.orderBy();
    }
    return new Statement(sql, args);
  }

  public Statement mutate(Protocol.Operation op) {
    catalog.columns(op.table());
    List<Object> args = new ArrayList<>();
    String sql;
    ApiException.require(Set.of("INSERT", "UPDATE", "DELETE").contains(op.type()), 400, "操作类型错误");
    if (op.type().equals("INSERT")) {
      ApiException.require(op.values() != null && !op.values().isEmpty(), 400, "缺少插入字段");
      List<String> fields = new ArrayList<>();
      op.values()
          .forEach(
              (k, v) -> {
                fields.add(k);
                args.add(value(op.table(), k, v));
              });
      sql =
          "INSERT INTO "
              + op.table()
              + " ("
              + String.join(",", fields)
              + ") VALUES ("
              + String.join(",", Collections.nCopies(fields.size(), "?"))
              + ")";
    } else {
      ApiException.require(op.where() != null && op.where().containsKey("id"), 400, "更新或删除必须指定主键");
      if (op.type().equals("UPDATE")) {
        ApiException.require(
            op.values() != null && !op.values().isEmpty() && !op.values().containsKey("id"),
            400,
            "更新字段无效");
        List<String> sets = new ArrayList<>();
        op.values()
            .forEach(
                (k, v) -> {
                  args.add(value(op.table(), k, v));
                  sets.add(k + " = ?");
                });
        sql = "UPDATE " + op.table() + " SET " + String.join(",", sets);
      } else sql = "DELETE FROM " + op.table();
      sql += where(op.table(), op.where(), args);
    }
    return new Statement(sql, args);
  }
}
