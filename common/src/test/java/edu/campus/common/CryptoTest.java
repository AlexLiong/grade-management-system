package edu.campus.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class CryptoTest {
  private final String key = Base64.getEncoder().encodeToString(new byte[32]);

  @Test
  void encryptedGradesBindIdentityVersionAndState() {
    String encrypted = Crypto.encrypt(key, "grade|course|student|DRAFT|1", "{\"score\":58}");
    assertEquals("{\"score\":58}", Crypto.decrypt(key, "grade|course|student|DRAFT|1", encrypted));
    assertThrows(
        ApiException.class,
        () -> Crypto.decrypt(key, "grade|course|student|SUBMITTED|1", encrypted));
    assertThrows(
        ApiException.class, () -> Crypto.decrypt(key, "other|course|student|DRAFT|1", encrypted));
  }

  @Test
  void randomNoncesPreventDeterministicCiphertext() {
    assertNotEquals(Crypto.encrypt(key, "id", "58"), Crypto.encrypt(key, "id", "58"));
  }

  @Test
  void corruptionIsRejected() {
    String encrypted = Crypto.encrypt(key, "id", "score");
    assertThrows(
        ApiException.class,
        () -> Crypto.decrypt(key, "id", encrypted.substring(0, encrypted.length() - 3) + "AAA"));
  }

  @Test
  void signaturesAreMessageBound() {
    assertNotEquals(Crypto.hmac(key, "SUBMIT"), Crypto.hmac(key, "DELETE"));
    assertFalse(Crypto.equal(null, "a"));
    assertTrue(Crypto.equal("same", "same"));
  }

  // ---------------------------------------------------------------- 派生密钥缓存

  /**
   * 缓存必须只影响速度、不影响结果：同一密文反复解密要给出同样的明文，不同的 AAD 仍然要失败。
   *
   * <p>密钥由 {@code (password, salt)} 决定，而 salt 存在密文里，因此缓存不会放宽校验边界——
   * 但仍然要有一条用例钉住「缓存命中后校验力度不变」，因为一旦这里出问题就是静默的安全退化。
   */
  @Test
  void cachedKeyDerivationStillBindsAad() {
    String encrypted = Crypto.encrypt(key, "grade|c1|s1|SUBMITTED|1", "{\"total\":88}");
    for (int i = 0; i < 5; i++) // 首次算密钥，之后全部命中缓存
      assertEquals(
          "{\"total\":88}", Crypto.decrypt(key, "grade|c1|s1|SUBMITTED|1", encrypted), "第 " + i + " 次");
    assertThrows(
        ApiException.class, () -> Crypto.decrypt(key, "grade|c1|s2|SUBMITTED|1", encrypted));
    assertThrows(
        ApiException.class, () -> Crypto.decrypt(key, "grade|c1|s1|DRAFT|1", encrypted));
  }

  /** 不同口令（不同密钥）不能互相解开；缓存按 (口令, salt) 区分，不能串味。 */
  @Test
  void cacheIsPartitionedByPassword() {
    // 注意不能再用 new byte[32]——全零字节的 Base64 与上面的 key 完全相同，会退化成「同密钥」。
    String other = Base64.getEncoder().encodeToString("another-32-byte-key-for-tests!".getBytes());
    assertNotEquals(key, other);
    String encrypted = Crypto.encrypt(key, "id", "secret");
    assertEquals("secret", Crypto.decrypt(key, "id", encrypted));
    assertThrows(ApiException.class, () -> Crypto.decrypt(other, "id", encrypted));
  }
}
