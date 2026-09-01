package edu.chd.practice.rmi.server.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

@Component
@Profile("!prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public DemoDataInitializer(JdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long users = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (users != null && users > 0) return;
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                new ClassPathResource("demo-data.sql"));
        populator.setSqlScriptEncoding("UTF-8");
        populator.setContinueOnError(false);
        populator.execute(dataSource);
    }
}
