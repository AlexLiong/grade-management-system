package edu.chd.practice.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class GradeWebApplicationTest {
    @Test
    void disablesTheUnusedGeneratedDefaultUser() {
        SpringBootApplication configuration =
                GradeWebApplication.class.getAnnotation(SpringBootApplication.class);

        assertThat(configuration.exclude()).contains(UserDetailsServiceAutoConfiguration.class);
    }
}
