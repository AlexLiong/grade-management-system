package edu.campus.business;

import static edu.campus.business.RemoteRepository.*;

import edu.campus.common.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 教务选课系统。
 *
 * <p>选课发布（course_selections）描述“哪些课程、在什么时间窗口、面向哪些组织、最低开课人数与
 * 学分上限是多少”；学生选课结果写入 enrollments；每一次选课、退课、批量代选与不满足最低开课
 * 人数的自动退回都会在 enrollment_records 里留下独立可查的记录，并随 {@code repo.mutate}
 * 进入加密审计账本。因此“谁在什么时候选了哪门课、被谁退回、为什么退回”都可以单独审计。
 *
 * <p>规则集中在一处：{@link #checkSelectable} 同时服务于“真正选课”和“可选课程列表的
 * eligible/reason”，保证页面上显示“可选”的课程一定能选上，反之亦然。
 *
 * <p>输出给前端的对象一律使用学院、专业、班级、教师的名称；除 id 之外不返回裸编号。
 */
@Service
public class SelectionService {

  private static final String OPEN = "OPEN";
  private static final String CLOSED = "CLOSED";
  private static final String CANCELLED = "CANCELLED";
  private static final String ACTIVE = "ACTIVE";
  private static final String DROPPED = "DROPPED";
  private static final String COURSE_CANCELLED = "CANCELLED";
  private static final String SUBMITTED = "SUBMITTED";
  private static final String SYSTEM_ACTOR = "SYSTEM";

  /** 固定提示文案：批量操作按文案区分“已选，跳过”与“校验失败”。 */
  private static final String ALREADY_SELECTED = "已选修该课程";
  private static final String NO_ENROLLMENT = "没有该课程的选课记录";
  private static final String GRADED_PUBLISH = "该课程已有教师录入成绩，不能发布选课";
  private static final String GRADED_DROP = "教师已录入成绩，不能退课";
  private static final String CODE_TAKEN = "本学期已选择同一课程代码的其他教学班";
  private static final String PASSED_BEFORE = "该课程此前已通过，不能重复修读";
  private static final String RETRY_FORBIDDEN = "本次选课不允许重修";
  private static final String ADD_FORBIDDEN = "本次选课不允许选课";
  private static final String DROP_FORBIDDEN = "本次选课不允许退课";
  private static final String OUT_OF_SCOPE = "你不在此次选课范围内";
  private static final String NOT_STARTED = "选课尚未开始";
  private static final String ENDED = "选课已结束";
  private static final String NOT_OPEN = "选课未开放";
  private static final String ALREADY_OPEN_PUBLISH = "该课程已存在于其他进行中的选课发布";
  private static final String BAD_TIME = "时间格式错误";
  /** 重修状态的展示文案：重修是「同一课程号在后续学年重新修读」，与课程名无关。 */
  private static final String RETRY_LABEL = "重修";
  private static final int MAX_MIN_ENROLL = 1000;

  private final RemoteRepository repo;
  private final OrganizationService organizations;

  public SelectionService(RemoteRepository repo, OrganizationService organizations) {
    this.repo = repo;
    this.organizations = organizations;
  }

  // ---------------------------------------------------------------- 查询

  /** 教务视角：分页列出全部选课发布，附课程、范围与已选人数（全部名称已翻译）。 */
  public Map<String, Object> list(Models.User u, Map<String, String> query) {
    u.require("SELECTION_ADMIN");
    String status = text(query.get("status"));
    String term = text(query.get("term"));
    var publishes = new ArrayList<Map<String, Object>>();
    for (var publish : rows("course_selections", Map.of())) {
      if (status != null && !status.equals(text(publish.get("status")))) continue;
      if (term != null && !term.equals(text(publish.get("term")))) continue;
      publishes.add(publish);
    }
    publishes.sort(
        Comparator.comparing((Map<String, Object> p) -> Objects.toString(p.get("term"), ""))
            .reversed()
            .thenComparing(p -> Objects.toString(p.get("id"), "")));
    var enrollments = rows("enrollments", Map.of());
    var index = courseIndex();
    var names = new Names();
    var items = new ArrayList<Map<String, Object>>();
    for (var publish : publishes) items.add(describe(publish, enrollments, index, names));
    return ApiController.page(items, query);
  }

  /**
   * 学生视角：当前可以选的选课批次。
   *
   * <p>只返回 status=OPEN、当前时间在窗口内、且该生落在选课范围内的批次；每门课程附上
   * 是否已选、已选人数，以及用与学生选课完全相同的规则算出来的 eligible/reason。
   */
  public List<Map<String, Object>> available(Models.User u) {
    ApiException.require(u.any("SELECTION_ENROLL", "SELECTION_ADMIN"), 403, "没有此操作权限");
    boolean student = "STUDENT".equals(u.role());
    var now = Instant.now();
    var index = courseIndex();
    var st = studentState(u, index);
    var enrollments = rows("enrollments", Map.of());
    var names = new Names();
    var result = new ArrayList<Map<String, Object>>();
    for (var publish : rows("course_selections", Map.of("status", OPEN))) {
      if (!OPEN.equals(text(publish.get("status")))) continue;
      try {
        requireWindow(publish, now);
      } catch (ApiException window) {
        continue; // 未开始或已结束的批次不出现在可选列表里
      }
      if (student && !OrganizationService.inScope(publish, st.profile)) continue;
      var row = describe(publish, enrollments, index, names);
      row.put("courses", options(st, publish, index, names, enrollments, student));
      result.add(row);
    }
    result.sort(
        Comparator.comparing((Map<String, Object> p) -> Objects.toString(p.get("endTime"), "")));
    return result;
  }

  /**
   * 学生的「挂科课程代码」索引：课程代码 → 挂科学期。
   *
   * <p>重修的口径是「同一课程号、不同学年重新修读」，因此判断某条选课记录是不是重修，
   * 只需要看该生**在更早学期**是否修读过同一课程代码且**没有通过**（正考挂科、补考也未通过）。
   * 这里刻意不把课程名与重修挂钩：重修的学生选的就是普通课程，课程名不含「重修」字样。
   */
  static final class FailedCodes {
    private final Map<String, String> firstFailedTerm = new HashMap<>();

    void add(String code, String term) {
      if (code == null || term == null) return;
      String existing = firstFailedTerm.get(code);
      if (existing == null || term.compareTo(existing) < 0) firstFailedTerm.put(code, term);
    }

    /** 该代码是否在 {@code term} 之前挂过科（即本学期这条记录属于重修）。 */
    boolean retakeIn(String code, String term) {
      if (code == null || term == null) return false;
      String failedTerm = firstFailedTerm.get(code);
      return failedTerm != null && failedTerm.compareTo(term) < 0;
    }
  }

  /**
   * 汇总每名学生「此前学期未通过的课程代码」。
   *
   * <p>只有**成绩已提交**且**有效分 < 60** 的修读才算挂科；成绩未出或未提交的记录不参与判断，
   * 避免把「正在修读」误判成重修。退课记录同样不参与。
   *
   * <p>成绩一次性读入内存索引（{@code course_id|student_id} → 成绩行），因此判定 N 条选课记录
   * 只需要一次查询，不会随着选课量增长而放大请求数。
   */
  Map<String, FailedCodes> failedCodes(
      Collection<Map<String, Object>> enrollments, Map<String, Map<String, Object>> index) {
    var byStudent = new HashMap<String, FailedCodes>();
    if (enrollments.isEmpty()) return byStudent;
    var gradeIndex = gradeIndex();
    for (var enrollment : enrollments) {
      if (!ACTIVE.equals(text(enrollment.get("status")))) continue;
      String studentId = text(enrollment.get("student_id"));
      var course = index.get(text(enrollment.get("course_id")));
      if (studentId == null || course == null) continue;
      Double effective = effectiveScore(course, studentId, gradeIndex);
      if (effective == null || effective >= 60) continue;
      byStudent
          .computeIfAbsent(studentId, k -> new FailedCodes())
          .add(text(course.get("code")), text(course.get("term")));
    }
    return byStudent;
  }

  /** 成绩索引：{@code course_id|student_id} → grades 行，供批量判定复用。 */
  private Map<String, Map<String, Object>> gradeIndex() {
    var index = new HashMap<String, Map<String, Object>>();
    for (var grade : rows("grades", Map.of()))
      index.put(text(grade.get("course_id")) + "|" + text(grade.get("student_id")), grade);
    return index;
  }

  /** 学生视角：本人全部有效选课记录（仅 ACTIVE），附课程详情、来源与是否重修。 */
  public List<Map<String, Object>> my(Models.User u) {
    u.require("SELECTION_ENROLL");
    var index = courseIndex();
    var names = new Names();
    // 重修判定需要该生全部学期的选课与成绩，这里一次性算好，避免逐行查询。
    var myEnrollments = rows("enrollments", Map.of("student_id", u.id()));
    var failed = failedCodes(myEnrollments, index);
    var result = new ArrayList<Map<String, Object>>();
    for (var enrollment : myEnrollments) {
      if (!ACTIVE.equals(text(enrollment.get("status")))) continue;
      var course = index.get(text(enrollment.get("course_id")));
      var row = new LinkedHashMap<String, Object>();
      row.put("id", text(enrollment.get("id")));
      row.put("enrollmentId", text(enrollment.get("id")));
      row.put("courseId", text(enrollment.get("course_id")));
      row.put("course_id", text(enrollment.get("course_id")));
      courseFields(row, course, names);
      row.put("publishId", text(enrollment.get("publish_id")));
      row.put("source", text(enrollment.get("source")));
      row.put("selectedAt", text(enrollment.get("selected_at")));
      row.put("selected_at", text(enrollment.get("selected_at")));
      row.put("status", text(enrollment.get("status")));
      // 重修：该课程代码在此前学期挂过科，本学期重新修读同一课程号。
      var myFailed = failed.get(u.id());
      boolean retake =
          course != null
              && myFailed != null
              && myFailed.retakeIn(text(course.get("code")), text(course.get("term")));
      row.put("retake", retake);
      row.put("retakeLabel", retake ? RETRY_LABEL : null);
      result.add(row);
    }
    result.sort(
        Comparator.comparing(
                (Map<String, Object> r) -> Objects.toString(r.get("selectedAt"), ""))
            .reversed());
    return result;
  }

  /** 教务视角：分页查询选课流水，附学生姓名、课程名称与课程代码。 */
  public Map<String, Object> records(Models.User u, Map<String, String> query) {
    u.require("SELECTION_ADMIN");
    var where = new LinkedHashMap<String, Object>();
    if (text(query.get("publishId")) != null) where.put("publish_id", text(query.get("publishId")));
    if (text(query.get("courseId")) != null) where.put("course_id", text(query.get("courseId")));
    if (text(query.get("studentId")) != null) where.put("student_id", text(query.get("studentId")));
    String action = text(query.get("action"));
    if (action != null) {
      ApiException.require(
          Models.ENROLLMENT_ACTIONS.contains(action), 400, "未知的选课动作：" + action);
      where.put("action", action);
    }
    var all = new ArrayList<>(rows("enrollment_records", where));
    all.sort(
        Comparator.comparing((Map<String, Object> r) -> Objects.toString(r.get("created_at"), ""))
            .reversed()
            .thenComparing(r -> Objects.toString(r.get("id"), "")));
    var index = courseIndex();
    var names = new Names();
    var items = new ArrayList<Map<String, Object>>();
    for (var record : all) {
      var course = index.get(text(record.get("course_id")));
      var row = new LinkedHashMap<String, Object>();
      row.put("id", text(record.get("id")));
      row.put("publishId", text(record.get("publish_id")));
      row.put("courseId", text(record.get("course_id")));
      row.put("course_id", text(record.get("course_id")));
      row.put("studentId", text(record.get("student_id")));
      row.put("studentName", names.student(text(record.get("student_id"))));
      row.put("courseName", course == null ? null : text(course.get("name")));
      row.put(
          "code",
          text(record.get("code")) != null
              ? text(record.get("code"))
              : course == null ? null : text(course.get("code")));
      row.put("term", text(record.get("term")));
      row.put("action", text(record.get("action")));
      row.put("actionName", actionName(text(record.get("action"))));
      row.put("reason", text(record.get("reason")));
      row.put("operator", text(record.get("operator")));
      row.put("createdAt", text(record.get("created_at")));
      row.put("created_at", text(record.get("created_at")));
      items.add(row);
    }
    return ApiController.page(items, query);
  }

  // ---------------------------------------------------------------- 发布与结算

  /**
   * 新建或修改选课发布。
   *
   * <p>鲁棒性要求：所选课程只要已经有教师录入成绩（DRAFT 或 SUBMITTED），一律拒绝发布；
   * 同一门课程也不允许同时出现在两个进行中的发布里，避免学生的选课结果互相覆盖。
   */
  public Map<String, Object> save(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ADMIN");
    String name = Models.text(b, "name", 100);
    String term = Models.text(b, "term", 20);
    ApiException.require(term.matches("20\\d{2}-[12]"), 400, "学期格式应为 2026-1");
    Instant start = parseTime(firstValue(b, "startTime", "start_time"));
    Instant end = parseTime(firstValue(b, "endTime", "end_time"));
    ApiException.require(start.isBefore(end), 400, "开始时间必须早于结束时间");
    int minEnroll = requiredInt(b, "minEnroll", 1, MAX_MIN_ENROLL, "最低开课人数必须为 1–1000 的整数");
    int maxCredits = optionalInt(b, "maxCredits", 0, 0, Models.MAX_CREDITS_LIMIT, "学分上限必须为 0–40");
    boolean allowAdd = Models.flag(b.get("allowAdd"), true);
    boolean allowDrop = Models.flag(b.get("allowDrop"), true);
    boolean allowRetake = Models.flag(b.get("allowRetake"), false);

    var index = courseIndex();
    var requested = strings(b.get("courseIds"));
    if (requested.isEmpty()) requested = strings(b.get("courseNames"));
    if (requested.isEmpty()) requested = strings(b.get("courses"));
    ApiException.require(!requested.isEmpty(), 400, "必须选择至少一门课程");
    var courseIds = new ArrayList<String>();
    for (String raw : requested) {
      var course = findCourse(index, raw);
      ApiException.require(course != null, 400, "课程不存在：" + raw);
      String courseId = text(course.get("id"));
      ApiException.require(!courseIds.contains(courseId), 400, "课程重复：" + raw);
      ApiException.require(
          term.equals(text(course.get("term"))),
          400,
          "课程学期与选课学期不一致：" + text(course.get("name")));
      ApiException.require(
          !COURSE_CANCELLED.equals(text(course.get("status"))), 409, "已停开课程不能发布选课");
      ApiException.require(rows("grades", Map.of("course_id", courseId)).isEmpty(), 409, GRADED_PUBLISH);
      courseIds.add(courseId);
    }

    String scopeColleges = resolveScope(Models.Level.COLLEGE, firstValue(b, "scopeCollegeIds", "scopeColleges"));
    String scopeMajors = resolveScope(Models.Level.MAJOR, firstValue(b, "scopeMajorIds", "scopeMajors"));
    String scopeClasses = resolveScope(Models.Level.CLASS, firstValue(b, "scopeClassIds", "scopeClasses"));

    String id = text(b.get("id"));
    var existing = id == null ? null : first("course_selections", Map.of("id", id));
    ApiException.require(id == null || existing != null, 404, "选课发布不存在");
    String status = existing == null ? OPEN : text(existing.get("status"));
    if (OPEN.equals(status))
      for (String courseId : courseIds)
        for (var other : rows("course_selections", Map.of("status", OPEN))) {
          if (id != null && id.equals(text(other.get("id")))) continue;
          if (OrganizationService.split(other.get("course_ids")).contains(courseId))
            throw new ApiException(409, "REQUEST_REJECTED", ALREADY_OPEN_PUBLISH);
        }

    var values = new LinkedHashMap<String, Object>();
    values.put("name", name);
    values.put("term", term);
    values.put("course_ids", String.join(",", courseIds));
    values.put("scope_college_ids", scopeColleges);
    values.put("scope_major_ids", scopeMajors);
    values.put("scope_class_ids", scopeClasses);
    values.put("start_time", localIso(start));
    values.put("end_time", localIso(end));
    values.put("min_enroll", minEnroll);
    values.put("max_credits", maxCredits);
    values.put("allow_add", allowAdd ? 1 : 0);
    values.put("allow_drop", allowDrop ? 1 : 0);
    values.put("allow_retake", allowRetake ? 1 : 0);
    values.put("note", Objects.toString(Models.optionalText(b, "note", 500), ""));

    Protocol.Operation operation;
    if (existing == null) {
      id = UUID.randomUUID().toString();
      values.put("id", id);
      values.put("status", OPEN);
      values.put("published_by", u.id());
      values.put("published_at", Instant.now().toString());
      values.put("version", 0);
      operation = insert("course_selections", values);
    } else {
      int version = intOf(existing.get("version"), 0);
      values.put("version", version + 1);
      operation = update("course_selections", id, values, version);
    }
    repo.mutate(List.of(operation), u.id(), "SELECTION_PUBLISH", id);
    return Map.of("ok", true, "id", id);
  }

  /** 关闭选课：只允许从 OPEN 改为 CLOSED。 */
  public Map<String, Object> close(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ADMIN");
    var publish = requirePublish(Models.text(b, "id", 100));
    requireOpen(publish);
    String id = text(publish.get("id"));
    int version = intOf(publish.get("version"), 0);
    repo.mutate(
        List.of(
            update(
                "course_selections", id, Map.of("status", CLOSED, "version", version + 1), version)),
        u.id(),
        "SELECTION_CLOSE",
        id);
    return Map.of("ok", true, "id", id);
  }

  /** 取消选课：整批作废，并把该批次所有 ACTIVE 选课记录按自动退回处理。 */
  public Map<String, Object> cancel(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ADMIN");
    var publish = requirePublish(Models.text(b, "id", 100));
    String id = text(publish.get("id"));
    ApiException.require(!CANCELLED.equals(text(publish.get("status"))), 409, "选课已取消");
    String reason = Models.optionalText(b, "reason", 500);
    var ops = new ArrayList<Protocol.Operation>();
    String refundReason = "选课已取消" + (reason == null ? "" : "：" + reason);
    int refunded =
        refund(
            ops,
            publish,
            rows("enrollments", Map.of("publish_id", id, "status", ACTIVE)),
            courseIndex(),
            refundReason,
            u.id());
    int version = intOf(publish.get("version"), 0);
    ops.add(
        update("course_selections", id, Map.of("status", CANCELLED, "version", version + 1), version));
    repo.mutate(ops, u.id(), "SELECTION_CANCEL", id);
    var result = new LinkedHashMap<String, Object>();
    result.put("ok", true);
    result.put("id", id);
    result.put("refunded", refunded);
    return result;
  }

  /** 手动结算：教务在选课窗口结束后点“结算”。 */
  public Map<String, Object> settle(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ADMIN");
    return settleBatch(Models.text(b, "id", 100), u.id());
  }

  /**
   * 最低开课人数结算（手动与定时任务共用这一份实现）。
   *
   * <p>对该批次每门课程统计 ACTIVE 选课人数：人数小于 min_enroll 的课程判定为不满足开课要求，
   * 逐条把选课记录置为 DROPPED、写一条 AUTO_REFUND 流水（含“课程X未达到最低开课人数N，已自动
   * 退回”的原因），并把课程状态置为 CANCELLED；最后把发布本身置为 CLOSED。只在 OPEN 状态允许。
   */
  public Map<String, Object> settleBatch(String publishId, String actor) {
    var publish = requirePublish(publishId);
    requireOpen(publish);
    String id = text(publish.get("id"));
    int minEnroll = intOf(publish.get("min_enroll"), 0);
    var courseIds = OrganizationService.split(publish.get("course_ids"));
    var index = courseIndex();
    var buckets = new LinkedHashMap<String, List<Map<String, Object>>>();
    for (String courseId : courseIds) buckets.put(courseId, new ArrayList<>());
    for (var enrollment : rows("enrollments", Map.of("publish_id", id, "status", ACTIVE))) {
      var bucket = buckets.get(text(enrollment.get("course_id")));
      if (bucket != null) bucket.add(enrollment);
    }
    var ops = new ArrayList<Protocol.Operation>();
    var cancelled = new ArrayList<Map<String, Object>>();
    int refunded = 0;
    for (String courseId : courseIds) {
      var selected = buckets.get(courseId);
      if (selected.size() >= minEnroll) continue;
      var course = index.get(courseId);
      String code = course == null ? null : text(course.get("code"));
      String courseName = course == null ? courseId : text(course.get("name"));
      String reason = "课程" + courseName + "未达到最低开课人数" + minEnroll + "，已自动退回";
      refunded += refund(ops, publish, selected, index, reason, actor);
      if (course != null) {
        int version = intOf(course.get("version"), 0);
        ops.add(
            update("courses", courseId, Map.of("status", COURSE_CANCELLED, "version", version + 1), version));
      }
      var item = new LinkedHashMap<String, Object>();
      item.put("courseId", courseId);
      item.put("code", code);
      item.put("name", courseName);
      item.put("selected", selected.size());
      item.put("minEnroll", minEnroll);
      cancelled.add(item);
    }
    int version = intOf(publish.get("version"), 0);
    ops.add(
        update("course_selections", id, Map.of("status", CLOSED, "version", version + 1), version));
    repo.mutate(ops, actor, "SELECTION_SETTLE", id);
    var result = new LinkedHashMap<String, Object>();
    result.put("ok", true);
    result.put("cancelled", cancelled);
    result.put("refunded", refunded);
    return result;
  }

  // ---------------------------------------------------------------- 选课与退课

  /**
   * 学生选课。校验顺序：发布状态 → 时间窗口 → 是否允许选课 → 学生身份 → 选课范围 →
   * 课程归属与学期 → 重复选课 → 同一课程代码的其他教学班 → 此前已通过 → 重修许可 → 学分上限。
   */
  public Map<String, Object> select(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ENROLL");
    String publishId = Models.text(b, "publishId", 100);
    String courseId = Models.text(b, "courseId", 100);
    var publish = requirePublish(publishId);
    requireOpen(publish);
    requireWindow(publish, Instant.now());
    var st = studentState(u, courseIndex());
    var course = checkSelectable(st, publish, courseId, true);
    String now = Instant.now().toString();
    var ops = new ArrayList<Protocol.Operation>();
    var existing = st.enrollment(courseId);
    if (existing == null || text(existing.get("id")) == null) {
      var values = new LinkedHashMap<String, Object>();
      values.put("id", UUID.randomUUID().toString());
      values.put("course_id", courseId);
      values.put("student_id", st.id);
      values.put("source", "SELECTION");
      values.put("publish_id", text(publish.get("id")));
      values.put("selected_at", now);
      values.put("status", ACTIVE);
      ops.add(insert("enrollments", values));
    } else {
      // 曾经退课（DROPPED）的记录直接复用：唯一索引是 (course_id,student_id)，不能重复插入。
      ops.add(
          update(
              "enrollments",
              text(existing.get("id")),
              Map.of(
                  "source",
                  "SELECTION",
                  "publish_id",
                  text(publish.get("id")),
                  "selected_at",
                  now,
                  "status",
                  ACTIVE),
              null));
    }
    ops.add(
        auditRecord(publish, courseId, text(course.get("code")), st.id, "SELECT", null, st.id, now));
    repo.mutate(ops, u.id(), "SELECTION_SELECT", courseId);
    return Map.of("ok", true);
  }

  /**
   * 退课。学生退本人课程，教务可代退（{@code studentId}）；发布 OPEN/CLOSED 都可以退，但必须
   * allow_drop=1；教师一旦录入成绩（任何状态）就不允许退课。
   */
  public Map<String, Object> drop(Models.User u, Map<String, Object> b) {
    ApiException.require(u.any("SELECTION_ENROLL", "SELECTION_ADMIN"), 403, "没有此操作权限");
    String publishId = Models.text(b, "publishId", 100);
    String courseId = Models.text(b, "courseId", 100);
    var publish = requirePublish(publishId);
    ApiException.require(!CANCELLED.equals(text(publish.get("status"))), 409, "选课已取消");
    ApiException.require(intOf(publish.get("allow_drop"), 0) == 1, 403, DROP_FORBIDDEN);
    String requested = Models.optionalText(b, "studentId", 100);
    boolean admin = u.any("SELECTION_ADMIN");
    String studentId = requested == null ? u.id() : requested;
    ApiException.require(admin || studentId.equals(u.id()), 403, "只能退选本人的课程");
    var enrollment =
        first("enrollments", Map.of("course_id", courseId, "student_id", studentId, "status", ACTIVE));
    ApiException.require(enrollment != null, 404, NO_ENROLLMENT);
    ApiException.require(
        rows("grades", Map.of("course_id", courseId, "student_id", studentId)).isEmpty(),
        409,
        GRADED_DROP);
    String reason = Models.optionalText(b, "reason", 500);
    String now = Instant.now().toString();
    var course = first("courses", Map.of("id", courseId));
    var ops = new ArrayList<Protocol.Operation>();
    ops.add(update("enrollments", text(enrollment.get("id")), Map.of("status", DROPPED), null));
    ops.add(
        auditRecord(
            publish,
            courseId,
            course == null ? null : text(course.get("code")),
            studentId,
            "DROP",
            reason,
            u.id(),
            now));
    repo.mutate(ops, u.id(), "SELECTION_DROP", courseId);
    return Map.of("ok", true);
  }

  /**
   * 教务批量代选 / 代退：按班级（名称或编号）或显式学生名单整体操作。
   *
   * <p>跳过时间窗口，其余规则与单个 select/drop 完全一致；已经选过的学生计入 skipped 而不是
   * 报错，个别学生不满足规则时只记录到 failed，不影响其他人。
   */
  public Map<String, Object> batch(Models.User u, Map<String, Object> b) {
    u.require("SELECTION_ADMIN");
    String publishId = Models.text(b, "publishId", 100);
    String courseId = Models.text(b, "courseId", 100);
    boolean remove = Models.flag(b.get("remove"), false);
    var publish = requirePublish(publishId);
    requireOpen(publish);
    var index = courseIndex();
    var course = index.get(courseId);
    ApiException.require(course != null, 404, "课程不存在");
    if (remove) ApiException.require(intOf(publish.get("allow_drop"), 0) == 1, 403, DROP_FORBIDDEN);

    Object classRaw = firstValue(b, "classId", "className");
    if (classRaw == null) classRaw = b.get("class");
    var studentIds = new ArrayList<String>();
    if (text(classRaw) != null) {
      String classId = organizations.resolveOwn(Models.Level.CLASS, classRaw);
      ApiException.require(classId != null, 400, "指定的班级不存在");
      for (var row : rows("users", Map.of("class_id", classId))) {
        if (!"STUDENT".equals(text(row.get("role"))) || intOf(row.get("enabled"), 0) != 1) continue;
        studentIds.add(Objects.toString(row.get("id"), ""));
      }
    }
    if (studentIds.isEmpty()) studentIds.addAll(strings(b.get("studentIds")));
    ApiException.require(!studentIds.isEmpty(), 400, "必须指定班级或学生名单");
    var unique = new LinkedHashSet<String>();
    for (String studentId : studentIds) if (text(studentId) != null) unique.add(studentId.strip());
    ApiException.require(
        unique.size() <= Models.MAX_BATCH_STUDENTS,
        400,
        "单次批量操作最多 " + Models.MAX_BATCH_STUDENTS + " 名学生");

    String reason = Models.optionalText(b, "reason", 500);
    String recordReason = reason == null ? (remove ? "教务批量退课" : "教务批量选课") : reason;
    String now = Instant.now().toString();
    var ops = new ArrayList<Protocol.Operation>();
    var failed = new ArrayList<Map<String, Object>>();
    int added = 0, removed = 0, skipped = 0;
    for (String studentId : unique) {
      try {
        var st = studentState(studentId, "STUDENT", null, null, null, index);
        if (remove) {
          var enrollment = st.enrollment(courseId);
          ApiException.require(
              enrollment != null && ACTIVE.equals(text(enrollment.get("status"))),
              404,
              NO_ENROLLMENT);
          ApiException.require(
              rows("grades", Map.of("course_id", courseId, "student_id", studentId)).isEmpty(),
              409,
              GRADED_DROP);
          ops.add(update("enrollments", text(enrollment.get("id")), Map.of("status", DROPPED), null));
          ops.add(
              auditRecord(
                  publish,
                  courseId,
                  text(course.get("code")),
                  studentId,
                  "ADMIN_REMOVE",
                  recordReason,
                  u.id(),
                  now));
          removed++;
        } else {
          checkSelectable(st, publish, courseId, true);
          var existing = st.enrollment(courseId);
          if (existing == null || text(existing.get("id")) == null) {
            var values = new LinkedHashMap<String, Object>();
            values.put("id", UUID.randomUUID().toString());
            values.put("course_id", courseId);
            values.put("student_id", studentId);
            values.put("source", "ADMIN");
            values.put("publish_id", text(publish.get("id")));
            values.put("selected_at", now);
            values.put("status", ACTIVE);
            ops.add(insert("enrollments", values));
          } else {
            ops.add(
                update(
                    "enrollments",
                    text(existing.get("id")),
                    Map.of(
                        "source", "ADMIN",
                        "publish_id", text(publish.get("id")),
                        "selected_at", now,
                        "status", ACTIVE),
                    null));
          }
          ops.add(
              auditRecord(
                  publish,
                  courseId,
                  text(course.get("code")),
                  studentId,
                  "ADMIN_ASSIGN",
                  recordReason,
                  u.id(),
                  now));
          added++;
        }
      } catch (ApiException e) {
        if (ALREADY_SELECTED.equals(e.getMessage()) || NO_ENROLLMENT.equals(e.getMessage()))
          skipped++;
        else failed.add(failure(studentId, e.getMessage()));
      }
    }
    if (!ops.isEmpty()) repo.mutate(ops, u.id(), "SELECTION_BATCH", courseId);
    var result = new LinkedHashMap<String, Object>();
    result.put("ok", true);
    result.put("added", added);
    result.put("skipped", skipped);
    result.put("removed", removed);
    result.put("failed", failed);
    return result;
  }

  /**
   * 教务按课程批量选课 / 退课。
   *
   * <p>这是合并后的「课程与选课」界面上的批量入口：可以指定一个、多个学生，或某个班级的全部学生
   * （两者同时给出时取并集并按学生去重）。与 {@link #batch} 不同，这里不要求选课发布处于 OPEN，也不
   * 要求课程落在发布范围内——教务在课程界面上直接维护名单；重复选课与学生本人已退课后的重选分别走
   * “计入 skipped”与“复用既有行”两条路，个别学生不满足规则时只记入 failed，不影响其他人。
   *
   * <p>每个真正被选入或退掉的学生都会写一条 {@code enrollment_records}（{@code ADMIN_ASSIGN} /
   * {@code ADMIN_REMOVE}），并与选课行一起在<b>同一次</b> {@code repo.mutate} 中提交，保证要么全成
   * 要么全滚；审计 action 为 {@code ENROLLMENT_BATCH}，resource 为课程 id。
   *
   * @param b {@code courseId} 必填；{@code studentIds}、{@code classId}/{@code className} 至少要有一个；
   *     {@code remove=true} 表示批量退课；{@code publishId} 可选，未给出时按课程学期自动匹配包含该
   *     课程的 OPEN（其次 CLOSED）批次
   */
  public Map<String, Object> batchByCourse(Models.User u, Map<String, Object> b) {
    u.require("GRADE_ADMIN");
    String courseId = Models.text(b, "courseId", 100);
    var index = courseIndex();
    var course = index.get(courseId);
    ApiException.require(course != null, 404, "课程不存在");
    boolean remove = Models.flag(b.get("remove"), false);
    String code = text(course.get("code"));
    String term = text(course.get("term"));

    // 目标学生 = 显式名单 ∪ 班级全体学生；班级成员只按 role 过滤，停用与否留给逐个学生判定。
    Object classRaw = firstValue(b, "classId", "class_id");
    if (text(classRaw) == null) classRaw = firstValue(b, "className", "class_name");
    boolean classGiven = text(classRaw) != null;
    var requested = strings(b.get("studentIds"));
    ApiException.require(!requested.isEmpty() || classGiven, 400, "必须指定学生名单或班级");
    var classStudents = new ArrayList<String>();
    if (classGiven) {
      String classId = organizations.resolveOwn(Models.Level.CLASS, classRaw);
      ApiException.require(classId != null, 400, "指定的班级不存在");
      for (var row : rows("users", Map.of("class_id", classId)))
        if ("STUDENT".equals(text(row.get("role"))))
          classStudents.add(Objects.toString(row.get("id"), ""));
    }
    var targets = new LinkedHashSet<String>();
    for (String studentId : requested) if (text(studentId) != null) targets.add(studentId.strip());
    targets.addAll(classStudents);
    ApiException.require(!targets.isEmpty(), 400, "没有找到可操作的学生");
    ApiException.require(
        targets.size() <= Models.MAX_BATCH_STUDENTS,
        400,
        "单次批量操作最多 " + Models.MAX_BATCH_STUDENTS + " 名学生");

    String requestedPublish = Models.optionalText(b, "publishId", 100);
    var publish = batchPublish(requestedPublish, courseId, term);
    String publishId = publish == null ? null : text(publish.get("id"));
    String action = remove ? "ADMIN_REMOVE" : "ADMIN_ASSIGN";
    String reason = remove ? "教务按课程批量退课" : "教务按课程批量选课";
    String now = Instant.now().toString();
    var ops = new ArrayList<Protocol.Operation>();
    var failed = new ArrayList<Map<String, Object>>();
    int added = 0, removed = 0, skipped = 0;
    for (String studentId : targets) {
      var st = studentState(studentId, "STUDENT", null, null, null, index);
      String name = text(st.profile.get("name"));
      String role = text(st.profile.get("role"));
      if (role == null) {
        failed.add(failure(studentId, name, "学生不存在"));
        continue;
      }
      if (!"STUDENT".equals(role)) {
        failed.add(failure(studentId, name, "仅学生可选课"));
        continue;
      }
      var existing = st.enrollment(courseId);
      if (remove) {
        if (existing == null || !ACTIVE.equals(text(existing.get("status")))) {
          skipped++; // 没有有效选课记录：跳过而不是报错，避免整批中断
          continue;
        }
        // 教师一旦录入成绩（任何状态）就不允许退课，只记录该学生，不影响其他人。
        if (!rows("grades", Map.of("course_id", courseId, "student_id", studentId)).isEmpty()) {
          failed.add(failure(studentId, name, GRADED_DROP));
          continue;
        }
        ops.add(update("enrollments", text(existing.get("id")), Map.of("status", DROPPED), null));
        ops.add(
            enrollmentRecord(
                publishId, courseId, code, term, studentId, action, reason, u.id(), now));
        removed++;
        continue;
      }
      if (st.active(courseId)) {
        skipped++; // 已经选过：计入 skipped，不报错
        continue;
      }
      if (intOf(st.profile.get("enabled"), 1) != 1) {
        failed.add(failure(studentId, name, "账号已停用，不能选课"));
        continue;
      }
      boolean reuse = existing != null && text(existing.get("id")) != null;
      var values = new LinkedHashMap<String, Object>();
      if (!reuse) {
        values.put("id", UUID.randomUUID().toString());
        values.put("course_id", courseId);
        values.put("student_id", studentId);
      }
      values.put("source", "ADMIN");
      put(values, "publish_id", publishId);
      values.put("selected_at", now);
      values.put("status", ACTIVE);
      // 唯一索引是 (course_id,student_id)：曾经退课的 DROPPED 行只能改，不能再插。
      ops.add(
          reuse
              ? update("enrollments", text(existing.get("id")), values, null)
              : insert("enrollments", values));
      ops.add(
          enrollmentRecord(publishId, courseId, code, term, studentId, action, reason, u.id(), now));
      added++;
    }
    if (!ops.isEmpty()) repo.mutate(ops, u.id(), "ENROLLMENT_BATCH", courseId);
    var result = new LinkedHashMap<String, Object>();
    result.put("ok", true);
    result.put("added", added);
    result.put("skipped", skipped);
    result.put("removed", removed);
    result.put("failed", failed);
    // 只有指定了班级才下发班级人数：前端据此显示「班级 N 人」，否则会显示「班级 0 人」。
    if (classGiven) result.put("classStudents", classStudents.size());
    result.put("total", targets.size());
    return result;
  }

  /**
   * 批量选课的所属批次：请求里给了 publishId 就优先用它（批次行不存在时也照样写入该编号），
   * 否则在该课程所属学期里找 {@code course_ids} 含该课程的 OPEN 批次，没有 OPEN 再找 CLOSED。
   */
  private Map<String, Object> batchPublish(String requestedId, String courseId, String term) {
    if (requestedId != null) {
      var publish = first("course_selections", Map.of("id", requestedId));
      return publish == null ? Map.of("id", requestedId) : publish;
    }
    for (String status : List.of(OPEN, CLOSED)) {
      for (var publish : rows("course_selections", Map.of("status", status))) {
        if (!status.equals(text(publish.get("status")))) continue;
        if (!Objects.equals(term, text(publish.get("term")))) continue;
        if (!OrganizationService.split(publish.get("course_ids")).contains(courseId)) continue;
        return publish;
      }
    }
    return null;
  }

  /**
   * 按课程批量操作的选课流水。
   *
   * <p>与既有的 {@link #auditRecord} 同表同列，区别只有两点：{@code publish_id} 允许为空（找不到
   * 所属批次时留空），{@code term} 用课程所属学期而不是发布批次里的学期。
   */
  private Protocol.Operation enrollmentRecord(
      String publishId,
      String courseId,
      String code,
      String term,
      String studentId,
      String action,
      String reason,
      String operator,
      String createdAt) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID().toString());
    put(row, "publish_id", publishId);
    put(row, "course_id", courseId);
    put(row, "code", code);
    put(row, "student_id", studentId);
    put(row, "term", term);
    row.put("action", action);
    put(row, "reason", reason);
    put(row, "operator", operator);
    put(row, "created_at", createdAt);
    return insert("enrollment_records", row);
  }

  /** 失败明细带学生姓名，前端可以直接展示“谁、为什么”。 */
  private Map<String, Object> failure(String studentId, String name, String reason) {
    var row = failure(studentId, reason);
    put(row, "name", name);
    return row;
  }

  /**
   * 自动结算定时任务。
   *
   * <p>每分钟扫描一次 status=OPEN 且 end_time 已经过去的选课发布，自动执行与
   * {@code POST /selections/settle} 完全相同的结算逻辑（同一个 {@link #settleBatch}），
   * 因此“不满足最低开课人数自动退回”在窗口结束后无需人工操作也会发生。
   *
   * <p>{@code @EnableScheduling} 已经在业务启动类 BusinessApplication 上开启；方法整体
   * try/catch，任何异常只打印日志，绝不中断调度线程。
   */
  @Scheduled(initialDelay = 15000, fixedDelay = 60000)
  public void autoSettleExpired() {
    int settled = 0;
    try {
      var now = Instant.now();
      for (var publish : rows("course_selections", Map.of("status", OPEN))) {
        if (!OPEN.equals(text(publish.get("status")))) continue;
        String id = text(publish.get("id"));
        if (id == null) continue;
        try {
          if (parseTime(publish.get("end_time")).isAfter(now)) continue;
          settleBatch(id, SYSTEM_ACTOR);
          settled++;
        } catch (Exception e) {
          System.err.println("[SelectionService] 自动结算失败 " + id + "：" + e.getMessage());
        }
      }
    } catch (Exception e) {
      System.err.println("[SelectionService] 自动结算任务异常：" + e);
    }
    if (settled > 0) System.out.println("[SelectionService] 自动结算完成 " + settled + " 个选课批次");
  }

  // ---------------------------------------------------------------- 规则

  /**
   * 校验某个学生能否选某门课，返回课程行；不满足时抛出带明确状态码与提示的异常。
   *
   * <p>{@code /selections/available} 把这里的异常转成 reason 文案，因此“页面上显示可选”与
   * “真的能选上”用的是同一套规则。
   *
   * @param studentChecks false 时跳过“仅学生可选”“选课范围”两条学生级规则，供管理员预览
   */
  private Map<String, Object> checkSelectable(
      StudentState st, Map<String, Object> publish, String courseId, boolean studentChecks) {
    // 3) 本次选课是否允许选课
    ApiException.require(intOf(publish.get("allow_add"), 0) == 1, 403, ADD_FORBIDDEN);
    if (studentChecks) {
      // 4) 必须是启用的学生
      ApiException.require("STUDENT".equals(st.role), 403, "仅学生可选课");
      Object enabled = st.profile.get("enabled");
      ApiException.require(enabled == null || intOf(enabled, 1) == 1, 403, "账号已停用");
      // 5) 必须在选课范围内
      ApiException.require(OrganizationService.inScope(publish, st.profile), 403, OUT_OF_SCOPE);
    }
    // 6) 课程必须属于本次发布
    ApiException.require(
        OrganizationService.split(publish.get("course_ids")).contains(courseId),
        400,
        "该课程不在本次选课范围内");
    // 7) 课程存在且未停开
    var course = st.allCourses.get(courseId);
    ApiException.require(course != null, 404, "课程不存在");
    ApiException.require(!COURSE_CANCELLED.equals(text(course.get("status"))), 409, "课程已停开");
    // 8) 学期必须与发布一致
    String term = text(publish.get("term"));
    ApiException.require(
        Objects.equals(term, text(course.get("term"))), 409, "课程学期与选课学期不一致");
    // 9) 不得重复选同一教学班
    ApiException.require(!st.active(courseId), 409, ALREADY_SELECTED);
    // 10/11/12) 同一课程代码的历史判定
    var history = codeHistory(st, term, text(course.get("code")), courseId);
    boolean allowRetake = intOf(publish.get("allow_retake"), 0) == 1;
    if (history.sameTermOtherClass)
      ApiException.require(history.failed && allowRetake, 409, CODE_TAKEN);
    ApiException.require(!history.passed, 409, PASSED_BEFORE);
    if (history.failed) ApiException.require(allowRetake, 403, RETRY_FORBIDDEN);
    // 13) 学分上限
    checkCredits(st, publish, course);
    return course;
  }

  /**
   * 同一课程代码的修读历史。
   *
   * <p>{@code passed}：此前学期已经通过（成绩已提交且有效分 >= 60），不允许重复修读；
   * {@code failed}：此前学期挂科，属于重修场景；{@code sameTermOtherClass}：本学期已经选了
   * 同一课程代码的另一个教学班（不同教师 / 不同教学班）。
   */
  private record CodeHistory(boolean passed, boolean failed, boolean sameTermOtherClass) {}

  private CodeHistory codeHistory(
      StudentState st, String term, String code, String targetCourseId) {
    if (code == null) return new CodeHistory(false, false, false);
    boolean passed = false, failed = false, sameTerm = false;
    for (var enrollment : st.enrollments) {
      // 只有仍然有效的选课记录才构成修读历史，退课记录不参与判定。
      if (!ACTIVE.equals(text(enrollment.get("status")))) continue;
      String otherId = text(enrollment.get("course_id"));
      if (otherId == null || otherId.equals(targetCourseId)) continue;
      var other = st.allCourses.get(otherId);
      if (other == null || !code.equals(text(other.get("code")))) continue;
      String otherTerm = text(other.get("term"));
      if (otherTerm == null) continue;
      if (otherTerm.equals(term)) {
        sameTerm = true;
        continue;
      }
      if (otherTerm.compareTo(term) > 0) continue; // 更晚学期的记录不构成“此前修读”
      Double effective = effectiveScore(other, st.id);
      if (effective == null) continue; // 未出成绩 / 未提交：既不算通过也不算挂科
      if (effective >= 60) passed = true;
      else failed = true;
    }
    return new CodeHistory(passed, failed, sameTerm);
  }

  /** 有效分：按课程 weights 求加权总评，再按补考封顶；未提交或成绩损坏返回 null。 */
  private Double effectiveScore(Map<String, Object> course, String studentId) {
    return effectiveScore(course, studentId, null);
  }

  /**
   * 有效分（可复用成绩索引的重载）。
   *
   * <p>{@code gradeIndex} 为 {@code null} 时按单条查询（适合只判断一门课的场景）；批量判定时
   * 传入一次读好的索引，避免逐条打 data-service。
   */
  private Double effectiveScore(
      Map<String, Object> course,
      String studentId,
      Map<String, Map<String, Object>> gradeIndex) {
    String courseId = text(course.get("id"));
    if (courseId == null || studentId == null) return null;
    var grade =
        gradeIndex == null
            ? first("grades", Map.of("course_id", courseId, "student_id", studentId))
            : gradeIndex.get(courseId + "|" + studentId);
    if (grade == null || !SUBMITTED.equals(text(grade.get("state")))) return null;
    try {
      return Models.effective(
          Models.object(grade.get("payload")), Models.object(course.get("weights")));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 学分上限：本学期已选 ACTIVE 课程学分合计 + 本课程学分 <= maxCredits（0 表示不限）。 */
  private void checkCredits(
      StudentState st, Map<String, Object> publish, Map<String, Object> course) {
    int maxCredits = intOf(publish.get("max_credits"), 0);
    if (maxCredits <= 0) return;
    String term = text(publish.get("term"));
    double used = 0;
    for (var enrollment : st.enrollments) {
      if (!ACTIVE.equals(text(enrollment.get("status")))) continue;
      var other = st.allCourses.get(text(enrollment.get("course_id")));
      if (other == null || !Objects.equals(term, text(other.get("term")))) continue;
      used += credits(other);
    }
    double wanted = credits(course);
    ApiException.require(
        used + wanted <= maxCredits + 1e-9,
        409,
        "超出学分上限：本学期已选 "
            + amount(used)
            + " 学分，本课程 "
            + amount(wanted)
            + " 学分，上限 "
            + maxCredits
            + " 学分");
  }

  // ---------------------------------------------------------------- 内部工具

  /**
   * 一名学生与本次操作相关的数据快照：用户行、全部选课记录、课程索引。
   *
   * <p>一次操作内只读一次库，既减少跨服务查询，也保证规则校验看到的是同一份数据。
   */
  private static final class StudentState {
    final String id;
    final String role;
    final Map<String, Object> profile;
    final List<Map<String, Object>> enrollments;
    final Map<String, Map<String, Object>> allCourses;
    private final Map<String, Map<String, Object>> byCourse = new LinkedHashMap<>();

    StudentState(
        String id,
        String role,
        Map<String, Object> profile,
        List<Map<String, Object>> enrollments,
        Map<String, Map<String, Object>> allCourses) {
      this.id = id;
      this.role = role;
      this.profile = profile;
      this.enrollments = enrollments;
      this.allCourses = allCourses;
      for (var enrollment : enrollments) {
        String courseId = text(enrollment.get("course_id"));
        if (courseId != null) byCourse.put(courseId, enrollment);
      }
    }

    /** 该生对该教学班是否已有 ACTIVE 选课记录。 */
    boolean active(String courseId) {
      var enrollment = byCourse.get(courseId);
      return enrollment != null && ACTIVE.equals(text(enrollment.get("status")));
    }

    /** 该生对该教学班的任何选课记录（可能已退课），用于复用既有行避免唯一索引冲突。 */
    Map<String, Object> enrollment(String courseId) {
      return byCourse.get(courseId);
    }
  }

  private StudentState studentState(Models.User u, Map<String, Map<String, Object>> index) {
    return studentState(u.id(), u.role(), u.collegeId(), u.majorId(), u.classId(), index);
  }

  private StudentState studentState(
      String id,
      String role,
      String collegeId,
      String majorId,
      String classId,
      Map<String, Map<String, Object>> index) {
    var profile = new LinkedHashMap<String, Object>();
    var stored = first("users", Map.of("id", id));
    if (stored != null) profile.putAll(stored);
    profile.putIfAbsent("id", id);
    // 用户行里缺失的组织归属用会话里的值补齐，保证选课范围匹配稳定。
    fill(profile, "college_id", collegeId);
    fill(profile, "major_id", majorId);
    fill(profile, "class_id", classId);
    var enrollments = new ArrayList<>(rows("enrollments", Map.of("student_id", id)));
    return new StudentState(id, role, profile, enrollments, index);
  }

  private static void fill(Map<String, Object> profile, String key, String value) {
    if (text(profile.get(key)) == null && value != null && !value.isBlank()) profile.put(key, value);
  }

  /** 课程索引：id -> 课程行；选课规则基本都在这份索引上判断，避免逐条查询。 */
  private Map<String, Map<String, Object>> courseIndex() {
    var index = new LinkedHashMap<String, Map<String, Object>>();
    for (var course : rows("courses", Map.of())) {
      String id = text(course.get("id"));
      if (id != null) index.put(id, course);
    }
    return index;
  }

  /** 教师的名称表与三级组织名称表；一次操作内只加载一次，前端拿到的永远是名称。 */
  private final class Names {
    private final Map<String, String> teachers = new HashMap<>();
    private final Map<String, String> colleges = new HashMap<>();
    private final Map<String, String> majors = new HashMap<>();
    private final Map<String, String> classes = new HashMap<>();
    private Map<String, String> students;

    Names() {
      for (var row : rows("users", Map.of("role", "TEACHER")))
        teachers.put(Objects.toString(row.get("id"), ""), Objects.toString(row.get("name"), ""));
      fill(colleges, Models.Level.COLLEGE);
      fill(majors, Models.Level.MAJOR);
      fill(classes, Models.Level.CLASS);
    }

    private void fill(Map<String, String> target, Models.Level level) {
      for (var row : rows(level.table, Map.of()))
        target.put(Objects.toString(row.get("id"), ""), OrganizationService.displayName(level, row));
    }

    String teacher(String id) {
      return id == null ? null : teachers.get(id);
    }

    String college(String id) {
      return id == null ? null : colleges.get(id);
    }

    String major(String id) {
      return id == null ? null : majors.get(id);
    }

    String className(String id) {
      return id == null ? null : classes.get(id);
    }

    String student(String id) {
      if (students == null) {
        students = new HashMap<>();
        for (var row : rows("users", Map.of("role", "STUDENT")))
          students.put(Objects.toString(row.get("id"), ""), Objects.toString(row.get("name"), ""));
      }
      return id == null ? null : students.get(id);
    }
  }

  /** 发布对象的对外形态：课程、范围全部翻译成名称，并附已选人数与状态文案。 */
  private Map<String, Object> describe(
      Map<String, Object> publish,
      List<Map<String, Object>> enrollments,
      Map<String, Map<String, Object>> index,
      Names names) {
    String id = text(publish.get("id"));
    String status = text(publish.get("status"));
    var row = new LinkedHashMap<String, Object>();
    row.put("id", id);
    row.put("name", text(publish.get("name")));
    row.put("term", text(publish.get("term")));
    row.put("status", status);
    row.put("statusName", statusName(status));
    row.put("startTime", text(publish.get("start_time")));
    row.put("start_time", text(publish.get("start_time")));
    row.put("endTime", text(publish.get("end_time")));
    row.put("end_time", text(publish.get("end_time")));
    row.put("minEnroll", intOf(publish.get("min_enroll"), 0));
    row.put("maxCredits", intOf(publish.get("max_credits"), 0));
    row.put("allowAdd", intOf(publish.get("allow_add"), 0) == 1);
    row.put("allowDrop", intOf(publish.get("allow_drop"), 0) == 1);
    row.put("allowRetake", intOf(publish.get("allow_retake"), 0) == 1);
    row.put("note", text(publish.get("note")));
    row.put("publishedBy", text(publish.get("published_by")));
    row.put("published_by", text(publish.get("published_by")));
    row.put("publishedAt", text(publish.get("published_at")));
    row.put("published_at", text(publish.get("published_at")));
    row.put("version", intOf(publish.get("version"), 0));
    var courseIds = OrganizationService.split(publish.get("course_ids"));
    row.put("courseIds", courseIds);
    // 该批次里哪些课程存在重修学生（按「同一课程号在更早学期挂过科」判定），
    // 教务据此知道这门课里有学生是重修，而不需要课程名带任何标记。
    var failedByStudent = failedCodes(enrollments, index);
    var courses = new ArrayList<Map<String, Object>>();
    for (String courseId : courseIds) {
      var course = index.get(courseId);
      if (course == null) continue;
      var courseData = courseRow(course, names);
      String code = text(course.get("code"));
      int retakeCount = 0;
      for (var enrollment : enrollments) {
        if (!ACTIVE.equals(text(enrollment.get("status")))) continue;
        if (!courseId.equals(text(enrollment.get("course_id")))) continue;
        String studentId = text(enrollment.get("student_id"));
        var studentFailed = studentId == null ? null : failedByStudent.get(studentId);
        if (studentFailed != null && studentFailed.retakeIn(code, text(course.get("term"))))
          retakeCount++;
      }
      courseData.put("retakeCount", retakeCount);
      courses.add(courseData);
    }
    row.put("courses", courses);
    var courseNames = new ArrayList<String>();
    for (var course : courses) courseNames.add(Objects.toString(course.get("name"), ""));
    row.put("courseNames", courseNames);
    row.put("courseCount", courses.size());
    row.put("selectedCount", count(enrollments, "publish_id", id, null));
    var scopeNames = new ArrayList<String>();
    scopeNames.addAll(
        scope(row, "scopeCollege", "scope_college_ids", Models.Level.COLLEGE, publish, names));
    scopeNames.addAll(
        scope(row, "scopeMajor", "scope_major_ids", Models.Level.MAJOR, publish, names));
    scopeNames.addAll(
        scope(row, "scopeClass", "scope_class_ids", Models.Level.CLASS, publish, names));
    row.put("scopeNames", scopeNames);
    row.put("scopeLabel", scopeNames.isEmpty() ? "全校不限" : String.join("、", scopeNames));
    return row;
  }

  private List<String> scope(
      Map<String, Object> row,
      String prefix,
      String column,
      Models.Level level,
      Map<String, Object> publish,
      Names names) {
    var ids = OrganizationService.split(publish.get(column));
    var display = new ArrayList<String>();
    for (String id : ids) {
      String name =
          switch (level) {
            case COLLEGE -> names.college(id);
            case MAJOR -> names.major(id);
            case CLASS -> names.className(id);
          };
      display.add(name == null ? id : name);
    }
    row.put(prefix + "Ids", ids);
    row.put(prefix + "Names", display);
    return display;
  }

  /** 可选课程列表里的单个课程：课程详情 + 是否已选 / 已选人数 / 是否满足选课规则 / 是否重修。 */
  private List<Map<String, Object>> options(
      StudentState st,
      Map<String, Object> publish,
      Map<String, Map<String, Object>> index,
      Names names,
      List<Map<String, Object>> enrollments,
      boolean student) {
    var result = new ArrayList<Map<String, Object>>();
    // 重修判定用学生自己的修读历史（已提交且有效分 < 60 的课程代码），一次算好供整批课程复用。
    var myFailed = failedCodes(st.enrollments, st.allCourses).get(st.id);
    for (String courseId : OrganizationService.split(publish.get("course_ids"))) {
      var course = index.get(courseId);
      if (course == null) continue;
      var option = courseRow(course, names);
      option.put("selected", st.active(courseId));
      option.put("enrolled", count(enrollments, "course_id", courseId, ACTIVE));
      // 该课程号此前挂过科 → 本学期这门课就是重修。
      boolean retake =
          myFailed != null && myFailed.retakeIn(text(course.get("code")), text(course.get("term")));
      option.put("retake", retake);
      option.put("retakeLabel", retake ? RETRY_LABEL : null);
      try {
        checkSelectable(st, publish, courseId, student);
        option.put("eligible", true);
        option.put("reason", null);
      } catch (ApiException e) {
        option.put("eligible", false);
        option.put("reason", e.getMessage());
      }
      result.add(option);
    }
    return result;
  }

  private Map<String, Object> courseRow(Map<String, Object> course, Names names) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", text(course.get("id")));
    courseFields(row, course, names);
    return row;
  }

  private void courseFields(Map<String, Object> row, Map<String, Object> course, Names names) {
    row.put("code", course == null ? null : text(course.get("code")));
    row.put("name", course == null ? null : text(course.get("name")));
    row.put("term", course == null ? null : text(course.get("term")));
    row.put("credits", course == null ? 0 : credits(course));
    row.put(
        "teacherName",
        course == null ? null : names.teacher(text(course.get("teacher_id"))));
    row.put("collegeName", course == null ? null : names.college(text(course.get("college_id"))));
    row.put("className", course == null ? null : names.className(text(course.get("class_id"))));
  }

  /** 不满足最低开课人数 / 整批取消的自动退回：置 DROPPED 并逐条写流水。 */
  private int refund(
      List<Protocol.Operation> ops,
      Map<String, Object> publish,
      List<Map<String, Object>> enrollments,
      Map<String, Map<String, Object>> index,
      String reason,
      String operator) {
    int refunded = 0;
    String now = Instant.now().toString();
    for (var enrollment : enrollments) {
      String enrollmentId = text(enrollment.get("id"));
      if (enrollmentId == null) continue;
      String courseId = text(enrollment.get("course_id"));
      var course = courseId == null ? null : index.get(courseId);
      ops.add(update("enrollments", enrollmentId, Map.of("status", DROPPED), null));
      ops.add(
          auditRecord(
              publish,
              courseId,
              course == null ? null : text(course.get("code")),
              text(enrollment.get("student_id")),
              "AUTO_REFUND",
              reason,
              operator,
              now));
      refunded++;
    }
    return refunded;
  }

  /** 选课流水：写一条 enrollment_records，随同一次 mutate 进入加密审计账本。 */
  private Protocol.Operation auditRecord(
      Map<String, Object> publish,
      String courseId,
      String code,
      String studentId,
      String action,
      String reason,
      String operator,
      String createdAt) {
    var row = new LinkedHashMap<String, Object>();
    row.put("id", UUID.randomUUID().toString());
    row.put("publish_id", text(publish.get("id")));
    put(row, "course_id", courseId);
    put(row, "code", code);
    put(row, "student_id", studentId);
    put(row, "term", text(publish.get("term")));
    row.put("action", action);
    put(row, "reason", reason);
    put(row, "operator", operator);
    put(row, "created_at", createdAt);
    return insert("enrollment_records", row);
  }

  /** 数据服务不接受空值字段，可选字段为空时不写入该列。 */
  private static void put(Map<String, Object> row, String key, String value) {
    if (value != null) row.put(key, value);
  }

  private Map<String, Object> failure(String studentId, String reason) {
    var row = new LinkedHashMap<String, Object>();
    row.put("studentId", studentId);
    row.put("reason", reason);
    return row;
  }

  private Map<String, Object> requirePublish(String id) {
    ApiException.require(id != null && !id.isBlank(), 400, "缺少选课发布编号");
    var publish = first("course_selections", Map.of("id", id));
    ApiException.require(publish != null, 404, "选课发布不存在");
    return publish;
  }

  private void requireOpen(Map<String, Object> publish) {
    ApiException.require(OPEN.equals(text(publish.get("status"))), 409, NOT_OPEN);
  }

  /** 时间窗口：未开始与已结束分别给出可读原因，前端可直接提示学生。 */
  private void requireWindow(Map<String, Object> publish, Instant now) {
    Instant start = parseTime(publish.get("start_time"));
    Instant end = parseTime(publish.get("end_time"));
    ApiException.require(!now.isBefore(start), 400, NOT_STARTED);
    ApiException.require(!now.isAfter(end), 400, ENDED);
  }

  /**
   * 宽松时间解析：支持 {@code 2026-09-01T08:00}、{@code 2026-09-01T08:00:00}、
   * {@code 2026-09-01T08:00:00Z} 与 {@code 2026-09-01 08:00}。
   *
   * <p>带时区的写法按该时区解释，不带时区的写法按本机默认时区解释，最终统一成 Instant 比较；
   * 解析失败一律 400「时间格式错误」。
   */
  static Instant parseTime(Object raw) {
    String value = text(raw);
    ApiException.require(value != null, 400, BAD_TIME);
    String normalized = value.replace(' ', 'T');
    try {
      return Instant.parse(normalized);
    } catch (RuntimeException ignored) {
      // 继续尝试其他写法
    }
    try {
      return OffsetDateTime.parse(normalized).toInstant();
    } catch (RuntimeException ignored) {
      // 继续尝试其他写法
    }
    try {
      return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          .atZone(ZoneId.systemDefault())
          .toInstant();
    } catch (RuntimeException ignored) {
      // 继续尝试其他写法
    }
    try {
      return LocalDate.parse(normalized).atStartOfDay(ZoneId.systemDefault()).toInstant();
    } catch (RuntimeException ignored) {
      // 全部写法都不匹配
    }
    throw new ApiException(400, "REQUEST_REJECTED", BAD_TIME);
  }

  /** 落库时统一成本地 ISO-8601（不带时区），与既有 Excel/前端展示习惯一致。 */
  private static String localIso(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  /** 把前端提交的组织（名称或编号）归一化成编号；空表示不限。 */
  private String resolveScope(Models.Level level, Object raw) {
    var values = strings(raw);
    if (values.isEmpty()) return "";
    var ids = new ArrayList<String>();
    for (String value : values) {
      String id = organizations.resolveOwn(level, value);
      ApiException.require(id != null, 400, "指定的" + level.label + "不存在：" + value);
      if (!ids.contains(id)) ids.add(id);
    }
    return String.join(",", ids);
  }

  private static Map<String, Object> findCourse(
      Map<String, Map<String, Object>> index, String raw) {
    var byId = index.get(raw);
    if (byId != null) return byId;
    for (var course : index.values())
      if (raw.equals(text(course.get("name"))) || raw.equals(text(course.get("code"))))
        return course;
    return null;
  }

  /** 接受数组、逗号分隔字符串或单个值；这是前端“名称/编号混用”的统一入口。 */
  private static List<String> strings(Object raw) {
    if (raw == null) return List.of();
    if (raw instanceof Collection<?> collection) {
      var result = new ArrayList<String>();
      for (Object item : collection) {
        String value = text(item);
        if (value != null) result.add(value.strip());
      }
      return result;
    }
    if (raw instanceof Object[] array) {
      var result = new ArrayList<String>();
      for (Object item : array) {
        String value = text(item);
        if (value != null) result.add(value.strip());
      }
      return result;
    }
    return OrganizationService.split(raw);
  }

  private static int requiredInt(
      Map<String, Object> body, String key, int min, int max, String message) {
    Object raw = body.get(key);
    ApiException.require(text(raw) != null, 400, message);
    int value;
    try {
      value = (int) Math.round(Models.number(raw));
    } catch (RuntimeException e) {
      throw new ApiException(400, "REQUEST_REJECTED", message);
    }
    ApiException.require(value >= min && value <= max, 400, message);
    return value;
  }

  private static int optionalInt(
      Map<String, Object> body, String key, int fallback, int min, int max, String message) {
    if (text(body.get(key)) == null) return fallback;
    return requiredInt(body, key, min, max, message);
  }

  /** 兼容 camelCase 与数据库风格两种字段名。 */
  private static Object firstValue(Map<String, Object> body, String camel, String snake) {
    Object value = body.get(camel);
    return value == null ? body.get(snake) : value;
  }

  private static String statusName(String status) {
    if (OPEN.equals(status)) return "进行中";
    if (CLOSED.equals(status)) return "已结束";
    if (CANCELLED.equals(status)) return "已取消";
    return status;
  }

  private static String actionName(String action) {
    if (action == null) return null;
    return switch (action) {
      case "SELECT" -> "选课";
      case "DROP" -> "退课";
      case "ADMIN_ASSIGN" -> "教务选入";
      case "ADMIN_REMOVE" -> "教务退课";
      case "AUTO_REFUND" -> "自动退回";
      default -> action;
    };
  }

  private static double credits(Map<String, Object> course) {
    Object value = course.get("credits");
    if (value == null) return 0;
    try {
      return Models.number(value);
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static String amount(double value) {
    return value == Math.rint(value)
        ? String.valueOf((long) value)
        : String.valueOf(Math.round(value * 100) / 100.0);
  }

  private static int count(
      List<Map<String, Object>> enrollments, String key, String value, String status) {
    int total = 0;
    for (var enrollment : enrollments) {
      if (value != null && !value.equals(text(enrollment.get(key)))) continue;
      if (status != null && !status.equals(text(enrollment.get("status")))) continue;
      total++;
    }
    return total;
  }

  /** 空安全的查询：局部 Mock 或服务异常时不会返回 null，调用方无需到处判空。 */
  private List<Map<String, Object>> rows(String table, Map<String, Object> where) {
    var found = repo.find(table, where);
    return found == null ? List.of() : found;
  }

  private Map<String, Object> first(String table, Map<String, Object> where) {
    var found = rows(table, where);
    return found.isEmpty() ? null : found.get(0);
  }

  private static String text(Object value) {
    return value == null || value.toString().isBlank() ? null : value.toString().strip();
  }

  private static int intOf(Object value, int fallback) {
    if (text(value) == null) return fallback;
    try {
      return (int) Math.round(Double.parseDouble(value.toString().strip()));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
