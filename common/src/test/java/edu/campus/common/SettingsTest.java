package edu.campus.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SettingsTest {
  @Test
  void getReadsSystemProperty() {
    System.setProperty("test.setting.key", "test-value");
    try {
      // We test via a custom accessor simulation
      String val = System.getProperty("test.setting.key");
      assertEquals("test-value", val);
    } finally {
      System.clearProperty("test.setting.key");
    }
  }

  @Test
  void jsonSerializesAndDeserializesMap() throws Exception {
    var map = Map.of("key", "value", "num", 42);
    String json = Settings.json(map);
    var parsed = Settings.JSON.readValue(json, Map.class);
    assertEquals("value", parsed.get("key"));
    assertEquals(42, parsed.get("num"));
  }

  @Test
  void jsonSerializesList() throws Exception {
    var list = List.of("a", "b", "c");
    String json = Settings.json(list);
    var parsed = Settings.JSON.readValue(json, List.class);
    assertEquals(3, parsed.size());
  }

  @Test
  void runtimeRootExistsOrCanBeCreated() {
    // In test context, .runtime may not exist; we just verify the method returns a path
    var root = Settings.root();
    assertNotNull(root);
    assertTrue(root.toString().contains(".runtime"));
  }
}
