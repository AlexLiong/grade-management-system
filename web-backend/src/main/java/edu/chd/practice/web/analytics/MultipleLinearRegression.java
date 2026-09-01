package edu.chd.practice.web.analytics;

import java.util.Arrays;
import java.util.List;

/** Ordinary least squares with pivoted elimination and a very small ridge for near-singular data. */
public final class MultipleLinearRegression {
    private static final double RIDGE = 1.0e-8;

    private MultipleLinearRegression() {
    }

    public static Model fit(List<String> featureNames, double[][] features, double[] targets) {
        if (featureNames == null || featureNames.isEmpty() || features == null || targets == null
                || features.length != targets.length || features.length <= featureNames.size() + 1) {
            throw new IllegalArgumentException("Regression requires more observations than fitted parameters");
        }
        int n = features.length;
        int p = featureNames.size() + 1;
        double[][] normal = new double[p][p];
        double[] right = new double[p];
        for (int row = 0; row < n; row++) {
            if (features[row] == null || features[row].length != p - 1 || !Double.isFinite(targets[row])) {
                throw new IllegalArgumentException("Regression matrix dimensions or values are invalid");
            }
            double[] design = design(features[row]);
            for (double value : design) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Regression features must be finite");
                }
            }
            for (int left = 0; left < p; left++) {
                right[left] += design[left] * targets[row];
                for (int column = 0; column < p; column++) {
                    normal[left][column] += design[left] * design[column];
                }
            }
        }
        for (int index = 1; index < p; index++) {
            normal[index][index] += RIDGE;
        }
        double[] coefficients = solve(copy(normal), right.clone());
        double[][] inverse = inverse(normal);
        double mean = Arrays.stream(targets).average().orElse(0);
        double residualSum = 0;
        double totalSum = 0;
        for (int row = 0; row < n; row++) {
            double residual = targets[row] - dot(coefficients, design(features[row]));
            residualSum += residual * residual;
            double centered = targets[row] - mean;
            totalSum += centered * centered;
        }
        double rSquared = totalSum < 1.0e-12 ? 1.0 : Math.max(0, 1 - residualSum / totalSum);
        double residualStandardError = Math.sqrt(residualSum / Math.max(1, n - p));
        return new Model(List.copyOf(featureNames), coefficients[0],
                Arrays.copyOfRange(coefficients, 1, coefficients.length), rSquared,
                residualStandardError, n, inverse);
    }

    private static double[] design(double[] features) {
        double[] result = new double[features.length + 1];
        result[0] = 1;
        System.arraycopy(features, 0, result, 1, features.length);
        return result;
    }

    private static double dot(double[] left, double[] right) {
        double result = 0;
        for (int index = 0; index < left.length; index++) {
            result += left[index] * right[index];
        }
        return result;
    }

    private static double[] solve(double[][] matrix, double[] right) {
        int size = right.length;
        for (int pivot = 0; pivot < size; pivot++) {
            int selected = pivot;
            for (int row = pivot + 1; row < size; row++) {
                if (Math.abs(matrix[row][pivot]) > Math.abs(matrix[selected][pivot])) {
                    selected = row;
                }
            }
            if (Math.abs(matrix[selected][pivot]) < 1.0e-12) {
                throw new IllegalArgumentException("Regression design matrix is singular");
            }
            swap(matrix, pivot, selected);
            double temporary = right[pivot];
            right[pivot] = right[selected];
            right[selected] = temporary;
            for (int row = pivot + 1; row < size; row++) {
                double factor = matrix[row][pivot] / matrix[pivot][pivot];
                for (int column = pivot; column < size; column++) {
                    matrix[row][column] -= factor * matrix[pivot][column];
                }
                right[row] -= factor * right[pivot];
            }
        }
        double[] result = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double value = right[row];
            for (int column = row + 1; column < size; column++) {
                value -= matrix[row][column] * result[column];
            }
            result[row] = value / matrix[row][row];
        }
        return result;
    }

    private static double[][] inverse(double[][] matrix) {
        int size = matrix.length;
        double[][] inverse = new double[size][size];
        for (int column = 0; column < size; column++) {
            double[] unit = new double[size];
            unit[column] = 1;
            double[] solved = solve(copy(matrix), unit);
            for (int row = 0; row < size; row++) {
                inverse[row][column] = solved[row];
            }
        }
        return inverse;
    }

    private static double[][] copy(double[][] source) {
        return Arrays.stream(source).map(double[]::clone).toArray(double[][]::new);
    }

    private static void swap(double[][] matrix, int left, int right) {
        if (left != right) {
            double[] temporary = matrix[left];
            matrix[left] = matrix[right];
            matrix[right] = temporary;
        }
    }

    public record Prediction(double value, double intervalLow, double intervalHigh) {
    }

    public record Model(List<String> featureNames, double intercept, double[] coefficients,
                        double rSquared, double residualStandardError, int sampleCount,
                        double[][] inverseNormalMatrix) {
        public Model {
            featureNames = List.copyOf(featureNames);
            coefficients = coefficients.clone();
            inverseNormalMatrix = Arrays.stream(inverseNormalMatrix).map(double[]::clone)
                    .toArray(double[][]::new);
        }

        public Prediction predict(double... features) {
            if (features.length != coefficients.length) {
                throw new IllegalArgumentException("Prediction feature count does not match the model");
            }
            double[] design = design(features);
            double value = intercept;
            for (int index = 0; index < features.length; index++) {
                value += coefficients[index] * features[index];
            }
            double leverage = 0;
            for (int row = 0; row < design.length; row++) {
                for (int column = 0; column < design.length; column++) {
                    leverage += design[row] * inverseNormalMatrix[row][column] * design[column];
                }
            }
            double margin = 1.96 * residualStandardError * Math.sqrt(Math.max(0, 1 + leverage));
            return new Prediction(value, value - margin, value + margin);
        }

        @Override public double[] coefficients() { return coefficients.clone(); }
        @Override public double[][] inverseNormalMatrix() {
            return Arrays.stream(inverseNormalMatrix).map(double[]::clone).toArray(double[][]::new);
        }
    }
}
