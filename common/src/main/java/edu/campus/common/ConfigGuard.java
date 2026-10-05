package edu.campus.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

/**
 * 密钥与数据库口令的统一入口：**动态生成、集中注入、缺失即失败**。
 *
 * <p>课程要求「禁止硬编码密钥/密码」。本工程原先在 {@code application.yml} 里给每个密钥写了
 * 默认字面量 {@code KEY}、给 TLS 与数据库口令写了固定值，等于把凭据写进了版本库。现在改成：
 *
 * <ol>
 *   <li>所有密钥与口令由 {@code scripts/setup.mjs}（或本类在开发档下）用 {@link SecureRandom}
 *       生成 32 字节随机值，写入 {@code .runtime/secrets.json}；该文件被 {@code .gitignore} 忽略，
 *       不进版本库；
 *   <li>启动脚本把文件里的键值对**同时**作为环境变量与 {@code -D} 启动参数注入（用户要求的
 *       「注入到启动参数」形态），IDEA 里直接运行四个主类时由本类读文件兜底；
 *   <li>{@code CAMPUS_PROFILE=prod}（生产档）下不再兜底：缺少密钥、长度不足、或仍是占位值
 *       就**直接拒绝启动**并打印缺失清单；开发档允许缺失的 TLS 口令回落到证书生成时的口令，
 *       以便「clone 下来直接跑」。
 * </ol>
 *
 * <p>数据库整库加密（H2 {@code CIPHER=AES}）需要一个**文件级口令**，它不能由用户手输、
 * 也不能硬编码，因此与其它密钥一起生成；H2 的会话口令格式是「文件口令 + 空格 + 用户口令」，
 * 见 {@link #databasePassword()}。
 *
 * <p>{@link #dataFingerprint()} 把密钥文件的内容摘要写进 {@code schema_meta}：密钥换了以后，
 * 库里用旧密钥加密的密文（成绩 payload、审计发件箱、账本区块）已经解不开，
 * 因此启动时发现指纹不一致就整库重建，而不是等到读某一行时才报完整性失败。
 */
public final class ConfigGuard {

    private ConfigGuard() {
    }

    /** 密钥清单：名字必须与 {@code application.yml} 的占位符、启动脚本注入的变量一一对应。 */
    public static final List<String> REQUIRED_KEYS = List.of(
            "GATEWAY_KEY",
            "BUSINESS_KEY",
            "DATA_KEY",
            "AUDIT_KEY",
            "LEDGER_KEY",
            "AUDIT_DATA_KEY",
            "TLS_PASSWORD",
            "DB_PASSWORD",
            "DB_CIPHER_KEY");

    /** 开发档下允许回落到证书脚本里的固定口令（仅用于本机演示证书）。 */
    private static final String DEV_TLS_FALLBACK = "campus-dev-tls-2024";

    /**
     * 生产档下视为「未改过的占位值」：出现这些值说明运维把示例配置直接搬上了生产。
     */
    private static final Set<String> PLACEHOLDERS =
            Set.of("KEY", "passwd", "password", "changeme", "campus-dev-tls-2024");

    private static final int MIN_LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile Map<String, String> secrets;
    private static volatile boolean production;
    private static volatile boolean generatedNow;

    /** 密钥文件路径：{@code campus.secrets} / {@code CAMPUS_SECRETS} 可覆盖，默认 {@code .runtime/secrets.json}。 */
    public static Path secretsFile() {
        String configured = firstNonBlank(
                System.getenv("CAMPUS_SECRETS"), System.getProperty("campus.secrets"));
        return configured != null ? Path.of(configured).toAbsolutePath() : Settings.root().resolve("secrets.json");
    }

    /** 是否生产档：{@code CAMPUS_PROFILE=prod} 或系统属性 {@code campus.profile=prod}。 */
    public static boolean production() {
        String profile = firstNonBlank(
                System.getenv("CAMPUS_PROFILE"), System.getProperty("campus.profile"));
        return profile != null && profile.equalsIgnoreCase("prod");
    }

