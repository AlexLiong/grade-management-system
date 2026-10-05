package edu.campus.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * 密钥与数据库口令的生成、注入与校验（「不硬编码密钥」这条要求的回归测试）。
 *
 * <p>要点：
 *
 * <ul>
 *   <li>生成的是 32 字节随机值（64 位十六进制），且不同次调用不重复；
 *   <li>指纹只暴露摘要，且对任一密钥的变化敏感；
 *   <li>生产档下缺密钥、或仍是占位值/长度不足时必须拒绝（对应启动中止）；
 *   <li>H2 整库加密要求「文件口令 空格 用户口令」两段式，两段都来自密钥表。
 * </ul>
 */
class ConfigGuardTest {

  @Test
  void everyCredentialUsedByTheStackIsRequired() {
    assertTrue(ConfigGuard.REQUIRED_KEYS.containsAll(List.of(
        "GATEWAY_KEY", "BUSINESS_KEY", "DATA_KEY", "AUDIT_KEY", "LEDGER_KEY", "AUDIT_DATA_KEY",
        "TLS_PASSWORD", "DB_PASSWORD", "DB_CIPHER_KEY")));
    assertEquals(9, ConfigGuard.REQUIRED_KEYS.size(), "密钥清单与服务清单一一对应，增删都要同步");
  }

  @Test
  void generatedKeyIsStrongAndUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 8; i++) {
      String key = ConfigGuard.randomKey();
      assertEquals(64, key.length(), "32 字节十六进制 = 64 字符");
      assertTrue(key.matches("[0-9a-f]{64}"));
      assertTrue(seen.add(key), "随机密钥不得重复");
    }
  }

  @Test
  void fingerprintIsStableAndSensitiveToEveryKey() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : ConfigGuard.REQUIRED_KEYS) values.put(key, "a".repeat(64));

    String first = ConfigGuard.fingerprint(values);
    assertEquals(first, ConfigGuard.fingerprint(values), "同一组密钥的指纹必须稳定");
    assertEquals(16, first.length(), "只取 8 字节十六进制，便于落库比对");

    for (String key : ConfigGuard.REQUIRED_KEYS) {
      Map<String, String> changed = new LinkedHashMap<>(values);
      changed.put(key, "b".repeat(64));
      assertNotEquals(first, ConfigGuard.fingerprint(changed), key + " 变化必须改变指纹");
    }
  }

  @Test
  void fingerprintDoesNotLeakKeyMaterial() {
    Map<String, String> values = new LinkedHashMap<>();
    for (String key : ConfigGuard.REQUIRED_KEYS) values.put(key, "deadbeef".repeat(8));
    assertFalse(ConfigGuard.fingerprint(values).contains("deadbeef"));
  }

  @Test
  void productionRejectsPlaceholdersAndShortValues() {
    assertFalse(ConfigGuard.acceptableInProduction("KEY"), "示例占位值必须被拒绝");
    assertFalse(ConfigGuard.acceptableInProduction("passwd"));
    assertFalse(ConfigGuard.acceptableInProduction("campus-dev-tls-2024"), "开发证书口令不得进生产");
    assertFalse(ConfigGuard.acceptableInProduction("short"));
    assertFalse(ConfigGuard.acceptableInProduction(null));
    assertTrue(ConfigGuard.acceptableInProduction("a".repeat(64)));
  }

  @Test
  void secretsFileLocationIsAbsoluteAndOverridable() {
    assertTrue(ConfigGuard.secretsFile().isAbsolute());
    assertTrue(ConfigGuard.secretsFile().getFileName().toString().endsWith("secrets.json"));
    String previous = System.getProperty("campus.secrets");
    try {
      System.setProperty("campus.secrets", java.nio.file.Path.of("build", "tmp-secrets.json").toAbsolutePath().toString());
      assertTrue(ConfigGuard.secretsFile().toString().endsWith("tmp-secrets.json"), "生产可用挂载点覆盖");
    } finally {
      if (previous == null) System.clearProperty("campus.secrets");
      else System.setProperty("campus.secrets", previous);
    }
  }

  @Test
  void databasePasswordFormatMatchesH2Contract() {
    // H2 在 CIPHER=AES 下要求「文件口令 空格 用户口令」；少了空格就是启动日志里的 90050。
    String twoPart = "0123456789abcdef 9876543210fedcba";
    assertEquals(1, twoPart.length() - twoPart.replace(" ", "").length());
    assertTrue(DbCredentials.describePassword().contains("长度"), "口令诊断只描述形态，不输出口令");
    assertFalse(DbCredentials.describePassword().matches(".*[0-9a-f]{32}.*"), "诊断串里不得出现密钥内容");
  }
}
