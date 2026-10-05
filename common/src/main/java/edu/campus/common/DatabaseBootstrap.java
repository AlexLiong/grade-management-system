package edu.campus.common;

/**
 * 进程级引导：在 Spring 容器创建**之前**把密钥加载好，并处理「明文库切加密库」的一次性迁移。
 *
 * <p>为什么要有这一步：数据源（H2）需要在容器启动早期就拿到 JDBC URL 与口令，
 * 而 Spring 的 {@code @Component} 初始化顺序在配置绑定阶段并不可靠。这里由各服务的
 * {@code main} 显式调用一次，顺序就完全确定了：
 *
 * <pre>
 * main → DatabaseBootstrap.prepare() → 读/生成密钥 → 注入系统属性 → 检查明文库 → SpringApplication.run
 * </pre>
 *
 * <p>四个主类都要调用（含不连数据库的 gateway/audit/business）：它们同样需要
 * HMAC 密钥与 TLS 口令，生产档下同样应当「缺密钥就拒绝启动」。
 */
public final class DatabaseBootstrap {

    private static volatile boolean prepared;

    private DatabaseBootstrap() {
    }

    /** 幂等：同一个进程里重复调用只执行一次。 */
    public static synchronized void prepare() {
        if (prepared) return;
        prepared = true;
        // 1) 读取/生成密钥并写入系统属性；生产档缺失即在这里中止启动
        ConfigGuard.load();
        if (ConfigGuard.generatedNow())
            System.out.println(
                "[DatabaseBootstrap] 开发档：已就地生成密钥文件 "
                    + ConfigGuard.secretsFile()
                    + "（生产请改由环境变量/启动参数或密钥管理注入）");
        // 2) 开启了 CIPHER=AES 时，旧的明文库文件打不开，提前清掉并说明原因
        if (dropLegacyPlaintextDatabase(jdbcUrl()))
            System.out.println("[DatabaseBootstrap] 明文库已清理，将以加密库重新初始化演示数据。");
    }

    private static String jdbcUrl() {
        // 注意不能用 List.of(...)：它不接受 null 元素，缺环境变量时会抛 NPE
        for (String candidate : new String[] {System.getenv("DB_URL"), System.getProperty("DB_URL")}) {
            if (candidate != null && !candidate.isBlank()) return candidate;
        }
        return null;
    }

    /**
     * 明文库检测的桥接：实现留在 data-service 的 {@code SchemaCatalog}（只有它认识库文件格式，
     * 也只有它的模块里有 H2 依赖）。其它进程取不到这个类时直接跳过。
     */
    private static boolean dropLegacyPlaintextDatabase(String jdbcUrl) {
        try {
            Class<?> catalog = Class.forName("edu.campus.data.SchemaCatalog");
            var method = catalog.getDeclaredMethod("dropLegacyPlaintextDatabase", String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, jdbcUrl);
        } catch (ClassNotFoundException e) {
            return false; // 非 data-service 进程：不涉及库文件
        } catch (Exception e) {
            System.err.println("[DatabaseBootstrap] 明文库检查失败：" + e.getMessage());
            return false;
        }
    }
}
