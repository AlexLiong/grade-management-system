package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.ApiException;
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

  // ------------------------------------------------- 学业预警：预测结果（R5）

  /**
   * 预测对象是「期末还没考」的学生：已有平时与实验成绩、期末尚未录入。
   *
   * <p>这条用例是为了锁住一个真实缺陷：筛选条件原本写成「要求 finalExam 非空」，
   * 结果一个学生都筛不出来，页面上永远看不到预测结果（接口返回 200 但 results 为空）。
   */
  @Test
  void predictReturnsRowsForStudentsWithoutFinalExamYet() {
    var repo = mock(RemoteRepository.class);
    var current =
        Map.<String, Object>of(
            "id", "now", "code", "CS", "term", "2026-1",
            "weights", Settings.json(CourseService.defaultWeights()));
    var teacher = new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("PREDICT"), 0);
    var courses = mock(CourseService.class);
    when(courses.access(teacher, "now", "PREDICT")).thenReturn(current);
    // 三个学年的历史成绩作为训练样本（每次 10 条 → 30 条样本、3 个年份）
    var history = new ArrayList<Map<String, Object>>();
    history.add(current);
    for (int year : List.of(2023, 2024, 2025)) {
      String id = "old" + year;
      history.add(Map.of("id", id, "term", year + "-1", "weights", current.get("weights")));
      var rows = new ArrayList<Map<String, Object>>();
      for (int i = 0; i < 10; i++)
        rows.add(
            Map.of(
                "student_id", "s" + i,
                "payload",
                Settings.json(Map.of("regular", 50 + i, "lab", 55 + i, "finalExam", 60 + i))));
      when(repo.find("grades", Map.of("course_id", id, "state", "SUBMITTED"))).thenReturn(rows);
    }
    when(repo.find("courses", Map.of("code", "CS"))).thenReturn(history);
    // 当前学期：两名学生已有平时/实验但期末未录入 → 应该被预测；一名已录入期末 → 跳过
    when(repo.find("grades", Map.of("course_id", "now")))
        .thenReturn(
            List.of(
                Map.of("student_id", "s0", "payload", Settings.json(Map.of("regular", 60, "lab", 65))),
                Map.of("student_id", "s1", "payload", Settings.json(Map.of("regular", 75, "lab", 78))),
                Map.of("student_id", "s2",
                    "payload", Settings.json(Map.of("regular", 80, "lab", 82, "finalExam", 85)))));

    var result = new AnalyticsService(repo, courses).predict(teacher, "now");
    assertEquals(30, result.get("samples"));
    @SuppressWarnings("unchecked")
    var rows = (List<Map<String, Object>>) result.get("results");
    assertEquals(2, rows.size(), "只有期末未录入的两名学生参与预测");
    var ids = rows.stream().map(r -> r.get("studentId")).toList();
    assertTrue(ids.contains("s0") && ids.contains("s1"));
    assertFalse(ids.contains("s2"), "已录入期末的学生不应再预测");
    for (var row : rows) {
      assertNotNull(row.get("predictedTotal"));
      assertNotNull(row.get("low"));
      assertNotNull(row.get("high"));
      assertTrue(row.containsKey("warning") && row.containsKey("risk"));
    }
  }

  /** 历史样本不足时给出 422 与明确文案，而不是抛 500。 */
  @Test
  void predictRejectsWhenNotEnoughHistory() {
    var repo = mock(RemoteRepository.class);
    var current =
        Map.<String, Object>of(
            "id", "now", "code", "CS", "term", "2026-1",
            "weights", Settings.json(CourseService.defaultWeights()));
    var teacher = new Models.User("t1", "teacher1", "T1", "TEACHER", Set.of("PREDICT"), 0);
    var courses = mock(CourseService.class);
    when(courses.access(teacher, "now", "PREDICT")).thenReturn(current);
    when(repo.find("courses", Map.of("code", "CS"))).thenReturn(List.of(current));

    ApiException ex =
        assertThrows(ApiException.class, () -> new AnalyticsService(repo, courses).predict(teacher, "now"));
    assertEquals(422, ex.status);
    assertTrue(ex.getMessage().contains("3 年"), ex.getMessage());
  }
}
