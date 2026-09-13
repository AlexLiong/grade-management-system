package edu.campus.common;

import java.util.*;

public final class Protocol {
  private Protocol() {}

  public record Selection(
      String table,
      List<String> fields,
      Map<String, Object> where,
      String orderBy,
      int offset,
      int limit) {}

  public record Operation(
      String type,
      String table,
      Map<String, Object> values,
      Map<String, Object> where,
      Integer expectedCount) {}

  public record Mutation(
      List<Operation> operations, String actor, String action, String resource, String requestId) {}

  public record Registration(String service, String instance, String url) {}

  public record AuditEvent(
      String id,
      String actor,
      String action,
      String resource,
      String time,
      List<Map<String, Object>> changes) {}

  public interface SelectInterface {
    String[][] select(Selection selection);
  }

  public interface ManipulationInterface {
    boolean manipulate(Mutation mutation);
  }
}
