package edu.campus.business;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "edu.campus")
@EnableScheduling
public class BusinessApplication {
  public static void main(String[] args) {
    System.setProperty("campus.service", "business");
    SpringApplication app = new SpringApplication(BusinessApplication.class);
    app.run(args);
  }
}
