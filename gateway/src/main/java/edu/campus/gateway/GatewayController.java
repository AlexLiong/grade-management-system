package edu.campus.gateway;

import edu.campus.common.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class GatewayController {
  private final RegistryController registry;
  private final RpcClient rpc = new RpcClient("gateway");

  public GatewayController(RegistryController registry) {
    this.registry = registry;
  }

  @RequestMapping("/api/**")
  public void proxy(HttpServletRequest request, HttpServletResponse response)
      throws java.io.IOException {
    ApiException.require(Set.of("GET", "POST").contains(request.getMethod()), 405, "不支持的请求方法");
    String origin = request.getHeader("Origin");
    ApiException.require(
        origin == null || origin.equals(Settings.get("PUBLIC_ORIGIN")), 403, "来源校验失败");
    byte[] bytes = request.getInputStream().readNBytes(1_000_001);
    ApiException.require(bytes.length <= 1_000_000, 413, "请求过大");
    Map<String, String> headers = new HashMap<>();
    for (String name : List.of("Cookie", "X-CSRF-Token"))
      if (request.getHeader(name) != null) headers.put(name, request.getHeader(name));
    headers.put("X-Client-IP", request.getRemoteAddr());
    var envelope =
        Map.of(
            "path",
            request.getRequestURI().substring(4),
            "method",
            request.getMethod(),
            "query",
            Objects.toString(request.getQueryString(), ""),
            "body",
            new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    var result =
        rpc.raw(
            registry.choose("business") + "/internal/api",
            "POST",
            Settings.json(envelope),
            headers);
    response.setStatus(result.statusCode());
    response.setContentType("application/json;charset=UTF-8");
    result.headers().allValues("set-cookie").forEach(v -> response.addHeader("Set-Cookie", v));
    response.getWriter().write(result.body());
  }
}
