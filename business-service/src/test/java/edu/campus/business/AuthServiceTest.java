package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final AuthService auth = new AuthService(repo);

  @Test
  void loginRejectsMissingPassword() {
    var ex =
        assertThrows(ApiException.class, () -> auth.login(Map.of("username", "admin"), null, null));
    assertEquals(400, ex.status);
  }

  @Test
  void loginLocksAfterFiveFailures() {
    String username = "lockeduser";
    String limitId = Crypto.hash(username);
    long now = java.time.Instant.now().getEpochSecond();
    // Simulate 5 previous failures
    when(repo.find("login_limits", Map.of("id", limitId)))
        .thenReturn(List.of(Map.of("failures", 5, "locked_until", String.valueOf(now + 300))));

    var ex =
        assertThrows(
            ApiException.class, () -> auth.login(Map.of("username", username, "password", "x"), null, null));
    assertEquals(429, ex.status);
  }

  @Test
  void loginIncrementsFailureCounter() {
    when(repo.find("login_limits", Map.of("id", Crypto.hash("u1")))).thenReturn(List.of());
    when(repo.find("users", Map.of("username", "u1"))).thenReturn(List.of());

    assertThrows(ApiException.class, () -> auth.login(Map.of("username", "u1", "password", "wrong"), null, null));
    verify(repo, atLeastOnce()).mutate(anyList(), eq("anonymous"), eq("LOGIN_FAILURE"), any());
  }

  @Test
  void authenticateRequiresToken() {
    jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
    when(request.getCookies()).thenReturn(null);
    var ex = assertThrows(ApiException.class, () -> auth.authenticate(request, false));
    assertEquals(401, ex.status);
  }

  @Test
  void authenticateRejectsExpiredSession() {
    jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
    jakarta.servlet.http.Cookie cookie = mock(jakarta.servlet.http.Cookie.class);
    when(cookie.getName()).thenReturn("CAMPUS_SESSION");
    when(cookie.getValue()).thenReturn("tok");
    when(request.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[] {cookie});
    when(repo.find("sessions", Map.of("id", Crypto.hash("tok"))))
        .thenReturn(List.of(Map.of("id", "s1", "expires", "1")));

    var ex = assertThrows(ApiException.class, () -> auth.authenticate(request, false));
    assertEquals(401, ex.status);
  }

  @Test
  void changePasswordRequiresOldPasswordMatch() {
    Models.User u =
        new Models.User("u1", "alice", "Alice", "TEACHER", Set.of("QUERY"), 0);
    var oldHash = AuthService.PASSWORDS.encode("oldpass");
    when(repo.one("users", "u1")).thenReturn(Map.of("id", "u1", "password", oldHash));
    var ex =
        assertThrows(
            ApiException.class,
            () -> auth.changePassword(u, Map.of("oldPassword", "wrong", "newPassword", "newpass1")));
    assertEquals(400, ex.status);
  }

  @Test
  void changePasswordRevokesAllSessions() {
    Models.User u =
        new Models.User("u1", "alice", "Alice", "TEACHER", Set.of("QUERY"), 0);
    var oldHash = AuthService.PASSWORDS.encode("oldpass");
    when(repo.one("users", "u1")).thenReturn(Map.of("id", "u1", "password", oldHash, "version", 0));
    when(repo.find("sessions", Map.of("user_id", "u1"))).thenReturn(List.of(Map.of("id", "s1")));
    auth.changePassword(u, Map.of("oldPassword", "oldpass", "newPassword", "newpass1"));
    verify(repo, atLeastOnce()).mutate(anyList(), eq("u1"), eq("PASSWORD_CHANGE"), eq("u1"));
  }

  @Test
  void logoutDeletesSessionAndClearsCookies() {
    Models.User u =
        new Models.User("u1", "alice", "Alice", "TEACHER", Set.of("QUERY"), 0);
    jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
    jakarta.servlet.http.Cookie cookie = mock(jakarta.servlet.http.Cookie.class);
    when(cookie.getName()).thenReturn("CAMPUS_SESSION");
    when(cookie.getValue()).thenReturn("tok");
    when(request.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[] {cookie});
    jakarta.servlet.http.HttpServletResponse response = mock(jakarta.servlet.http.HttpServletResponse.class);
    auth.logout(request, response, u);
    verify(repo).mutate(anyList(), eq("u1"), eq("LOGOUT"), eq("session"));
  }
}
