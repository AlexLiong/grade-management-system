package edu.campus.data;

import edu.campus.common.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 演示数据初始化。
 *
 * <p>数据库按学院—专业—班级三级组织重建，覆盖 <b>4 所学院、8 个专业、21 个班级、
 * 3 名管理员/教务与 15 名教师（11 名演示教师 + 4 名不可登录的历史样本教师）、187 名学生
 * （144 名正课学生 + 40 名历史样本学生 + 3 名待分班），合计 205 个账号</b>，
 * 并按学期铺开 <b>155 个教学班</b>（64 个正课 + 91 个历史样本教学班，2020-1 至 2026-1）、
 * <b>1354 条选课</b>与 <b>1354 条成绩</b>，另预置一个进行中的选课批次。
 * 启动时 {@link #purgeTestArtifacts()} 还会清掉端到端脚本留下的测试教学班与测试选课批次。
 *
 * <p>数据遵守选课系统的唯一性约束：
 *
 * <ul>
 *   <li><b>同一学生不会在两个学期修读相同课程代码</b>，唯一的例外是刻意的重修样本——见
 *       {@link #RETAKES}：5 名学生先在某个学期挂科（正考与补考都不及格），再于后续学期修读
 *       <b>同一课程代码</b>。重修不体现在课程名上：同一课程号的课程只是开设学年不同
 *       （例如 CS102「程序设计基础」在 2023-1 与 2024-1 各有一个教学班），「谁在重修」由成绩单
 *       推出来，因此课程名里不会出现「（重修）」这类字样。
 *   <li><b>学业预警（预测）对每门有选课的课程都可用</b>：每门课都保证「同一课程代码在更早学期有
 *       3 个不同年份、≥24 条三分项齐全的已提交成绩」，并且本班至少有 1 名学生「有平时与实验、
 *       缺期末」作为预测对象；2020-1/2021-1/2022-1/2023-2 的历史样本教学班与缓考样本学生专门
 *       为此准备（见 {@link #SAMPLE_LAYERS} 与 {@link #PARTIAL_POOL_CLASS}），
 *       {@link #verifyPredictionCoverage()} 在启动时逐门校验。
 *   <li><b>组织编号即主键 {@code id}</b>（{@code C01001}/{@code M01001}/{@code B01001}），
 *       由 {@code OrganizationService.nextId} 按「同层最大 + 1」自动生成；界面上展示的就是它，
 *       没有另外的显示编号字段（{@code colleges/majors/classes} 里遗留的 {@code code} 列不再读写）。
 *   <li><b>班级不设辅导员</b>（结构版本 3 已删除该列）。
 *   <li><b>管理员不归属任何组织</b>，三级组织字段写空串；写入后由
 *       {@link #verifyOrganizationIntegrity()} 自检。
 * </ul>
 *
 * <p>触发条件（任一成立即整库重建）：
 *
 * <ol>
 *   <li>{@link SchemaCatalog} 检测到结构版本变化并已删除全部业务表；
 *   <li>显式重建开关 {@code -Dcampus.reset-db=true} 或环境变量 {@code CAMPUS_RESET_DB=true}；
 *   <li>业务表为空（首次启动）。
 * </ol>
 *
 * <p>重建会同时清空独立审计账本与 EVM 锚点，因为旧账本保存的是已删除成绩行的快照。
 */
@Component
@Order(100)
public class DemoInitializer implements ApplicationRunner {

  /** 演示账号统一初始密码。 */
  private static final String DEFAULT_PASSWORD = "passwd";

  public static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder(12);

  /** 管理员账号：不归属任何学院/专业/班级。 */
  public static final Set<String> ADMIN_USERNAMES = Set.of("admin", "jw001", "jw002");

  /**
   * 禁用账号：历史样本教师。数据（课程、名单、成绩）完整，但不能登录——
   * 它们的课是学业预警的训练样本，学期本身没有更早年份，不适合作为演示账号暴露在「我的课程」里。
   */
  public static final Set<String> DISABLED_USERNAMES = Set.of("ht2020", "ht2021", "ht2022", "ht2023");

  private static final String ADMIN_PERMISSIONS =
      "GRADE_ADMIN,USER_ADMIN,AUDIT,ORG_ADMIN,SELECTION_ADMIN";

  // ------------------------------------------------------------------ 组织

  private record Org(String id, String name, String code, String parent, Object extra) {}

  /** 学院。id 为主键（{@code C + 号段 + 序号}），code 为两位显示编号。 */
  private static final List<Org> COLLEGES =
      List.of(
          new Org("C01001", "信息工程学院", "01", null, "信息学院"),
          new Org("C02001", "经济与管理学院", "02", null, "经管学院"),
          new Org("C03001", "建筑工程学院", "03", null, "建筑学院"),
          new Org("C04001", "外国语学院", "04", null, "外语学院"));

  /** 专业。extra 为授予学位；parent 为所属学院编号。code 在专业层内全局唯一并递增。 */
  private static final List<Org> MAJORS =
      List.of(
          new Org("M01001", "软件工程", "01", "C01001", "工学"),
          new Org("M01002", "计算机科学与技术", "02", "C01001", "工学"),
          new Org("M02001", "工商管理", "03", "C02001", "管理学"),
          new Org("M02002", "会计学", "04", "C02001", "管理学"),
          new Org("M03001", "土木工程", "05", "C03001", "工学"),
          new Org("M03002", "工程管理", "06", "C03001", "管理学"),
          new Org("M04001", "英语", "07", "C04001", "文学"),
          new Org("M04002", "日语", "08", "C04001", "文学"));

  /** 班级。名称按需求采用「XXXX级-XX专业-XX班」形式；extra 为年级。不设辅导员。 */
  private static final List<Org> CLASSES =
      List.of(
          new Org("B01001", "2023级-软件工程-2301班", "01", "M01001", "2023"),
          new Org("B01002", "2024级-软件工程-2401班", "02", "M01001", "2024"),
          new Org("B01003", "2023级-计算机科学与技术-2301班", "03", "M01002", "2023"),
          new Org("B01004", "2024级-计算机科学与技术-2401班", "04", "M01002", "2024"),
          new Org("B02001", "2023级-工商管理-2301班", "05", "M02001", "2023"),
          new Org("B02002", "2024级-工商管理-2401班", "06", "M02001", "2024"),
          new Org("B02003", "2023级-会计学-2301班", "07", "M02002", "2023"),
          new Org("B02004", "2024级-会计学-2401班", "08", "M02002", "2024"),
          new Org("B03001", "2023级-土木工程-2301班", "09", "M03001", "2023"),
          new Org("B03002", "2024级-土木工程-2401班", "10", "M03001", "2024"),
          new Org("B03003", "2023级-工程管理-2301班", "11", "M03002", "2023"),
          new Org("B03004", "2024级-工程管理-2401班", "12", "M03002", "2024"),
          new Org("B04001", "2023级-英语-2301班", "13", "M04001", "2023"),
          new Org("B04002", "2024级-英语-2401班", "14", "M04001", "2024"),
          new Org("B04003", "2023级-日语-2301班", "15", "M04002", "2023"),
          new Org("B04004", "2024级-日语-2401班", "16", "M04002", "2024"),
          // 历史样本班级：只用来承载学业预警所需的历史成绩样本（见 SAMPLE_LAYERS），
          // 不会被任何正课引用，因此这些学生不会自动进入正课名单。
          new Org("B01005", "2020级-软件工程-2001班", "17", "M01001", "2020"),
          new Org("B02005", "2021级-工商管理-2101班", "18", "M02001", "2021"),
          new Org("B03005", "2022级-土木工程-2201班", "19", "M03001", "2022"),
          new Org("B04005", "2023级-英语-2302班", "20", "M04001", "2023"),
          new Org("B01006", "2022级-软件工程-2202班", "21", "M01001", "2022"));

  // ------------------------------------------------------------------ 账号

  /** 账号。管理员的三级组织字段为 null；教师到学院-专业；学生到学院-专业-班级。 */
  private record Person(
      String id,
      String name,
      String role,
      String permissions,
      String college,
      String major,
      String klass) {}

  private static Person admin(String id, String name) {
    return new Person(id, name, "ADMIN", ADMIN_PERMISSIONS, null, null, null);
  }

  private static Person teacher(String id, String name, String college, String major) {
    return new Person(id, name, "TEACHER", "QUERY,ENTRY,MAINTAIN,PREDICT", college, major, null);
  }

  private static Person student(String id, String name, String college, String major, String klass) {
    return new Person(id, name, "STUDENT", "QUERY,PREDICT,SELECTION_ENROLL", college, major, klass);
  }

  private static final List<Person> PEOPLE =
      List.of(
          admin("admin", "教务管理员"),
          admin("jw001", "教务处王老师"),
          admin("jw002", "教务处刘老师"),
          // 信息工程学院 · 软件工程
          teacher("t1101", "陈老师", "C01001", "M01001"),
          teacher("t1102", "李老师", "C01001", "M01001"),
          // 信息工程学院 · 计算机科学与技术
          teacher("t1201", "周老师", "C01001", "M01002"),
          teacher("t1202", "吴老师", "C01001", "M01002"),
          // 经济与管理学院 · 工商管理
          teacher("t2101", "赵老师", "C02001", "M02001"),
          // 经济与管理学院 · 会计学
          teacher("t2201", "钱老师", "C02001", "M02002"),
          teacher("t2202", "孙老师", "C02001", "M02002"),
          // 建筑工程学院 · 土木工程
          teacher("t3101", "郑老师", "C03001", "M03001"),
          // 建筑工程学院 · 工程管理
          teacher("t3201", "王老师", "C03001", "M03002"),
          // 外国语学院 · 英语
          teacher("t4101", "冯老师", "C04001", "M04001"),
          // 外国语学院 · 日语
          teacher("t4201", "蒋老师", "C04001", "M04002"),
          // 历史样本教师：各带一个学期的历史教学班（见 SAMPLE_LAYERS）。它们不参与演示登录
          // （{@link #DISABLED_USERNAMES} 里置为 enabled=0）：2020-1 这类最早的课没有更早年份，
          // 若作为可登录教师，其「我的课程」会要求每门课都能预测，反而违背验收标准。
          teacher("ht2020", "史料教师·2020-1", "C01001", "M01001"),
          teacher("ht2021", "史料教师·2021-1", "C01001", "M01001"),
          teacher("ht2022", "史料教师·2022-1", "C01001", "M01001"),
          teacher("ht2023", "史料教师·2023-2", "C01001", "M01001"));

  /**
   * 学生：学号、姓名、班级编号（可为空表示待分班）、专业编号。
   *
   * <p>正课班级每个 9 人，共 16 × 9 = 144 名已分班学生，另有 3 名待分班学生（班级为空），
   * 用于演示组织管理的「批量调入学生」；此外还有 45 名历史样本学生（{@link #SAMPLE_LAYERS} 与
   * {@link #PARTIAL_POOL_CLASS}），他们的成绩只服务于学业预警的训练样本与预测对象。班级人数足够多，历史教学班才能凑出足够的学习预警
   * 训练样本（见 {@link #verifyPredictionData()}）。
   */
  private static final String[][] STUDENTS = {
    {"20231530", "林知夏", "B01001", "M01001"},
    {"20231531", "周予安", "B01001", "M01001"},
    {"20231532", "陈嘉宁", "B01001", "M01001"},
    // 每班再补 6 人（学号沿用同一届的「16」号段），让一个教学班能产出 9 条成绩。
    {"20231601", "顾言之", "B01001", "M01001"},
    {"20231602", "沈砚清", "B01001", "M01001"},
    {"20231603", "陆星河", "B01001", "M01001"},
    {"20231604", "江照影", "B01001", "M01001"},
    {"20231605", "白鹿鸣", "B01001", "M01001"},
    {"20231606", "谢知遥", "B01001", "M01001"},
    {"20241530", "许清和", "B01002", "M01001"},
    {"20241531", "冯亦舟", "B01002", "M01001"},
    {"20241532", "邓墨白", "B01002", "M01001"},
    {"20241604", "温书白", "B01002", "M01001"},
    {"20241605", "阮时雨", "B01002", "M01001"},
    {"20241606", "龚明煦", "B01002", "M01001"},
    {"20241607", "戚星月", "B01002", "M01001"},
    {"20241608", "岑月白", "B01002", "M01001"},
    {"20241609", "邹清晚", "B01002", "M01001"},
    {"20231533", "李明远", "B01003", "M01002"},
    {"20231534", "王思齐", "B01003", "M01002"},
    {"20231535", "张书涵", "B01003", "M01002"},
    {"20231607", "秦望舒", "B01003", "M01002"},
    {"20231608", "楚云舒", "B01003", "M01002"},
    {"20231609", "柳含烟", "B01003", "M01002"},
    {"20231610", "唐映雪", "B01003", "M01002"},
    {"20231611", "宋雨棠", "B01003", "M01002"},
    {"20231612", "邵清嘉", "B01003", "M01002"},
    {"20241533", "曹语彤", "B01004", "M01002"},
    {"20241534", "彭一凡", "B01004", "M01002"},
    {"20241535", "苏念安", "B01004", "M01002"},
    {"20241610", "屈书宁", "B01004", "M01002"},
    {"20241611", "简星禾", "B01004", "M01002"},
    {"20241612", "梅长宁", "B01004", "M01002"},
    {"20241613", "卫清让", "B01004", "M01002"},
    {"20241614", "覃思远", "B01004", "M01002"},
    {"20241615", "邹月明", "B01004", "M01002"},
    {"20231536", "赵一诺", "B02001", "M02001"},
    {"20231537", "刘景行", "B02001", "M02001"},
    {"20231538", "孙若溪", "B02001", "M02001"},
    {"20231613", "程水瑶", "B02001", "M02001"},
    {"20231614", "傅以安", "B02001", "M02001"},
    {"20231615", "崔明烛", "B02001", "M02001"},
    {"20231616", "侯清越", "B02001", "M02001"},
    {"20231617", "杜若洲", "B02001", "M02001"},
    {"20231618", "罗时予", "B02001", "M02001"},
    {"20241536", "沈墨言", "B02002", "M02001"},
    {"20241537", "韩星辰", "B02002", "M02001"},
    {"20241538", "蒋知微", "B02002", "M02001"},
    {"20241616", "毕嘉禾", "B02002", "M02001"},
    {"20241617", "郝雨眠", "B02002", "M02001"},
    {"20241618", "常思宁", "B02002", "M02001"},
    {"20241619", "尹明棠", "B02002", "M02001"},
    {"20241620", "詹云谦", "B02002", "M02001"},
    {"20241621", "戚明彦", "B02002", "M02001"},
    {"20231539", "吴星野", "B02003", "M02002"},
    {"20231540", "郑以宁", "B02003", "M02002"},
    {"20231541", "何雨晴", "B02003", "M02002"},
    {"20231619", "卢见月", "B02003", "M02002"},
    {"20231620", "余时晏", "B02003", "M02002"},
    {"20231621", "邢知白", "B02003", "M02002"},
    {"20231622", "贺听雪", "B02003", "M02002"},
    {"20231623", "夏疏桐", "B02003", "M02002"},
    {"20231624", "洪亦清", "B02003", "M02002"},
    {"20241540", "杜若蘅", "B02004", "M02002"},
    {"20241541", "余安然", "B02004", "M02002"},
    {"20241542", "卢向晚", "B02004", "M02002"},
    {"20241622", "邱听白", "B02004", "M02002"},
    {"20241623", "樊清溪", "B02004", "M02002"},
    {"20241624", "万知秋", "B02004", "M02002"},
    {"20241625", "洪雨桐", "B02004", "M02002"},
    {"20241626", "夏语棠", "B02004", "M02002"},
    {"20241627", "齐云开", "B02004", "M02002"},
    {"20231542", "崔明轩", "B03001", "M03001"},
    {"20231543", "侯书言", "B03001", "M03001"},
    {"20231544", "杜清和", "B03001", "M03001"},
    {"20231625", "汪星辞", "B03001", "M03001"},
    {"20231626", "石望川", "B03001", "M03001"},
    {"20231627", "田沐阳", "B03001", "M03001"},
    {"20231628", "钟玉衡", "B03001", "M03001"},
    {"20231629", "姜长歌", "B03001", "M03001"},
    {"20231630", "范惊鸿", "B03001", "M03001"},
    {"20241543", "罗予墨", "B03002", "M03001"},
    {"20241544", "万嘉树", "B03002", "M03001"},
    {"20241545", "邢云舟", "B03002", "M03001"},
    {"20241628", "鲁知远", "B03002", "M03001"},
    {"20241629", "苗晚晴", "B03002", "M03001"},
    {"20241630", "汤书鸣", "B03002", "M03001"},
    {"20241631", "殷照临", "B03002", "M03001"},
    {"20241632", "岳星洲", "B03002", "M03001"},
    {"20241633", "尚清许", "B03002", "M03001"},
    {"20231545", "夏知野", "B03003", "M03002"},
    {"20231546", "洪亦辰", "B03003", "M03002"},
    {"20231547", "龚清越", "B03003", "M03002"},
    {"20231631", "彭拂衣", "B03003", "M03002"},
    {"20231632", "蒋拾光", "B03003", "M03002"},
    {"20231633", "崔晏清", "B03003", "M03002"},
    {"20231634", "潘朝雨", "B03003", "M03002"},
    {"20231635", "于听风", "B03003", "M03002"},
    {"20231636", "董沐雪", "B03003", "M03002"},
    {"20241546", "邹星阑", "B03004", "M03002"},
    {"20241547", "贺轻舟", "B03004", "M03002"},
    {"20241548", "尹照野", "B03004", "M03002"},
    {"20241634", "柏景初", "B03004", "M03002"},
    {"20241635", "储知秋", "B03004", "M03002"},
    {"20241636", "岑听月", "B03004", "M03002"},
    {"20241637", "娄清扬", "B03004", "M03002"},
    {"20241638", "路明泽", "B03004", "M03002"},
    {"20241639", "甘雨薇", "B03004", "M03002"},
    {"20231548", "葛晚舟", "B04001", "M04001"},
    {"20231549", "傅言蹊", "B04001", "M04001"},
    {"20231550", "阮听澜", "B04001", "M04001"},
    {"20231637", "袁归舟", "B04001", "M04001"},
    {"20231638", "叶岁安", "B04001", "M04001"},
    {"20231639", "阎令仪", "B04001", "M04001"},
    {"20231640", "章松月", "B04001", "M04001"},
    {"20231641", "卓竹清", "B04001", "M04001"},
    {"20231642", "施芷若", "B04001", "M04001"},
    {"20241549", "简栖迟", "B04002", "M04001"},
    {"20241550", "屈南州", "B04002", "M04001"},
    {"20241551", "梅疏影", "B04002", "M04001"},
    {"20241640", "应清和", "B04002", "M04001"},
    {"20241641", "向晚亭", "B04002", "M04001"},
    {"20241642", "席明澜", "B04002", "M04001"},
    {"20241643", "童语禾", "B04002", "M04001"},
    {"20241644", "车明月", "B04002", "M04001"},
    {"20241645", "宁知微", "B04002", "M04001"},
    {"20231551", "关山月", "B04003", "M04002"},
    {"20231552", "裴云岫", "B04003", "M04002"},
    {"20231553", "温以澜", "B04003", "M04002"},
    {"20231643", "韦兰因", "B04003", "M04002"},
    {"20231644", "毕清晏", "B04003", "M04002"},
    {"20231645", "郝云程", "B04003", "M04002"},
    {"20231646", "尹听竹", "B04003", "M04002"},
    {"20231647", "常语棠", "B04003", "M04002"},
    {"20231648", "康望之", "B04003", "M04002"},
    {"20241552", "卫长风", "B04004", "M04002"},
    {"20241553", "覃清昼", "B04004", "M04002"},
    {"20241554", "祁无咎", "B04004", "M04002"},
    {"20241646", "晏清如", "B04004", "M04002"},
    {"20241647", "容与安", "B04004", "M04002"},
    {"20241648", "樊听露", "B04004", "M04002"},
    {"20241649", "邹清和", "B04004", "M04002"},
    {"20241650", "傅明溪", "B04004", "M04002"},
    {"20241651", "骆星原", "B04004", "M04002"},
    // 历史样本学生：承载学业预警所需的历史成绩（见 SAMPLE_LAYERS），每人每池 8 人。
    // 每个池对应一个历史样本层，池与池之间不共用学生，因此任何学生都不会在同一课程代码上
    // 出现两段记录（重修样本除外）；他们也完全不参与正课的整班铺开。
    {"20201601", "楚怀瑾", "B01005", "M01001"},
    {"20201602", "苏景年", "B01005", "M01001"},
    {"20201603", "温言之", "B01005", "M01001"},
    {"20201604", "沈知许", "B01005", "M01001"},
    {"20201605", "陆时行", "B01005", "M01001"},
    {"20201606", "江照微", "B01005", "M01001"},
    {"20201607", "白鹤洲", "B01005", "M01001"},
    {"20201608", "谢清玄", "B01005", "M01001"},
    {"20211601", "秦望山", "B02005", "M02001"},
    {"20211602", "柳含章", "B02005", "M02001"},
    {"20211603", "唐云起", "B02005", "M02001"},
    {"20211604", "宋雨眠", "B02005", "M02001"},
    {"20211605", "邵清越", "B02005", "M02001"},
    {"20211606", "程若谷", "B02005", "M02001"},
    {"20211607", "傅明川", "B02005", "M02001"},
    {"20211608", "崔知白", "B02005", "M02001"},
    {"20221601", "卢见山", "B03005", "M03001"},
    {"20221602", "余清许", "B03005", "M03001"},
    {"20221603", "邢照川", "B03005", "M03001"},
    {"20221604", "贺云归", "B03005", "M03001"},
    {"20221605", "夏疏影", "B03005", "M03001"},
    {"20221606", "洪清和", "B03005", "M03001"},
    {"20221607", "汪星舟", "B03005", "M03001"},
    {"20221608", "石沐雪", "B03005", "M03001"},
    {"20231649", "钟玉书", "B04005", "M04001"},
    {"20231650", "姜长明", "B04005", "M04001"},
    {"20231651", "范清昼", "B04005", "M04001"},
    {"20231652", "彭听竹", "B04005", "M04001"},
    {"20231653", "蒋拾月", "B04005", "M04001"},
    {"20231654", "崔晏舟", "B04005", "M04001"},
    {"20231655", "潘朝云", "B04005", "M04001"},
    {"20231656", "于清和", "B04005", "M04001"},
    // 缓考样本池：为每门已提交成绩的正课补一名「有平时与实验、缺期末」的学生，
    // 让学业预警在这些课上也有可预测对象（名单来源见 PARTIAL_POOL_CLASS）。
    {"20221609", "袁归远", "B01006", "M01001"},
    {"20221610", "叶岁晚", "B01006", "M01001"},
    {"20221611", "阎令昭", "B01006", "M01001"},
    {"20221612", "章松雪", "B01006", "M01001"},
    {"20221613", "卓清月", "B01006", "M01001"},
    {"20221614", "施芷兰", "B01006", "M01001"},
    {"20221615", "韦兰舟", "B01006", "M01001"},
    {"20221616", "毕清禾", "B01006", "M01001"},
    // 待分班学生：班级留空、只归属到专业，用于演示「组织管理 → 批量调入学生」
    {"20241601", "安时雨", "", "M01001"},
    {"20241602", "柏舟", "", "M02001"},
    {"20241603", "岑云归", "", "M03001"},
  };

  // ------------------------------------------------------------------ 课程

  /**
   * 教学班。
   *
   * @param id 主键
   * @param code 课程代码
   * @param name 课程名称
   * @param term 学期
   * @param teacher 授课教师
   * @param credits 学分
   * @param college 开设院系
   * @param klass 面向班级
   * @param regular 平时分权重
   * @param lab 实验分权重
   * @param finalExam 期末分权重
   * @param submitted 是否已提交成绩
   */
  private record Course(
      String id,
      String code,
      String name,
      String term,
      String teacher,
      double credits,
      String college,
      String klass,
      int regular,
      int lab,
      int finalExam,
      boolean submitted) {}

  /**
   * 64 个正课教学班，横跨 5 个学期（2023-1、2024-1、2025-1、2025-2、2026-1）；另有
   * {@link #SAMPLE_COURSES} 里的 91 个历史样本教学班（2020-1、2021-1、2022-1、2023-2）。
   * 同一学期内课程代码可以重复（不同班级分别开课），但除 {@link #RETAKES} 里的重修样本外，
   * 同一学生的修读轨迹不重复课程代码。
   *
   * <p><b>重修不体现在课程名上</b>：重修就是「同一课程代码在后续学年重新开设」，课程名与普通
   * 教学班完全一致（CS102「程序设计基础」在 2023-1 与 2024-1 各有一个教学班，只有开设学年不同），
   * 因此这里没有任何「（重修）」字样。谁是重修由成绩单推出来：更早学期同一课程代码挂过科。
   *
   * <p>重修落点：{@code c21-cs102b}、{@code c33-mg101b}、{@code c34-ce101b} 是 2024-1 的教学班
   * （面向新的年级开课，重修学生按 {@link #RETAKES} 显式补录选课）；{@code c35-cs102c} 是 2026-1
   * 当前学期、成绩未提交的重修班（只接收重修学生），教师登录后可直接看到并现场录入成绩。
   */
  private static final List<Course> COURSES =
      List.of(
          // ---- 2023-1：四个学院八个班的公共基础课
          new Course("c1-cs101", "CS101", "计算机导论", "2023-1", "t1101", 3, "C01001", "B01001", 40, 0, 60, true),
          new Course("c2-cs102", "CS102", "程序设计基础", "2023-1", "t1102", 4, "C01001", "B01001", 30, 20, 50, true),
          new Course("c3-ma101", "MA101", "高等数学（上）", "2023-1", "t1201", 5, "C01001", "B01003", 40, 0, 60, true),
          new Course("c4-mg101", "MG101", "管理学原理", "2023-1", "t2101", 3, "C02001", "B02001", 40, 0, 60, true),
          new Course("c5-ac101", "AC101", "会计学基础", "2023-1", "t2201", 3, "C02001", "B02003", 40, 0, 60, true),
          new Course("c6-ce101", "CE101", "工程制图", "2023-1", "t3101", 3, "C03001", "B03001", 30, 20, 50, true),
          new Course("c7-em101", "EM101", "工程项目管理", "2023-1", "t3201", 3, "C03001", "B03003", 40, 0, 60, true),
          new Course("c8-en101", "EN101", "综合英语（一）", "2023-1", "t4101", 4, "C04001", "B04001", 40, 0, 60, true),
          new Course("c9-jp101", "JP101", "基础日语（一）", "2023-1", "t4201", 4, "C04001", "B04003", 40, 0, 60, true),
          // ---- 2024-1：面向 2023 级与 2024 级
          new Course("c10-cs201", "CS201", "数据结构", "2024-1", "t1101", 4, "C01001", "B01001", 40, 0, 60, true),
          new Course("c11-cs202", "CS202", "计算机网络", "2024-1", "t1201", 3, "C01001", "B01003", 30, 20, 50, true),
          new Course("c12-cs103", "CS103", "面向对象程序设计", "2024-1", "t1102", 3, "C01001", "B01002", 30, 20, 50, true),
          new Course("c13-cs104", "CS104", "离散数学", "2024-1", "t1202", 3, "C01001", "B01004", 40, 0, 60, true),
          new Course("c14-mg201", "MG201", "市场营销", "2024-1", "t2101", 3, "C02001", "B02002", 40, 0, 60, true),
          new Course("c15-ac201", "AC201", "财务会计", "2024-1", "t2201", 4, "C02001", "B02004", 40, 0, 60, true),
          new Course("c16-ce201", "CE201", "结构力学", "2024-1", "t3101", 4, "C03001", "B03002", 40, 0, 60, true),
          new Course("c17-en201", "EN201", "英语听力（一）", "2024-1", "t4101", 2, "C04001", "B04002", 50, 0, 50, true),
          new Course("c18-jp201", "JP201", "日语听力（一）", "2024-1", "t4201", 2, "C04001", "B04004", 50, 0, 50, true),
          // 重修落点（2024-1）：课程名与挂科学期的同一代码完全一致，只是面向下一届重新开设；
          // 重修学生不在面向班级里，由 seedEnrollmentsAndGrades() 按 RETAKES 显式补录选课。
          new Course("c21-cs102b", "CS102", "程序设计基础", "2024-1", "t1102", 4, "C01001", "B01002", 30, 20, 50, true),
          new Course("c33-mg101b", "MG101", "管理学原理", "2024-1", "t2101", 3, "C02001", "B02002", 40, 0, 60, true),
          new Course("c34-ce101b", "CE101", "工程制图", "2024-1", "t3101", 3, "C03001", "B03002", 30, 20, 50, true),
          // ---- 2025-1：CS401 是同一课程代码的第二个教学班（不同教师），供「不得选不同教师的同一门课」使用
          new Course("c19-cs401a", "CS401", "软件工程", "2025-1", "t1101", 3, "C01001", "B01001", 30, 20, 50, true),
          new Course("c20-cs401b", "CS401", "软件工程", "2025-1", "t1102", 3, "C01001", "B01002", 30, 20, 50, true),
          // ---- 2025-2：数据库系统面向已通过前置课程的 2023 级
          new Course("c22-cs302", "CS302", "数据库系统", "2025-2", "t1102", 4, "C01001", "B01001", 30, 20, 50, true),
          new Course("c23-ac301", "AC301", "成本会计", "2025-2", "t2202", 3, "C02001", "B02003", 40, 0, 60, true),
          // ---- 2026-1：当前学期，成绩未提交，供教师现场录入与选课演示
          new Course("c24-cs301", "CS301", "网络软件与安全", "2026-1", "t1101", 3, "C01001", "B01002", 30, 20, 50, false),
          new Course("c25-cs403", "CS403", "人工智能导论", "2026-1", "t1102", 3, "C01001", "B01002", 30, 20, 50, false),
          new Course("c26-cs203", "CS203", "操作系统", "2026-1", "t1202", 4, "C01001", "B01004", 30, 20, 50, false),
          new Course("c27-mg301", "MG301", "运营管理", "2026-1", "t2101", 3, "C02001", "B02002", 40, 0, 60, false),
          new Course("c28-ac401", "AC401", "审计学", "2026-1", "t2202", 3, "C02001", "B02004", 40, 0, 60, false),
          new Course("c29-ce301", "CE301", "混凝土结构设计", "2026-1", "t3101", 4, "C03001", "B03002", 30, 20, 50, false),
          new Course("c30-em201", "EM201", "工程经济学", "2026-1", "t3201", 3, "C03001", "B03004", 40, 0, 60, false),
          new Course("c31-en301", "EN301", "英语写作", "2026-1", "t4101", 2, "C04001", "B04002", 50, 0, 50, false),
          new Course("c32-jp301", "JP301", "日语会话", "2026-1", "t4201", 2, "C04001", "B04004", 50, 0, 50, false),
          // 2026-1 的重修班：只接收重修学生（见 RETAKE_ONLY_COURSES），成绩未提交，
          // 教师登录后能直接看到这些重修学生并现场录入成绩。
          new Course("c35-cs102c", "CS102", "程序设计基础", "2026-1", "t1102", 4, "C01001", "B01002", 30, 20, 50, false),
          // ---- 学业预警训练样本：学业预警（AnalyticsService.predict）取「同一课程代码在更早学期」
          // 的已提交成绩做训练集，要求 ≥3 个不同年份、≥24 条且平时/实验/期末三分项齐全。
          // 下面为 2026-1 正在开课的每个课程代码补历史教学班：2023-1、2024-1、2025-1 各一个，
          // 分别面向 3 个不同的班级，因此同一名学生不会重复修读同一代码（重修样本不受影响），
          // 每个代码得到 3 × 9 = 27 条完整样本；CS102 另有 2023-1/2024-1 两个原有教学班（19 条），
          // 再补两个即 37 条。权重统一 30/20/50，保证三个分项都会写进成绩载荷；
          // 面向班级一律避开「已修过该代码」和「2026-1 正在修读该代码」的班级。
          new Course("c36-cs301a", "CS301", "网络软件与安全", "2023-1", "t1101", 3, "C01001", "B01001", 30, 20, 50, true),
          new Course("c37-cs301b", "CS301", "网络软件与安全", "2024-1", "t1101", 3, "C01001", "B01003", 30, 20, 50, true),
          new Course("c38-cs301c", "CS301", "网络软件与安全", "2025-1", "t1101", 3, "C01001", "B01004", 30, 20, 50, true),
          new Course("c39-cs403a", "CS403", "人工智能导论", "2023-1", "t1102", 3, "C01001", "B01003", 30, 20, 50, true),
          new Course("c40-cs403b", "CS403", "人工智能导论", "2024-1", "t1102", 3, "C01001", "B01001", 30, 20, 50, true),
          new Course("c41-cs403c", "CS403", "人工智能导论", "2025-1", "t1102", 3, "C01001", "B01004", 30, 20, 50, true),
          new Course("c42-cs203a", "CS203", "操作系统", "2023-1", "t1202", 4, "C01001", "B01003", 30, 20, 50, true),
          new Course("c43-cs203b", "CS203", "操作系统", "2024-1", "t1202", 4, "C01001", "B01001", 30, 20, 50, true),
          new Course("c44-cs203c", "CS203", "操作系统", "2025-1", "t1202", 4, "C01001", "B01002", 30, 20, 50, true),
          new Course("c45-cs102d", "CS102", "程序设计基础", "2024-1", "t1102", 4, "C01001", "B01004", 30, 20, 50, true),
          new Course("c46-cs102e", "CS102", "程序设计基础", "2025-1", "t1102", 4, "C01001", "B01003", 30, 20, 50, true),
          new Course("c47-mg301a", "MG301", "运营管理", "2023-1", "t2101", 3, "C02001", "B02001", 30, 20, 50, true),
          new Course("c48-mg301b", "MG301", "运营管理", "2024-1", "t2101", 3, "C02001", "B02003", 30, 20, 50, true),
          new Course("c49-mg301c", "MG301", "运营管理", "2025-1", "t2101", 3, "C02001", "B02004", 30, 20, 50, true),
          new Course("c50-ac401a", "AC401", "审计学", "2023-1", "t2202", 3, "C02001", "B02003", 30, 20, 50, true),
          new Course("c51-ac401b", "AC401", "审计学", "2024-1", "t2202", 3, "C02001", "B02001", 30, 20, 50, true),
          new Course("c52-ac401c", "AC401", "审计学", "2025-1", "t2202", 3, "C02001", "B02002", 30, 20, 50, true),
          new Course("c53-ce301a", "CE301", "混凝土结构设计", "2023-1", "t3101", 4, "C03001", "B03001", 30, 20, 50, true),
          new Course("c54-ce301b", "CE301", "混凝土结构设计", "2024-1", "t3101", 4, "C03001", "B03003", 30, 20, 50, true),
          new Course("c55-ce301c", "CE301", "混凝土结构设计", "2025-1", "t3101", 4, "C03001", "B03004", 30, 20, 50, true),
          new Course("c56-em201a", "EM201", "工程经济学", "2023-1", "t3201", 3, "C03001", "B03003", 30, 20, 50, true),
          new Course("c57-em201b", "EM201", "工程经济学", "2024-1", "t3201", 3, "C03001", "B03001", 30, 20, 50, true),
          new Course("c58-em201c", "EM201", "工程经济学", "2025-1", "t3201", 3, "C03001", "B03002", 30, 20, 50, true),
          new Course("c59-en301a", "EN301", "英语写作", "2023-1", "t4101", 2, "C04001", "B04001", 30, 20, 50, true),
          new Course("c60-en301b", "EN301", "英语写作", "2024-1", "t4101", 2, "C04001", "B04003", 30, 20, 50, true),
          new Course("c61-en301c", "EN301", "英语写作", "2025-1", "t4101", 2, "C04001", "B04004", 30, 20, 50, true),
          new Course("c62-jp301a", "JP301", "日语会话", "2023-1", "t4201", 2, "C04001", "B04003", 30, 20, 50, true),
          new Course("c63-jp301b", "JP301", "日语会话", "2024-1", "t4201", 2, "C04001", "B04001", 30, 20, 50, true),
          new Course("c64-jp301c", "JP301", "日语会话", "2025-1", "t4201", 2, "C04001", "B04002", 30, 20, 50, true));

  /** 成绩拟合用的固定噪声，保证每次重建结果一致。 */
  private static final Random RNG = new Random(20260101);

  /** 当前学期：只有它正在开课，2026-1 的每个课程代码都需要可预测的历史样本。 */
  private static final String CURRENT_TERM = "2026-1";

  /** 学业预警的训练样本下限：至少 3 个不同年份、24 条三分项齐全的成绩。 */
  private static final int MIN_PREDICTION_YEARS = 3;

  private static final int MIN_PREDICTION_SAMPLES = 24;

  // ---- 学业预警的历史成绩样本 ------------------------------------------

  /**
   * 历史样本层：在 {@code term} 学期为 {@code codes} 里的每个课程代码开一个教学班，
   * 名单与成绩都来自 {@code poolClass} 班级的样本学生（每层 8 人），任课教师是 {@code teacher}。
   *
   * <p>学业预警（{@code AnalyticsService.predict}）要求被预测课程代码在<b>更早学期</b>有 3 个不同年份、
   * 24 条三分项齐全的已提交成绩；而正课最早只到 2023-1，所以必须补 2020-1/2021-1/2022-1/2023-2
   * 的历史教学班。这些学期是最早的期次，没有任何更早学期可查，因此它们有选课与成绩也不会引入
   * 新的覆盖要求（{@link #verifyPredictionCoverage()} 只校验真正的正课）。
   */
  private record SampleLayer(String term, String poolClass, String teacher, List<String> codes) {}

  /** 2020-1 层：所有在 2023-1 有正课的 18 个代码——它们需要 2020/2021/2022 三个更早年份。 */
  private static final List<String> SAMPLE_CODES_2020 =
      List.of(
          "CS101", "CS102", "MA101", "MG101", "AC101", "CE101", "EM101", "EN101", "JP101",
          "CS301", "CS403", "CS203", "MG301", "AC401", "CE301", "EM201", "EN301", "JP301");

  /** 2021-1 / 2022-1 层：需要支持的全部 30 个代码＝上面 18 个 ＋ 2024-1 起才有正课的 12 个。 */
  private static final List<String> SAMPLE_CODES_2021 =
      List.of(
          "CS101", "CS102", "MA101", "MG101", "AC101", "CE101", "EM101", "EN101", "JP101",
          "CS301", "CS403", "CS203", "MG301", "AC401", "CE301", "EM201", "EN301", "JP301",
          "CS201", "CS202", "CS103", "CS104", "MG201", "AC201", "CE201", "EN201", "JP201",
          "CS401", "CS302", "AC301");

  /**
   * 2023-2 层：2024-1 起才有正课的 12 个代码（它们的第 3 个样本年份只能是 2023），
   * 外加 MG101——它在 2023-1 的正课权重没有实验分，成绩不含 {@code lab}，无法充当训练样本。
   */
  private static final List<String> SAMPLE_CODES_2023 =
      List.of(
          "CS201", "CS202", "CS103", "CS104", "MG201", "AC201", "CE201", "EN201", "JP201",
          "CS401", "CS302", "AC301", "MG101");

  private static final List<SampleLayer> SAMPLE_LAYERS =
      List.of(
          new SampleLayer("2020-1", "B01005", "ht2020", SAMPLE_CODES_2020),
          new SampleLayer("2021-1", "B02005", "ht2021", SAMPLE_CODES_2021),
          new SampleLayer("2022-1", "B03005", "ht2022", SAMPLE_CODES_2021),
          new SampleLayer("2023-2", "B04005", "ht2023", SAMPLE_CODES_2023));

  /** 缓考样本池：为每门已提交成绩的正课补一名「有平时与实验、缺期末」的学生。 */
  private static final String PARTIAL_POOL_CLASS = "B01006";

  /** 历史样本教学班：有名单、有成绩，任课教师是各层的 {@code ht20xx}（不可登录）。 */
  private static final List<Course> SAMPLE_COURSES = buildSampleCourses();

  private static final Set<String> SAMPLE_COURSE_IDS = sampleCourseIds();

  /** 历史样本成绩用的固定噪声：与正课分开，保证正课拟合出的成绩不因新增样本而变化。 */
  private static final Random SAMPLE_RNG = new Random(20260202);

  private static List<Course> buildSampleCourses() {
    var result = new ArrayList<Course>();
    for (SampleLayer layer : SAMPLE_LAYERS)
      for (String code : layer.codes()) {
        Course prototype = prototypeOf(code);
        result.add(
            new Course(
                "h-" + layer.term() + "-" + code.toLowerCase(Locale.ROOT),
                code,
                prototype.name(),
                layer.term(),
                layer.teacher(),
                prototype.credits(),
                prototype.college(),
                layer.poolClass(),
                30,
                20,
                50,
                true));
      }
    return List.copyOf(result);
  }

  private static Set<String> sampleCourseIds() {
    Set<String> ids = new LinkedHashSet<>();
    for (Course c : SAMPLE_COURSES) ids.add(c.id());
    return ids;
  }

  /** 同名同代码的正课：样本教学班沿用它的课程名、学分与开设院系，保证「同一门课」名称一致。 */
  private static Course prototypeOf(String code) {
    for (Course c : COURSES) if (c.code().equals(code)) return c;
    throw new IllegalStateException("历史样本引用了不存在的课程代码：" + code);
  }

  /** 某个班级的学生（按 STUDENTS 的声明顺序）。 */
  private static List<String> studentsOfClass(String classId) {
    var ids = new ArrayList<String>();
    for (String[] s : STUDENTS) if (classId.equals(s[2])) ids.add(s[0]);
    return ids;
  }

  /**
   * 重修轨迹：一名学生先在 {@code failedCourse} 挂科（正考与补考都不及格），再于后续学期修读
   * {@code retakeCourse}——两者必须是<b>同一课程代码</b>，课程名与其他教学班完全一致。
   *
   * @param student 学号
   * @param code 课程代码（挂科学期与重修学期必须相同）
   * @param failedCourse 挂科的教学班编号
   * @param retakeCourse 重修的教学班编号（学期已结束则须通过；当前学期不提交成绩）
   */
  private record Retake(String student, String code, String failedCourse, String retakeCourse) {}

  /**
   * 5 名重修学生，覆盖 3 个课程代码（CS102/MG101/CE101）与 3 所学院：前 3 人已于 2024-1 重修
   * 通过，后 2 人正在 2026-1（当前学期）重修且成绩未提交，供教师现场录入。
   */
  private static final List<Retake> RETAKES =
      List.of(
          new Retake("20231530", "CS102", "c2-cs102", "c21-cs102b"),
          new Retake("20231536", "MG101", "c4-mg101", "c33-mg101b"),
          new Retake("20231542", "CE101", "c6-ce101", "c34-ce101b"),
          new Retake("20241531", "CS102", "c21-cs102b", "c35-cs102c"),
          new Retake("20241532", "CS102", "c21-cs102b", "c35-cs102c"));

  /** 只接收重修学生的教学班：不做整班铺开，名单完全来自 {@link #RETAKES} 的显式补录。 */
  private static final Set<String> RETAKE_ONLY_COURSES = Set.of("c35-cs102c");

  /** 自检下限：至少要有这么多名学生具备「挂科 → 后续学期同一代码重修」的轨迹。 */
  private static final int MIN_RETAKERS = 3;

  /** 及格线：业务侧以「有效分 ≥ 60」判通过。 */
  private static final double PASS_SCORE = 60;

  /** 挂科成绩：加权总评不及格，补考 52 分同样不及格，有效分 52（< 60）。 */
  private static final String FAILED_PAYLOAD =
      "{\"regular\":45.0,\"lab\":50.0,\"finalExam\":48.0,\"makeup\":52.0}";

  /** 重修通过成绩：加权总评不及格，补考 87 分按「补考最高 60 分」封顶，有效分 60。 */
  private static final String RETAKE_PASSED_PAYLOAD =
      "{\"regular\":50.0,\"lab\":55.0,\"finalExam\":52.0,\"makeup\":87.0}";

  @Autowired private JdbcTemplate jdbc;

  @Autowired private SchemaCatalog catalog;

  /** 数据加密密钥：成绩载荷与账本快照的 AAD 都依赖它。 */
  @Value("${campus.DATA_KEY}")
  public String KEY;

  private final RpcClient audit = new RpcClient("data");

  @Override
  public void run(ApplicationArguments args) {
    boolean explicitReset = resetRequested();
    // 清理测试残留放在最前面：系统没有课程/批次删除接口，端到端脚本留下的教学班与选课批次
    // 只能在每次启动时兜底清掉；它按「名称含测试/低人数」或「学期不在本类声明的集合里」判断，
    // 且发生在本次 seeding 之前，因此不会误删演示数据。
    purgeTestArtifacts();
    long users = count("users");
    // 需要灌数据的三种情况：显式重置开关、结构重建丢弃了旧数据、或者库里本来就没账号。
    // wasRebuilt() 只在「原有业务数据被清空」时为 true，空库首次建表不算重建。
    boolean rebuilt = catalog.wasRebuilt();
    boolean empty = users == 0;
    if (!explicitReset && !rebuilt && !empty) {
      System.out.println(
          "[DemoInitializer] 数据库已有 " + users + " 个账号且结构版本一致，跳过初始化。");
      return;
    }
    // 灌库前先清空：clearAll() 会删掉全部业务表与结构标记，再由 catalog.init() 按当前定义重建。
    //  · rebuilt：SchemaCatalog 已经整库重建过，表是刚建的空表，不必再删一次；
    //  · explicitReset：显式要求重建，删除后重灌；
    //  · empty（没有账号）：业务表里可能残留上一次未写完的半灌数据——初始化中途失败或被中断时
    //    会停在「colleges 有行、users 为空」这类状态，此时直接重灌会撞主键冲突
    //    （如 colleges 已有 C01001），报错后库再也无法自愈。先清后灌让初始化可重复执行。
    if (!rebuilt) {
      System.out.println(
          explicitReset
              ? "[DemoInitializer] 收到显式重建开关，删除全部业务表并重新灌入数据。"
              : "[DemoInitializer] 检测到未完成的历史灌库数据，清空全部业务表后重新灌入。");
      clearAll();
    }
    System.out.println(
        "[DemoInitializer] 开始重建演示数据（结构版本 " + SchemaCatalog.SCHEMA_VERSION + "）。");
    try {
      resetLedger();
      seedOrganizations();
      seedUsers();
      seedCourses();
      seedEnrollmentsAndGrades();
      seedCourseSelection();
      System.out.println(
          "[DemoInitializer] 数据已写入："
              + count("colleges") + " 个学院、"
              + count("majors") + " 个专业、"
              + count("classes") + " 个班级、"
              + count("users") + " 个账号、"
              + count("courses") + " 个教学班、"
              + count("enrollments") + " 条选课、"
              + count("grades") + " 条成绩、"
              + count("course_selections") + " 个选课批次。");
    } catch (Exception e) {
      System.err.println("[DemoInitializer] 初始化失败：" + e.getMessage());
      e.printStackTrace();
      return; // 数据没写完整，跳过自检（与旧版一致：只报错不中止启动）
    }
    // 数据自检放在兜底 catch 之外：演示数据不合法时直接抛异常中止启动，
    // 避免脏数据被当成正常数据使用，也避免选课规则与学业预警随之误判。
    verifyOrganizationIntegrity();
    verifyTranscriptIntegrity();
    verifyPredictionCoverage();
    try {
      seedAuditLedger();
    } catch (Exception e) {
      System.err.println("[DemoInitializer] 账本重建失败（不影响演示数据）：" + e.getMessage());
      e.printStackTrace();
    }
  }

  /** 显式重建开关：系统属性优先，其次环境变量，便于 Windows 与 IDEA 运行配置使用。 */
  public static boolean resetRequested() {
    return "true".equalsIgnoreCase(Objects.toString(System.getProperty("campus.reset-db"), ""))
        || "true".equalsIgnoreCase(Objects.toString(System.getenv("CAMPUS_RESET_DB"), ""));
  }

  private long count(String table) {
    try {
      Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
      return value == null ? 0 : value;
    } catch (Exception e) {
      return 0;
    }
  }

  /** 显式重建：删除全部业务表，使结构重新按当前定义创建。 */
  private void clearAll() {
    var tables = new ArrayList<>(catalog.tables.keySet());
    Collections.reverse(tables);
    for (String table : tables)
      try {
        jdbc.execute("DROP TABLE IF EXISTS " + table);
      } catch (Exception e) {
        System.err.println("[DemoInitializer] 删除表 " + table + " 失败：" + e.getMessage());
      }
    // 结构版本表也要一起清掉，否则 catalog.init() 会认为结构已经是最新的。
    // 列名用 version_value 而不是保留字 value。
    try {
      jdbc.execute("DROP TABLE IF EXISTS schema_meta");
    } catch (Exception ignored) {
    }
    catalog.init();
  }

  /** 本类声明的全部学期：清理测试残留时用它区分「不是演示数据的学期」。 */
  private static Set<String> declaredTerms() {
    Set<String> terms = new LinkedHashSet<>();
    for (Course c : COURSES) terms.add(c.term());
    for (Course c : SAMPLE_COURSES) terms.add(c.term());
    return terms;
  }

  /**
   * 清除测试残留：端到端脚本会在 {@code 2027-2} 之类的一次性学期里建教学班与选课批次，而系统
   * <b>没有课程/批次的删除接口</b>，这些残留只能由初始化器在每次启动时兜底清理。
   *
   * <p>判据统一为「名称含『测试』或『低人数』」＋「学期/批次不在本类声明的集合内」，因此不会误删
   * 演示数据本身；而且它在本次 seeding 之前执行，不会碰到刚写进去的数据。表间没有外键约束，
   * 选课、成绩、选课流水与成绩分析都要按 course_id 自行级联删除。
   */
  private void purgeTestArtifacts() {
    Set<String> declared = declaredTerms();
    var doomedCourses = new ArrayList<String>();
    for (var row : jdbc.queryForList("SELECT id,name,term FROM courses")) {
      String name = column(row, "name");
      if (name.contains("测试") || name.contains("低人数") || !declared.contains(column(row, "term")))
        doomedCourses.add(column(row, "id"));
    }
    int enrollments = 0, grades = 0, records = 0;
    for (String id : doomedCourses) {
      enrollments += jdbc.update("DELETE FROM enrollments WHERE course_id=?", id);
      grades += jdbc.update("DELETE FROM grades WHERE course_id=?", id);
      records += jdbc.update("DELETE FROM enrollment_records WHERE course_id=?", id);
      jdbc.update("DELETE FROM analyses WHERE course_id=?", id);
      jdbc.update("DELETE FROM courses WHERE id=?", id);
    }
    var doomedSelections = new ArrayList<String>();
    for (var row : jdbc.queryForList("SELECT id,name FROM course_selections")) {
      String name = column(row, "name");
      if (name.contains("测试") || name.contains("低人数")) doomedSelections.add(column(row, "id"));
    }
    for (String id : doomedSelections) {
      records += jdbc.update("DELETE FROM enrollment_records WHERE publish_id=?", id);
      jdbc.update("DELETE FROM course_selections WHERE id=?", id);
    }
    if (doomedCourses.isEmpty() && doomedSelections.isEmpty()) {
      System.out.println("[DemoInitializer] 未发现测试课程/测试选课批次残留。");
      return;
    }
    System.out.println(
        "[DemoInitializer] 已清除 "
            + doomedCourses.size() + " 门测试课程、"
            + enrollments + " 条选课、"
            + grades + " 条成绩、"
            + doomedSelections.size() + " 个测试选课批次（另有 "
            + records + " 条选课流水）。");
  }

  /** 演示库重建时同步清空独立账本与链锚点：旧快照指向已删除的成绩行。 */
  private void resetLedger() {
    try {
      audit.post("audit", "/internal/reset", Map.of(), Map.class);
    } catch (Exception e) {
      System.err.println("[DemoInitializer] 账本重建接口不可用，改为直接清理文件：" + e.getMessage());
      try {
        Path file = Settings.root().resolve("ledger/events.jsonl");
        if (Files.exists(file)) Files.write(file, new byte[0]);
      } catch (Exception again) {
        System.err.println("[DemoInitializer] 清理账本文件失败：" + again.getMessage());
      }
    }
  }

  // ------------------------------------------------------------------ 写入

  /**
   * 写入后自检：把三类组织字段的层级关系核一遍，防止「班级 parent 当成学院」这类静默错误。
   *
   * <p>曾经出现过的真实缺陷：学生的 {@code college_id} 被写成专业编号（因为取了班级的 parent），
   * 结果选课范围校验（比较 {@code college_id}）永远不通过，而列表页看不出来。这里在初始化结束时
   * 直接抛异常，避免再次静默入库。
   */
  private void verifyOrganizationIntegrity() {
    for (var row : jdbc.queryForList("SELECT id,role,college_id,major_id,class_id FROM users")) {
      String id = Objects.toString(row.get("ID"), Objects.toString(row.get("id"), ""));
      String role = Objects.toString(row.get("ROLE"), Objects.toString(row.get("role"), ""));
      String college = blank(Objects.toString(row.get("COLLEGE_ID"), Objects.toString(row.get("college_id"), "")));
      String major = blank(Objects.toString(row.get("MAJOR_ID"), Objects.toString(row.get("major_id"), "")));
      String klass = blank(Objects.toString(row.get("CLASS_ID"), Objects.toString(row.get("class_id"), "")));
      if (role.equals("ADMIN")) {
        if (!college.isEmpty() || !major.isEmpty() || !klass.isEmpty())
          throw new IllegalStateException("管理员不应归属组织：" + id);
        continue;
      }
      if (find(COLLEGES, college) == null)
        throw new IllegalStateException("账号的学院编号无效（疑似写成了专业编号）：" + id + " -> " + college);
      var majorRow = find(MAJORS, major);
      if (majorRow == null)
        throw new IllegalStateException("账号的专业编号无效：" + id + " -> " + major);
      if (!majorRow.parent().equals(college))
        throw new IllegalStateException("账号的专业不属于其学院：" + id);
      if (!klass.isEmpty()) {
        var klassRow = find(CLASSES, klass);
        if (klassRow == null) throw new IllegalStateException("账号的班级编号无效：" + id + " -> " + klass);
        if (!klassRow.parent().equals(major))
          throw new IllegalStateException("账号的班级不属于其专业：" + id);
      }
    }
  }

  /**
   * 成绩单自检：把「同一学生跨学期修读同一课程代码」的轨迹核一遍，写入后立刻失败，而不是等
   * 选课规则把「已通过不得重选」误判成正常数据。
   *
   * <p>三条硬性约束（不满足直接抛异常中止启动）：
   *
   * <ol>
   *   <li>同一课程代码最多出现两次——挂科学期与重修学期各一次，中间不再出现；出现第三次或出现
   *       未登记在 {@link #RETAKES} 里的跨学期重复，说明有人写出了「已通过又重选」的脏数据；
   *   <li>前一次必须是<b>已提交的挂科</b>（有效分 &lt; 60），后一次若已提交成绩必须<b>通过</b>
   *       （有效分 ≥ 60）；当前学期未提交成绩视为「正在重修」；
   *   <li>至少 {@link #MIN_RETAKERS} 名学生具备这样的重修轨迹，避免以后改数据把样本删没。
   * </ol>
   *
   * <p>有效分的口径与业务侧一致：按课程权重算加权总评，补考按 60 分封顶后取较高者。
   */
  private void verifyTranscriptIntegrity() {
    Map<String, Course> courses = coursesById();
    Map<String, GradeRow> grades = loadGrades();
    Map<String, Double> effective = new HashMap<>();
    for (var entry : grades.entrySet()) {
      if (!"SUBMITTED".equals(entry.getValue().state())) continue;
      Double value = effective(courses.get(courseIdOf(entry.getKey())), entry.getValue().scores());
      if (value != null) effective.put(entry.getKey(), value);
    }
    // 学生 -> 课程代码 -> 该代码的全部修读记录（含未提交成绩的重修）。
    Map<String, Map<String, List<Attempt>>> history = new LinkedHashMap<>();
    for (var row : jdbc.queryForList("SELECT course_id,student_id,status FROM enrollments")) {
      if (!"ACTIVE".equals(column(row, "status"))) continue;
      String courseId = column(row, "course_id");
      Course course = courses.get(courseId);
      if (course == null) continue; // 运行时新建的教学班不在演示数据范围内
      String studentId = column(row, "student_id");
      history
          .computeIfAbsent(studentId, key -> new LinkedHashMap<>())
          .computeIfAbsent(course.code(), key -> new ArrayList<>())
          .add(new Attempt(courseId, course.term(), effective.get(courseId + "|" + studentId)));
    }
    Set<String> retakers = new LinkedHashSet<>();
    for (var byStudent : history.entrySet())
      for (var byCode : byStudent.getValue().entrySet()) {
        var attempts = new ArrayList<>(byCode.getValue());
        if (attempts.size() < 2) continue; // 只修读过一次，必然不构成「已通过重选」
        attempts.sort(Comparator.comparing(Attempt::term));
        String who = byStudent.getKey() + " / " + byCode.getKey();
        if (attempts.size() != 2)
          throw new IllegalStateException(
              "同一课程代码出现了 " + attempts.size() + " 次修读（应为挂科与重修各一次）：" + who);
        Retake designed = findRetake(byStudent.getKey(), byCode.getKey());
        if (designed == null)
          throw new IllegalStateException("出现未登记的重修轨迹（已通过不得重选）：" + who);
        if (!designed.failedCourse().equals(attempts.get(0).courseId())
            || !designed.retakeCourse().equals(attempts.get(1).courseId()))
          throw new IllegalStateException(
              "重修轨迹与 RETAKES 清单不一致：" + who + " -> " + attempts);
        if (attempts.get(0).effective() == null || attempts.get(0).effective() >= PASS_SCORE)
          throw new IllegalStateException(
              "重修前的成绩必须是已提交的挂科（有效分 < " + (int) PASS_SCORE + "）：" + who);
        if (attempts.get(1).effective() != null && attempts.get(1).effective() < PASS_SCORE)
          throw new IllegalStateException("重修学期已提交的成绩必须通过：" + who);
        retakers.add(byStudent.getKey());
      }
    if (retakers.size() < MIN_RETAKERS)
      throw new IllegalStateException(
          "重修样本不足：至少需要 "
              + MIN_RETAKERS
              + " 名学生具备「挂科 → 后续学期同一代码重修」的轨迹，当前只有 "
              + retakers.size()
              + " 名");
    // 反向核对：清单里的每条轨迹都必须真的落库，避免清单与数据脱节。
    for (Retake r : RETAKES)
      if (!retakers.contains(r.student()))
        throw new IllegalStateException("重修清单里的样本没有落库：" + r.student() + " / " + r.code());
    System.out.println(
        "[DemoInitializer] 成绩单自检通过："
            + retakers.size()
            + " 名重修学生（"
            + RETAKES.stream().map(Retake::code).distinct().count()
            + " 个课程代码）、"
            + history.size()
            + " 名学生，无「已通过重选」的跨学期重复课程代码。");
  }

  /** 一条修读记录：教学班编号、学期与有效分（成绩未提交时为 null）。 */
  private record Attempt(String courseId, String term, Double effective) {}

  private static Retake findRetake(String studentId, String code) {
    for (Retake r : RETAKES) if (r.student().equals(studentId) && r.code().equals(code)) return r;
    return null;
  }

  /**
   * 学业预警覆盖自检：{@code AnalyticsService.predict} 取「同一课程代码在<b>更早学期</b>」的已提交
   * 成绩做训练集（要求 ≥{@link #MIN_PREDICTION_YEARS} 个不同年份、≥{@link #MIN_PREDICTION_SAMPLES} 条
   * 同时含 {@code regular}/{@code lab}/{@code finalExam} 的成绩），并只对「有平时与实验、缺期末」
   * 的成绩行给出预测结果。
   *
   * <p>这里对<b>每一门有 ACTIVE 选课的课程</b>（学生端「我的课程」与教师端名单里能点开的课）
   * 都按同一口径核一遍：更早年份数、样本条数、本班缺期末人数三者缺一不可，不满足就中止启动，
   * 覆盖范围不会随数据改动悄悄退化。历史样本教学班（{@link #SAMPLE_COURSES}）没有选课记录，
   * 不属于「要预测的课程」，因此不会形成无限回归。
   */
  private void verifyPredictionCoverage() {
    Map<String, Course> courses = coursesById();
    Map<String, GradeRow> grades = loadGrades();
    // 需要支持预测的课程：有 ACTIVE 选课的**正课**。历史样本教学班（SAMPLE_COURSES）是训练数据
    // 本身，学期又在最早年份、没有任何更早学期可查，因此不参与这条覆盖要求。
    Set<String> targets = new LinkedHashSet<>();
    for (var row : jdbc.queryForList("SELECT course_id,status FROM enrollments")) {
      if (!"ACTIVE".equals(column(row, "status"))) continue;
      String courseId = column(row, "course_id");
      if (courses.containsKey(courseId) && !SAMPLE_COURSE_IDS.contains(courseId))
        targets.add(courseId);
    }
    var report = new StringBuilder();
    for (String id : targets) {
      Course target = courses.get(id);
      Set<String> years = new TreeSet<>();
      int samples = 0;
      int awaiting = 0;
      for (var entry : grades.entrySet()) {
        String courseId = courseIdOf(entry.getKey());
        Course course = courses.get(courseId);
        if (course == null) continue;
        var scores = entry.getValue().scores();
        if (courseId.equals(id)) {
          // 这门课里「期末未录入」的学生：接口的预测对象，一个都没有就会返回空结果。
          if (awaitingFinal(scores)) awaiting++;
          continue;
        }
        // 与 predict 一致：同一课程代码、学期严格更早、已提交、三分项齐全。
        if (course.code().equals(target.code())
            && course.term().compareTo(target.term()) < 0
            && "SUBMITTED".equals(entry.getValue().state())
            && complete(scores)) {
          years.add(course.term().substring(0, 4));
          samples++;
        }
      }
      String who = target.code() + "@" + target.term();
      if (years.size() < MIN_PREDICTION_YEARS
          || samples < MIN_PREDICTION_SAMPLES
          || awaiting < 1)
        throw new IllegalStateException(
            "学业预警覆盖不足："
                + who
                + " 需要至少 "
                + MIN_PREDICTION_YEARS
                + " 个更早年份、"
                + MIN_PREDICTION_SAMPLES
                + " 条含平时/实验/期末的历史成绩、1 名缺期末的学生，当前为 "
                + years.size()
                + " 年 / "
                + samples
                + " 条 / "
                + awaiting
                + " 人");
      report
          .append("\n[DemoInitializer]   ")
          .append(who)
          .append(" 更早年份=")
          .append(years.size())
          .append(" 样本=")
          .append(samples)
          .append(" 缺期末=")
          .append(awaiting);
    }
    System.out.println(
        "[DemoInitializer] 学业预警覆盖自检通过："
            + targets.size()
            + " 门有选课的正课全部可预测。"
            + report);
  }

  /** 一条成绩：解密后的分项表与状态。 */
  private record GradeRow(Map<String, Object> scores, String state) {}

  private static boolean complete(Map<String, Object> scores) {
    return scores.get("regular") != null
        && scores.get("lab") != null
        && scores.get("finalExam") != null;
  }

  /** 是否「期末还没录入」：学业预警要预测的正是这类成绩行。 */
  private static boolean awaitingFinal(Map<String, Object> scores) {
    return scores.get("regular") != null
        && scores.get("lab") != null
        && scores.get("finalExam") == null;
  }

  /** 成绩键 {@code 教学班编号|学号} 里的教学班编号。 */
  private static String courseIdOf(String key) {
    int split = key.indexOf('|');
    return split < 0 ? key : key.substring(0, split);
  }

  /** 教学班编号 -> 教学班，便于按成绩行与选课行反查课程代码与学期。 */
  private static Map<String, Course> coursesById() {
    Map<String, Course> courses = new LinkedHashMap<>();
    for (Course c : COURSES) courses.put(c.id(), c);
    for (Course c : SAMPLE_COURSES) courses.put(c.id(), c);
    return courses;
  }

  /**
   * 全部成绩的明文分项（含 DRAFT）：{@code 教学班编号|学号 -> 分项表 + 状态}。
   *
   * <p>成绩在库里是密文，这里按与写入时相同的 AAD 解密后再解析，等于顺带核了一遍密文完整性。
   */
  private Map<String, GradeRow> loadGrades() {
    Map<String, GradeRow> result = new LinkedHashMap<>();
    for (var row :
        jdbc.queryForList("SELECT id,course_id,student_id,payload,state,version FROM grades")) {
      String gradeId = column(row, "id");
      String courseId = column(row, "course_id");
      String studentId = column(row, "student_id");
      String state = column(row, "state");
      String version = column(row, "version");
      String aad = gradeId + "|" + courseId + "|" + studentId + "|" + state + "|" + version;
      result.put(
          courseId + "|" + studentId,
          new GradeRow(scores(Crypto.decrypt(KEY, aad, column(row, "payload"))), state));
    }
    return result;
  }

  /** 解析成绩明文载荷；损坏时直接失败，避免自检把坏数据当成「合法」。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> scores(String plain) {
    try {
      return Settings.JSON.readValue(plain, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("成绩载荷无法解析：" + plain);
    }
  }

  /**
   * 有效分：按课程权重算加权总评（缺少权重大于 0 的分项时视为未出分，返回 null），
   * 补考按「最高 60 分」封顶后与总评取较高者——与业务侧 GradeService 的口径一致。
   *
   * <p>演示数据的权重只用到平时分、实验分与期末分，其余分项权重均为 0，因此这里只取这三项。
   */
  private static Double effective(Course c, Map<String, Object> scores) {
    String[] keys = {"regular", "lab", "finalExam"};
    int[] weights = {c.regular(), c.lab(), c.finalExam()};
    double total = 0;
    for (int i = 0; i < keys.length; i++) {
      if (weights[i] <= 0) continue;
      Object value = scores.get(keys[i]);
      if (value == null) return null;
      total += Double.parseDouble(value.toString()) * weights[i] / 100.0;
    }
    Object makeup = scores.get("makeup");
    if (makeup == null) return total;
    return Math.max(total, Math.min(PASS_SCORE, Double.parseDouble(makeup.toString())));
  }

  /** 取值：H2 返回大写列名、MySQL 返回声明时的小写列名，两者都要兼容。 */
  private static String column(Map<String, Object> row, String name) {
    Object value = row.get(name.toUpperCase(Locale.ROOT));
    if (value == null) value = row.get(name);
    return value == null ? "" : value.toString();
  }

  private void seedOrganizations() {
    for (Org c : COLLEGES)
      jdbc.update(
          "INSERT INTO colleges(id,name,code,short_name,description,enabled,version) VALUES(?,?,?,?,?,?,?)",
          c.id(), c.name(), c.code(), c.extra(), c.name() + "，开设本学院各专业与教学班。", 1, 0);
    for (Org m : MAJORS) {
      if (find(COLLEGES, m.parent()) == null)
        throw new IllegalStateException("专业缺少所属学院：" + m.id());
      jdbc.update(
          "INSERT INTO majors(id,college_id,name,code,degree,years,enabled,version) VALUES(?,?,?,?,?,?,?,?)",
          m.id(), m.parent(), m.name(), m.code(), m.extra(), 4, 1, 0);
    }
    for (Org b : CLASSES) {
      var major = find(MAJORS, b.parent());
      if (major == null) throw new IllegalStateException("班级缺少所属专业：" + b.id());
      jdbc.update(
          "INSERT INTO classes(id,major_id,college_id,name,grade_year,code,enabled,version)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          b.id(), b.parent(), major.parent(), b.name(), b.extra(), b.code(), 1, 0);
    }
  }

  private static Org find(List<Org> list, String id) {
    for (Org o : list) if (o.id().equals(id)) return o;
    return null;
  }

  private void seedUsers() {
    String sql =
        "INSERT INTO users(id,username,password,name,role,permissions,department,"
            + "college_id,major_id,class_id,enabled,version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)";
    List<Object[]> rows = new ArrayList<>();
    for (Person p : PEOPLE) {
      String department = "";
      if (p.major() != null) {
        var major = find(MAJORS, p.major());
        String majorName = major == null ? "" : major.name();
        department =
            p.role().equals("STUDENT")
                ? majorName + "·" + classNameOf(p.klass())
                : collegeNameOf(p.college()) + "·" + majorName;
      }
      rows.add(
          new Object[] {
            p.id(), p.id(), PASSWORDS.encode(DEFAULT_PASSWORD), p.name(), p.role(), p.permissions(),
            department, blank(p.college()), blank(p.major()), blank(p.klass()),
            DISABLED_USERNAMES.contains(p.id()) ? 0 : 1, 0
          });
    }
    for (String[] s : STUDENTS) {
      String id = s[0], name = s[1], classId = s[2], majorId = s[3];
      // 学院一律从「专业 → 学院」反推：Org.parent() 指向上一级编号，
      // 班级的 parent 是专业、专业的 parent 才是学院，因此不能拿班级的 parent 当学院。
      var major = find(MAJORS, majorId);
      String collegeId = major == null ? "" : Objects.toString(major.parent(), "");
      String majorName = major == null ? "" : major.name();
      String department = majorName + (classId.isBlank() ? "" : "·" + classNameOf(classId));
      rows.add(
          new Object[] {
            id, id, PASSWORDS.encode(DEFAULT_PASSWORD), name, "STUDENT",
            "QUERY,PREDICT,SELECTION_ENROLL", department, collegeId, majorId, classId, 1, 0
          });
    }
    jdbc.batchUpdate(sql, rows);
  }

  /** 组织字段在库里用空串表示「不设置」；SqlCompiler 拒绝 null 值。 */
  private static String blank(String value) {
    return value == null ? "" : value;
  }

  private static String collegeNameOf(String collegeId) {
    var college = find(COLLEGES, collegeId);
    return college == null ? "" : college.name();
  }

  private static String classNameOf(String classId) {
    for (Org c : CLASSES) if (c.id().equals(classId)) return c.name();
    return "";
  }

  private void seedCourses() {
    String sql =
        "INSERT INTO courses(id,code,name,term,teacher_id,credits,weights,college_id,class_id,"
            + "status,version) VALUES(?,?,?,?,?,?,?,?,?,?,?)";
    List<Object[]> rows = new ArrayList<>();
    var all = new ArrayList<Course>(COURSES);
    all.addAll(SAMPLE_COURSES);
    for (Course c : all) {
      String weights =
          "{\"regular\":"
              + c.regular()
              + ",\"attendance\":0,\"homework\":0,\"lab\":"
              + c.lab()
              + ",\"midterm\":0,\"finalExam\":"
              + c.finalExam()
              + "}";
      rows.add(
          new Object[] {
            c.id(), c.code(), c.name(), c.term(), c.teacher(), c.credits(), weights,
            c.college(), c.klass(), "ACTIVE", 0
          });
    }
    jdbc.batchUpdate(sql, rows);
  }

  /** 选课时间戳：学期首月 1 日 08:00（ISO-8601），仅用于演示数据的稳定排序。 */
  private static String selectedAt(String term) {
    String year = term.substring(0, 4);
    String half = term.substring(term.indexOf('-') + 1);
    String month = half.equals("1") ? "09" : "02";
    return year + "-" + month + "-01T08:00:00Z";
  }

  /**
   * 写入选课与成绩。
   *
   * <p>普通教学班按「面向班级」整班铺开；重修学生不一样：他们不在重修班的面向班级里，若为了
   * 个别人改动 {@code Course.klass} 会把整班都算成重修，因此这里按 {@link #RETAKES} <b>显式补录</b>
   * 挂科教学班与重修教学班各一条选课记录（{@link #RETAKE_ONLY_COURSES} 里的班级干脆不做整班铺开）。
   *
   * <p>此外为每门<b>已提交成绩</b>的正课补一名 {@link #PARTIAL_POOL_CLASS} 的「缓考」学生：
   * 他只有平时与实验成绩、没有期末成绩，是学业预警在这门课上的预测对象；否则接口虽然成功，
   * 但 {@code results} 会是空的。历史样本教学班（{@link #SAMPLE_COURSES}）与本池学生照常整班铺开，
   * 做到「有成绩的人恰好就是选了这门课的人」。
   */
  private void seedEnrollmentsAndGrades() {
    String enrollSql =
        "INSERT INTO enrollments(id,course_id,student_id,source,publish_id,selected_at,status)"
            + " VALUES(?,?,?,?,?,?,?)";
    String gradeSql =
        "INSERT INTO grades(id,course_id,student_id,payload,state,version) VALUES(?,?,?,?,?,?)";
    Map<String, List<String>> classStudents = new HashMap<>();
    for (String[] s : STUDENTS)
      // 待分班学生（班级为空）不参与任何教学班，只在组织管理里演示「批量调入学生」。
      if (!s[2].isBlank()) classStudents.computeIfAbsent(s[2], k -> new ArrayList<>()).add(s[0]);
    Set<String> retakePairs = retakePairs();
    var all = new ArrayList<Course>(COURSES);
    all.addAll(SAMPLE_COURSES);

    List<Object[]> enrollments = new ArrayList<>();
    int[] seq = {0};
    for (Course c : all) {
      List<String> roster =
          RETAKE_ONLY_COURSES.contains(c.id())
              ? List.of()
              : classStudents.getOrDefault(c.klass(), List.of());
      for (String sid : roster)
        // 重修学生由下面统一显式补录，避免同一教学班出现两条选课记录。
        if (!retakePairs.contains(c.id() + "|" + sid))
          insertEnrollment(gradeSql, enrollments, seq, c, sid);
      for (Retake r : RETAKES)
        if (r.failedCourse().equals(c.id()) || r.retakeCourse().equals(c.id()))
          insertEnrollment(gradeSql, enrollments, seq, c, r.student());
    }
    seedPartialStudents(gradeSql, enrollments, seq);
    jdbc.batchUpdate(enrollSql, enrollments);
  }

  /**
   * 缓考成绩：为每门已提交成绩的正课补一名「有平时与实验、缺期末」的样本学生。
   *
   * <p>缓考学生出自 {@link #PARTIAL_POOL_CLASS}，该班级不被任何正课引用，因此不会自动进入正课名单；
   * 这里逐个显式补选课记录，名单与成绩保持一致。同一名缓考学生只承担不同课程代码的课，
   * 避免出现新的「同一课程代码两段修读」。
   */
  private void seedPartialStudents(String gradeSql, List<Object[]> enrollments, int[] seq) {
    List<String> pool = studentsOfClass(PARTIAL_POOL_CLASS);
    Map<String, Set<String>> codesTaken = new HashMap<>();
    int cursor = 0;
    for (Course c : COURSES) {
      if (!c.submitted()) continue; // 未提交成绩的当前学期课已经由 insertEnrollment 写暂存成绩
      String chosen = null;
      for (int i = 0; i < pool.size(); i++) {
        String candidate = pool.get(cursor);
        cursor = (cursor + 1) % pool.size();
        if (codesTaken.computeIfAbsent(candidate, key -> new HashSet<>()).add(c.code())) {
          chosen = candidate;
          break;
        }
      }
      if (chosen == null)
        throw new IllegalStateException("缓考样本池不足以覆盖课程：" + c.id() + "（" + c.code() + "）");
      seq[0]++;
      enrollments.add(
          new Object[] {
            c.id() + "-e" + seq[0], c.id(), chosen, "SEED", null, selectedAt(c.term()), "ACTIVE"
          });
      insertPartialGrade(gradeSql, c, chosen, "d" + seq[0]);
    }
  }

  /**
   * 历史样本成绩：三分项齐全、分数有高有低（用独立的固定 {@link #SAMPLE_RNG}，与正课成绩互不影响）。
   * 历史样本教学班通过 {@link #insertEnrollment} 写入，因此成绩与选课记录天然一一对应。
   */
  private static String samplePayload(Course c) {
    double regular = 45 + SAMPLE_RNG.nextInt(51);
    double lab = 45 + SAMPLE_RNG.nextInt(51);
    double exam = Math.min(100, Math.max(30, .7 * regular + 12 + SAMPLE_RNG.nextGaussian() * 10));
    var parts = new ArrayList<String>();
    if (c.regular() > 0) parts.add("\"regular\":" + round(regular));
    if (c.lab() > 0) parts.add("\"lab\":" + round(lab));
    parts.add("\"finalExam\":" + round(exam));
    return "{" + String.join(",", parts) + "}";
  }

  /** 全部「教学班 + 学生」的重修选课对：挂科教学班与重修教学班各一条。 */
  private static Set<String> retakePairs() {
    Set<String> pairs = new LinkedHashSet<>();
    for (Retake r : RETAKES) {
      pairs.add(r.failedCourse() + "|" + r.student());
      pairs.add(r.retakeCourse() + "|" + r.student());
    }
    return pairs;
  }

  /** 追加一条 ACTIVE 选课记录；课程已提交成绩时同时写入一条加密成绩。 */
  private void insertEnrollment(
      String gradeSql, List<Object[]> enrollments, int[] seq, Course c, String studentId) {
    seq[0]++;
    enrollments.add(
        new Object[] {
          c.id() + "-e" + seq[0], c.id(), studentId, "SEED", null, selectedAt(c.term()), "ACTIVE"
        });
    String payload = SAMPLE_COURSE_IDS.contains(c.id()) ? samplePayload(c) : payload(c, studentId);
    if (payload == null) {
      // 当前学期：写入一条**暂存**成绩（只有平时与实验，期末未录入）。
      // 学业预警要预测的正是「期末还没考」的学生，没有这条暂存成绩就一个预测对象都筛不出来；
      // 状态是 DRAFT，因此不会被当成已通过/已挂科，也不会进入重修判定与学业记录。
      insertPartialGrade(gradeSql, c, studentId, "d" + seq[0]);
      return;
    }
    String gradeId = c.id() + "-g" + seq[0];
    String aad = gradeId + "|" + c.id() + "|" + studentId + "|SUBMITTED|0";
    jdbc.update(
        gradeSql, gradeId, c.id(), studentId, Crypto.encrypt(KEY, aad, payload), "SUBMITTED", 0);
  }

  /** 写入一条 DRAFT 的「暂存」成绩：只有平时与实验、没有期末，即学业预警的预测对象。 */
  private void insertPartialGrade(String gradeSql, Course c, String studentId, String suffix) {
    String gradeId = c.id() + "-" + suffix;
    String aad = gradeId + "|" + c.id() + "|" + studentId + "|DRAFT|0";
    jdbc.update(
        gradeSql,
        gradeId,
        c.id(),
        studentId,
        Crypto.encrypt(KEY, aad, partialPayload()),
        "DRAFT",
        0);
  }

  /**
   * 「暂存」成绩载荷：平时与实验已录入、期末未录入，用于学业预警的预测对象。
   *
   * <p>不按课程权重裁剪：预测模型的两路输入都需要平时分与实验分（总评是否用到实验分由课程自身的
   * 权重决定），因此即使课程没有实验学分项也照样给出实验分，保证每门课都有可预测的学生。
   * 使用独立的 {@link #SAMPLE_RNG}，与正课拟合成绩互不影响。
   */
  private static String partialPayload() {
    double regular = 54 + SAMPLE_RNG.nextInt(44);
    double lab = 50 + SAMPLE_RNG.nextInt(48);
    return "{\"regular\":" + round(regular) + ",\"lab\":" + round(lab) + "}";
  }

  /**
   * 生成一条成绩的明文载荷。{@code null} 表示该课程成绩尚未提交。
   *
   * <p>重修学生的成绩由 {@link #RETAKES} 决定，与其他学生用同一套拟合逻辑区分开：
   *
   * <ul>
   *   <li>挂科学期：正考（加权总评）不及格，补考同样不及格，有效分 52（{@link #FAILED_PAYLOAD}）；
   *   <li>已结束的重修学期：通过，补考 87 分按「最高 60 分」封顶得到有效分 60
   *       （{@link #RETAKE_PASSED_PAYLOAD}）；
   *   <li>当前学期（2026-1，{@code submitted=false}）：不写成绩，留给教师现场录入。
   * </ul>
   */
  private static String payload(Course c, String studentId) {
    if (!c.submitted()) return null;
    for (Retake r : RETAKES) {
      if (!r.student().equals(studentId) || !r.code().equals(c.code())) continue;
      if (r.failedCourse().equals(c.id())) return FAILED_PAYLOAD;
      if (r.retakeCourse().equals(c.id())) return RETAKE_PASSED_PAYLOAD;
    }
    double regular = 54 + RNG.nextInt(44);
    double exam = Math.min(100, Math.max(32, .55 * regular + 42 + RNG.nextGaussian() * 7));
    var parts = new ArrayList<String>();
    if (c.regular() > 0) parts.add("\"regular\":" + round(regular));
    if (c.lab() > 0) parts.add("\"lab\":" + round(50 + RNG.nextInt(48)));
    parts.add("\"finalExam\":" + round(exam));
    return "{" + String.join(",", parts) + "}";
  }

  private static double round(double value) {
    return Math.round(value * 100) / 100.0;
  }

  /**
   * 预置一个「进行中」的选课批次，覆盖 2026-1 的教学班，便于登录后直接演示学生选课、
   * 教务按课程批量选课、最低开课人数结算等流程。
   *
   * <p>**只接收重修学生的教学班（{@link #RETAKE_ONLY_COURSES}）不放进这个批次**：
   * 它们的名单来自教务按 {@link #RETAKES} 的显式补录，本身只有两三个人，如果进了演示批次，
   * 一旦执行「最低开课人数结算」就会被判为人数不足而取消，反而把重修样本冲掉。
   */
  private void seedCourseSelection() {
    var current = new ArrayList<String>();
    for (Course c : COURSES)
      if (CURRENT_TERM.equals(c.term()) && !RETAKE_ONLY_COURSES.contains(c.id())) current.add(c.id());
    if (current.isEmpty()) return;
    String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    String start = LocalDateTime.now().minusDays(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    String end = LocalDateTime.now().plusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
    jdbc.update(
        "INSERT INTO course_selections(id,name,term,course_ids,scope_college_ids,scope_major_ids,"
            + "scope_class_ids,start_time,end_time,min_enroll,max_credits,allow_add,allow_drop,"
            + "allow_retake,status,published_by,published_at,note,version)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        "sel-2026-1-demo",
        "2026-1 学期网上选课",
        "2026-1",
        String.join(",", current),
        "",
        "",
        "",
        start,
        end,
        3,
        30,
        1,
        1,
        1,
        "OPEN",
        "jw001",
        now,
        "演示批次：范围为全校，最低开课人数 3，允许选课、退课与挂科重修。",
        0);
  }

  // ------------------------------------------------------------------ 账本

  private void seedAuditLedger() throws Exception {
    List<String> courseIds = jdbc.queryForList("SELECT id FROM courses ORDER BY id", String.class);
    List<String> gradeIds = jdbc.queryForList("SELECT id FROM grades ORDER BY id", String.class);
    if (gradeIds.isEmpty()) {
      System.out.println("[DemoInitializer] 没有成绩需要写入账本。");
      return;
    }
    List<Protocol.AuditEvent> events = new ArrayList<>();
    for (String gid : gradeIds) {
      Map<String, Object> row = jdbc.queryForMap("SELECT * FROM grades WHERE id=?", gid);
      String courseId = Objects.toString(row.get("course_id"), "");
      String studentId = Objects.toString(row.get("student_id"), "");
      String state = Objects.toString(row.get("state"), "");
      int version = row.get("version") == null ? 0 : ((Number) row.get("version")).intValue();
      // 演示种子事件与运行时路径同构：payload 以密文进入账本，明文只在审计服务读取时还原。
      String eventId = UUID.randomUUID().toString();
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("id", gid);
      after.put("course_id", courseId);
      after.put("student_id", studentId);
      after.put("state", state);
      after.put("version", version);
      after.put(
          "payload",
          TransactionService.sealSnapshot(
              eventId,
              "after",
              "payload",
              Crypto.decrypt(
                  KEY,
                  gid + "|" + courseId + "|" + studentId + "|" + state + "|" + version,
                  Objects.toString(row.get("payload"), ""))));
      events.add(
          new Protocol.AuditEvent(
              eventId, "DEMO_SEED", "GRADE_SEED", courseId, Instant.now().toString(),
              List.of(Map.of("table", "grades", "id", gid, "before", Map.of(), "after", after))));
    }
    audit.post("audit", "/internal/bootstrap-anchored", events, Map.class);
    System.out.println(
        "[DemoInitializer] 账本已按新数据库重建并锚定 "
            + events.size()
            + " 条成绩事件（课程 "
            + courseIds.size()
            + " 门）。");
  }
}
