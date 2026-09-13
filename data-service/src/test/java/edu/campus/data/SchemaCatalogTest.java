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
}
