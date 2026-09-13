package edu.campus.data;

import edu.campus.common.*;
import jakarta.annotation.PostConstruct;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SchemaCatalog {
  public final Map<String, LinkedHashMap<String, String>> tables = new LinkedHashMap<>();
  private final JdbcTemplate jdbc;

  public SchemaCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    add(
        "users",
        "id:S,username:S,password:S,name:S,role:S,permissions:S,department:S,enabled:I,version:I");
    add("courses", "id:S,code:S,name:S,term:S,teacher_id:S,credits:D,weights:S,version:I");
    add("enrollments", "id:S,course_id:S,student_id:S");
    add("grades", "id:S,course_id:S,student_id:S,payload:S,state:S,version:I");
    add("analyses", "id:S,course_id:S,content:S,version:I");
    add("sessions", "id:S,user_id:S,csrf:S,expires:S");
    add("audits", "id:S,payload:S,delivered:I");
    add("login_limits", "id:S,failures:I,locked_until:S");
  }

  private void add(String table, String fields) {
    var columns = new LinkedHashMap<String, String>();
    for (String f : fields.split(",")) {
      var p = f.split(":");
      columns.put(p[0], p[1]);
    }
    tables.put(table, columns);
  }

  public LinkedHashMap<String, String> columns(String table) {
    ApiException.require(tables.containsKey(table), 400, "不允许访问该数据表");
    return tables.get(table);
  }

  @PostConstruct
  public void init() {
    String vendor;
    try (var c = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
      vendor = c.getMetaData().getDatabaseProductName();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    for (var t : tables.entrySet()) {
      boolean exists;
      try {
        jdbc.queryForList("SELECT id FROM " + t.getKey() + " WHERE 1=0");
        exists = true;
      } catch (Exception e) {
        exists = false;
      }
      if (exists) continue;
      List<String> fields = new ArrayList<>();
      for (var c : t.getValue().entrySet()) {
        String sqlType =
            switch (c.getValue()) {
              case "I" -> "INTEGER";
              case "D" -> "DECIMAL(10,2)";
              default ->
                  c.getKey().equals("payload") || c.getKey().equals("content")
                      ? (vendor.contains("MySQL")
                          ? "LONGTEXT"
                          : vendor.contains("Microsoft") ? "VARCHAR(MAX)" : "CLOB")
                      : "VARCHAR(1000)";
            };
        if (c.getKey().equals("id")) sqlType = "VARCHAR(100) PRIMARY KEY";
        fields.add(c.getKey() + " " + sqlType);
      }
      jdbc.execute("CREATE TABLE " + t.getKey() + " (" + String.join(",", fields) + ")");
    }
    index("users_username", "users(username)");
    index("enrollment_unique", "enrollments(course_id,student_id)");
    index("grade_unique", "grades(course_id,student_id)");
    index("analysis_unique", "analyses(course_id)");
  }

  private void index(String name, String target) {
    try {
      jdbc.execute("CREATE UNIQUE INDEX " + name + " ON " + target);
    } catch (org.springframework.dao.DataAccessException ignored) {
    }
  }
}
