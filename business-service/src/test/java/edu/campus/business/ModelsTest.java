package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelsTest {
  private final Map<String, Object> weights = CourseService.defaultWeights();

  @Test
  void defaultWeightsSumToOneHundred() {
    double sum = 0;
    for (var v : CourseService.defaultWeights().values()) sum += ((Number) v).doubleValue();
    assertEquals(100.0, sum, 0.0001);
  }

  @Test
  void allSixComponentsPresent() {
    assertEquals(
        Set.of("regular", "attendance", "homework", "lab", "midterm", "finalExam"),
        new HashSet<>(Models.COMPONENTS));
  }

  @Test
  void permissionSetsCoverAllRoles() {
    assertEquals(Set.of("QUERY", "ENTRY", "MAINTAIN", "PREDICT"), Models.PERMISSIONS.get("TEACHER"));
    assertEquals(Set.of("QUERY", "PREDICT"), Models.PERMISSIONS.get("STUDENT"));
    assertEquals(Set.of("GRADE_ADMIN", "USER_ADMIN", "AUDIT"), Models.PERMISSIONS.get("ADMIN"));
  }

  @Test
  void userRequiresCheckPasses() {
    var u =
        new Models.User(
            "u1",
            "alice",
            "Alice",
            "TEACHER",
            Set.of("QUERY", "ENTRY", "MAINTAIN", "PREDICT"),
            0);
    assertDoesNotThrow(() -> u.require("ENTRY"));
    assertDoesNotThrow(() -> u.require("QUERY"));
  }

  @Test
  void userRequiresCheckFailsForMissingPermission() {
    var u =
        new Models.User(
            "u1", "alice", "Alice", "STUDENT", Set.of("QUERY", "PREDICT"), 0);
    assertThrows(ApiException.class, () -> u.require("ENTRY"));
  }

  @Test
  void publicUserRemovesPassword() {
    var user =
        new LinkedHashMap<String, Object>(
            Map.of(
                "id",
                "u1",
                "username",
                "alice",
                "password",
                "secret123",
                "name",
                "Alice",
                "role",
                "TEACHER"));
    var publicUser = Models.publicUser(user);
    assertFalse(publicUser.containsKey("password"));
    assertEquals("alice", publicUser.get("username"));
  }

  @Test
  void gradeConvertsPayloadToJson() {
    var row =
        new LinkedHashMap<String, Object>(
            Map.of("id", "g1", "payload", "{\"regular\":50,\"lab\":50}", "state", "DRAFT"));
    var grade = Models.grade(row);
    assertTrue(grade.containsKey("scores"));
    assertFalse(grade.containsKey("payload"));
  }

  @Test
  void textExtractsAndTrimsString() {
    var body = Map.<String, Object>of("name", "  course  ");
    assertEquals("course", Models.text(body, "name", 100));
  }

  @Test
  void textRejectsEmptyOrMissing() {
    assertThrows(ApiException.class, () -> Models.text(Map.of(), "name", 100));
    assertThrows(ApiException.class, () -> Models.text(Map.of("name", ""), "name", 100));
    assertThrows(ApiException.class, () -> Models.text(Map.of("name", 123), "name", 100));
  }

  @Test
  void textRejectsOverMaxLen() {
    assertThrows(ApiException.class, () -> Models.text(Map.of("name", "a".repeat(101)), "name", 100));
  }

  @Test
  void numberParseFiniteOnly() {
    assertEquals(50.0, Models.number("50"));
    assertThrows(ApiException.class, () -> Models.number(Double.toString(Double.NaN)));
    assertThrows(ApiException.class, () -> Models.number(Double.toString(Double.POSITIVE_INFINITY)));
  }

  @Test
  void objectFromMapAndJsonString() throws Exception {
    var map = new LinkedHashMap<String, Object>();
    map.put("a", 1);
    var parsed = Models.object(map);
    assertEquals(map, parsed);
    var parsed2 = Models.object("{\"b\":2}");
    assertEquals(2, parsed2.get("b"));
  }
}
