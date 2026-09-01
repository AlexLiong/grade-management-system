package edu.chd.practice.web.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultipleLinearRegressionTest {
    @Test
    void recoversKnownTwoFeatureEquation() {
        double[][] features = {
                {10, 20}, {20, 5}, {30, 40}, {40, 10}, {50, 60}, {60, 30}, {70, 80}, {80, 50}
        };
        double[] targets = java.util.Arrays.stream(features)
                .mapToDouble(row -> 0.6 * row[0] + 0.3 * row[1] + 5).toArray();

        MultipleLinearRegression.Model model = MultipleLinearRegression.fit(
                List.of("usual", "lab"), features, targets);

        assertThat(model.intercept()).isCloseTo(5, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(model.coefficients()[0]).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(model.coefficients()[1]).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(model.rSquared()).isCloseTo(1, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(model.predict(55, 45).value()).isCloseTo(51.5,
                org.assertj.core.data.Offset.offset(1e-5));
    }
}
