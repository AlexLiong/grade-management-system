package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.Settings;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnalyticsRulesTest {
  private List<Map<String, Object>> detect(double[] scores, Object makeup, boolean history) {
    var repo = mock(RemoteRepository.class);
    var current =
        Map.<String, Object>of(
            "id",
            "now",
            "code",
            "CS",
            "term",
            "2026-1",
            "weights",
            Settings.json(CourseService.defaultWeights()));
    when(repo.one("courses", "now")).thenReturn(current);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < scores.length; i++) {
      var payload =
          new LinkedHashMap<String, Object>(
              Map.of("regular", scores[i], "lab", scores[i], "finalExam", scores[i]));
      if (i == 0 && makeup != null) payload.put("makeup", makeup);
      rows.add(Map.of("student_id", "s" + i, "payload", Settings.json(payload)));
    }
    when(repo.find("grades", Map.of("course_id", "now"))).thenReturn(rows);
    List<Map<String, Object>> courses = new ArrayList<>();
    courses.add(current);
    if (history)
      for (int year : List.of(2024, 2025)) {
        String id = "old" + year;
        courses.add(Map.of("id", id, "term", year + "-1", "weights", current.get("weights")));
        when(repo.find("grades", Map.of("course_id", id, "state", "SUBMITTED")))
            .thenReturn(
                List.of(
                    Map.of(
                        "student_id",
                        "s0",
                        "payload",
                        Settings.json(Map.of("regular", 90, "lab", 90, "finalExam", 90)))));
      }
    when(repo.find("courses", Map.of("code", "CS"))).thenReturn(courses);
    return new AnalyticsService(repo, mock(CourseService.class)).anomalies("now");
  }

  @Test
  void lowClassMeanIsFlagged() {
    var alerts = detect(new double[] {10, 10, 10, 10, 10}, null, false);
    assertTrue(alerts.stream().anyMatch(a -> a.get("rule").equals("CLASS_MEAN")));
  }

  @Test
  void outlierAndLowerPercentileAreFlagged() {
    var alerts = detect(new double[] {0, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80, 80}, null, false);
    assertTrue(
        alerts.stream()
            .anyMatch(a -> a.get("rule").equals("THREE_SIGMA") && a.get("studentId").equals("s0")));
    assertTrue(alerts.stream().anyMatch(a -> a.get("rule").equals("PERCENTILE_05")));
  }

  @Test
  void historicalShiftAndExtremeMakeupAreFlagged() {
    var alerts = detect(new double[] {30, 70, 75, 80, 85}, 99, true);
    assertTrue(alerts.stream().anyMatch(a -> a.get("rule").equals("HISTORY_SHIFT")));
    assertTrue(alerts.stream().anyMatch(a -> a.get("rule").equals("MAKEUP_EXTREME")));
  }

  @Test
  void ordinaryScoresDoNotTriggerAlerts() {
    assertTrue(detect(new double[] {70, 72, 74, 76, 78}, null, false).isEmpty());
  }
}
