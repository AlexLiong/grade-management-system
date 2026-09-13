package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.util.*;
import org.springframework.stereotype.Service;
import weka.classifiers.functions.LinearRegression;
import weka.classifiers.trees.REPTree;
import weka.core.*;

@Service
public class AnalyticsService {
  private final RemoteRepository repo;
  private final CourseService courses;

  public AnalyticsService(RemoteRepository repo, CourseService courses) {
    this.repo = repo;
    this.courses = courses;
  }

  public List<Map<String, Object>> anomalies(String id) {
    var c = repo.one("courses", id);
    var weights = Models.object(c.get("weights"));
    var grades = repo.find("grades", Map.of("course_id", id));
    List<Map<String, Object>> alerts = new ArrayList<>();
    var totals =
        grades.stream()
            .map(g -> Models.total(Models.object(g.get("payload")), weights))
            .filter(Objects::nonNull)
            .sorted()
            .toList();
    double mean = totals.stream().mapToDouble(x -> x).average().orElse(0),
        sd = Math.sqrt(totals.stream().mapToDouble(x -> Math.pow(x - mean, 2)).average().orElse(0));
    if (totals.size() >= 5 && (mean < 50 || mean > 95))
      alerts.add(
          Map.of(
              "studentId",
              "CLASS",
              "rule",
              "CLASS_MEAN",
              "message",
              "班级均分超出 50–95 区间",
              "value",
              mean));
    var prior =
        repo.find("courses", Map.of("code", c.get("code"))).stream()
            .filter(p -> p.get("term").toString().compareTo(c.get("term").toString()) < 0)
            .toList();
    Map<String, List<Double>> history = new HashMap<>();
    for (var p : prior)
      for (var g : repo.find("grades", Map.of("course_id", p.get("id"), "state", "SUBMITTED"))) {
        Double t = Models.total(Models.object(g.get("payload")), Models.object(p.get("weights")));
        if (t != null)
          history.computeIfAbsent(g.get("student_id").toString(), k -> new ArrayList<>()).add(t);
      }
    for (var g : grades) {
      var s = Models.object(g.get("payload"));
      Double t = Models.total(s, weights);
      String sid = g.get("student_id").toString();
      if (t != null && sd > 0 && Math.abs(t - mean) > 3 * sd)
        alerts.add(
            Map.of(
                "studentId",
                sid,
                "rule",
                "THREE_SIGMA",
                "message",
                "总评分数超过均值正负 3 标准差",
                "value",
                t));
      if (t != null
          && totals.size() >= 10
          && t <= totals.get((int) Math.floor((totals.size() - 1) * .05))
          && t < 60)
        alerts.add(
            Map.of(
                "studentId", sid, "rule", "PERCENTILE_05", "message", "总评处于后 5% 且不及格", "value", t));
      var h = history.getOrDefault(sid, List.of());
      if (t != null
          && h.size() >= 2
          && Math.abs(t - h.stream().mapToDouble(x -> x).average().orElse(t)) > 25)
        alerts.add(
            Map.of(
                "studentId",
                sid,
                "rule",
                "HISTORY_SHIFT",
                "message",
                "与本人历史均分相差超过 25 分",
                "value",
                t));
      if (s.get("makeup") != null
          && (Models.number(s.get("makeup")) > 95 || Models.number(s.get("makeup")) < 20))
        alerts.add(
            Map.of(
                "studentId",
                sid,
                "rule",
                "MAKEUP_EXTREME",
                "message",
                "补考卷面分超出 20–95 区间",
                "value",
                s.get("makeup")));
    }
    return alerts;
  }

  public Map<String, Object> statistics(Models.User u, String id) {
    var c = courses.access(u, id, u.role().equals("ADMIN") ? "GRADE_ADMIN" : "QUERY");
    ApiException.require(!u.role().equals("STUDENT"), 403, "学生不可查看班级分析明细");
    var grades = repo.find("grades", Map.of("course_id", id));
    var weights = Models.object(c.get("weights"));
    var totals =
        grades.stream()
            .map(g -> Models.total(Models.object(g.get("payload")), weights))
            .filter(Objects::nonNull)
            .toList();
    Map<String, Long> bands = new LinkedHashMap<>();
    bands.put("0–59", totals.stream().filter(x -> x < 60).count());
    bands.put("60–69", totals.stream().filter(x -> x >= 60 && x < 70).count());
    bands.put("70–79", totals.stream().filter(x -> x >= 70 && x < 80).count());
    bands.put("80–89", totals.stream().filter(x -> x >= 80 && x < 90).count());
    bands.put("90–100", totals.stream().filter(x -> x >= 90).count());
    var a = repo.find("analyses", Map.of("course_id", id));
    return Map.of(
        "count",
        totals.size(),
        "mean",
        totals.stream().mapToDouble(x -> x).average().orElse(0),
        "passRate",
        totals.isEmpty() ? 0 : totals.stream().filter(x -> x >= 60).count() * 100.0 / totals.size(),
        "bands",
        bands,
        "analysis",
        a.isEmpty() ? Map.of("content", "", "version", -1) : a.get(0),
        "anomalies",
        anomalies(id));
  }

  public void analysis(Models.User u, Map<String, Object> b) {
    String id = Models.text(b, "courseId", 100);
    courses.access(u, id, "MAINTAIN");
    ApiException.require(u.role().equals("TEACHER"), 403, "仅教师可编辑成绩分析");
    String content = Models.text(b, "content", 10000);
    var rows = repo.find("analyses", Map.of("course_id", id));
    int version = Models.integer(b.get("version"));
    Protocol.Operation op;
    if (rows.isEmpty()) {
      ApiException.require(version == -1, 409, "分析版本错误");
      op =
          insert(
              "analyses",
              Map.of(
                  "id",
                  UUID.randomUUID().toString(),
                  "course_id",
                  id,
                  "content",
                  content,
                  "version",
                  0));
    } else
      op =
          update(
              "analyses",
              rows.get(0).get("id").toString(),
              Map.of("content", content, "version", version + 1),
              version);
    repo.mutate(List.of(op), u.id(), "ANALYSIS_SAVE", id);
  }

