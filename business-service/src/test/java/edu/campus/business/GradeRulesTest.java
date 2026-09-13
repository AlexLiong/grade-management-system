package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class GradeRulesTest {
  private final Map<String, Object> weights = CourseService.defaultWeights();

  @Test
  void weightedTotalAndMakeupAreSeparate() {
    var scores =
        new LinkedHashMap<String, Object>(
            Map.of("regular", 50, "lab", 50, "finalExam", 66, "makeup", 87));
    assertEquals(58, Models.total(scores, weights));
    assertEquals(60, Models.effective(scores, weights));
    scores.put("makeup", 57);
    assertEquals(58, Models.effective(scores, weights));
    assertEquals(57, Models.number(scores.get("makeup")));
  }

  @Test
  void incompleteDraftAllowedButSubmissionRejected() {
    var scores = Map.<String, Object>of("regular", 50, "lab", 60);
    assertNull(Models.total(scores, weights));
    GradeService.validateScores(scores, weights, false);
    assertThrows(ApiException.class, () -> GradeService.validateScores(scores, weights, true));
  }

  @Test
  void rejectsOutOfRangeAndNonFinite() {
    for (double score : new double[] {-1, 101, Double.NaN, Double.POSITIVE_INFINITY})
      assertThrows(
          ApiException.class,
          () -> GradeService.validateScores(Map.of("regular", score), weights, false));
  }

  @Test
  void makeupOnlyForFailingRegularExam() {
    assertThrows(
        ApiException.class,
        () ->
            GradeService.validateScores(
                Map.of("regular", 90, "lab", 90, "finalExam", 90, "makeup", 50), weights, true));
  }

  @Test
  void unknownFieldsRejected() {
    assertThrows(
        ApiException.class,
        () -> GradeService.validateScores(Map.of("sql", "DROP TABLE grades"), weights, false));
  }

  @Test
  void passwordPolicyAndHash() {
    assertThrows(ApiException.class, () -> AuthService.validatePassword("123456"));
    AuthService.validatePassword("StrongExample123");
    String hash = AuthService.PASSWORDS.encode("StrongExample123");
    assertTrue(AuthService.PASSWORDS.matches("StrongExample123", hash));
    assertFalse(AuthService.PASSWORDS.matches("wrong", hash));
  }
}
