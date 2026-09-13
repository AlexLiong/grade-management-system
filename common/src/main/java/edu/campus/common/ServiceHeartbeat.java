package edu.campus.common;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ServiceHeartbeat {
  @Value("${server.port}")
  private int port;
  @Value("${server.address}")
  private String serverAddress;
  @Value("${campus.gateway-url}")
  private String gatewayUrl;
  private final String id = UUID.randomUUID().toString();

  @Scheduled(initialDelay = 500, fixedDelay = 5000)
  public void beat() {
    String service = System.getProperty("campus.service");
    if ("gateway".equals(service)) return;
    try {
      new RpcClient(service)
          .postUrl(
              gatewayUrl + "/internal/register",
              new Protocol.Registration(
                  service,
                  id,
                  String.format("https://%s:%d", serverAddress, port)),
              Boolean.class);
    } catch (Exception e) {
        e.printStackTrace();
        System.err.println("Registration deferred: " + service);
    }
  }
}
