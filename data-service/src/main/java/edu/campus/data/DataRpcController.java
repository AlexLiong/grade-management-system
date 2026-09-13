package edu.campus.data;

import edu.campus.common.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class DataRpcController implements Protocol.SelectInterface, Protocol.ManipulationInterface {
  private final TransactionService service;

  public DataRpcController(TransactionService service) {
    this.service = service;
  }

  @PostMapping("/internal/select")
  public String[][] select(@RequestBody Protocol.Selection s) {
    return service.select(s);
  }

  @PostMapping("/internal/manipulate")
  public boolean manipulate(@RequestBody Protocol.Mutation m) {
    return service.manipulate(m);
  }

  @PostMapping("/internal/status")
  public Map<String, Object> status() {
    return service.status();
  }
}
