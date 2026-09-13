package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import jakarta.servlet.http.*;

import java.time.Instant;
import java.util.*;

import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    public static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);
    private final RemoteRepository repo;

    public AuthService(RemoteRepository repo) {
        this.repo = repo;
    }

    public Map<String, Object> login(
            Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
        String username = Models.text(body, "username", 60),
               password = Models.text(body, "password", 128);
        String limitId = Crypto.hash(username);
        long now = Instant.now().getEpochSecond();
        var limits = repo.find("login_limits", Map.of("id", limitId));
        if (!limits.isEmpty())
            ApiException.require(
                    Long.parseLong(limits.get(0).get("locked_until").toString()) <= now,
                    429,
                    "尝试次数过多，请在 5 分钟后重试");
        var users = repo.find("users", Map.of("username", username));
        boolean valid =
                !users.isEmpty()
                        && Models.integer(users.get(0).get("enabled")) == 1
                        && PASSWORDS.matches(password, users.get(0).get("password").toString());
        if (!valid) {
            int failures = limits.isEmpty() ? 1 : Models.integer(limits.get(0).get("failures")) + 1;
            var data =
                    Map.<String, Object>of(
                            "failures", failures, "locked_until", "" + (failures >= 5 ? now + 300 : 0));
            var op =
                    limits.isEmpty()
                            ? insert(
                            "login_limits",
                            Map.of(
                                    "id",
                                    limitId,
                                    "failures",
                                    failures,
                                    "locked_until",
                                    "" + (failures >= 5 ? now + 300 : 0)))
                            : update("login_limits", limitId, data, null);
            repo.mutate(List.of(op), "anonymous", "LOGIN_FAILURE", limitId);
            throw new ApiException(401, "LOGIN_FAILED", "账号或密码错误");
        }
        var user = users.get(0);
        String token = Crypto.random(), csrf = Crypto.random();
        List<Protocol.Operation> ops = new ArrayList<>();
        if (!limits.isEmpty()) ops.add(delete("login_limits", limitId, null));
        ops.add(
                insert(
                        "sessions",
                        Map.of(
                                "id",
                                Crypto.hash(token),
                                "user_id",
                                user.get("id"),
                                "csrf",
                                csrf,
                                "expires",
                                "" + (now + 7200))));
        repo.mutate(ops, user.get("id").toString(), "LOGIN", "session");
        cookie(response, "CAMPUS_SESSION", token, true, 7200);
        cookie(response, "CAMPUS_CSRF", csrf, false, 7200);
        return Map.of("user", Models.publicUser(user), "csrf", csrf);
    }

    public Models.User authenticate(HttpServletRequest request, boolean mutation) {
        String token = cookie(request, "CAMPUS_SESSION"), csrf = request.getHeader("X-CSRF-Token");
        ApiException.require(token != null, 401, "请先登录");
        var rows = repo.find("sessions", Map.of("id", Crypto.hash(token)));
        ApiException.require(
                !rows.isEmpty()
                        && Long.parseLong(rows.get(0).get("expires").toString())
                        > Instant.now().getEpochSecond(),
                401,
                "登录已过期");
        if (mutation)
            ApiException.require(
                    Crypto.equal(rows.get(0).get("csrf").toString(), csrf), 403, "请求校验失败，请刷新页面");
        var u = repo.one("users", rows.get(0).get("user_id").toString());
        ApiException.require(Models.integer(u.get("enabled")) == 1, 403, "账号已停用");
        return new Models.User(
                u.get("id").toString(),
                u.get("username").toString(),
                u.get("name").toString(),
                u.get("role").toString(),
                new HashSet<>(Arrays.asList(u.get("permissions").toString().split(","))),
                Models.integer(u.get("version")));
    }

    public void logout(HttpServletRequest request, HttpServletResponse response, Models.User u) {
        repo.mutate(
                List.of(delete("sessions", Crypto.hash(cookie(request, "CAMPUS_SESSION")), null)),
                u.id(),
                "LOGOUT",
                "session");
        cookie(response, "CAMPUS_SESSION", "", true, 0);
        cookie(response, "CAMPUS_CSRF", "", false, 0);
    }

    public void changePassword(Models.User u, Map<String, Object> body) {
        var row = repo.one("users", u.id());
        String old = Models.text(body, "oldPassword", 128),
                next = Models.text(body, "newPassword", 128);
        demoValidatePassword(next);
        ApiException.require(PASSWORDS.matches(old, row.get("password").toString()), 400, "原密码错误");
        List<Protocol.Operation> ops = new ArrayList<>();
        ops.add(
                update(
                        "users",
                        u.id(),
                        Map.of("password", PASSWORDS.encode(next), "version", u.version() + 1),
                        u.version()));
        repo.find("sessions", Map.of("user_id", u.id()))
                .forEach(s -> ops.add(delete("sessions", s.get("id").toString(), null)));
        repo.mutate(ops, u.id(), "PASSWORD_CHANGE", u.id());
    }

    public static void validatePassword(String p) {
        ApiException.require(
                p.length() >= 12 && p.length() <= 72 && p.matches(".*[A-Za-z].*") && p.matches(".*[0-9].*"),
                400,
                "密码需为 12–72 位，包含字母和数字");
    }

    public static void demoValidatePassword(String p) {
        ApiException.require(p != null && !p.isBlank(), 400, "密码不能为空");
    }

    private static String cookie(HttpServletRequest r, String name) {
        if (r.getCookies() != null)
            for (var c : r.getCookies()) if (c.getName().equals(name)) return c.getValue();
        return null;
    }

    private static void cookie(
            HttpServletResponse r, String name, String value, boolean httpOnly, long age) {
        r.addHeader(
                "Set-Cookie",
                ResponseCookie.from(name, value)
                        .httpOnly(httpOnly)
                        .secure(true)
                        .sameSite("Strict")
                        .path("/")
                        .maxAge(age)
                        .build()
                        .toString());
    }
}
