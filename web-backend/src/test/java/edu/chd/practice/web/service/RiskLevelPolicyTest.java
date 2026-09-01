package edu.chd.practice.web.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskLevelPolicyTest {
    @Test
    void scoreBelowFiftyFiveIsHighRisk() {
        assertThat(RiskLevelPolicy.level(54.99, 0)).isEqualTo("HIGH");
    }

    @Test
    void boundaryFiftyFiveIsMediumRisk() {
        assertThat(RiskLevelPolicy.level(55.0, 0)).isEqualTo("MEDIUM");
    }
}
