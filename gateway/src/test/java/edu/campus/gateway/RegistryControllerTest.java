package edu.campus.gateway;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.*;

class RegistryControllerTest {
  private RegistryController registry;

  @BeforeEach
  void setup() {
    registry = new RegistryController();
    System.setProperty("SERVICE_HOSTS", "localhost,127.0.0.1");
    System.setProperty("campus.service", "gateway");
  }

  @AfterEach
  void cleanup() {
    System.clearProperty("SERVICE_HOSTS");
    System.clearProperty("campus.service");
  }

  @Test
  void registerValidService() {
    // Caller must equal service name
    var r = new Protocol.Registration("business", "inst-1", "https://localhost:9442/");
    assertTrue(registry.register(r, "business"));
  }

  @Test
  void discoverReturnsUrl() {
    var r = new Protocol.Registration("data", "inst-1", "https://localhost:9443/");
    registry.register(r, "data");
    var result = registry.discover(Map.of("service", "data"));
    assertEquals("https://localhost:9443/", result.get("url"));
  }

  @Test
  void discoverRejectsUnknownService() {
    assertThrows(ApiException.class, () -> registry.choose("nonexistent"));
  }

  @Test
  void registerRejectsHttpScheme() {
    var r = new Protocol.Registration("business", "inst-1", "http://localhost:9442/");
    assertThrows(ApiException.class, () -> registry.register(r, "business"));
  }

  @Test
  void registerRejectsMismatchedCaller() {
    var r = new Protocol.Registration("business", "inst-1", "https://localhost:9442/");
    // Caller "audit" does not match service "business"
    assertThrows(ApiException.class, () -> registry.register(r, "audit"));
  }

  @Test
  void healthReturnsUpStatus() {
    var r = new Protocol.Registration("audit", "inst-1", "https://localhost:9444/");
    registry.register(r, "audit");
    var health = registry.health();
    assertEquals("UP", health.get("status"));
    assertFalse(((List<?>) health.get("services")).isEmpty());
  }

  @Test
  void chooseRotatesAmongInstances() {
    registry.register(new Protocol.Registration("biz", "inst-a", "https://localhost:9442/"), "biz");
    registry.register(new Protocol.Registration("biz", "inst-b", "https://localhost:9443/"), "biz");
    String first = registry.choose("biz");
    String second = registry.choose("biz");
    assertNotEquals(first, second);
  }

  @Test
  void registerRejectsUserInfoInUrl() {
    var r = new Protocol.Registration("business", "inst-1", "https://user:pass@localhost:9442/");
    assertThrows(ApiException.class, () -> registry.register(r, "business"));
  }

  @Test
  void registerRejectsQueryInUrl() {
    var r = new Protocol.Registration("business", "inst-1", "https://localhost:9442/?foo=bar");
    assertThrows(ApiException.class, () -> registry.register(r, "business"));
  }

  @Test
  void registerRejectsPathWithSegments() {
    var r = new Protocol.Registration("business", "inst-1", "https://localhost:9442/api/v1");
    assertThrows(ApiException.class, () -> registry.register(r, "business"));
  }
}
