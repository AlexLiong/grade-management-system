package edu.campus.data;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaCatalogTest {
  private JdbcTemplate jdbc;
  private SchemaCatalog catalog;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    catalog = new SchemaCatalog(jdbc);
    catalog.init();
  }

  @Test
  void allTablesAreCreated() {
    for (var t : catalog.tables.keySet()) {
      Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Integer.class);
      assertNotNull(count);
    }
  }

  @Test
  void usersTableHasExpectedColumns() {
    var cols = catalog.columns("users");
    assertTrue(cols.containsKey("id"));
    assertTrue(cols.containsKey("username"));
    assertTrue(cols.containsKey("password"));
    assertTrue(cols.containsKey("role"));
    assertTrue(cols.containsKey("enabled"));
    assertTrue(cols.containsKey("version"));
  }

  @Test
  void gradesTableHasPayloadColumn() {
    var cols = catalog.columns("grades");
    assertTrue(cols.containsKey("payload"));
    assertTrue(cols.containsKey("state"));
    assertTrue(cols.containsKey("course_id"));
    assertTrue(cols.containsKey("student_id"));
  }

  @Test
  void invalidTableRejected() {
    assertThrows(
        ApiException.class,
        () -> catalog.columns("nonexistent_table"));
  }

  @Test
  void uniqueIndexesExist() {
    // enrollment_unique
    jdbc.update("INSERT INTO enrollments(id,course_id,student_id) VALUES(?,?,?)", "e1", "c1", "s1");
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> jdbc.update("INSERT INTO enrollments(id,course_id,student_id) VALUES(?,?,?)", "e2", "c1", "s1"));

    // grade_unique
    jdbc.update("INSERT INTO grades(id,course_id,student_id,payload,state,version) VALUES(?,?,?,?,?,?)", "g1", "c1", "s1", "{}", "DRAFT", 0);
    assertThrows(
        org.springframework.dao.DuplicateKeyException.class,
        () -> jdbc.update("INSERT INTO grades(id,course_id,student_id,payload,state,version) VALUES(?,?,?,?,?,?)", "g2", "c1", "s1", "{}", "DRAFT", 0));
  }

  @Test
  void newTablesAndColumnsArePresent() {
    for (String table : List.of("colleges", "majors", "classes", "course_selections", "enrollment_records")) {
      Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
      assertNotNull(count, table + " 应已创建");
    }
    assertTrue(catalog.columns("users").keySet().containsAll(List.of("college_id", "major_id", "class_id")));
    assertTrue(catalog.columns("courses").keySet().containsAll(List.of("college_id", "class_id", "status")));
    assertTrue(catalog.columns("enrollments").keySet().containsAll(List.of("source", "publish_id", "selected_at", "status")));
  }

  /**
   * 同一结构版本内的增量升级：表已存在但缺少新列时补列，且必须保留原有数据。
   *
   * <p>这条用例锁住两个曾经的缺陷：读列元数据时若用 {@code jdbc.query} 会在空结果集上
   * 完全不回调提取器，导致把所有列都误判为缺失；补列时若沿用建表用的类型，会把
   * {@code PRIMARY KEY} 一起带进 {@code ALTER TABLE ADD COLUMN} 而直接报语法错误。
   */
  @Test
  void addsMissingColumnsWithoutDroppingData() {
    String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    var ds = new DriverManagerDataSource(url, "sa", "");
    var jdbcTemplate = new JdbcTemplate(ds);

    // 1) 先用当前定义建好整库（结构版本也写成当前版本），确认首次建库不重建。
    var fresh = new SchemaCatalog(jdbcTemplate);
    fresh.init();
    assertFalse(fresh.wasRebuilt(), "首次建库不应触发重建");

    // 2) 人为把 users 回退成「缺新列」的形态并写入一行数据，模拟同一结构版本内的增量升级。
    //    注意不能删 schema_meta：删了就等于「结构版本落后」，那是整库重建的分支而不是补列分支。
    jdbcTemplate.update(
        "INSERT INTO users (id,username,name,role,permissions,department,enabled,version) VALUES (?,?,?,?,?,?,?,?)",
        "u1", "alice", "Alice", "STUDENT", "QUERY", "软件工程", 1, 0);
    for (String column : List.of("college_id", "major_id", "class_id"))
      jdbcTemplate.execute("ALTER TABLE users DROP COLUMN " + column);

    // 3) 再次执行启动时的结构检查：应为 users 补齐三列，并保留原有数据。
    var catalog = new SchemaCatalog(jdbcTemplate);
    catalog.init();

    assertFalse(catalog.wasRebuilt(), "结构版本一致时不应整库重建");
    assertTrue(catalog.columns("users").keySet().containsAll(List.of("college_id", "major_id", "class_id")));

    Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM users WHERE id='u1'");
    assertEquals("alice", row.get("USERNAME"));
    assertNull(row.get("COLLEGE_ID"), "补出的列应为空值而不是丢失整行");

    // 4) 补列后新列可正常写入。
    jdbcTemplate.update("UPDATE users SET college_id=? WHERE id='u1'", "C01001");
    assertEquals(
        "C01001",
        jdbcTemplate.queryForObject("SELECT college_id FROM users WHERE id='u1'", String.class));
  }

  /** 结构版本落后时必须整库重建，并用日志语义（wasRebuilt）暴露出来供初始化器决策。 */
  @Test
  void rebuildsWhenStructureVersionIsOutdated() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbcTemplate = new JdbcTemplate(ds);
    new SchemaCatalog(jdbcTemplate).init();

    // 写入一个明显落后的结构版本，并放一行数据用于确认重建会清空它。
    jdbcTemplate.update("UPDATE schema_meta SET version_value='1' WHERE name='version'");
    jdbcTemplate.update(
        "INSERT INTO users (id,username,name,role,permissions,department,enabled,version) VALUES (?,?,?,?,?,?,?,?)",
        "u1", "alice", "Alice", "STUDENT", "QUERY", "信息工程学院", 1, 0);

    var catalog = new SchemaCatalog(jdbcTemplate);
    catalog.init();

    assertTrue(catalog.wasRebuilt(), "结构版本不一致时应整库重建");
    assertEquals(
        0,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class).intValue(),
        "重建后旧数据应被清空");
    // 结构标记现在是「结构版本:密钥指纹」——密钥换了也要触发重建，避免用旧密钥加密的
    // 成绩/审计密文解不开（见 SchemaCatalog.structureMarker()）。
    String marker =
        jdbcTemplate.queryForObject(
            "SELECT version_value FROM schema_meta WHERE name='version'", String.class);
    assertNotNull(marker);
    assertTrue(
        marker.startsWith(SchemaCatalog.SCHEMA_VERSION + ":"),
        "重建后应写入当前结构版本 + 密钥指纹，实际=" + marker);
    assertEquals(
        SchemaCatalog.SCHEMA_VERSION + ":" + edu.campus.common.ConfigGuard.dataFingerprint(), marker);
  }

  /** 结构版本相同但密钥指纹不同（轮换密钥）时，也必须整库重建。 */
  @Test
  void rebuildsWhenSecretsFingerprintChanges() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbcTemplate = new JdbcTemplate(ds);
    new SchemaCatalog(jdbcTemplate).init();
    jdbcTemplate.update(
        "INSERT INTO users (id,username,name,role,permissions,department,enabled,version) VALUES (?,?,?,?,?,?,?,?)",
        "u1", "alice", "Alice", "STUDENT", "QUERY", "信息工程学院", 1, 0);
    // 结构版本对得上、指纹对不上：模拟「换了密钥但库还是旧的」
    jdbcTemplate.update(
        "UPDATE schema_meta SET version_value=? WHERE name='version'",
        SchemaCatalog.SCHEMA_VERSION + ":0000000000000000");

    var catalog = new SchemaCatalog(jdbcTemplate);
    catalog.init();

    assertTrue(catalog.wasRebuilt(), "密钥指纹变化时应整库重建");
    assertEquals(
        0,
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class).intValue(),
        "旧密钥加密的数据在轮换后无法解密，必须清空重建");
  }
}
