package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdminServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final AdminService service = new AdminService(repo);

  @Test
  void usersRequiresUserAdmin() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY"), 0);
    assertThrows(ApiException.class, () -> service.users(teacher));
  }

  @Test
  void usersReturnsPublicUserList() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("USER_ADMIN"), 0);
    when(repo.find("users", Map.of()))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    "u1",
                    "username",
                    "alice",
                    "password",
                    "$2a$12$xyz",
                    "name",
                    "Alice",
                    "role",
                    "TEACHER")));
    var result = service.users(admin);
    assertFalse(result.isEmpty());
    assertTrue(result.get(0) instanceof Map);
    assertFalse(((Map<?, ?>) result.get(0)).containsKey("password"));
  }

  @Test
  void saveUserRequiresUserAdmin() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY"), 0);
    assertThrows(ApiException.class, () -> service.saveUser(teacher, Map.of()));
  }

  @Test
  void saveUserRejectsInvalidRole() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("USER_ADMIN"), 0);
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                service.saveUser(
                    admin,
                    Map.of(
                        "username", "newuser",
                        "role", "INVALID",
                        "permissions", List.of(),
                        "department", "CS",
                        "enabled", 1,
                        "password", "StrongPass123")));
    assertEquals(400, ex.status);
  }

  @Test
  void saveUserRejectsExistingRoleChange() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("USER_ADMIN"), 0);
    when(repo.one("users", "u1"))
        .thenReturn(
            Map.of(
                "id", "u1",
                "username", "alice",
                "role", "TEACHER",
                "enabled", 1,
                "version", 0));
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                service.saveUser(
                    admin,
                    Map.of(
                        "id", "u1",
                        "username", "alice",
                        "role", "STUDENT",
                        "permissions", List.of(),
                        "department", "CS",
                        "enabled", 1,
                        "password", "StrongPass123")));
    assertEquals(400, ex.status);
  }

  @Test
  void saveUserPreventsSelfDisable() {
    var admin =
        new Models.User("a1", "admin", "Admin", "ADMIN", Set.of("USER_ADMIN", "AUDIT", "GRADE_ADMIN"), 0);
    when(repo.one("users", "a1"))
        .thenReturn(
            Map.of(
                "id", "a1",
                "username", "admin",
                "role", "ADMIN",
                "enabled", 1,
                "version", 0));
    var ex =
        assertThrows(
            ApiException.class,
            () ->
                service.saveUser(
                    admin,
                    Map.of(
                        "id", "a1",
                        "username", "admin",
                        "role", "ADMIN",
                        "permissions", List.of("AUDIT"),
                        "department", "",
                        "enabled", 0,
                        "password", "")));
    assertEquals(400, ex.status);
  }

  @Test
  void reviewRequiresAuditPermission() {
    var teacher =
        new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("QUERY"), 0);
    assertThrows(ApiException.class, () -> service.review(teacher, Map.of()));
  }

  @Test
  void demoPasswordKnownUsersHaveSimplePassword() {
    assertTrue(AdminService.demoPassword("admin").isPresent());
    assertTrue(AdminService.demoPassword("20231530").isPresent());
    assertFalse(AdminService.demoPassword("randomuser").isPresent());
  }
}
