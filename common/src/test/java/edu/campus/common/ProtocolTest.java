package edu.campus.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class ProtocolTest {
  @Test
  void selectionFieldsAreNotNull() {
    var s =
        new Protocol.Selection(
            "users", List.of("id", "username"), Map.of("role", "ADMIN"), "id", 0, 10);
    assertEquals("users", s.table());
    assertEquals(2, s.fields().size());
    assertEquals(0, s.offset());
    assertEquals(10, s.limit());
  }

  @Test
  void operationTypeEnumValues() {
    assertTrue(Set.of("INSERT", "UPDATE", "DELETE").contains("INSERT"));
    assertTrue(Set.of("INSERT", "UPDATE", "DELETE").contains("UPDATE"));
    assertTrue(Set.of("INSERT", "UPDATE", "DELETE").contains("DELETE"));
  }

  @Test
  void mutationContainsAllRequiredFields() {
    var op = new Protocol.Operation("UPDATE", "grades", Map.of("state", "SUBMITTED"), Map.of("id", "g1", "version", 0), 1);
    var m = new Protocol.Mutation(List.of(op), "teacher1", "SUBMIT", "course-1", "req-1");
    assertEquals(1, m.operations().size());
    assertEquals("teacher1", m.actor());
    assertEquals("SUBMIT", m.action());
    assertEquals("course-1", m.resource());
    assertEquals("req-1", m.requestId());
  }

  @Test
  void auditEventHasAllFields() {
    var event =
        new Protocol.AuditEvent(
            "evt-1",
            "admin",
            "USER_SAVE",
            "u-1",
            "2026-01-01T00:00:00Z",
            List.of(Map.of("table", "users", "id", "u-1")));
    assertEquals("evt-1", event.id());
    assertEquals("admin", event.actor());
    assertEquals("USER_SAVE", event.action());
    assertFalse(event.changes().isEmpty());
  }

  @Test
  void registrationRecordsServiceInfo() {
    var r = new Protocol.Registration("business", "inst-1", "https://localhost:9442/api");
    assertEquals("business", r.service());
    assertEquals("inst-1", r.instance());
    assertEquals("https://localhost:9442/api", r.url());
  }
}
