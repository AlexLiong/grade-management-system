package edu.chd.practice.web.service;

import edu.chd.practice.web.error.ApiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradeReversionPayloadTest {
    @Test
    void smallReversionClearsMakeupButPreservesRegularGrade() {
        GradeReversionService.GradePayload submitted = new GradeReversionService.GradePayload(
                Map.of("DAILY", new BigDecimal("55"), "FINAL", new BigDecimal("57")),
                new BigDecimal("56.40"), new BigDecimal("85"), new BigDecimal("60"),
                new BigDecimal("60"), "SUBMITTED");

        GradeReversionService.GradePayload reset = GradeReversionService.resetMakeup(submitted);

        assertThat(reset.componentScores()).isEqualTo(submitted.componentScores());
        assertThat(reset.regularScore()).isEqualByComparingTo("56.40");
        assertThat(reset.makeupRawScore()).isNull();
        assertThat(reset.makeupEffectiveScore()).isNull();
        assertThat(reset.makeupStatus()).isNull();
        assertThat(reset.finalScore()).isEqualByComparingTo("56.40");
    }

    @Test
    void largeTargetRejectsAnyInvalidTokenInsteadOfSilentlyShrinkingTheBatch() {
        assertThatThrownBy(() -> GradeReversionService.parseGradeIds("gradeIds:grade-1,not valid,grade-2"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(400);
                    assertThat(error.code()).isEqualTo("TARGET_FILTER_INVALID");
                });
        assertThatThrownBy(() -> GradeReversionService.parseGradeIds("gradeIds:grade-1,,grade-2"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void largeTargetDeduplicatesAndSortsOnlyAfterValidation() {
        assertThat(GradeReversionService.parseGradeIds("gradeIds:grade-2,grade-1,grade-2"))
                .containsExactly("grade-1", "grade-2");
    }
}
