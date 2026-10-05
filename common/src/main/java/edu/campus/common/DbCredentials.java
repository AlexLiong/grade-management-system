package edu.campus.common;

import java.util.List;

/**
 * 数据源凭据的统一解析入口。
 *
 * <p>为什么不用配置文件里的 {@code ${DB_PASSWORD:}} 占位符：数据源在 Spring 容器启动早期就
 * 读取凭据，而密钥的加载/生成由 {@link ConfigGuard} 负责，两者的先后顺序在配置绑定阶段容易被
 * 环境里的同名单值干扰（例如运维单独导出了 {@code DB_PASSWORD}，但整库加密需要的是
 * 「文件口令 用户口令」两段式）。这里把解析放在 Java 端，顺序与取值完全确定：
 *
 * <ol>
 *   <li>{@code DB_PASSWORD} 环境变量或 {@code -DDB_PASSWORD=...} 优先——启动脚本与 IDEA
 *       运行配置就是走这条「注入到启动参数」的路径，允许运维直接指定；
 *   <li>否则由 {@link ConfigGuard} 从密钥文件读取，并拼成 H2 需要的两段式口令。
 * </ol>
 */
public final class DbCredentials {

    private DbCredentials() {
    }

    /** 数据源的会话口令；开启整库加密时是「文件口令 + 空格 + 用户口令」。 */
    public static String password() {
        String injected = firstNonBlank(
                System.getenv("DB_PASSWORD"), System.getProperty("DB_PASSWORD"));
        if (injected != null) return injected;
        return ConfigGuard.databasePassword();
    }

    /**
     * 口令来源与形态诊断（**不打印口令本身**）。
     *
     * <p>整库加密的口令必须是「文件口令 空格 用户口令」两段式：一旦中间的空格被启动参数、
     * 环境变量或 shell 吞掉，H2 会报「Wrong password format, must be: file password &lt;space&gt;
     * user password」。这条日志给出「来源 + 长度 + 空格数」，用于快速定位这类问题。
     */
    public static String describePassword() {
        String envValue = trimToNull(System.getenv("DB_PASSWORD"));
        String propValue = trimToNull(System.getProperty("DB_PASSWORD"));
        String source = envValue != null ? "环境变量" : propValue != null ? "-D 启动参数" : "密钥文件（两段式）";
        String value = password();
        int spaces = value.length() - value.replace(" ", "").length();
        return source + "，长度 " + value.length() + "，空格数 " + spaces;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 数据源用户名；默认 {@code sa}，生产可通过环境变量换成最小权限账号。 */
    public static String username() {
        String injected = firstNonBlank(
                System.getenv("DB_USER"), System.getProperty("DB_USER"));
        return injected != null ? injected : "sa";
    }

    /**
     * JDBC URL。默认 H2 文件库并开启 {@code CIPHER=AES}（整库加密）；
     * 换成 MySQL/SQLServer/Oracle 时用 {@code DB_URL} 覆盖即可。
     */
    public static String url() {
        String injected = firstNonBlank(
                System.getenv("DB_URL"), System.getProperty("DB_URL"));
        return injected != null
                ? injected
                : "jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE";
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates)
            if (candidate != null && !candidate.isBlank()) return candidate;
        return null;
    }

    /** H2 的两段式口令格式说明，供日志与文档引用。 */
    public static List<String> cipherParts() {
        return List.of(ConfigGuard.secret("DB_CIPHER_KEY"), ConfigGuard.secret("DB_PASSWORD"));
    }
}
