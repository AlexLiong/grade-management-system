package edu.campus.data;

import com.zaxxer.hikari.HikariDataSource;
import edu.campus.common.DbCredentials;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 数据源装配：**显式**把「整库加密的两段式口令」交给 H2。
 *
 * <p>为什么不直接用 Spring Boot 的自动配置：加密口令依赖启动时动态加载的密钥，
 * 而自动配置的取值链路（{@code spring.datasource.password} → 配置绑定 → DataSourceProperties）
 * 会与「环境变量 / -D 启动参数 / 密钥文件」三处来源的优先级相互干扰，一旦取到单段口令，
 * H2 只会报 {@code Wrong password format, must be: file password <space> user password}，
 * 很难定位。这里由 Java 显式取值并构造连接池，取值来源与顺序完全确定，日志也只打印
 * 「来源 + 长度 + 空格数」，不泄露口令本身。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource() {
        String url = DbCredentials.url();
        System.out.println(
            "[DataSourceConfig] 数据库 " + url + "；会话口令：" + DbCredentials.describePassword());
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(DbCredentials.username());
        dataSource.setPassword(DbCredentials.password());
        // 显式指定驱动，避免依赖 DriverManager 的自动发现（fat jar 内也稳定）
        if (url.contains(":h2:")) dataSource.setDriverClassName("org.h2.Driver");
        return dataSource;
    }

    /**
     * 保留 Spring Boot 的 {@code spring.datasource.*} 绑定，供排障与其它模块复用；
     * 口令仍由 {@link DbCredentials} 决定，不从这里取。
     */
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }
}
