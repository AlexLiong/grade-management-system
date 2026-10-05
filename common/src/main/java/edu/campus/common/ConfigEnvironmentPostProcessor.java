package edu.campus.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 在 Spring 绑定配置**之前**注入密钥与数据源凭据（含整库加密的两段式口令）。
 *
 * <p>为什么需要它：{@code spring.datasource.password} 这类属性在配置绑定阶段就被读取，
 * 而密钥的读取/生成由 {@link ConfigGuard} 负责。若只依赖 {@code @Component} 初始化，
 * 顺序无法保证，会先拿到空口令去连加密库。这里挂在 Environment 准备阶段，顺序是确定的：
 *
 * <pre>
 * SpringApplication.run → EnvironmentPostProcessor（本类）→ 绑定配置 → 建数据源 → 业务 Bean
 * </pre>
 *
 * <p>注册方式见 {@code common/src/main/resources/META-INF/spring.factories}。
 * 环境变量与 {@code -D} 启动参数**优先**（用户要求把密钥注入到启动参数里），
 * 本类只在两者都没有时用密钥文件兜底，因此 IDEA 直接跑主类也能起来。
 */
public class ConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // 读取/生成密钥；生产档缺失会在这里抛出并中止启动（先把话说在日志里）
        Map<String, String> secrets = ConfigGuard.load();
        Map<String, Object> injected = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            String key = entry.getKey();
            // 环境变量/系统属性已给出的值优先，不覆盖运维的显式配置
            boolean explicit = firstNonBlank(System.getenv(key), System.getProperty(key)) != null;
            if (explicit) continue;
            injected.put(key, entry.getValue());
        }
        // 数据源凭据：默认 H2 加密文件库 + 两段式口令
        if (firstNonBlank(System.getenv("DB_URL"), System.getProperty("DB_URL"), System.getProperty("spring.datasource.url")) == null)
            injected.put("spring.datasource.url", DbCredentials.url());
        if (firstNonBlank(System.getenv("DB_USER"), System.getProperty("DB_USER"), System.getProperty("spring.datasource.username")) == null)
            injected.put("spring.datasource.username", DbCredentials.username());
        if (firstNonBlank(System.getenv("DB_PASSWORD"), System.getProperty("DB_PASSWORD"), System.getProperty("spring.datasource.password")) == null)
            injected.put("spring.datasource.password", DbCredentials.password());
        if (injected.isEmpty()) return;
        MapPropertySource source = new MapPropertySource("campusSecrets", injected);
        // addFirst：让这些值真正生效；addLast 会被 application.yml 里的同名字面量挡住
        environment.getPropertySources().addFirst(source);
        // 口令形态诊断（不含口令内容）：见 DbCredentials.describePassword 的说明
        System.out.println("[ConfigGuard] 数据源口令来源：" + DbCredentials.describePassword());
    }

    @Override
    public int getOrder() {
        // 早于 Spring Boot 的数据源/配置处理，但晚于命令行参数解析（本类不覆盖 -D）
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates)
            if (candidate != null && !candidate.isBlank()) return candidate;
        return null;
    }
}
