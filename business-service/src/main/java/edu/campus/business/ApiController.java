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
  private final RemoteRepository repo;
  private final List<Routes> routes;

  public ApiController(AuthService auth, RemoteRepository repo, List<Routes> routes) {
    this.auth = auth;
    this.repo = repo;
    this.routes = routes;
  }

  @PostMapping("/internal/api")
  public Object dispatch(
      @RequestBody Map<String, String> envelope,
      HttpServletRequest request,
      HttpServletResponse response) {
    String path = envelope.get("path"), method = envelope.get("method");
    boolean write = "POST".equals(method);
    Map<String, Object> body =
        envelope.get("body") == null || envelope.get("body").isBlank()
            ? Map.of()
            : Models.object(envelope.get("body"));
    Map<String, String> query = new HashMap<>();
    for (String pair : Objects.toString(envelope.get("query"), "").split("&")) {
      if (pair.isBlank()) continue;
      var p = pair.split("=", 2);
      query.put(
          URLDecoder.decode(p[0], StandardCharsets.UTF_8),
          p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
    }
    if (path.equals("/login") && write) return auth.login(body, request, response);
    Models.User u = auth.authenticate(request, write);
    try {
      for (Routes route : routes)
        if (route.handles().contains(path))
          return route.dispatch(
              new Routes.Request(path, u, write, body, query, request, response));
      throw new ApiException(404, "NOT_FOUND", "接口不存在");
    } catch (ApiException e) {
      if (e.status == 403)
        try {
          repo.securityEvent(u.id(), "ACCESS_DENIED", path);
        } catch (Exception ignored) {
        }
      throw e;
    }
  }

  /** 统一分页包装，供各领域路由复用。 */
  public static Map<String, Object> page(
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
