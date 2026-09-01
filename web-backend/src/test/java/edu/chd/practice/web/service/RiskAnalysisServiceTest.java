package edu.chd.practice.web.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAnalysisServiceTest {
    @Test
    void historicalFinalMeanWarningIncludesExactTenPointBoundary() {
        assertThat(RiskAnalysisService.significantlyBelowHistoricalFinalMean(60.0, 70.0)).isTrue();
        assertThat(RiskAnalysisService.significantlyBelowHistoricalFinalMean(60.01, 70.0)).isFalse();
        assertThat(RiskAnalysisService.significantlyBelowHistoricalFinalMean(80.0, 70.0)).isFalse();
        assertThat(RiskAnalysisService.significantlyBelowHistoricalFinalMean(Double.NaN, 70.0)).isFalse();
    }
}
