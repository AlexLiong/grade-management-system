package edu.chd.practice.web;

import edu.chd.practice.web.config.AppProperties;
import edu.chd.practice.web.config.OcrProperties;
import edu.chd.practice.web.config.RmiProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableMethodSecurity
@EnableConfigurationProperties({AppProperties.class, RmiProperties.class, OcrProperties.class})
public class GradeWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(GradeWebApplication.class, args);
    }
}
