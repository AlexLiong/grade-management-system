package edu.campus.audit;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class LedgerServiceTest {
  @Test
  void blockRecordStructureIsCorrect() {
    LedgerService.Block b =
        new LedgerService.Block(
            5L, "prev-hash-value", "encrypted-data", "current-hash", "signature-value", "tx-hash");
    assertEquals(5L, b.index());
    assertEquals("prev-hash-value", b.previous());
    assertEquals("encrypted-data", b.ciphertext());
    assertEquals("current-hash", b.hash());
    assertEquals("signature-value", b.signature());
    assertEquals("tx-hash", b.transaction());
  }

  @Test
  void auditEventConstruction() {
    var event =
        new Protocol.AuditEvent(
            "event-123",
            "teacher-1",
            "GRADE_SAVE",
            "course-net-2026",
            "2026-09-12T10:00:00Z",
            List.of(Map.of("table", "grades", "id", "g1")));
    assertEquals("event-123", event.id());
    assertEquals("teacher-1", event.actor());
    assertEquals("GRADE_SAVE", event.action());
    assertEquals("course-net-2026", event.resource());
    assertFalse(event.changes().isEmpty());
  }

  @Test
  void emptyChangesListIsAllowed() {
    var event =
        new Protocol.AuditEvent(
            "event-2",
            "admin",
            "LOGIN",
            "session",
            "2026-09-12T11:00:00Z",
            List.of());
    assertEquals("event-2", event.id());
    assertTrue(event.changes().isEmpty());
  }
}
