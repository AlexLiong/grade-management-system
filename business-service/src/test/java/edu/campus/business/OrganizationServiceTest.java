package edu.campus.business;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import edu.campus.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 组织服务：编号生成、名称归一化、重名与删除约束、选课范围匹配。 */
class OrganizationServiceTest {
  private final RemoteRepository repo = mock(RemoteRepository.class);
  private final OrganizationService service = new OrganizationService(repo);
  private final Models.User admin =
      new Models.User("a1", "admin", "管理员", "ADMIN", Set.of("ORG_ADMIN"), 0);
  private final Models.User teacher =
      new Models.User("t1", "t1", "教师", "TEACHER", Set.of("QUERY"), 0);

  // ---------------------------------------------------------------- 编号

  @Test
  void nextIdIncrementsWithinEachParent() {
    // 学院自身用上级序号 00，因此新建学院在 00 系列内递增；C01001/C01002 属于种子数据的
    // 01/02 系列，互不干扰。
    when(repo.find("colleges", Map.of()))
        .thenReturn(
            List.of(
                row("id", "C00001", "name", "人工智能学院"),
                row("id", "C01001", "name", "信息工程学院"),
                row("id", "C01002", "name", "经济与管理学院")));
    when(repo.find("majors", Map.of()))
        .thenReturn(
            List.of(
                row("id", "M01001", "name", "软件工程", "college_id", "C01001"),
                row("id", "M01002", "name", "计算机科学与技术", "college_id", "C01001"),
                row("id", "M02001", "name", "工商管理", "college_id", "C01002")));
    when(repo.find("classes", Map.of()))
        .thenReturn(
            List.of(
                row("id", "B01001", "name", "2023级-软件工程-2301班"),
                row("id", "B01002", "name", "2024级-软件工程-2401班"),
                row("id", "B01003", "name", "软件工程2601班"),
                row("id", "B01004", "name", "2023级-计算机科学与技术-2301班"),
                row("id", "B02001", "name", "2024级-工商管理-2401班"),
                row("id", "B02002", "name", "2024级-会计学-2401班")));
    // 学院编号的第 2-3 位是学院自己的号段：已有号段 01（含 C00001 与 C01001/C01002），
    // 因此下一所学院取号段 02，本级序号从 001 开始，得到 C02001。
    assertEquals("C02001", service.nextId(Models.Level.COLLEGE, null));
    assertEquals("M01003", service.nextId(Models.Level.MAJOR, "C01001"));
    // 专业与班级沿用上级编号的第 2-3 位：C01001 与 C01002 的号段同为 01，因此专业共用
    // M01 号段并按该段最大值递增（M01001、M01002 → M01003）。
    assertEquals("M01003", service.nextId(Models.Level.MAJOR, "C01002"));
    assertEquals("B01005", service.nextId(Models.Level.CLASS, "M01001"));
    assertEquals("B02003", service.nextId(Models.Level.CLASS, "M02002"));
  }

