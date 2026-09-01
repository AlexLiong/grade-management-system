package edu.chd.practice.rmi.contract;

import edu.chd.practice.rmi.contract.dto.EncryptedGradePayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GradeCipherCodecTest {
    @Test
    void deterministicVectorAndRoundTripRemainCompatible() {
        byte[] master = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
        byte[] nonce = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
        String json = "{\"componentScores\":{\"finalExam\":85.00},\"rawScore\":85.00,"
                + "\"effectiveScore\":85.00,\"examType\":\"REGULAR\"}";

        EncryptedGradePayload encrypted = GradeCipherCodec.encrypt(master, "42", json, nonce);

        assertEquals("AAECAwQFBgcICQoL", encrypted.getNonce());
        assertTrue(encrypted.getCiphertext().startsWith("v1."));
        assertTrue(GradeCipherCodec.verify(master, "42", encrypted));
        assertEquals(json, GradeCipherCodec.decrypt(master, "42", encrypted));
        assertFalse(GradeCipherCodec.verify(master, "43", encrypted));
    }
}
