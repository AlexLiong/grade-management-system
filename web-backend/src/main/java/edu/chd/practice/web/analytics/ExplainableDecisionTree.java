package edu.chd.practice.web.analytics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Small deterministic CART classifier used to expose the complete warning decision path. */
public final class ExplainableDecisionTree {
    private ExplainableDecisionTree() {
    }

    public static Model fit(List<String> featureNames, double[][] features, boolean[] failed,
                            int maxDepth, int minLeaf) {
        if (featureNames == null || featureNames.isEmpty() || features == null || failed == null
                || features.length != failed.length || features.length < minLeaf * 2
                || maxDepth < 1 || minLeaf < 1) {
            throw new IllegalArgumentException("Decision tree training data or limits are invalid");
        }
        List<Sample> samples = new ArrayList<>();
        for (int index = 0; index < features.length; index++) {
            if (features[index] == null || features[index].length != featureNames.size()) {
                throw new IllegalArgumentException("Decision tree feature dimensions are invalid");
            }
            samples.add(new Sample(features[index].clone(), failed[index]));
        }
        return new Model(List.copyOf(featureNames), build(samples, 0, maxDepth, minLeaf));
    }

    private static Node build(List<Sample> samples, int depth, int maxDepth, int minLeaf) {
        int failures = (int) samples.stream().filter(Sample::failed).count();
        double probability = failures / (double) samples.size();
        if (depth >= maxDepth || failures == 0 || failures == samples.size() || samples.size() < minLeaf * 2) {
            return Node.leaf(probability, samples.size(), failures);
        }
        Split best = null;
        double parentImpurity = gini(failures, samples.size());
        for (int feature = 0; feature < samples.get(0).features().length; feature++) {
            int featureIndex = feature;
            List<Double> values = samples.stream().map(sample -> sample.features()[featureIndex]).distinct()
                    .sorted().toList();
            for (int index = 1; index < values.size(); index++) {
                double threshold = (values.get(index - 1) + values.get(index)) / 2.0;
                List<Sample> left = samples.stream()
                        .filter(sample -> sample.features()[featureIndex] <= threshold).toList();
                List<Sample> right = samples.stream()
                        .filter(sample -> sample.features()[featureIndex] > threshold).toList();
                if (left.size() < minLeaf || right.size() < minLeaf) {
                    continue;
                }
                int leftFailures = (int) left.stream().filter(Sample::failed).count();
                int rightFailures = failures - leftFailures;
                double weighted = left.size() * gini(leftFailures, left.size()) / samples.size()
                        + right.size() * gini(rightFailures, right.size()) / samples.size();
                double gain = parentImpurity - weighted;
                Split candidate = new Split(featureIndex, threshold, gain, left, right);
                if (best == null || candidate.betterThan(best)) {
                    best = candidate;
                }
            }
        }
        if (best == null || best.gain() <= 1.0e-12) {
            return Node.leaf(probability, samples.size(), failures);
        }
        return new Node(false, best.feature(), best.threshold(), probability, samples.size(), failures,
                build(best.left(), depth + 1, maxDepth, minLeaf),
                build(best.right(), depth + 1, maxDepth, minLeaf));
    }

    private static double gini(int failures, int total) {
        double probability = failures / (double) total;
        return 1 - probability * probability - (1 - probability) * (1 - probability);
    }

    private record Sample(double[] features, boolean failed) {
    }

    private record Split(int feature, double threshold, double gain, List<Sample> left, List<Sample> right) {
        boolean betterThan(Split other) {
            if (gain > other.gain + 1.0e-12) return true;
            if (Math.abs(gain - other.gain) > 1.0e-12) return false;
            if (feature != other.feature) return feature < other.feature;
            return threshold < other.threshold;
        }
    }

    public record Node(boolean leaf, int featureIndex, double threshold, double failureProbability,
                       int samples, int failures, Node left, Node right) {
        static Node leaf(double probability, int samples, int failures) {
            return new Node(true, -1, Double.NaN, probability, samples, failures, null, null);
        }
    }

    public record Decision(double failureProbability, List<String> path) {
    }

    public record Model(List<String> featureNames, Node root) {
        public Decision predict(double... features) {
            if (features.length != featureNames.size()) {
                throw new IllegalArgumentException("Decision tree feature count does not match the model");
            }
            List<String> path = new ArrayList<>();
            Node node = root;
            while (!node.leaf()) {
                String feature = featureNames.get(node.featureIndex());
                if (features[node.featureIndex()] <= node.threshold()) {
                    path.add(feature + " <= " + format(node.threshold()));
                    node = node.left();
                } else {
                    path.add(feature + " > " + format(node.threshold()));
                    node = node.right();
                }
            }
            path.add("叶节点: " + node.failures() + "/" + node.samples() + " 个样本未及格");
            return new Decision(node.failureProbability(), List.copyOf(path));
        }

        private String format(double value) {
            return java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString();
        }
    }
}