  public Map<String, Object> predict(Models.User u, String id) {
    ApiException.require(Set.of("STUDENT", "TEACHER").contains(u.role()), 403, "预测隐私仅本人及授课教师可见");
    var c = courses.access(u, id, "PREDICT");
    var prior =
        repo.find("courses", Map.of("code", c.get("code"))).stream()
            .filter(p -> p.get("term").toString().compareTo(c.get("term").toString()) < 0)
            .toList();
    List<double[]> samples = new ArrayList<>();
    Set<String> years = new TreeSet<>();
    for (var p : prior)
      for (var g : repo.find("grades", Map.of("course_id", p.get("id"), "state", "SUBMITTED"))) {
        var s = Models.object(g.get("payload"));
        if (s.get("regular") != null && s.get("lab") != null && s.get("finalExam") != null) {
          String year = p.get("term").toString().substring(0, 4);
          years.add(year);
          samples.add(
              new double[] {
                Models.number(s.get("regular")),
                Models.number(s.get("lab")),
                Models.number(s.get("finalExam")),
                Double.parseDouble(year)
              });
        }
      }
    ApiException.require(
        years.size() >= 3 && samples.size() >= 24, 422, "至少需要 3 年、24 条完整历史成绩，当前数据不足，未生成预测");
    try {
      Instances train = instances(samples);
      LinearRegression linear = new LinearRegression();
      linear.setAttributeSelectionMethod(
          new SelectedTag(LinearRegression.SELECTION_NONE, LinearRegression.TAGS_SELECTION));
      linear.buildClassifier(train);
      REPTree tree = new REPTree();
      tree.setMaxDepth(4);
      tree.setMinNum(3);
      tree.setSeed(42);
      tree.buildClassifier(train);
      double latest = Double.parseDouble(((TreeSet<String>) years).last());
      var historical = samples.stream().filter(s -> s[3] < latest).toList();
      var holdout = samples.stream().filter(s -> s[3] == latest).toList();
      LinearRegression validation = new LinearRegression();
      validation.buildClassifier(instances(historical));
      REPTree validationTree = new REPTree();
      validationTree.setMaxDepth(4);
      validationTree.setMinNum(3);
      validationTree.setSeed(42);
      validationTree.buildClassifier(instances(historical));
      double error = 0, treeError = 0;
      for (var s : holdout) {
        var x = instance(train, s[0], s[1]);
        error += Math.pow(validation.classifyInstance(x) - s[2], 2);
        treeError += Math.pow(validationTree.classifyInstance(x) - s[2], 2);
      }
      double rmse = Math.sqrt(error / holdout.size()),
          treeRmse = Math.sqrt(treeError / holdout.size());
      var grades =
          repo.find(
              "grades",
              u.role().equals("STUDENT")
                  ? Map.of("course_id", id, "student_id", u.id())
                  : Map.of("course_id", id));
      List<Map<String, Object>> result = new ArrayList<>();
      var w = Models.object(c.get("weights"));
      for (var g : grades) {
        var s = Models.object(g.get("payload"));
        if (s.get("regular") == null || s.get("lab") == null || s.get("finalExam") != null)
          continue;
        Instance x = instance(train, Models.number(s.get("regular")), Models.number(s.get("lab")));
        double exam = clamp(linear.classifyInstance(x)), treeExam = clamp(tree.classifyInstance(x));
        s.put("finalExam", exam);
        Double total = Models.total(s, w);
        if (total == null) continue;
        s.put("finalExam", treeExam);
        Double treeTotal = Models.total(s, w);
        double spread = 1.96 * rmse * Models.number(w.get("finalExam")) / 100;
        result.add(
            Map.of(
                "studentId",
                g.get("student_id"),
                "linearExam",
                exam,
                "treeExam",
                treeExam,
                "predictedTotal",
                total,
                "treeTotal",
                treeTotal,
                "low",
                clamp(total - spread),
                "high",
                clamp(total + spread),
                "warning",
                total < 55 || treeTotal < 55,
                "risk",
                total < 60 || treeTotal < 60));
      }
      return Map.of(
          "model",
          "Weka LinearRegression + REPTree",
          "trainingYears",
          years,
          "samples",
          samples.size(),
          "validationYear",
          (int) latest,
          "holdoutRmse",
          rmse,
          "treeHoldoutRmse",
          treeRmse,
          "linearFormula",
          linear.toString(),
          "tree",
          tree.toString(),
          "results",
          result);
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(422, "MODEL_FAILURE", "历史数据无法支持模型训练");
    }
  }

  private static double clamp(double x) {
    return Math.round(Math.max(0, Math.min(100, x)) * 100) / 100.0;
  }

  private static Instances instances(List<double[]> samples) {
    var attrs = new ArrayList<Attribute>();
    attrs.add(new Attribute("regular"));
    attrs.add(new Attribute("lab"));
    attrs.add(new Attribute("finalExam"));
    Instances result = new Instances("historical-grades", attrs, samples.size());
    result.setClassIndex(2);
    for (var s : samples) result.add(new DenseInstance(1, new double[] {s[0], s[1], s[2]}));
    return result;
  }

  private static Instance instance(Instances data, double regular, double lab) {
    Instance i = new DenseInstance(1, new double[] {regular, lab, Utils.missingValue()});
    i.setDataset(data);
    return i;
  }
}
