package edu.chd.practice.web.service;

public final class RiskLevelPolicy {
    private RiskLevelPolicy() {
    }

    public static String level(double predictedFinal, double treeFailureProbabilityPercent) {
        if (predictedFinal < 55 || treeFailureProbabilityPercent >= 65) return "HIGH";
        if (predictedFinal < 60 || treeFailureProbabilityPercent >= 35) return "MEDIUM";
        return "LOW";
    }
}
