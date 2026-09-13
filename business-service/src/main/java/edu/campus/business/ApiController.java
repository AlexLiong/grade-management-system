package edu.campus.business;

import edu.campus.common.*;
import jakarta.servlet.http.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
  private final AuthService auth;
  private final CourseService courses;
  private final GradeService grades;
  private final AnalyticsService analytics;
  private final AdminService admin;
  private final RemoteRepository repo;

  public ApiController(
      AuthService auth,
      CourseService courses,
      GradeService grades,
      AnalyticsService analytics,
      AdminService admin,
      RemoteRepository repo) {
    this.auth = auth;
    this.courses = courses;
    this.grades = grades;
    this.analytics = analytics;
    this.admin = admin;
    this.repo = repo;
  }

  @PostMapping("/internal/api")
  public Object dispatch(
      @RequestBody Map<String, String> envelope,
      HttpServletRequest request,
      HttpServletResponse response) {
    String path = envelope.get("path"), method = envelope.get("method");
    boolean write = "POST".equals(method);
    Map<String, Object> body =
        envelope.get("body").isBlank() ? Map.of() : Models.object(envelope.get("body"));
    Map<String, String> query = new HashMap<>();
    for (String pair : envelope.get("query").split("&")) {
      if (pair.isBlank()) continue;
      var p = pair.split("=", 2);
      query.put(
          URLDecoder.decode(p[0], StandardCharsets.UTF_8),
          p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
    }
    if (path.equals("/login") && write) return auth.login(body, request, response);
    Models.User u = auth.authenticate(request, write);
    try {
      if (!write)
        return switch (path) {
          case "/me" -> u;
          case "/courses" ->
              page(
                  courses.list(u).stream()
                      .filter(
                          c ->
                              query.getOrDefault("term", "").isBlank()
                                  || c.get("term").equals(query.get("term")))
                      .filter(
                          c -> c.get("name").toString().contains(query.getOrDefault("search", "")))
                      .toList(),
                  query);
          case "/roster" -> courses.roster(u, query.get("courseId"));
          case "/grades" -> page(grades.list(u, query.get("courseId")), query);
          case "/statistics" -> analytics.statistics(u, query.get("courseId"));
          case "/transcript" -> grades.transcript(u);
          case "/users" -> page(admin.users(u), query);
          case "/audit" -> {
            u.require("AUDIT");
            yield repo.ledger();
          }
          case "/integrity" -> admin.integrity(u);
          case "/status" -> {
            ApiException.require(u.role().equals("ADMIN"), 403, "仅管理员可查看状态");
            yield repo.status();
          }
          default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
        };
      switch (path) {
        case "/logout" -> auth.logout(request, response, u);
        case "/password" -> auth.changePassword(u, body);
        case "/grades/save" -> {
          return grades.save(u, body);
        }
        case "/grades/transition" -> grades.transition(u, body);
        case "/weights" -> courses.weights(u, body);
        case "/analysis" -> analytics.analysis(u, body);
        case "/predict" -> {
          return analytics.predict(u, Models.text(body, "courseId", 100));
        }
        case "/users/save" -> admin.saveUser(u, body);
        case "/courses/save" -> courses.saveCourse(u, body);
        case "/enrollments" -> courses.enroll(u, body);
        case "/audit/review" -> admin.review(u, body);
        case "/audit/classify" -> {
          u.require("AUDIT");
          return repo.logModel();
        }
        default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
      }
      return Map.of("ok", true);
    } catch (ApiException e) {
      if (e.status == 403)
        try {
          repo.securityEvent(u.id(), "ACCESS_DENIED", path);
        } catch (Exception ignored) {
        }
      throw e;
    }
  }

  private static Map<String, Object> page(
      List<Map<String, Object>> all, Map<String, String> query) {
    int page = Integer.parseInt(query.getOrDefault("page", "1")),
        size = Integer.parseInt(query.getOrDefault("size", "100"));
    ApiException.require(page >= 1 && page <= 100000 && size >= 1 && size <= 300, 400, "分页范围错误");
    int start = Math.min(all.size(), (page - 1) * size);
    return Map.of(
        "items",
        all.subList(start, Math.min(all.size(), start + size)),
        "total",
        all.size(),
        "page",
        page,
        "size",
        size);
  }
}
