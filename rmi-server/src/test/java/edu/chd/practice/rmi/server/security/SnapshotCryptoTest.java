package edu.chd.practice.rmi.server.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshotCryptoTest {
    @Test
    void derivesPurposeSpecificKeysAndRoundTripsSnapshot() {
        byte[] master = new byte[32];
        Arrays.fill(master, (byte) 11);
        byte[] snapshotKey = KeyDerivation.hmacSha256(master, "grade-ledger-snapshot-aes-v1");
        byte[] ledgerKey = KeyDerivation.hmacSha256(master, "integrity-ledger-hmac-v1");
        SnapshotCrypto crypto = new SnapshotCrypto(master);

        assertFalse(Arrays.equals(master, snapshotKey));
        assertFalse(Arrays.equals(snapshotKey, ledgerKey));
        String encrypted = crypto.encrypt("{\"grade\":\"g-1\"}");
        assertEquals("{\"grade\":\"g-1\"}", crypto.decrypt(encrypted));

        byte[] otherMaster = master.clone();
        otherMaster[0] ^= 1;
        assertThrows(SecurityException.class, () -> new SnapshotCrypto(otherMaster).decrypt(encrypted));
    }
}
