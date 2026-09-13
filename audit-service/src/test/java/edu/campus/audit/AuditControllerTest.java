package edu.campus.audit;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.*;

class AuditControllerTest {
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
  void blockRecordHoldsAllFields() {
    LedgerService.Block b =
        new LedgerService.Block(
            0L, "prev-hash", "cipher", "hash", "sig", "tx-1");
    assertEquals(0L, b.index());
    assertEquals("prev-hash", b.previous());
    assertEquals("cipher", b.ciphertext());
    assertEquals("hash", b.hash());
    assertEquals("sig", b.signature());
    assertEquals("tx-1", b.transaction());
  }

  @Test
  void auditEventOrderingByTime() {
    var events =
        List.of(
            new Protocol.AuditEvent("e2", "a", "B", "r", "2026-01-01T00:02:00Z", List.of()),
            new Protocol.AuditEvent("e1", "a", "A", "r", "2026-01-01T00:01:00Z", List.of()));
    var sorted = events.stream().sorted(Comparator.comparing(Protocol.AuditEvent::time)).toList();
    assertEquals("e1", sorted.get(0).id());
    assertEquals("e2", sorted.get(1).id());
  }
}
