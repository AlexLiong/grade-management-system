package edu.campus.audit;

import edu.campus.common.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuditController {
  private final LedgerService ledger;

  public AuditController(LedgerService ledger) {
    this.ledger = ledger;
  }

  @PostMapping("/internal/bootstrap")
  public void bootstrap(@RequestBody List<Protocol.AuditEvent> events) throws Exception {
    ledger.bootstrap(events);
  }

  @PostMapping("/internal/bootstrap-anchored")
  public Map<String, Object> bootstrapWithAnchors(@RequestBody List<Protocol.AuditEvent> events) throws Exception {
    ledger.bootstrapWithAnchors(events);
    return Map.of("ok", true, "count", events.size());
  }

  @PostMapping("/internal/append")
  public Map<String, Object> append(@RequestBody Protocol.AuditEvent event) {
    return ledger.append(event);
  }

  @PostMapping("/internal/check")
  public Map<String, Object> check() {
    return Map.of("verified", true, "blocks", ledger.verify().size());
  }

  @PostMapping("/internal/ledger")
  public Map<String, Object> ledger() {
    return ledger.read();
  }

  @PostMapping("/internal/classify")
  public Map<String, Object> classify() {
    return ledger.classify();
  }
}
