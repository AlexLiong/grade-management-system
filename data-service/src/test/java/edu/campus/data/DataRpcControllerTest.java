package edu.campus.data;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DataRpcControllerTest {
  private JdbcTemplate jdbc;
  private SqlCompiler compiler;
  private SchemaCatalog catalog;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    catalog = new SchemaCatalog(jdbc);
    catalog.init();
    compiler = new SqlCompiler(catalog);
  }

  @Test
  void selectUsersReturnsColumns() {
    jdbc.update(
        "INSERT INTO users(id,username,password,name,role,permissions,department,enabled,version) VALUES(?,?,?,?,?,?,?,?,?)",
        "u1",
        "alice",
        "$2a$12$xyz",
        "Alice",
        "TEACHER",
        "QUERY,ENTRY",
        "CS",
        1,
        0);
    var result =
        compiler
            .select(
                new Protocol.Selection(
                    "users", List.of("id", "username", "name"), Map.of(), null, 0, 10))
            .sql();
    assertNotNull(result);
  }

  @Test
  void selectWithWhereClause() {
    jdbc.update(
        "INSERT INTO users(id,username,password,name,role,permissions,department,enabled,version) VALUES(?,?,?,?,?,?,?,?,?)",
        "u1",
        "alice",
        "$2a$12$xyz",
        "Alice",
        "TEACHER",
        "QUERY",
        "CS",
        1,
        0);
    jdbc.update(
        "INSERT INTO users(id,username,password,name,role,permissions,department,enabled,version) VALUES(?,?,?,?,?,?,?,?,?)",
        "u2",
        "bob",
        "$2a$12$xyz",
        "Bob",
        "STUDENT",
        "QUERY",
        "CS",
        1,
        0);
    var stmt =
        compiler.select(
            new Protocol.Selection(
                "users", List.of("id", "username"), Map.of("role", "STUDENT"), null, 0, 10));
    List<String[]> rows =
        jdbc.query(
            stmt.sql(),
            ps -> ps.setString(1, "STUDENT"),
            rs -> {
              List<String[]> result = new ArrayList<>();
              while (rs.next()) {
                result.add(new String[] {rs.getString(1), rs.getString(2)});
              }
              return result;
            });
    assertEquals(1, rows.size());
    assertEquals("bob", rows.get(0)[1]);
  }

  @Test
  void insertAndSelectRoundTrip() {
    var op =
        compiler.mutate(
            new Protocol.Operation(
                "INSERT",
                "users",
                Map.of(
                    "id",
                    "u1",
                    "username",
                    "alice",
                    "password",
                    "$2a$12$x",
                    "name",
                    "Alice",
                    "role",
                    "TEACHER",
                    "permissions",
                    "QUERY",
                    "department",
                    "CS",
                    "enabled",
                    1,
                    "version",
                    0),
                Map.of(),
                1));
    jdbc.update(op.sql(), op.parameters().toArray());
    var stmt =
        compiler.select(
            new Protocol.Selection(
                "users", List.of("username"), Map.of("id", "u1"), null, 0, 10));
    String username =
        jdbc.queryForObject(stmt.sql(), String.class, "u1");
    assertEquals("alice", username);
  }

  @Test
  void invalidTableRejected() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("nonexistent", List.of("id"), Map.of(), null, 0, 10)));
  }

  @Test
  void invalidColumnRejected() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("nonexistent_col"), Map.of(), null, 0, 10)));
  }

  @Test
  void updateWithoutPrimaryKeyRejected() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(
                new Protocol.Operation(
                    "UPDATE", "users", Map.of("name", "Alice"), Map.of(), 1)));
  }

  @Test
  void deleteWithoutPrimaryKeyRejected() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(
                new Protocol.Operation("DELETE", "users", Map.of(), Map.of(), null)));
  }

  @Test
  void insertRequiresValues() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(
                new Protocol.Operation("INSERT", "users", Map.of(), Map.of(), 1)));
  }

  @Test
  void operationTypeValidation() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(
                new Protocol.Operation("INVALID", "users", Map.of("id", "x"), Map.of(), 1)));
  }

  @Test
  void selectPaginationLimitsEnforced() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("id"), Map.of(), "id", 0, 10001)));
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("id"), Map.of(), "id", -1, 10)));
  }
}
