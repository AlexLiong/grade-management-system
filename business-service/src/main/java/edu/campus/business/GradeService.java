package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;

import java.util.*;

import org.springframework.stereotype.Service;

@Service
public class GradeService {
    private final RemoteRepository repo;
    private final CourseService courses;
    private final AnalyticsService analytics;

    public GradeService(RemoteRepository repo, CourseService courses, AnalyticsService analytics) {
        this.repo = repo;
        this.courses = courses;
        this.analytics = analytics;
    }

    public List<Map<String, Object>> list(Models.User u, String courseId) {
        var c = courses.access(u, courseId, u.role().equals("ADMIN") ? "GRADE_ADMIN" : "QUERY");
        var rows =
                repo.find(
                        "grades",
                        u.role().equals("STUDENT")
                                ? Map.of("course_id", courseId, "student_id", u.id(), "state", "SUBMITTED")
                                : Map.of("course_id", courseId));
        var weights = Models.object(c.get("weights"));
        return rows.stream()
                .map(
                        g -> {
                            var r = Models.grade(g);
                            var scores = Models.object(r.get("scores"));
                            r.put("total", Models.total(scores, weights));
                            r.put("effective", Models.effective(scores, weights));
                            r.put(
                                    "makeupDisplay",
                                    scores.get("makeup") == null
                                            ? null
                                            : Math.min(60, Models.number(scores.get("makeup"))));
                            return r;
                        })
                .toList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> save(Models.User u, Map<String, Object> b) {
        String id = Models.text(b, "courseId", 100);
        var c = courses.access(u, id, "ENTRY");
        ApiException.require(u.role().equals("TEACHER"), 403, "仅授课教师可录入成绩");
        var weights = Models.object(c.get("weights"));
        var rows = (List<Map<String, Object>>) b.get("grades");
        ApiException.require(
                rows != null && !rows.isEmpty() && rows.size() <= 300, 400, "一次可录入 1–300 条成绩");
        var enrolled =
                repo.find("enrollments", Map.of("course_id", id)).stream()
                        .map(e -> e.get("student_id").toString())
                        .collect(java.util.stream.Collectors.toSet());
        Map<String, Map<String, Object>> existing = new HashMap<>();
        repo.find("grades", Map.of("course_id", id))
                .forEach(g -> existing.put(g.get("student_id").toString(), g));
        List<Protocol.Operation> ops = new ArrayList<>();
        int cv = Models.integer(b.get("courseVersion"));
        ops.add(update("courses", id, Map.of("version", cv + 1), cv));
        Set<String> seen = new HashSet<>();
        for (var entry : rows) {
            String student = Models.text(entry, "studentId", 100);
            ApiException.require(enrolled.contains(student) && seen.add(student), 400, "学生未选课或重复录入");
            Map<String, Object> scores = Models.object(entry.get("scores"));
            validateScores(scores, weights, false);
            var old = existing.get(student);
            var v = new LinkedHashMap<String, Object>();
            v.put("payload", Settings.json(scores));
            v.put("state", "DRAFT");
            if (old == null) {
                ApiException.require(entry.get("version") == null, 409, "成绩版本不存在");
                v.put("id", UUID.randomUUID().toString());
                v.put("course_id", id);
                v.put("student_id", student);
                v.put("version", 0);
                ops.add(insert("grades", v));
            } else {
                int version = Models.integer(entry.get("version"));
                v.put("version", version + 1);
                ops.add(update("grades", old.get("id").toString(), v, version));
            }
        }
        repo.mutate(ops, u.id(), "GRADE_SAVE", id);
        var anomalies = analytics.anomalies(id);
        if (!anomalies.isEmpty())
            repo.securityEvent(u.id(), "GRADE_ANOMALY", id + ":" + anomalies.size());
        return Map.of("saved", rows.size(), "anomalies", anomalies);
    }

    public static void validateScores(
            Map<String, Object> s, Map<String, Object> weights, boolean complete) {
        Set<String> allowed = new HashSet<>(Models.COMPONENTS);
        allowed.add("makeup");
        ApiException.require(allowed.containsAll(s.keySet()), 400, "存在未知成绩字段");
        for (var v : s.values())
            if (v != null) {
                double x = Models.number(v);
                ApiException.require(x >= 0 && x <= 100, 400, "成绩必须在 0–100 分之间");
            }
        Double total = Models.total(s, weights);
        if (complete) ApiException.require(total != null, 400, "所有有权重的成绩项必须完整后才能提交");
        // 补考只对正考不及格的学生开放：正考总评已达到 60 分以上时不允许再录补考。
        if (s.get("makeup") != null)
            ApiException.require(total != null && total < 60, 400, "正考已及格，不能录入补考成绩");
    }

    public void transition(Models.User u, Map<String, Object> b) {
        String id = Models.text(b, "courseId", 100), action = Models.text(b, "action", 30);
        ApiException.require(
                Set.of("SUBMIT", "WITHDRAW", "SMALL_REVOKE", "DELETE_ALL").contains(action), 400, "未知成绩操作");
        boolean admin = Set.of("SMALL_REVOKE", "DELETE_ALL").contains(action);
        var c = courses.access(u, id, admin ? "GRADE_ADMIN" : "MAINTAIN");
        ApiException.require(
                admin ? u.role().equals("ADMIN") : u.role().equals("TEACHER"), 403, "角色不允许执行此操作");
        if (admin) Models.text(b, "reason", 500);
        if (action.equals("DELETE_ALL"))
            ApiException.require(id.equals(b.get("confirmation")), 400, "大撤销需确认课程编号");
        var grades = repo.find("grades", Map.of("course_id", id));
        ApiException.require(!grades.isEmpty(), 409, "课程没有成绩");
        var weights = Models.object(c.get("weights"));
        if (action.equals("SUBMIT")) {
            // 只要求“在读”学生全部录入：退课记录不会再有成绩，不应阻塞提交。
            var active =
                    repo.find("enrollments", Map.of("course_id", id)).stream()
                            .filter(e -> !"DROPPED".equals(e.get("status")))
                            .count();
            ApiException.require(grades.size() == active, 400, "必须录入所有选课学生的成绩");
            for (var g : grades) {
                ApiException.require(g.get("state").equals("DRAFT"), 409, "课程成绩已提交");
                validateScores(Models.object(g.get("payload")), weights, true);
            }
        } else if (action.equals("WITHDRAW")) {
            ApiException.require(
                    grades.stream().allMatch(g -> g.get("state").equals("SUBMITTED")), 409, "只能撤销已提交的课程成绩");
        } else
            ApiException.require(
                    grades.stream().allMatch(g -> g.get("state").equals("SUBMITTED")), 409, "只能撤销已提交的课程成绩");
        int cv = Models.integer(b.get("courseVersion"));
        List<Protocol.Operation> ops = new ArrayList<>();
        ops.add(update("courses", id, Map.of("version", cv + 1), cv));
        for (var g : grades) {
            int v = Models.integer(g.get("version"));
            ops.add(
                    action.equals("DELETE_ALL")
                            ? delete("grades", g.get("id").toString(), v)
                            : update(
                            "grades",
                            g.get("id").toString(),
                            Map.of(
                                    "payload",
                                    g.get("payload"),
                                    "state",
                                    action.equals("SUBMIT") ? "SUBMITTED" : "DRAFT",
                                    "version",
                                    v + 1),
                            v));
        }
        repo.mutate(ops, u.id(), action, id + (admin ? ":" + b.get("reason") : ""));
    }

    /**
     * 学生本人的学业记录（只含**已提交**成绩）。
     *
     * <p>重修不体现在课程名上：同一个课程代码在「挂科学期」与「重修学期」是同一门课，
     * 所以这里按「同一课程代码在更早学期是否已提交且不及格」给每条记录标出 {@code retake}，
     * 学生端据此显示「重修」状态（见 {@code App.vue} 的「我的成绩」表）。
     */
    public List<Map<String, Object>> transcript(Models.User u) {
        ApiException.require(u.role().equals("STUDENT"), 403, "仅学生可查看本人学业记录");
        u.require("QUERY");
        List<Map<String, Object>> result = new ArrayList<>();
        for (var c : courses.list(u)) {
            var all = repo.find("grades", Map.of("course_id", c.get("id"), "state", "SUBMITTED"));
            var weights = Models.object(c.get("weights"));
            for (var g : all)
                if (g.get("student_id").equals(u.id())) {
                    var scores = Models.object(g.get("payload"));
                    Double total = Models.total(scores, weights),
                            effective = Models.effective(scores, weights);
                    long rank =
                            1
                                    + all.stream()
                                    .filter(
                                            r ->
                                                    Models.effective(Models.object(r.get("payload")), weights)
                                                            > effective)
                                    .count();
                    var row = new LinkedHashMap<String, Object>(c);
                    row.put("total", total);
                    row.put("effective", effective);
                    row.put(
                            "makeup",
                            scores.get("makeup") == null
                                    ? null
                                    : Math.min(60, Models.number(scores.get("makeup"))));
                    row.put("rank", rank);
                    row.put("classSize", all.size());
                    row.put("failed", effective < 60);
                    result.add(row);
                }
        }
        markRetakes(result);
        // 学期倒序、同学期按课程代码，便于「我的成绩」直接展示最近的修读。
        result.sort(
                Comparator.comparing(
                                (Map<String, Object> r) -> blank(r.get("term")) == null ? "" : blank(r.get("term")),
                                Comparator.reverseOrder())
                        .thenComparing(r -> blank(r.get("code")) == null ? "" : blank(r.get("code"))));
        return result;
    }

    /**
     * 标注重修：某条记录若在**更早学期**有同一课程代码的修读记录，则它是一次重修。
     *
     * <p>之所以只看「更早学期是否存在同一代码」就够了：学业记录里只包含**已提交**的成绩，
     * 而初始化数据的 {@code verifyTranscriptIntegrity()} 已经保证「同一学生跨学期修读同一代码时
     * 前一次必须是挂科」，选课规则也禁止已通过的课程再次修读。因此同一代码出现第二次，
     * 一定是因为第一次没通过——也就是重修。正在修读（成绩未提交）不在学业记录里，不参与判断。
     */
    private void markRetakes(List<Map<String, Object>> rows) {
        // 课程代码 → 该代码下出现过的学期（升序）
        var termsByCode = new HashMap<String, TreeSet<String>>();
        for (var row : rows) {
            String code = blank(row.get("code"));
            String term = blank(row.get("term"));
            if (code == null || term == null) continue;
            termsByCode.computeIfAbsent(code, k -> new TreeSet<>()).add(term);
        }
        for (var row : rows) {
            String code = blank(row.get("code"));
            String term = blank(row.get("term"));
            var terms = code == null ? null : termsByCode.get(code);
            boolean retake = terms != null && term != null && terms.lower(term) != null;
            row.put("retake", retake);
            row.put("retakeLabel", retake ? "重修" : null);
        }
    }

    /** 空安全的字符串读取：空串与 null 一律返回 null，便于比较。 */
    private static String blank(Object value) {
        if (value == null) return null;
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }
}
