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
}
