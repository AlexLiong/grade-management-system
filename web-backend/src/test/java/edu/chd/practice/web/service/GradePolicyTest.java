package edu.chd.practice.web.service;

import edu.chd.practice.web.error.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradePolicyTest {
    @Test
    void keepsMakeupBelowCap() {
        assertThat(GradePolicy.makeupEffective(new BigDecimal("58"), new BigDecimal("57")))
                .isEqualByComparingTo("57");
    }

    @Test
    void capsMakeupAtSixty() {
        assertThat(GradePolicy.makeupEffective(new BigDecimal("58"), new BigDecimal("80")))
                .isEqualByComparingTo("60");
    }

    @Test
    void rejectsMakeupAfterPassingRegularExam() {
        assertThatThrownBy(() -> GradePolicy.makeupEffective(new BigDecimal("60"), new BigDecimal("50")))
                .isInstanceOf(ApiException.class).hasMessageContaining("正考已及格");
    }
}
