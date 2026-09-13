package edu.campus.data;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "edu.campus")
@EnableScheduling
public class DataApplication {
  public static void main(String[] args) {
    System.setProperty("campus.service", "data");
    SpringApplication.run(DataApplication.class, args);
  }
}
