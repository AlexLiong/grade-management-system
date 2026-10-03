package edu.campus.business;

import jakarta.servlet.http.*;
import java.util.*;

/**
 * 领域路由注册表。
 *
 * <p>每个领域服务把自己的 HTTP 路径实现成一个 {@code Routes} 实现并以 Spring 组件注册；
 * {@link ApiController} 只负责统一信封解析、会话/CSRF 校验、权限拒绝审计与分页，
 * 然后按 {@link #handles()} 声明的路径把请求交给唯一匹配的实现。
 *
 * <p>这样新增业务域（组织管理、选课等）无需修改原来的分发代码，避免所有人改同一个
 * switch，也让每个域的读写分支可以各自独立测试。
 */
public interface Routes {

  /** 本实现负责的请求路径（与网关去掉 {@code /api} 前缀后的路径一致）。 */
  Set<String> handles();

  /**
   * 处理一个请求。
   *
   * @param request 已解析的信封
   * @return 响应载荷；返回 {@code null} 表示按统一成功响应 {@code {"ok":true}} 处理
   */
  Object dispatch(Request request);

  /**
   * 请求上下文。
   *
   * @param path 已去掉 {@code /api} 前缀的请求路径
   * @param user 已通过会话与 CSRF 校验的用户
   * @param write true 表示 POST（写操作），false 表示 GET（读操作）
   * @param body 解析后的 JSON 请求体，读操作时为空 Map
   * @param query 已解码的查询参数，未提供的键不会出现
   * @param servletRequest 原始请求，供登录/登出等需要读写 Cookie 的场景使用
   * @param servletResponse 原始响应，供写 Cookie 的场景使用
   */
  record Request(
      String path,
      Models.User user,
      boolean write,
      Map<String, Object> body,
      Map<String, String> query,
      HttpServletRequest servletRequest,
      HttpServletResponse servletResponse) {}
}
