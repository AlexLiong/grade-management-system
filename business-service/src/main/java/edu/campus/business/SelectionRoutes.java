package edu.campus.business;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * 选课系统的 HTTP 路径。
 *
 * <p>只做“路径 → 权限 → 服务方法”的分发：权限在服务方法里用 {@code require} 校验，这里保持
 * 与 {@link CoreRoutes} 一致的结构，避免所有业务域改同一个 switch。
 *
 * <p>定时结算不在这里注册：{@code @EnableScheduling} 已由业务启动类 BusinessApplication 开启，
 * {@link SelectionService#autoSettleExpired()} 上的 {@code @Scheduled} 会随之生效。
 */
@Component
public class SelectionRoutes implements Routes {

  private static final Set<String> PATHS =
      Set.of(
          "/selections",
          "/selections/available",
          "/selections/my",
          "/selections/records",
          "/selections/save",
          "/selections/close",
          "/selections/cancel",
          "/selections/settle",
          "/selections/select",
          "/selections/drop",
          "/selections/batch");

  private final SelectionService selections;

  public SelectionRoutes(SelectionService selections) {
    this.selections = selections;
  }

  @Override
  public Set<String> handles() {
    return PATHS;
  }

  @Override
  public Object dispatch(Request request) {
    Models.User u = request.user();
    Map<String, Object> body = request.body();
    Map<String, String> query = request.query();
    if (!request.write())
      return switch (request.path()) {
        case "/selections" -> selections.list(u, query);
        case "/selections/available" -> selections.available(u);
        case "/selections/my" -> selections.my(u);
        case "/selections/records" -> selections.records(u, query);
        default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
      };
    return switch (request.path()) {
      case "/selections/save" -> selections.save(u, body);
      case "/selections/close" -> selections.close(u, body);
      case "/selections/cancel" -> selections.cancel(u, body);
      case "/selections/settle" -> selections.settle(u, body);
      case "/selections/select" -> selections.select(u, body);
      case "/selections/drop" -> selections.drop(u, body);
      case "/selections/batch" -> selections.batch(u, body);
      default -> throw new ApiException(404, "NOT_FOUND", "接口不存在");
    };
  }
}
