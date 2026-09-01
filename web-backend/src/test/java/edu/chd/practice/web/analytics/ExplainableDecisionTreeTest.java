package edu.chd.practice.web.analytics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainableDecisionTreeTest {
    @Test
    void learnsDeterministicSplitAndExportsPath() {
        double[][] features = {{20, 20}, {30, 30}, {45, 40}, {70, 70}, {80, 75}, {90, 90}};
        boolean[] failed = {true, true, true, false, false, false};

        ExplainableDecisionTree.Model model = ExplainableDecisionTree.fit(
                List.of("usualScore", "labScore"), features, failed, 3, 2);

        assertThat(model.root().leaf()).isFalse();
        ExplainableDecisionTree.Decision low = model.predict(25, 25);
        ExplainableDecisionTree.Decision high = model.predict(85, 85);
        assertThat(low.failureProbability()).isGreaterThan(high.failureProbability());
        assertThat(low.path()).anyMatch(step -> step.contains("usualScore"));
        assertThat(low.path().get(low.path().size() - 1)).contains("叶节点");
    }
}
