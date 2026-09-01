package edu.chd.practice.rmi.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.chd.practice.rmi.server.config.LedgerProperties;
import edu.chd.practice.rmi.server.config.RmiProperties;
import edu.chd.practice.rmi.server.config.SecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties({RmiProperties.class, SecurityProperties.class, LedgerProperties.class})
public class RmiDataServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(RmiDataServerApplication.class, args);
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
