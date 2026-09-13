package edu.campus.audit;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "edu.campus")
@EnableScheduling
public class AuditApplication {
  public static void main(String[] args) {
    System.setProperty("campus.service", "audit");
    SpringApplication app = new SpringApplication(AuditApplication.class);
    app.run(args);
  }
}
