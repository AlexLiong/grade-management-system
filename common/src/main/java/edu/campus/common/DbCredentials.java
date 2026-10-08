package edu.campus.common;

import java.util.List;
import java.util.Locale;

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

    /**
     * 数据源的会话口令；开启整库加密时是「文件口令 + 空格 + 用户口令」。
     *
     * <p>{@code DB_PASSWORD} 被**显式**注入（环境变量或 {@code -D}）时直接采用。但要注意：
     * {@link ConfigGuard#load()} 会把密钥文件里的**单段** {@code DB_PASSWORD} 一并写进系统属性，
     * 那是「兜底加载」而不是运维的显式配置。若把它当成两段式直接交给 H2，驱动会报
     * 「Wrong password format, must be: file password &lt;space&gt; user password (90050)」。
     *
     * <p>因此这里做一次形态归一：注入值里没有空格、而 JDBC URL 又开了 {@code CIPHER=AES} 时，
     * 用 {@code DB_CIPHER_KEY} 补齐成两段式。这样「IDEA 直接跑主类」「脚本注入两段式」
     * 「运维手写两段式」三种路径得到的都是同一个有效口令。
     */
    public static String password() {
        String injected = firstNonBlank(
                System.getenv("DB_PASSWORD"), System.getProperty("DB_PASSWORD"));
        if (injected == null) return ConfigGuard.databasePassword();
        if (injected.indexOf(' ') >= 0) return injected;
        if (!cipherEnabled()) return injected;
        return ConfigGuard.secret("DB_CIPHER_KEY") + " " + injected;
    }

    /** JDBC URL 是否开启 H2 整库加密（只有此时口令才必须是两段式）。 */
    private static boolean cipherEnabled() {
        return url().toUpperCase(Locale.ROOT).contains("CIPHER=AES");
    }

    /**
     * 口令来源与形态诊断（**不打印口令本身**）。
     *
     * <p>整库加密的口令必须是「文件口令 空格 用户口令」两段式：一旦中间的空格被启动参数、
     * 环境变量或 shell 吞掉，H2 会报「Wrong password format, must be: file password &lt;space&gt;
     * user password」。这条日志给出「来源 + 长度 + 空格数」，用于快速定位这类问题。
     *
     * <p>注意「来源」一栏：{@link ConfigGuard#load()} 兜底注入的值也表现为系统属性，因此这里
     * 只区分环境变量 / 系统属性 / 密钥文件，遇到系统属性时写明「-D 或密钥文件注入」，避免把
     * 兜底值误读成运维的显式 {@code -D} 配置。
     */
    public static String describePassword() {
        String envValue = trimToNull(System.getenv("DB_PASSWORD"));
        String propValue = trimToNull(System.getProperty("DB_PASSWORD"));
        String source =
                envValue != null
                        ? "环境变量"
                        : propValue != null ? "系统属性（-D 或密钥文件注入）" : "密钥文件（两段式）";
        String value = password();
        int spaces = value.length() - value.replace(" ", "").length();
        String shape = spaces > 0 ? "两段式，可开整库加密" : "单段，该库未开启整库加密";
        return source + "，长度 " + value.length() + "，空格数 " + spaces + "，" + shape;
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