    /**
     * 读取密钥并注入进程：环境变量 > 系统属性 > 密钥文件 = 生成（仅开发档）。
     *
     * <p>可重复调用：{@code Settings.Loader} 与数据服务的早期引导都会调用一次。
     *
     * @return 生效的密钥表（不打印任何值）
     */
    public static synchronized Map<String, String> load() {
        if (secrets != null) return secrets;
        production = production();
        Map<String, String> fromFile = readSecretsFile();
        // 生产档：密钥文件里出现占位值/长度不足时直接拒绝——这类文件是「运维把示例配置搬上了生产」，
        // 静默放行等于把默认口令带上线。
        if (production && !fromFile.isEmpty()) {
            List<String> fileWeak = new ArrayList<>();
            for (String key : REQUIRED_KEYS) {
                String value = fromFile.get(key);
                if (value != null && !acceptableInProduction(value))
                    fileWeak.add(key + "（长度 " + value.length() + "）");
            }
            if (!fileWeak.isEmpty()) {
                System.err.println("密钥文件 " + secretsFile() + " 含占位值或弱值，生产档拒绝使用。");
                reject(List.of(), fileWeak);
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        List<String> weak = new ArrayList<>();

        for (String key : REQUIRED_KEYS) {
            String value = firstNonBlank(System.getenv(key), System.getProperty(key), fromFile.get(key));
            if (value == null && !production && "TLS_PASSWORD".equals(key)) value = DEV_TLS_FALLBACK;
            if (value == null) {
                missing.add(key);
                continue;
            }
            if (production && (PLACEHOLDERS.contains(value) || value.length() < MIN_LENGTH)) {
                weak.add(key + "（长度 " + value.length() + "）");
                continue;
            }
            resolved.put(key, value);
        }

        // 开发档缺密钥就地生成并落盘：保证「clone 下来直接跑」也能起来，
        // 同时让四个服务与 chain-worker 拿到同一份值。
        if (!production && !missing.isEmpty()) {
            Map<String, String> merged = new LinkedHashMap<>(fromFile);
            for (String key : missing) merged.put(key, random());
            writeSecretsFile(merged);
            generatedNow = true;
            for (String key : missing) resolved.put(key, merged.get(key));
            missing.clear();
            fromFile = merged;
        }

        if (!missing.isEmpty() || !weak.isEmpty()) {
            reject(missing, weak);
        }
        // 注入系统属性：这样即便运维只准备了密钥文件，所有进程内取用点也能拿到值。
        for (Map.Entry<String, String> e : resolved.entrySet())
            System.setProperty(e.getKey(), e.getValue());
        secrets = Collections.unmodifiableMap(resolved);
        return secrets;
    }

    public static String secret(String key) {
        load();
        String value = secrets.get(key);
        if (value == null) throw new IllegalStateException("缺少密钥：" + key + "（见 .runtime/secrets.json）");
        return value;
    }

    /**
     * H2 整库加密的会话口令。
     *
     * <p>H2 在 {@code CIPHER=AES} 下的 {@code user/password} 采用「文件口令 会话口令」两段格式，
     * 因此这里把生成的随机口令串起来；两段都是随机的，用户无需也不能手输。
     */
    public static String databasePassword() {
        return secret("DB_CIPHER_KEY") + " " + secret("DB_PASSWORD");
    }

    /**
     * 密钥内容指纹，用于判断「库是不是用当前密钥创建的」。
     *
     * <p>只取摘要前 16 位十六进制，写进 {@code schema_meta}；它能被用于比对，
     * 但无法反推出任何密钥。
     */
    public static String dataFingerprint() {
        return fingerprint(load());
    }

    /** 计算某一组密钥的指纹（便于测试与脚本复用）。 */
    public static String fingerprint(Map<String, String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            for (String key : REQUIRED_KEYS) canonical.append(key).append('=').append(values.getOrDefault(key, "")).append('\n');
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", hash[i]));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 本次启动是否就地生成了密钥（启动日志据此提示「已生成开发密钥」）。 */
    public static boolean generatedNow() {
        return generatedNow;
    }

    /**
     * 生成一项随机密钥：32 字节（64 位十六进制），与 {@code scripts/setup.mjs} 的
     * {@code crypto.randomBytes(32)} 保持同一强度与格式。
     */
    static String randomKey() {
        return random();
    }

    /** 生产档的「占位值/长度」判据，独立出来便于测试。 */
    static boolean acceptableInProduction(String value) {
        return value != null && !PLACEHOLDERS.contains(value) && value.length() >= MIN_LENGTH;
    }

    /** 密钥是否已加载：用于避免重复加载，也便于测试断言。 */
    public static boolean isLoaded() {
        return secrets != null;
    }

    private static void reject(List<String> missing, List<String> weak) {
        StringBuilder message = new StringBuilder();
        message.append("配置校验未通过，拒绝启动（CAMPUS_PROFILE=prod）。\n");
        if (!missing.isEmpty()) message.append("  缺少密钥：").append(String.join("、", missing)).append('\n');
        if (!weak.isEmpty())
            message.append("  仍是占位值或长度不足 ").append(MIN_LENGTH).append("：").append(String.join("、", weak)).append('\n');
        message.append("  处理方式：运行 `node scripts/setup.mjs` 生成 .runtime/secrets.json（开发），")
                .append("或由密钥管理系统注入环境变量/启动参数（生产）。\n");
        throw new IllegalStateException(message.toString());
    }

    private static Map<String, String> readSecretsFile() {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = secretsFile();
        if (!Files.exists(file)) return values;
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // 去掉 UTF-8 BOM：Windows 上用 PowerShell 的 Set-Content/Out-File 生成的 JSON 常带 BOM，
            // Jackson 会因 \uFEFF 直接报「Unexpected character」。
            if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
            JsonNode node = new ObjectMapper().readTree(text);
            node.fields().forEachRemaining(e -> {
                if (e.getValue() != null && !e.getValue().asText().isBlank())
                    values.put(e.getKey(), e.getValue().asText());
            });
        } catch (Exception e) {
            throw new IllegalStateException("密钥文件无法解析：" + file + "（" + e.getMessage() + "）");
        }
        return values;
    }

    private static void writeSecretsFile(Map<String, String> values) {
        Path file = secretsFile();
        try {
            Files.createDirectories(file.getParent());
            StringBuilder json = new StringBuilder("{\n");
            int index = 0;
            for (String key : REQUIRED_KEYS) {
                String value = values.get(key);
                if (value == null) continue;
                json.append("  \"").append(key).append("\": \"").append(value).append("\"");
                if (++index < values.size()) json.append(',');
                json.append('\n');
            }
            json.append("}\n");
            Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
            restrictPermissions(file);
            System.out.println("[ConfigGuard] 已生成开发密钥文件：" + file + "（已加入 .gitignore，不进版本库）");
        } catch (Exception e) {
            throw new IllegalStateException("写入密钥文件失败：" + file, e);
        }
    }

    /** 尽力收紧权限：POSIX 上设 0600，Windows 上依赖 ACL（失败不影响运行）。 */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (Exception ignored) {
            // Windows 或非 POSIX 文件系统：忽略
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates)
            if (candidate != null && !candidate.isBlank()) return candidate;
        return null;
    }
}
