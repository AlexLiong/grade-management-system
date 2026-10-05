package edu.campus.audit;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "edu.campus")
@EnableScheduling
public class AuditApplication {
  public static void main(String[] args) {
    // 先加载/注入密钥并处理明文库迁移，再交给 Spring：顺序确定，且生产档缺密钥会在此中止
    edu.campus.common.DatabaseBootstrap.prepare();
    System.setProperty("campus.service", "audit");
    SpringApplication app = new SpringApplication(AuditApplication.class);
    app.run(args);
  }
}