  @Test
  void nextIdRejectsExhaustedLevel() {
    // 学院号段只有两位，99 号段之后必须拒绝而不是回绕。
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 1; i <= 99; i++) rows.add(row("id", "C" + String.format("%02d", i) + "001"));
    when(repo.find("colleges", Map.of())).thenReturn(rows);
    ApiException ex = assertThrows(ApiException.class, () -> service.nextId(Models.Level.COLLEGE, null));
    assertEquals(409, ex.status);
  }

  // ------------------------------------------------- 与演示数据一致的编号

  @Test
  void nextIdFollowsDemoDataShape() {
    // 与 DemoInitializer 的真实数据一致：4 学院、8 专业、16 班级，每所学院 4 个班级。
    when(repo.find("colleges", Map.of())).thenReturn(collegeRows());
    when(repo.find("majors", Map.of())).thenReturn(majorRows());
    when(repo.find("classes", Map.of())).thenReturn(classRows());
    // 学院：现有最大号段 04，下一所取 05 号段，本级序号从 001 开始 → C05001。
    assertEquals("C05001", service.nextId(Models.Level.COLLEGE, null));
    // 专业：沿用上级 C01001 的 01 号段，该段内已有 M01001/M01002（序号 001/002）→ M01003；
    // C02001 的 02 号段内已有 M02001/M02002，同理得到 M02003。
    assertEquals("M01003", service.nextId(Models.Level.MAJOR, "C01001"));
    assertEquals("M02003", service.nextId(Models.Level.MAJOR, "C02001"));
    // 班级：沿用上级编号的号段并在段内递增。01 号段已有 B01001–B01004 → B01005；
    // 04 号段已有 B04001–B04004 → B04005。
    assertEquals("B01005", service.nextId(Models.Level.CLASS, "M01001"));
    assertEquals("B04005", service.nextId(Models.Level.CLASS, "M04002"));
  }

  @Test
  void nextIdNeverCollidesWithExistingIds() {
    var colleges = collegeRows();
    var majors = majorRows();
    var classes = classRows();
    when(repo.find("colleges", Map.of())).thenReturn(colleges);
    when(repo.find("majors", Map.of())).thenReturn(majors);
    when(repo.find("classes", Map.of())).thenReturn(classes);
    String college = service.nextId(Models.Level.COLLEGE, null);
    String major = service.nextId(Models.Level.MAJOR, "C01001");
    String klass = service.nextId(Models.Level.CLASS, "M01001");
    // 生成的编号不能与同层任何已有编号重复，格式仍是「层前缀 + 5 位数字」。
    assertFalse(idsOf(colleges).contains(college));
    assertFalse(idsOf(majors).contains(major));
    assertFalse(idsOf(classes).contains(klass));
    assertTrue(college.matches("C\\d{5}"));
    assertTrue(major.matches("M\\d{5}"));
    assertTrue(klass.matches("B\\d{5}"));
  }

  // ---------------------------------------------------------------- 名称归一化

  @Test
  void resolveOwnAcceptsIdAndName() {
    var college = row("id", "C01001", "name", "信息工程学院");
    when(repo.findOne("colleges", Map.of("id", "C01001"))).thenReturn(college);
    when(repo.findOne("colleges", Map.of("id", "信息工程学院"))).thenReturn(null);
    when(repo.findOne("colleges", Map.of("name", "信息工程学院"))).thenReturn(college);
    assertEquals("C01001", service.resolveOwn(Models.Level.COLLEGE, "C01001"));
    assertEquals("C01001", service.resolveOwn(Models.Level.COLLEGE, " 信息工程学院 "));
    assertNull(service.resolveOwn(Models.Level.COLLEGE, "   "));
    assertNull(service.resolveOwn(Models.Level.COLLEGE, null));
  }

  @Test
  void resolveOwnRejectsUnknownName() {
    when(repo.findOne("colleges", Map.of("id", "未知学院"))).thenReturn(null);
    when(repo.findOne("colleges", Map.of("name", "未知学院"))).thenReturn(null);
    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.resolveOwn(Models.Level.COLLEGE, "未知学院"));
    assertEquals(400, ex.status);
    assertTrue(ex.getMessage().contains("学院"));
  }

  @Test
  void requireOrganizationRejectsMissingId() {
    ApiException ex =
        assertThrows(ApiException.class, () -> service.requireOrganization(Models.Level.CLASS, " "));
    assertEquals(400, ex.status);
  }

  // ---------------------------------------------------------------- 保存

  @Test
  void saveRejectsDuplicateNameInSameLevel() {
    when(repo.findOne("colleges", Map.of("id", "信息工程学院"))).thenReturn(null);
    when(repo.findOne("colleges", Map.of("name", "信息工程学院")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.findOne("majors", Map.of("name", "软件工程")))
        .thenReturn(row("id", "M01001", "name", "软件工程", "college_id", "C01001"));
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.save(admin, Models.Level.MAJOR, Map.of("name", "软件工程", "college", "信息工程学院")));
    assertEquals(400, ex.status);
    assertTrue(ex.getMessage().contains("同名"));
  }

  @Test
  void saveAllowsKeepingOwnNameAndReturnsExistingId() {
    var major = row("id", "M01001", "name", "软件工程", "college_id", "C01001");
    when(repo.findOne("majors", Map.of("name", "软件工程"))).thenReturn(major);
    when(repo.findOne("majors", Map.of("id", "M01001"))).thenReturn(major);
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    assertEquals(
        "M01001",
        service.save(
            admin,
            Models.Level.MAJOR,
            Map.of("id", "M01001", "name", "软件工程", "collegeId", "C01001")));
  }

  @Test
  void saveGeneratesNextIdForNewMajor() {
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.findOne("majors", Map.of("name", "人工智能"))).thenReturn(null);
    when(repo.find("majors", Map.of()))
        .thenReturn(List.of(row("id", "M01001", "name", "软件工程", "college_id", "C01001")));
    assertEquals(
        "M01002",
        service.save(admin, Models.Level.MAJOR, Map.of("name", "人工智能", "collegeId", "C01001")));
  }

  @Test
  void saveRequiresOrgAdmin() {
    ApiException ex =
        assertThrows(
            ApiException.class,
            () -> service.save(teacher, Models.Level.COLLEGE, Map.of("name", "新学院")));
    assertEquals(403, ex.status);
  }

  // ---------------------------------------------------------------- 删除

  @Test
  void deleteCollegeBlockedByCourses() {
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.find("courses", Map.of("college_id", "C01001")))
        .thenReturn(List.of(row("id", "net-2023", "name", "网络软件与安全")));
    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.deleteOps(admin, Models.Level.COLLEGE, "C01001", true));
    assertEquals(409, ex.status);
    assertTrue(ex.getMessage().contains("课程"));
  }

  @Test
  void deleteClassBlockedByAccounts() {
    when(repo.findOne("classes", Map.of("id", "B01001")))
        .thenReturn(row("id", "B01001", "name", "2023级-软件工程-2301班", "college_id", "C01001", "major_id", "M01001"));
    when(repo.find("courses", Map.of("class_id", "B01001"))).thenReturn(List.of());
    when(repo.find("users", Map.of("class_id", "B01001"))).thenReturn(List.of(row("id", "s1")));
    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.deleteOps(admin, Models.Level.CLASS, "B01001", false));
    assertEquals(409, ex.status);
    assertTrue(ex.getMessage().contains("账号"));
  }

  @Test
  void deleteMajorWithoutForceRejectedWhenChildrenExist() {
    when(repo.findOne("majors", Map.of("id", "M01001")))
        .thenReturn(row("id", "M01001", "name", "软件工程", "college_id", "C01001"));
    when(repo.find("colleges", Map.of())).thenReturn(List.of());
    when(repo.find("classes", Map.of("major_id", "M01001")))
        .thenReturn(List.of(row("id", "B01001", "name", "2023级-软件工程-2301班")));
    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.deleteOps(admin, Models.Level.MAJOR, "M01001", false));
    assertEquals(409, ex.status);
    assertTrue(ex.getMessage().contains("级联"));
  }

  @Test
  void deleteCollegeCascadesChildrenInOneBatch() {
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
    var ops = service.deleteOps(admin, Models.Level.COLLEGE, "C01001", true);
    assertEquals(2, ops.size());
    assertEquals("DELETE", ops.get(0).type());
    assertEquals("majors", ops.get(0).table());
    assertEquals(Map.of("id", "M01001"), ops.get(0).where());
    assertEquals("colleges", ops.get(1).table());
    assertEquals(Map.of("id", "C01001"), ops.get(1).where());
  }

  @Test
  void deleteRequiresOrgAdmin() {
    ApiException ex =
        assertThrows(
            ApiException.class, () -> service.deleteOps(teacher, Models.Level.CLASS, "B01001", true));
    assertEquals(403, ex.status);
  }

  // ---------------------------------------------------------------- 查询与展示

  @Test
  void listRequiresOrgAdminAndListAllSortsById() {
    when(repo.find("majors", Map.of()))
        .thenReturn(List.of(row("id", "M01002"), row("id", "M01001")));
    assertEquals(
        List.of("M01001", "M01002"),
        service.listAll(Models.Level.MAJOR).stream().map(r -> r.get("id")).toList());
    ApiException ex = assertThrows(ApiException.class, () -> service.list(teacher, Models.Level.MAJOR));
    assertEquals(403, ex.status);
  }

  @Test
  void enrichOwnAddsParentNames() {
    when(repo.findOne("colleges", Map.of("id", "C01001")))
        .thenReturn(row("id", "C01001", "name", "信息工程学院"));
    when(repo.findOne("majors", Map.of("id", "M01001")))
        .thenReturn(row("id", "M01001", "name", "软件工程"));
    var enriched =
        service.enrichOwn(
            row("id", "s1", "college_id", "C01001", "major_id", "M01001"),
            Map.of("college_id", Models.Level.COLLEGE, "major_id", Models.Level.MAJOR));
    assertEquals("信息工程学院", enriched.get("collegeName"));
    assertEquals("软件工程", enriched.get("majorName"));
  }

  @Test
  void classDisplayNameFollowsGradeMajorNumberForm() {
    // 班级名称本身即「XXXX级-XX专业-XX班」的规范形式，displayName 直接返回名称。
    assertEquals(
        "2023级-软件工程-2301班",
        OrganizationService.displayName(
            Models.Level.CLASS, row("name", "2023级-软件工程-2301班", "grade_year", "2023")));
    // 学院与专业同样返回各自名称。
    assertEquals(
        "信息工程学院",
        OrganizationService.displayName(Models.Level.COLLEGE, row("name", "信息工程学院")));
  }

  @Test
  void classNameHelperComposesCanonicalForm() {
    assertEquals("2023级-软件工程-2301班", OrganizationService.className("2023", "软件工程", "2301班"));
  }

  // ---------------------------------------------------------------- 选课范围

  @Test
  void emptyScopeMatchesEveryStudent() {
    assertTrue(OrganizationService.inScope(Map.of(), Map.of("class_id", "B01001")));
    assertTrue(
        OrganizationService.inScope(
            Map.of("scope_college_ids", "", "scope_major_ids", ""),
            Map.of("college_id", "C01002", "major_id", "M02001")));
  }

  @Test
  void scopeFiltersByCollegeMajorAndClass() {
    var publish = Map.<String, Object>of("scope_class_ids", "B01001");
    assertTrue(OrganizationService.inScope(publish, Map.of("class_id", "B01001")));
    assertFalse(OrganizationService.inScope(publish, Map.of("class_id", "B01002")));
    assertFalse(OrganizationService.inScope(publish, Map.of()));

    var byMajor = Map.<String, Object>of("scope_major_ids", "M01001,M01002");
    assertTrue(OrganizationService.inScope(byMajor, Map.of("major_id", "M01002")));
    assertFalse(OrganizationService.inScope(byMajor, Map.of("major_id", "M02001")));

    var byCollege = Map.<String, Object>of("scope_college_ids", "C01001");
    assertTrue(OrganizationService.inScope(byCollege, Map.of("college_id", "C01001")));
    assertFalse(OrganizationService.inScope(byCollege, Map.of("college_id", "C01002")));
  }

  @Test
  void splitParsesCommaSeparatedIds() {
    assertEquals(List.of("C01001", "C01002"), OrganizationService.split("C01001, C01002"));
    assertEquals(List.of(), OrganizationService.split(null));
    assertEquals(List.of(), OrganizationService.split(" , "));
  }

  // ---------------------------------------------------------------- 工具

  /** DemoInitializer 的 4 所学院：C01001、C02001、C03001、C04001。 */
  private static List<Map<String, Object>> collegeRows() {
    return List.of(
        row("id", "C01001", "name", "信息工程学院"),
        row("id", "C02001", "name", "经济与管理学院"),
        row("id", "C03001", "name", "建筑工程学院"),
        row("id", "C04001", "name", "外国语学院"));
  }

  /** DemoInitializer 的 8 个专业：每所学院 2 个，号段与所属学院一致。 */
  private static List<Map<String, Object>> majorRows() {
    return List.of(
        row("id", "M01001", "name", "软件工程", "college_id", "C01001"),
        row("id", "M01002", "name", "计算机科学与技术", "college_id", "C01001"),
        row("id", "M02001", "name", "工商管理", "college_id", "C02001"),
        row("id", "M02002", "name", "会计学", "college_id", "C02001"),
        row("id", "M03001", "name", "土木工程", "college_id", "C03001"),
        row("id", "M03002", "name", "工程管理", "college_id", "C03001"),
        row("id", "M04001", "name", "英语", "college_id", "C04001"),
        row("id", "M04002", "name", "日语", "college_id", "C04001"));
  }

  /** DemoInitializer 的 16 个班级：每所学院 4 个，段内序号 001–004。 */
  private static List<Map<String, Object>> classRows() {
    return List.of(
        row("id", "B01001", "name", "2023级-软件工程-2301班", "major_id", "M01001"),
        row("id", "B01002", "name", "2024级-软件工程-2401班", "major_id", "M01001"),
        row("id", "B01003", "name", "2023级-计算机科学与技术-2301班", "major_id", "M01002"),
        row("id", "B01004", "name", "2024级-计算机科学与技术-2401班", "major_id", "M01002"),
        row("id", "B02001", "name", "2023级-工商管理-2301班", "major_id", "M02001"),
        row("id", "B02002", "name", "2024级-工商管理-2401班", "major_id", "M02001"),
        row("id", "B02003", "name", "2023级-会计学-2301班", "major_id", "M02002"),
        row("id", "B02004", "name", "2024级-会计学-2401班", "major_id", "M02002"),
        row("id", "B03001", "name", "2023级-土木工程-2301班", "major_id", "M03001"),
        row("id", "B03002", "name", "2024级-土木工程-2401班", "major_id", "M03001"),
        row("id", "B03003", "name", "2023级-工程管理-2301班", "major_id", "M03002"),
        row("id", "B03004", "name", "2024级-工程管理-2401班", "major_id", "M03002"),
        row("id", "B04001", "name", "2023级-英语-2301班", "major_id", "M04001"),
        row("id", "B04002", "name", "2024级-英语-2401班", "major_id", "M04001"),
        row("id", "B04003", "name", "2023级-日语-2301班", "major_id", "M04002"),
        row("id", "B04004", "name", "2024级-日语-2401班", "major_id", "M04002"));
  }

  private static List<String> idsOf(List<Map<String, Object>> rows) {
    return rows.stream().map(r -> r.get("id").toString()).toList();
  }

  private static Map<String, Object> row(Object... pairs) {
    var map = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
    return map;
  }
}
