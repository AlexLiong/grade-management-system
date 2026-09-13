package edu.campus.gateway;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "edu.campus")
@EnableScheduling
public class GatewayApplication {
  public static void main(String[] args) {
    System.setProperty("campus.service", "gateway");
    SpringApplication app = new SpringApplication(GatewayApplication.class);
    app.run(args);
  }
}
