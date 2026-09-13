package edu.campus.data;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SqlCompilerTest {
  JdbcTemplate jdbc;
  SqlCompiler compiler;

  @BeforeEach
  void setup() {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    jdbc = new JdbcTemplate(ds);
    var catalog = new SchemaCatalog(jdbc);
    catalog.init();
    compiler = new SqlCompiler(catalog);
  }

  @Test
  void sqlInjectionRemainsBoundData() {
    String attack = "Robert'); DROP TABLE users; --";
    var op =
        compiler.mutate(
            new Protocol.Operation(
                "INSERT", "users", Map.of("id", "a", "username", attack), Map.of(), 1));
    assertFalse(op.sql().contains(attack));
    jdbc.update(op.sql(), op.parameters().toArray());
    assertEquals(
        attack, jdbc.queryForObject("SELECT username FROM users WHERE id='a'", String.class));
  }

  @Test
  void invalidTableColumnAndSortAreRejected() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection(
                    "users;DROP TABLE users", List.of("id"), Map.of(), null, 0, 10)));
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("password;"), Map.of(), null, 0, 10)));
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("id"), Map.of(), "id desc; --", 0, 10)));
  }

  @Test
  void updateAndDeleteRequirePrimaryKey() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(new Protocol.Operation("DELETE", "grades", Map.of(), Map.of(), null)));
    assertThrows(
        ApiException.class,
        () ->
            compiler.mutate(
                new Protocol.Operation("UPDATE", "grades", Map.of("version", 1), Map.of(), null)));
  }

  @Test
  void transactionRollsBackWholeBatch() {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    assertThrows(
        Exception.class,
        () ->
            tx.executeWithoutResult(
                s -> {
                  jdbc.update("INSERT INTO users(id,username) VALUES (?,?)", "a", "alice");
                  jdbc.update("INSERT INTO users(id,username) VALUES (?,?)", "b", "alice");
                }));
    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
  }

  @Test
  void optimisticVersionRejectsStaleWrite() {
    jdbc.update("INSERT INTO grades(id,version) VALUES (?,?)", "g", 0);
    var s =
        compiler.mutate(
            new Protocol.Operation(
                "UPDATE", "grades", Map.of("version", 1), Map.of("id", "g", "version", 0), 1));
    assertEquals(1, jdbc.update(s.sql(), s.parameters().toArray()));
    assertEquals(0, jdbc.update(s.sql(), s.parameters().toArray()));
  }

  @Test
  void paginationBoundariesEnforced() {
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("id"), Map.of(), "id", -1, 10)));
    assertThrows(
        ApiException.class,
        () ->
            compiler.select(
                new Protocol.Selection("users", List.of("id"), Map.of(), "id", 0, 10001)));
  }
}
