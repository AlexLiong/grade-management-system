package edu.campus.data;

import edu.campus.common.*;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 数据库结构、列名和类型白名单。
 *
 * <p>启动时把内存里的目标结构（{@link #tables}）与真实库比对：
 *
 * <ul>
 *   <li>缺表：按目标结构创建；
 *   <li>缺列：自动 {@code ALTER TABLE ADD COLUMN} 补列，便于旧库平滑升级；
 *   <li>结构版本落后（{@code schema_meta} 记录的版本与当前不一致）：整库重建。
 * </ul>
 *
 * <p>结构版本只在“旧库无法用补列修复”时提升；重建会删除全部业务表，随后由
 * {@link DemoInitializer} 重新写入符合新结构的初始化数据。
 */
@Component
public class SchemaCatalog {
  /**
   * 当前结构版本。变更到无法补列兼容时递增，触发完整重建。
   *
   * <p>版本 3：班级表去掉「辅导员」列（需求要求不设辅导员）。
   */
  public static final int SCHEMA_VERSION = 3;

  private static final String META_TABLE = "schema_meta";

  public final Map<String, LinkedHashMap<String, String>> tables = new LinkedHashMap<>();
  private final JdbcTemplate jdbc;

  /** 本次启动是否执行了整库重建，供初始化器决定是否重新灌入数据。 */
  private volatile boolean rebuilt = false;

  public SchemaCatalog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    add(
        "users",
        "id:S,username:S,password:S,name:S,role:S,permissions:S,department:S,"
            + "college_id:S,major_id:S,class_id:S,enabled:I,version:I");
    add(
        "courses",
        "id:S,code:S,name:S,term:S,teacher_id:S,credits:D,weights:S,"
            + "college_id:S,class_id:S,status:S,version:I");
    add(
        "enrollments",
        "id:S,course_id:S,student_id:S,source:S,publish_id:S,selected_at:S,status:S");
    add("grades", "id:S,course_id:S,student_id:S,payload:S,state:S,version:I");
    add("analyses", "id:S,course_id:S,content:S,version:I");
    add("sessions", "id:S,user_id:S,csrf:S,expires:S");
    add("audits", "id:S,payload:S,delivered:I");
    add("login_limits", "id:S,failures:I,locked_until:S");
    add("colleges", "id:S,name:S,code:S,short_name:S,description:S,enabled:I,version:I");
    add("majors", "id:S,college_id:S,name:S,code:S,degree:S,years:I,enabled:I,version:I");
    add(
        "classes",
        "id:S,major_id:S,college_id:S,name:S,grade_year:S,code:S,enabled:I,version:I");
    add(
        "course_selections",
        "id:S,name:S,term:S,course_ids:S,scope_college_ids:S,scope_major_ids:S,"
            + "scope_class_ids:S,start_time:S,end_time:S,min_enroll:I,max_credits:I,"
            + "allow_add:I,allow_drop:I,allow_retake:I,status:S,published_by:S,"
            + "published_at:S,note:S,version:I");
    add(
        "enrollment_records",
        "id:S,publish_id:S,course_id:S,code:S,student_id:S,term:S,action:S,reason:S,"
            + "operator:S,created_at:S");
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
    // 只有「库里已有业务数据」且结构标记不匹配时才算真正的重建（会丢弃数据）。
    // 空库上标记也是 null（连 schema_meta 都没有），那只是首次建表，不应报告为重建，
    // 否则 wasRebuilt() 会把「新建库」误报成「丢弃了数据」。
    // 标记比较是整串比对（结构版本 + 密钥指纹），既能识别结构升级，也能识别密钥轮换。
    String stored = storedMarker();
    if (!structureMarker().equals(stored)) {
      boolean hadData = hasBusinessRows();
      System.out.println(
          "[SchemaCatalog] 结构版本 "
              + describeMarker(stored)
              + " 与目标 "
              + SCHEMA_VERSION
              + "（密钥指纹 "
              + ConfigGuard.dataFingerprint()
              + "）不一致：删除全部业务表后重建（原有数据："
              + (hadData ? "有，将被清空" : "无，属首次建表")
              + "）。");
      dropAll();
      rebuilt = hadData;
    }
    for (var t : tables.entrySet()) {
      String table = t.getKey();
      if (!tableExists(table)) {
        jdbc.execute("CREATE TABLE " + table + " (" + definition(t.getValue(), vendor) + ")");
        continue;
      }
      // 已存在的表只补缺列。注意 ADD COLUMN 不能带 PRIMARY KEY，因此补列时用
      // plainType 去掉主键约束；id 列本来就不会缺失（建表时已带主键）。
      for (String column : missingColumns(table, t.getValue()))
        jdbc.execute(
            "ALTER TABLE "
                + table
                + " ADD COLUMN "
                + column
                + " "
                + plainType(vendor, column, t.getValue().get(column)));
    }
    uniqueIndex("users_username", "users(username)");
    uniqueIndex("enrollment_unique", "enrollments(course_id,student_id)");
    uniqueIndex("grade_unique", "grades(course_id,student_id)");
    uniqueIndex("analysis_unique", "analyses(course_id)");
    uniqueIndex("college_name_unique", "colleges(name)");
    uniqueIndex("major_unique", "majors(college_id,name)");
    uniqueIndex("class_unique", "classes(major_id,name)");
    index("user_college", "users(college_id)");
    index("user_major", "users(major_id)");
    index("user_class", "users(class_id)");
    index("course_college", "courses(college_id)");
    index("course_term", "courses(term)");
    index("enrollment_publish", "enrollments(publish_id,student_id)");
    writeStructureVersion();
  }

  /**
   * 本次启动是否**丢弃了已有业务数据**（整库重建）。
   *
   * <p>只有「库里原本已有业务行、且结构版本不匹配因而被删除」时才为 true。空库首次建表不算
   * 重建，因此初始化器可以直接用这个标志判断「是否需要重新灌入演示数据」。
   */
  public boolean wasRebuilt() {
    return rebuilt;
  }

  /** 库里是否已有业务数据；用于区分「首次建表」与「丢弃已有数据后重建」。 */
  private boolean hasBusinessRows() {
    for (String table : List.of("users", "courses", "colleges", "classes", "grades")) {
      if (!tableExists(table)) continue;
      try {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (count != null && count > 0) return true;
      } catch (Exception ignored) {
        // 表结构不兼容时忽略：下面本来就会重建。
      }
    }
    return false;
  }

  /**
   * 结构版本标记的落库形式：{@code 结构版本:密钥指纹}。
   *
   * <p>把密钥指纹一起写进 {@code schema_meta} 是「动态密钥」的配套设计：成绩 payload、
   * 审计发件箱、账本区块都是用当时那组密钥加密的，密钥一换这些密文就解不开了。
   * 启动时比对指纹，不一致就整库重建，避免运行到读某一行时才报完整性失败。
   */
  private String structureMarker() {
    return SCHEMA_VERSION + ":" + ConfigGuard.dataFingerprint();
  }

  /**
   * 读取已落库的结构标记；表或版本行不存在时返回 null，表示需要重建。
   *
   * <p>列名用 {@code version_value} 而不是 {@code value}：{@code VALUE} 是 H2 等数据库的
   * 保留字，用它做列名会让建表语句直接报语法错误。
   */
  private String storedMarker() {
    try {
      return jdbc.queryForObject(
          "SELECT version_value FROM " + META_TABLE + " WHERE name='version'", String.class);
    } catch (Exception e) {
      return null;
    }
  }

  /** 建表并写入当前结构版本与密钥指纹；表已存在时只更新。 */
  private void writeStructureVersion() {
    try {
      jdbc.execute(
          "CREATE TABLE IF NOT EXISTS "
              + META_TABLE
              + " (name VARCHAR(50) PRIMARY KEY, version_value VARCHAR(200))");
      localStorageMarker(structureMarker());
    } catch (Exception e) {
      System.err.println("[SchemaCatalog] 写入结构版本失败：" + e.getMessage());
    }
  }

  private void localStorageMarker(String marker) {
    Integer updated =
        jdbc.update(
            "UPDATE " + META_TABLE + " SET version_value=? WHERE name='version'", marker);
    if (updated == null || updated == 0)
      jdbc.update(
          "INSERT INTO " + META_TABLE + " (name,version_value) VALUES ('version',?)", marker);
  }

  /** 把落库标记转成日志可读形式：结构版本 + 密钥指纹（指纹为 null 表示旧库或空库）。 */
  private String describeMarker(String stored) {
    if (stored == null) return "未知（空库或旧库）";
    int colon = stored.indexOf(':');
    return colon < 0 ? stored + "（旧格式，无密钥指纹）" : stored;
  }

  /**
   * 启动前检查：库文件是否是「未加密」的老库。
   *
   * <p>开启 {@code CIPHER=AES} 后，H2 打不开此前生成的明文库文件，会在连接阶段抛
   * 「File corrupted while reading record」这类让人摸不着头脑的异常（实测 H2 2.2.224）。
   * 这里提前按文件头判断并给出明确处置：删除明文库重建——教学演示数据是合成的、可重建。
   *
   * <p>判据来自实测：加密库文件头是 {@code H2encrypt}，明文库文件头是
   * {@code H:2,block:...,blockSize:...}。因此「以 H:2 开头且不是 H2encrypt」即为明文库。
   *
   * @return 是否因为切换到加密库而清掉了明文库文件
   */
  static boolean dropLegacyPlaintextDatabase(String jdbcUrl) {
    if (jdbcUrl == null
        || !jdbcUrl.startsWith("jdbc:h2:file:")
        || !jdbcUrl.toUpperCase(Locale.ROOT).contains("CIPHER=AES")) return false;
    String path = jdbcUrl.substring("jdbc:h2:file:".length());
    int semicolon = path.indexOf(';');
    if (semicolon >= 0) path = path.substring(0, semicolon);
    Path file = Path.of(path).toAbsolutePath();
    if (!path.endsWith(".mv.db")) file = Path.of(file + ".mv.db");
    if (!Files.exists(file)) return false;
    try {
      byte[] all = Files.readAllBytes(file);
      String head = new String(all, 0, Math.min(all.length, 64), java.nio.charset.StandardCharsets.ISO_8859_1);
      if (!head.startsWith("H:2") || head.startsWith("H2encrypt")) return false;
      Files.delete(file);
      System.out.println(
          "[SchemaCatalog] 检测到未加密的 H2 明文库，已删除并改为加密库重建："
              + file
              + "（演示数据由初始化器重新灌入）");
      return true;
    } catch (Exception e) {
      System.err.println("[SchemaCatalog] 检查明文库文件失败：" + e.getMessage());
      return false;
    }
  }

  /**
   * 删除全部业务表。此操作会丢弃旧库全部内容，只在结构版本不匹配时执行；
   * 统一使用 {@code DROP TABLE IF EXISTS}，不依赖建表顺序。
   */
  private void dropAll() {
    var names = new ArrayList<>(tables.keySet());
    Collections.reverse(names);
    for (String table : names) drop(table);
    drop(META_TABLE);
  }

  private void drop(String table) {
    try {
      jdbc.execute("DROP TABLE IF EXISTS " + table);
    } catch (Exception e) {
      System.err.println("[SchemaCatalog] 删除表 " + table + " 失败：" + e.getMessage());
    }
  }

  private boolean tableExists(String table) {
    try {
      jdbc.queryForList("SELECT 1 FROM " + table + " WHERE 1=0");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * 返回目标结构里存在但真实表缺少的列，顺序与目标结构一致。
   *
   * <p>用 {@code jdbc.execute(ConnectionCallback)} 读 {@link ResultSetMetaData}，而不是
   * {@code jdbc.query(...)}：后者在结果集没有行时根本不会回调提取器，会把所有列都误判成缺失。
   */
  private List<String> missingColumns(String table, LinkedHashMap<String, String> target) {
    Set<String> actual = new LinkedHashSet<>();
    try {
      jdbc.execute(
          (Connection connection) -> {
            try (var statement = connection.createStatement();
                var rs = statement.executeQuery("SELECT * FROM " + table + " WHERE 1=0")) {
              var meta = rs.getMetaData();
              for (int i = 1; i <= meta.getColumnCount(); i++)
                actual.add(meta.getColumnName(i).toLowerCase(Locale.ROOT));
            }
            return null;
          });
    } catch (Exception e) {
      throw new IllegalStateException("读取表结构失败：" + table, e);
    }
    var missing = new ArrayList<String>();
    for (String column : target.keySet())
      if (!actual.contains(column.toLowerCase(Locale.ROOT))) missing.add(column);
    return missing;
  }

  private String definition(LinkedHashMap<String, String> columns, String vendor) {
    List<String> fields = new ArrayList<>();
    for (var c : columns.entrySet())
      fields.add(c.getKey() + " " + sqlType(vendor, c.getKey(), c.getValue()));
    return String.join(",", fields);
  }

  /** {@code ALTER TABLE ... ADD COLUMN} 用的类型：与建表一致，但不带主键约束。 */
  private String plainType(String vendor, String column, String type) {
    String declared = sqlType(vendor, column, type);
    return declared.endsWith(" PRIMARY KEY")
        ? declared.substring(0, declared.length() - " PRIMARY KEY".length())
        : declared;
  }

  private String sqlType(String vendor, String column, String type) {
    if (column.equals("id")) return "VARCHAR(100) PRIMARY KEY";
    return switch (type) {
      case "I" -> "INTEGER";
      case "D" -> "DECIMAL(10,2)";
      default -> {
        boolean longText =
            column.equals("payload")
                || column.equals("content")
                || column.endsWith("_ids")
                || column.equals("weights")
                || column.equals("note")
                || column.equals("description");
        if (!longText) yield "VARCHAR(1000)";
        if (vendor.contains("MySQL")) yield column.equals("payload") || column.equals("content") ? "LONGTEXT" : "TEXT";
        if (vendor.contains("Microsoft")) yield column.equals("payload") || column.equals("content") ? "VARCHAR(MAX)" : "NVARCHAR(MAX)";
        yield "CLOB";
      }
    };
  }

  private void uniqueIndex(String name, String target) {
    try {
      jdbc.execute("CREATE UNIQUE INDEX " + name + " ON " + target);
    } catch (org.springframework.dao.DataAccessException ignored) {
    }
  }

  private void index(String name, String target) {
    try {
      jdbc.execute("CREATE INDEX " + name + " ON " + target);
    } catch (org.springframework.dao.DataAccessException ignored) {
    }
  }
}
