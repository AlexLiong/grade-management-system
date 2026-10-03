package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 组织管理路由：路径覆盖、层级校验、分页与名称附加、保存与删除的审计组装、人员调整。 */
class OrganizeRoutesTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final OrganizationService organizations = new OrganizationService(repo);
  private final OrganizeRoutes routes = new OrganizeRoutes(organizations, repo);
  private final Models.User admin =
      new Models.User("a1", "admin", "管理员", "ADMIN", Set.of("ORG_ADMIN", "USER_ADMIN"), 0);
  private final Models.User student =
      new Models.User("s9", "stu", "学生", "STUDENT", Set.of("QUERY"), 0);

  // ---------------------------------------------------------------- 路径

  @Test
  void handlesCoversOrganizationPaths() {
    assertTrue(
        routes
            .handles()
            .containsAll(
                Set.of(
                    "/organizations",
                    "/organizations/options",
                    "/organizations/impact",
                    "/organizations/members",
                    "/organizations/students",
                    "/organizations/save",
                    "/organizations/delete",
                    "/organizations/assign")));
    assertEquals(8, routes.handles().size());
    assertFalse(routes.handles().contains("/organizations/members/save"));
  }

  @Test
  void dispatchRejectsUnknownPath() {
    assertEquals(404, assertThrows(ApiException.class, () -> routes.dispatch(get("/organizations/x", Map.of(), admin))).status);
    assertEquals(
        404,
        assertThrows(
                ApiException.class,
                () -> routes.dispatch(post("/organizations/members/save", Map.of(), admin)))
            .status);
  }

  @Test
  void dispatchRejectsUnknownOrMissingLevel() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> routes.dispatch(get("/organizations", Map.of("level", "UNKNOWN"), admin)))
            .status);
    assertEquals(
        400,
        assertThrows(ApiException.class, () -> routes.dispatch(get("/organizations", Map.of(), admin)))
            .status);
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> routes.dispatch(post("/organizations/save", Map.of("level", "campus"), admin)))
            .status);
  }

  @Test
  void unknownLevelRejectedBeforePermissionCheck() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () -> routes.dispatch(get("/organizations", Map.of("level", "x"), student)))
            .status);
  }

  // ---------------------------------------------------------------- 列表

  @Test
  void listRequiresOrgAdmin() {
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () -> routes.dispatch(get("/organizations", Map.of("level", "COLLEGE"), student)))
            .status);
  }

  @Test
  void listCollegesAddsMajorClassAndStudentCounts() {
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院", "short_name", "信息学院")));
    when(repo.find("majors", Map.of("college_id", "C01001")))
        .thenReturn(List.of(row("id", "M01001"), row("id", "M01002")));
    when(repo.find("classes", Map.of("college_id", "C01001")))
        .thenReturn(List.of(row("id", "B01001"), row("id", "B01002"), row("id", "B01003")));
    when(repo.find("users", Map.of("college_id", "C01001", "role", "STUDENT")))
        .thenReturn(List.of(row("id", "s1"), row("id", "s2"), row("id", "s3"), row("id", "s4"), row("id", "s5")));
    var result = page(routes.dispatch(get("/organizations", Map.of("level", "COLLEGE"), admin)));
    var items = items(result);
    assertEquals(1, items.size());
    assertEquals(2, items.get(0).get("majorCount"));
    assertEquals(3, items.get(0).get("classCount"));
    assertEquals(5, items.get(0).get("studentCount"));
    assertEquals(1, result.get("total"));
  }

  @Test
  void listMajorsFiltersByCollegeNameAndAddsCollegeName() {
    when(repo.find("colleges", Map.of()))
        .thenReturn(
            List.of(
                row("id", "C01001", "name", "信息工程学院"),
                row("id", "C01002", "name", "经济与管理学院")));
    when(repo.findOne("colleges", Map.of("id", "信息工程学院"))).thenReturn(null);
    when(repo.findOne("colleges", Map.of("name", "信息工程学院")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.find("majors", Map.of()))
        .thenReturn(
            List.of(
                row("id", "M01001", "name", "软件工程", "college_id", "C01001"),
                row("id", "M02001", "name", "工商管理", "college_id", "C01002")));
    when(repo.find("classes", Map.of("major_id", "M01001")))
        .thenReturn(List.of(row("id", "B01001")));
    when(repo.find("users", Map.of("major_id", "M01001", "role", "STUDENT")))
        .thenReturn(List.of(row("id", "s1"), row("id", "s2")));
    var result =
        page(
            routes.dispatch(
                get("/organizations", Map.of("level", "MAJOR", "college", "信息工程学院"), admin)));
    var items = items(result);
    assertEquals(1, items.size());
    assertEquals("M01001", items.get(0).get("id"));
    assertEquals("信息工程学院", items.get(0).get("collegeName"));
    assertEquals(1, items.get(0).get("classCount"));
    assertEquals(2, items.get(0).get("studentCount"));
  }

  @Test
  void listClassesAddsCollegeAndMajorNames() {
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row("id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023", "college_id", "C01001", "major_id", "M01001")));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("majors", Map.of())).thenReturn(List.of(row("id", "M01001", "name", "软件工程")));
    when(repo.find("users", Map.of("class_id", "B01001", "role", "STUDENT")))
        .thenReturn(List.of(row("id", "s1")));
    var result = page(routes.dispatch(get("/organizations", Map.of("level", "CLASS"), admin)));
    var item = items(result).get(0);
    assertEquals("信息工程学院", item.get("collegeName"));
    assertEquals("软件工程", item.get("majorName"));
    assertEquals(1, item.get("studentCount"));
  }

  @Test
  void optionsAreReadableByAnyUserAndCarryParentNames() {
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院", "short_name", "信息学院")));
    when(repo.find("majors", Map.of()))
        .thenReturn(List.of(row("id", "M01001", "name", "软件工程", "college_id", "C01001")));
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row(
                    "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                    "college_id", "C01001", "major_id", "M01001")));
    @SuppressWarnings("unchecked")
    var result = (Map<String, Object>) routes.dispatch(get("/organizations/options", Map.of(), student));
    @SuppressWarnings("unchecked")
    var colleges = (List<Map<String, Object>>) result.get("colleges");
    @SuppressWarnings("unchecked")
    var majors = (List<Map<String, Object>>) result.get("majors");
    @SuppressWarnings("unchecked")
    var classes = (List<Map<String, Object>>) result.get("classes");
    assertEquals("信息学院", colleges.get(0).get("shortName"));
    assertEquals("信息工程学院", majors.get(0).get("collegeName"));
    assertEquals("M01001", classes.get(0).get("majorId"));
    assertEquals("软件工程", classes.get(0).get("majorName"));
    assertEquals("2023", classes.get(0).get("gradeYear"));
  }

  @Test
  void listItemsDoNotExposeCodeKey() {
    // 库表里可能仍带 code 列（历史结构），接口一律不再输出该键：编号就是主键 id。
    stubLegacyRows();
    for (String level : List.of("COLLEGE", "MAJOR", "CLASS")) {
      var listed = items(page(routes.dispatch(get("/organizations", Map.of("level", level), admin))));
      assertFalse(listed.isEmpty(), level + " 应至少有一行");
      for (var item : listed) {
        assertNotNull(item.get("id"));
        assertFalse(item.containsKey("code"), level + " 列表不应输出 code 键");
        assertFalse(item.containsKey("counselor"), level + " 列表不应输出 counselor 键");
      }
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void optionsItemsDoNotExposeCodeKey() {
    stubLegacyRows();
    var options =
        (Map<String, Object>) routes.dispatch(get("/organizations/options", Map.of(), student));
    for (String group : List.of("colleges", "majors", "classes")) {
      var groupItems = (List<Map<String, Object>>) options.get(group);
      assertFalse(groupItems.isEmpty(), group + " 应至少有一项");
      for (var item : groupItems) {
        assertNotNull(item.get("id"));
        assertFalse(item.containsKey("code"), group + " 不应输出 code 键");
      }
    }
    assertFalse(((List<Map<String, Object>>) options.get("classes")).get(0).containsKey("counselor"));
  }

  @Test
  void impactReturnsCascadeCounts() {
    when(repo.findOne("classes", Map.of("id", "B01001")))
        .thenReturn(row("id", "B01001", "name", "2023级-软件工程-2301班", "college_id", "C01001", "major_id", "M01001"));
    when(repo.find("users", Map.of("class_id", "B01001")))
        .thenReturn(List.of(row("id", "s1"), row("id", "s2")));
    when(repo.find("courses", Map.of("class_id", "B01001"))).thenReturn(List.of(row("id", "net-2026")));
    var result =
        (Map<String, Object>)
            routes.dispatch(get("/organizations/impact", Map.of("level", "CLASS", "id", "B01001"), admin));
    assertEquals(2, result.get("students"));
    assertEquals(1, result.get("courses"));
    assertEquals(2, result.get("total"));
  }

  @Test
  void membersRequireOrgAdminAndAddNames() {
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        get("/organizations/members", Map.of("level", "CLASS", "id", "B01001"), student)))
            .status);
    when(repo.findOne("classes", Map.of("id", "B01001")))
        .thenReturn(
            row(
                "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                "college_id", "C01001", "major_id", "M01001"));
    when(repo.find("users", Map.of("class_id", "B01001")))
        .thenReturn(
            List.of(
                row(
                    "id", "s1", "name", "张三", "role", "STUDENT", "password", "hash",
                    "college_id", "C01001", "major_id", "M01001", "class_id", "B01001")));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("majors", Map.of())).thenReturn(List.of(row("id", "M01001", "name", "软件工程")));
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row(
                    "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                    "college_id", "C01001", "major_id", "M01001")));
    var result =
        page(
            routes.dispatch(
                get("/organizations/members", Map.of("level", "CLASS", "id", "B01001"), admin)));
    var item = items(result).get(0);
    assertFalse(item.containsKey("password"));
    assertEquals("信息工程学院", item.get("collegeName"));
    assertEquals("软件工程", item.get("majorName"));
    assertEquals("2023级-软件工程-2301班", item.get("className"));
    assertEquals("软件工程·2023级-软件工程-2301班", item.get("department"));
  }

  // ---------------------------------------------------------------- 学生名册

  @Test
  void studentsRequireOrgAdmin() {
    assertEquals(
        403,
        assertThrows(
                ApiException.class, () -> routes.dispatch(get("/organizations/students", Map.of(), student)))
            .status);
  }

  @Test
  void studentsFilterByClassAndExposeRawClassName() {
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(
            List.of(
                student("s2", "20241530", "李四", "B01002"),
                student("s1", "20231530", "张三", "B01001"),
                row("id", "s3", "username", "20231531", "name", "停用", "role", "STUDENT", "enabled", "0", "class_id", "B01001"),
                row("id", "t1", "username", "t1", "name", "教师", "role", "TEACHER", "enabled", "1", "class_id", "B01001")));
    stubOrganizationNames();
    var result = page(routes.dispatch(get("/organizations/students", Map.of(), admin)));
    var items = items(result);
    assertEquals(2, items.size());
    assertEquals(2, result.get("total"));
    // 先按班级、再按编号排序；停用账号与教师都不出现。
    assertEquals("s1", items.get(0).get("id"));
    assertEquals("s2", items.get(1).get("id"));
    var first = items.get(0);
    assertEquals("20231530", first.get("username"));
    assertEquals("张三", first.get("name"));
    assertEquals("STUDENT", first.get("role"));
    assertEquals("1", first.get("enabled"));
    assertEquals("C01001", first.get("collegeId"));
    assertEquals("信息工程学院", first.get("collegeName"));
    assertEquals("M01001", first.get("majorId"));
    assertEquals("软件工程", first.get("majorName"));
    assertEquals("B01001", first.get("classId"));
    assertEquals("2023级-软件工程-2301班", first.get("className"));
    assertEquals("2023级-软件工程-2301班", first.get("classNameRaw"));
    assertEquals("软件工程·2023级-软件工程-2301班", first.get("department"));

    // 分页稳定：第二页只有 s2。
    var second = page(routes.dispatch(get("/organizations/students", Map.of("page", "2", "size", "1"), admin)));
    assertEquals(1, items(second).size());
    assertEquals("s2", items(second).get(0).get("id"));
    assertEquals(2, second.get("total"));
  }

  @Test
  void studentsAcceptClassIdOrClassName() {
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(
            List.of(student("s1", "20231530", "张三", "B01001"), student("s2", "20241530", "李四", "B01002")));
    stubOrganizationNames();
    when(repo.findOne("classes", Map.of("id", "B01001")))
        .thenReturn(row("id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023"));
    when(repo.findOne("classes", Map.of("id", "2024级-软件工程-2401班"))).thenReturn(null);
    when(repo.findOne("classes", Map.of("name", "2024级-软件工程-2401班")))
        .thenReturn(row("id", "B01002", "name", "2024级-软件工程-2401班", "grade_year", "2024"));
    var byId = page(routes.dispatch(get("/organizations/students", Map.of("classId", "B01001"), admin)));
    assertEquals(1, items(byId).size());
    assertEquals("s1", items(byId).get(0).get("id"));
    var byName =
        page(
            routes.dispatch(
                get("/organizations/students", Map.of("className", "2024级-软件工程-2401班"), admin)));
    assertEquals(1, items(byName).size());
    assertEquals("s2", items(byName).get(0).get("id"));
    assertEquals("2024级-软件工程-2401班", items(byName).get(0).get("className"));
  }

  @Test
  void studentsSupportUnassignedAndSearch() {
    when(repo.find("users", Map.of("role", "STUDENT")))
        .thenReturn(
            List.of(
                student("s1", "20231530", "张三", "B01001"),
                row(
                    "id", "s2", "username", "20241530", "name", "李四", "role", "STUDENT",
                    "enabled", "1", "college_id", "C01001", "major_id", "M01001", "class_id", "")));
    stubOrganizationNames();
    var unassigned =
        page(routes.dispatch(get("/organizations/students", Map.of("unassigned", "true"), admin)));
    assertEquals(1, items(unassigned).size());
    assertEquals("s2", items(unassigned).get(0).get("id"));
    assertNull(items(unassigned).get(0).get("className"));
    assertNull(items(unassigned).get(0).get("classNameRaw"));
    assertEquals("软件工程", items(unassigned).get(0).get("department"));

    var byName = page(routes.dispatch(get("/organizations/students", Map.of("search", "李"), admin)));
    assertEquals(1, items(byName).size());
    assertEquals("s2", items(byName).get(0).get("id"));

    var byUsername = page(routes.dispatch(get("/organizations/students", Map.of("search", "2023"), admin)));
    assertEquals(1, items(byUsername).size());
    assertEquals("s1", items(byUsername).get(0).get("id"));
  }

  @Test
  void studentsRejectInvalidUnassignedFlag() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        get("/organizations/students", Map.of("unassigned", "perhaps"), admin)))
            .status);
  }

  // ---------------------------------------------------------------- 保存

  @Test
  void saveCreatesCollegeWithGeneratedIdOnly() {
    when(repo.findOne("colleges", Map.of("name", "人工智能学院"))).thenReturn(null);
    // 学院主键的第 2-3 位是学院自己的号段：已有 01/02 号段，下一所学院取 03 号段（C03001）。
    when(repo.find("colleges", Map.of()))
        .thenReturn(
            List.of(
                row("id", "C01001", "name", "信息工程学院"),
                row("id", "C02001", "name", "经济与管理学院")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/save",
                    row(
                        "level", "COLLEGE",
                        "name", "人工智能学院",
                        "shortName", "智能学院",
                        "description", "新学院简介"),
                    admin));
    // 响应只有 ok 与 id：编号就是主键，前端拿 id 提示「已新增学院：人工智能学院（编号 C03001）」。
    assertEquals(Boolean.TRUE, result.get("ok"));
    assertEquals(Set.of("ok", "id"), result.keySet());
    assertFalse(result.containsKey("code"));
    assertFalse(result.containsKey("codeEditable"));
    assertTrue(result.get("id").toString().matches("C\\d{5}"));
    assertEquals("C03001", result.get("id"));
    var ops = mutateOps("a1", "ORG_SAVE", "C03001");
    assertEquals(1, ops.size());
    var op = ops.get(0);
    assertEquals("INSERT", op.type());
    assertEquals("colleges", op.table());
    assertEquals("C03001", op.values().get("id"));
    assertEquals("人工智能学院", op.values().get("name"));
    assertFalse(op.values().containsKey("code"));
    assertEquals("智能学院", op.values().get("short_name"));
    assertEquals("新学院简介", op.values().get("description"));
    assertEquals(1, op.values().get("enabled"));
    assertEquals(0, op.values().get("version"));
  }

  @Test
  void saveCreatesClassUnderNamedMajorAndDerivesCollege() {
    var major = row("id", "M01001", "name", "软件工程", "college_id", "C01001");
    when(repo.findOne("majors", Map.of("id", "软件工程"))).thenReturn(null);
    when(repo.findOne("majors", Map.of("name", "软件工程"))).thenReturn(major);
    when(repo.findOne("majors", Map.of("id", "M01001"))).thenReturn(major);
    when(repo.findOne("classes", Map.of("name", "软件工程2602班"))).thenReturn(null);
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row("id", "B01001", "name", "2023级-软件工程-2301班"),
                row("id", "B01002", "name", "2024级-软件工程-2401班")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/save",
                    row(
                        "level", "CLASS",
                        "name", "软件工程2602班",
                        "major", "软件工程",
                        "gradeYear", "2026",
                        // 辅导员已取消：请求体带了也不进库。
                        "counselor", "张老师"),
                    admin));
    // 班级编号沿用上级专业的 01 号段，段内已有 001/002 → B01003。
    assertEquals("B01003", result.get("id"));
    var op = mutateOps("a1", "ORG_SAVE", "B01003").get(0);
    assertEquals("INSERT", op.type());
    assertEquals("classes", op.table());
    assertEquals("M01001", op.values().get("major_id"));
    assertEquals("C01001", op.values().get("college_id"));
    assertEquals("2026", op.values().get("grade_year"));
    assertFalse(op.values().containsKey("code"));
    assertFalse(op.values().containsKey("counselor"));
    assertEquals(0, op.values().get("version"));
  }

  @Test
  void saveCreateResponseOnlyCarriesOkAndId() {
    // 新建班级：响应只有 {ok, id}，编号即主键 B01003，没有 code / codeEditable 之类的旧字段。
    var major = row("id", "M01001", "name", "软件工程", "college_id", "C01001");
    when(repo.findOne("majors", Map.of("id", "软件工程"))).thenReturn(null);
    when(repo.findOne("majors", Map.of("name", "软件工程"))).thenReturn(major);
    when(repo.findOne("majors", Map.of("id", "M01001"))).thenReturn(major);
    when(repo.findOne("classes", Map.of("name", "软件工程2603班"))).thenReturn(null);
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row("id", "B01001", "name", "2023级-软件工程-2301班"),
                row("id", "B01002", "name", "2024级-软件工程-2401班")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/save",
                    row("level", "CLASS", "name", "软件工程2603班", "major", "软件工程", "gradeYear", "2026"),
                    admin));
    assertEquals(Set.of("ok", "id"), result.keySet());
    assertEquals(Boolean.TRUE, result.get("ok"));
    assertTrue(result.get("id").toString().matches("B\\d{5}"));
    assertEquals("B01003", result.get("id"));
    assertEquals(1, mutateOps("a1", "ORG_SAVE", "B01003").size());
  }

  @Test
  void saveUpdateCannotRewritePrimaryKey() {
    var existing =
        row(
            "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
            "college_id", "C01001", "major_id", "M01001", "counselor", "张老师", "enabled", 1,
            "version", 2);
    when(repo.findOne("classes", Map.of("name", "2023级-软件工程-2301班"))).thenReturn(existing);
    when(repo.findOne("classes", Map.of("id", "B01001"))).thenReturn(existing);
    when(repo.findOne("majors", Map.of("id", "软件工程"))).thenReturn(null);
    when(repo.findOne("majors", Map.of("name", "软件工程")))
        .thenReturn(row("id", "M01001", "name", "软件工程", "college_id", "C01001"));
    when(repo.findOne("majors", Map.of("id", "M01001")))
        .thenReturn(row("id", "M01001", "name", "软件工程", "college_id", "C01001"));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/save",
                    row(
                        "id", "B01001",
                        "level", "CLASS",
                        "name", "2023级-软件工程-2301班",
                        "major", "软件工程",
                        "counselor", "李老师"),
                    admin));
    // 请求体里的 id 只用于定位被修改的对象，主键仍是原值，响应也只有 ok 与 id。
    assertEquals("B01001", result.get("id"));
    assertEquals(Set.of("ok", "id"), result.keySet());
    var op = mutateOps("a1", "ORG_SAVE", "B01001").get(0);
    assertEquals("UPDATE", op.type());
    assertEquals(Map.of("id", "B01001", "version", 2), op.where());
    assertFalse(op.values().containsKey("id"));
    assertFalse(op.values().containsKey("code"));
    assertFalse(op.values().containsKey("counselor"));
    assertEquals(3, op.values().get("version"));
  }

  @Test
  void saveUpdatesExistingCollegeAndIncrementsVersion() {
    var existing =
        row(
            "id", "C01001", "name", "信息工程学院", "code", "01", "short_name", "老简称",
            "description", "旧简介", "enabled", 1, "version", 3);
    when(repo.findOne("colleges", Map.of("name", "信息工程学院"))).thenReturn(existing);
    when(repo.findOne("colleges", Map.of("id", "C01001"))).thenReturn(existing);
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/save",
                    row(
                        "id", "C01001",
                        "level", "COLLEGE",
                        "name", "信息工程学院",
                        "shortName", "信息学院"),
                    admin));
    assertEquals("C01001", result.get("id"));
    assertEquals(Set.of("ok", "id"), result.keySet());
    var op = mutateOps("a1", "ORG_SAVE", "C01001").get(0);
    assertEquals("UPDATE", op.type());
    assertEquals(Map.of("id", "C01001", "version", 3), op.where());
    assertFalse(op.values().containsKey("id"));
    assertEquals(4, op.values().get("version"));
    assertEquals("信息学院", op.values().get("short_name"));
    // 库表里即便残留 code 列，组织接口也不再读写它。
    assertFalse(op.values().containsKey("code"));
    assertEquals("旧简介", op.values().get("description"));
    assertEquals(1, op.values().get("enabled"));
  }

  @Test
  void saveRejectsDuplicateNameAndWritesNothing() {
    when(repo.findOne("colleges", Map.of("name", "经济与管理学院")))
        .thenReturn(row("id", "C01002", "name", "经济与管理学院"));
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post(
                            "/organizations/save",
                            row("level", "COLLEGE", "name", "经济与管理学院"),
                            admin)))
            .status);
    verify(repo, never()).mutate(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  void saveRequiresOrgAdmin() {
    assertEquals(
        403,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post("/organizations/save", row("level", "COLLEGE", "name", "新学院"), student)))
            .status);
    verify(repo, never()).mutate(anyList(), anyString(), anyString(), anyString());
  }

  // ---------------------------------------------------------------- 删除

  @Test
  void deleteCascadesWithForceAndAudits() {
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.find("courses", Map.of("college_id", "C01001"))).thenReturn(List.of());
    when(repo.find("majors", Map.of("college_id", "C01001")))
        .thenReturn(List.of(row("id", "M01001", "name", "软件工程", "college_id", "C01001")));
    when(repo.find("classes", Map.of("college_id", "C01001"))).thenReturn(List.of());
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("classes", Map.of("major_id", "M01001"))).thenReturn(List.of());
    when(repo.find("users", Map.of("major_id", "M01001"))).thenReturn(List.of());
    when(repo.find("users", Map.of("college_id", "C01001"))).thenReturn(List.of());
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post("/organizations/delete", row("level", "COLLEGE", "id", "C01001", "force", "true"), admin));
    assertEquals(Boolean.TRUE, result.get("ok"));
    var ops = mutateOps("a1", "ORG_DELETE", "C01001");
    assertEquals(2, ops.size());
    assertEquals("M01001", ops.get(0).where().get("id"));
    assertEquals("C01001", ops.get(1).where().get("id"));
  }

  @Test
  void deleteRequiresForceWhenChildrenExist() {
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.find("courses", Map.of("college_id", "C01001"))).thenReturn(List.of());
    when(repo.find("majors", Map.of("college_id", "C01001")))
        .thenReturn(List.of(row("id", "M01001", "name", "软件工程", "college_id", "C01001")));
    when(repo.find("classes", Map.of("college_id", "C01001"))).thenReturn(List.of());
    assertEquals(
        409,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post("/organizations/delete", row("level", "COLLEGE", "id", "C01001"), admin)))
            .status);
    verify(repo, never()).mutate(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  void deleteRejectsInvalidForceValue() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post(
                            "/organizations/delete",
                            row("level", "MAJOR", "id", "M01001", "force", "maybe"),
                            admin)))
            .status);
  }

  // ---------------------------------------------------------------- 人员调整

  @Test
  void assignMovesStudentsToClass() {
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(
            row(
                "id", "B01002", "name", "2024级-软件工程-2401班", "grade_year", "2024",
                "college_id", "C01001", "major_id", "M01001"));
    when(repo.one("users", "s1"))
        .thenReturn(
            row(
                "id", "s1", "role", "STUDENT", "version", 2, "college_id", "C01001",
                "major_id", "M01001", "class_id", "B01001"));
    when(repo.one("users", "s2"))
        .thenReturn(
            row(
                "id", "s2", "role", "STUDENT", "version", 0, "college_id", "C01001",
                "major_id", "M01001", "class_id", "B01001"));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("majors", Map.of())).thenReturn(List.of(row("id", "M01001", "name", "软件工程")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/assign",
                    row("level", "CLASS", "id", "B01002", "studentIds", List.of("s1", "s2")),
                    admin));
    assertEquals(Boolean.TRUE, result.get("ok"));
    assertEquals(2, result.get("moved"));
    var ops = mutateOps("a1", "ORG_ASSIGN", "B01002");
    assertEquals(2, ops.size());
    var first = ops.get(0);
    assertEquals("UPDATE", first.type());
    assertEquals("users", first.table());
    assertEquals(Map.of("id", "s1", "version", 2), first.where());
    assertEquals("C01001", first.values().get("college_id"));
    assertEquals("M01001", first.values().get("major_id"));
    assertEquals("B01002", first.values().get("class_id"));
    assertEquals(3, first.values().get("version"));
    assertEquals("软件工程·2024级-软件工程-2401班", first.values().get("department"));
    assertFalse(first.values().containsKey("id"));
    assertEquals(Map.of("id", "s2", "version", 0), ops.get(1).where());
    assertEquals(1, ops.get(1).values().get("version"));
  }

  @Test
  void assignMovesWholeClassByClassId() {
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(
            row(
                "id", "B01002", "name", "2024级-软件工程-2401班", "grade_year", "2024",
                "college_id", "C01001", "major_id", "M01001"));
    when(repo.findOne("classes", Map.of("id", "B01001")))
        .thenReturn(
            row(
                "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                "college_id", "C01001", "major_id", "M01001"));
    when(repo.find("users", Map.of("class_id", "B01001", "role", "STUDENT")))
        .thenReturn(
            List.of(
                row("id", "s1", "role", "STUDENT", "version", 1),
                row("id", "s2", "role", "STUDENT", "version", 1)));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("majors", Map.of())).thenReturn(List.of(row("id", "M01001", "name", "软件工程")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/assign",
                    row("level", "CLASS", "id", "B01002", "classId", "B01001"),
                    admin));
    assertEquals(2, result.get("moved"));
    var ops = mutateOps("a1", "ORG_ASSIGN", "B01002");
    assertEquals(2, ops.size());
    assertEquals("B01002", ops.get(0).values().get("class_id"));
  }

  @Test
  void assignMovesTeachersToMajorWithoutClassId() {
    when(repo.findOne("majors", Map.of("id", "M01002")))
        .thenReturn(row("id", "M01002", "name", "计算机科学与技术", "college_id", "C01001"));
    when(repo.one("users", "t1"))
        .thenReturn(row("id", "t1", "role", "TEACHER", "version", 5, "college_id", "C01002", "major_id", "M02001"));
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    var result =
        (Map<String, Object>)
            routes.dispatch(
                post(
                    "/organizations/assign",
                    row("level", "MAJOR", "id", "M01002", "teacherIds", List.of("t1")),
                    admin));
    assertEquals(1, result.get("moved"));
    var op = mutateOps("a1", "ORG_ASSIGN", "M01002").get(0);
    assertEquals(Map.of("id", "t1", "version", 5), op.where());
    assertEquals("C01001", op.values().get("college_id"));
    assertEquals("M01002", op.values().get("major_id"));
    assertEquals("信息工程学院·计算机科学与技术", op.values().get("department"));
    assertFalse(op.values().containsKey("class_id"));
    assertEquals(6, op.values().get("version"));
  }

  @Test
  void assignRejectsStudentsTargetingMajor() {
    when(repo.findOne("majors", Map.of("id", "M01002")))
        .thenReturn(row("id", "M01002", "name", "计算机科学与技术", "college_id", "C01001"));
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post(
                            "/organizations/assign",
                            row("level", "MAJOR", "id", "M01002", "studentIds", List.of("s1")),
                            admin)))
            .status);
  }

  @Test
  void assignRejectsTeachersTargetingClass() {
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(row("id", "B01002", "name", "2024级-软件工程-2401班", "college_id", "C01001", "major_id", "M01001"));
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post(
                            "/organizations/assign",
                            row("level", "CLASS", "id", "B01002", "teacherIds", List.of("t1")),
                            admin)))
            .status);
  }

  @Test
  void assignRejectsAdminAccountInEitherList() {
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(
            row(
                "id", "B01002", "name", "2024级-软件工程-2401班", "grade_year", "2024",
                "college_id", "C01001", "major_id", "M01001"));
    when(repo.findOne("majors", Map.of("id", "M01002")))
        .thenReturn(row("id", "M01002", "name", "计算机科学与技术", "college_id", "C01001"));
    // 管理员不属于任何组织：列在学生里被拒。
    when(repo.one("users", "a9")).thenReturn(row("id", "a9", "role", "ADMIN", "version", 0));
    ApiException asStudent =
        assertThrows(
            ApiException.class,
            () ->
                routes.dispatch(
                    post(
                        "/organizations/assign",
                        row("level", "CLASS", "id", "B01002", "studentIds", List.of("a9")),
                        admin)));
    assertEquals(400, asStudent.status);
    assertTrue(asStudent.getMessage().contains("管理员不归属学院/专业/班级"));

    // 列在教师里同样被拒。
    ApiException asTeacher =
        assertThrows(
            ApiException.class,
            () ->
                routes.dispatch(
                    post(
                        "/organizations/assign",
                        row("level", "MAJOR", "id", "M01002", "teacherIds", List.of("a9")),
                        admin)));
    assertEquals(400, asTeacher.status);
    assertTrue(asTeacher.getMessage().contains("管理员不归属学院/专业/班级"));
    verify(repo, never()).mutate(anyList(), anyString(), anyString(), anyString());
  }

  @Test
  void assignRequiresStudentRole() {
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(row("id", "B01002", "name", "2024级-软件工程-2401班", "college_id", "C01001", "major_id", "M01001"));
    when(repo.one("users", "t1")).thenReturn(row("id", "t1", "role", "TEACHER", "version", 0));
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post(
                            "/organizations/assign",
                            row("level", "CLASS", "id", "B01002", "studentIds", List.of("t1")),
                            admin)))
            .status);
  }

  @Test
  void assignRequiresTargetAndPayload() {
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post("/organizations/assign", row("level", "CLASS", "studentIds", List.of("s1")), admin)))
            .status);
    when(repo.findOne("classes", Map.of("id", "B01002")))
        .thenReturn(row("id", "B01002", "name", "2024级-软件工程-2401班", "college_id", "C01001", "major_id", "M01001"));
    assertEquals(
        400,
        assertThrows(
                ApiException.class,
                () ->
                    routes.dispatch(
                        post("/organizations/assign", row("level", "CLASS", "id", "B01002"), admin)))
            .status);
    verify(repo, never()).mutate(anyList(), anyString(), anyString(), anyString());
  }

  // ---------------------------------------------------------------- 工具

  @SuppressWarnings("unchecked")
  private static Map<String, Object> page(Object payload) {
    return (Map<String, Object>) payload;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> payload) {
    return (List<Map<String, Object>>) payload.get("items");
  }

  private static Routes.Request get(String path, Map<String, String> query, Models.User user) {
    return new Routes.Request(path, user, false, Map.of(), query, null, null);
  }

  private static Routes.Request post(String path, Map<String, Object> body, Models.User user) {
    return new Routes.Request(path, user, true, body, Map.of(), null, null);
  }

  @SuppressWarnings("unchecked")
  private List<Protocol.Operation> mutateOps(String actor, String action, String resource) {
    ArgumentCaptor<List<Protocol.Operation>> captor = ArgumentCaptor.forClass(List.class);
    verify(repo).mutate(captor.capture(), eq(actor), eq(action), eq(resource));
    return captor.getValue();
  }

  /** 三级组织各一行，并故意带上历史遗留的 code / counselor 列，用于验证接口不再输出它们。 */
  private void stubLegacyRows() {
    when(repo.find("colleges", Map.of()))
        .thenReturn(
            List.of(
                row("id", "C01001", "name", "信息工程学院", "short_name", "信息学院", "code", "01")));
    when(repo.find("majors", Map.of()))
        .thenReturn(
            List.of(row("id", "M01001", "name", "软件工程", "college_id", "C01001", "code", "02")));
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row(
                    "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                    "college_id", "C01001", "major_id", "M01001", "code", "03",
                    "counselor", "张老师")));
  }

  /** 学生名册的公共组织名称桩：学院、专业、班级各一行。 */
  private void stubOrganizationNames() {
    when(repo.find("colleges", Map.of()))
        .thenReturn(List.of(row("id", "C01001", "name", "信息工程学院")));
    when(repo.find("majors", Map.of())).thenReturn(List.of(row("id", "M01001", "name", "软件工程")));
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row(
                    "id", "B01001", "name", "2023级-软件工程-2301班", "grade_year", "2023",
                    "college_id", "C01001", "major_id", "M01001"),
                row(
                    "id", "B01002", "name", "2024级-软件工程-2401班", "grade_year", "2024",
                    "college_id", "C01001", "major_id", "M01001")));
  }

  private static Map<String, Object> student(String id, String username, String name, String classId) {
    return row(
        "id", id, "username", username, "name", name, "role", "STUDENT", "enabled", "1",
        "college_id", "C01001", "major_id", "M01001", "class_id", classId, "version", 0);
  }

  private static Map<String, Object> row(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
    return map;
  }
}
