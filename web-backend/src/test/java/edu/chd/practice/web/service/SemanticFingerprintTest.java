package edu.chd.practice.web.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticFingerprintTest {
    @Test
    void canonicalizesMapOrderAndDecimalScaleButKeepsListOrderAndPayloadChanges() {
        Map<String, BigDecimal> left = new LinkedHashMap<>();
        left.put("FINAL", new BigDecimal("80.0"));
        left.put("DAILY", new BigDecimal("90"));
        Map<String, BigDecimal> right = new LinkedHashMap<>();
        right.put("DAILY", new BigDecimal("90.00"));
        right.put("FINAL", new BigDecimal("80"));

        String first = SemanticFingerprint.of("draft", List.of("e-1", "e-2"), left);
        String equivalent = SemanticFingerprint.of("draft", List.of("e-1", "e-2"), right);

        assertThat(equivalent).isEqualTo(first);
        assertThat(SemanticFingerprint.of("draft", List.of("e-2", "e-1"), right)).isNotEqualTo(first);
        assertThat(SemanticFingerprint.of("draft", List.of("e-1", "e-2"), Map.of("FINAL", 81)))
                .isNotEqualTo(first);
        assertThat(first).hasSize(64);
    }
}
