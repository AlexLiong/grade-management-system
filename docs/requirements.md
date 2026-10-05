# 需求分析与实现追踪

## 原始需求保留

`requirements/original-requirements.pdf` 为提供的 22 页原文；`original-requirements.md` 按 PDF 物理页码提取；`original-prompt.md` 保留补充要求。参考架构图应查看 PDF 第 4 页，文字提取不替代原图。用户明确暂不要求答辩材料，因此不生成 PPT 和虚构个人报告。

## 需求解释

1. 百分制为唯一成绩类别，六项组成可设为 0%，总和必须 100%。正考总评保留两位小数，不提前四舍五入到整数。
2. 正考不足 60 分才可录入补考。保存原卷面分，学生展示 `58 / 60`；补考 57 展示 `58 / 57`。最终有效分取正考与封顶后补考的较大值，排名按该值计算。同分采用竞赛排名 1、1、3。这个“最终有效分”取值是项目明确补充的教务规则，学校采用其他规则时修改 `Models.effective` 并更新测试。
3. 以整门教学班为提交/撤销单位，避免半个班级成绩公开。提交前需全部选课学生有完整的带权成绩；学生只能看已提交记录。教师可以撤回自己提交的课程成绩。
4. 小撤销保留成绩和版本，回到暂存；大撤销删除业务成绩行，独立审计保留前后快照。大撤销需输入课程编号与原因。
5. 人员维护采用启用/停用而非删除历史主体；已有角色不直接转换，以保持成绩、授课、审计引用稳定。
6. PDF 对预测同时提出“仅本人和教师可见”和“教师或管理员点击运行”。发生冲突时以隐私约束优先：管理员可查看统计/风险审计，无权获取个人预测分；本人或授课教师主动点击生成。
7. PDF 要求根据数值类型决定 SQL 引号。本工程使用 JDBC 参数绑定实现同等类型语义，不直接把值拼接到 SQL。结构对象只接受白名单表和列，浏览器不携带任意 SQL。
8. 预测收集至少三年同课程已提交历史数据，至少 24 个完整样本。样本不足返回 422，不以假预测填补。演示数据的历史样本由 `DemoInitializer` 铺开并在启动时自检（`verifyPredictionCoverage()`，只校验正课）；当前为 91 个历史样本教学班 × 8 人 = 728 条样本成绩。
9. OCR/语音为二选一，选择浏览器本地 OCR，原图不上传服务器；数字和 ASCII 学号使用英文模型。中文姓名不作为自动匹配依据。识别文本必须人工核对再暂存。（本轮落地情况：OCR 与语音**都已实现**，但"本地化"这一取舍没有变——语音通道仍由浏览器厂商的 `SpeechRecognition` 承担音频处理，必须逐次点击「开始识别」才开麦（面板里原先那条固定隐私提示已按用户要求删除）；识别与解析本身都不上行数据，见第 32–37 条。）

## 新增需求解释（组织管理与网上选课）

本节记录本轮新增的「学院—专业—班级三级组织管理」与「网上选课系统」的需求解释。组织与选课由教务管理员统一维护，学生只在已发布且处于选课窗口内的批次里自助选退课。

10. 组织采用三级模型与可读编号：学院 `C01001`、专业 `M01001`、班级 `B01001`。编号固定 6 位，由「1 位层前缀 + 2 位号段 + 3 位本级序号」组成（`Models.codePattern` 约束为单字母前缀 + 5 位数字）。学院的 2 位号段是该学院自己的递增序号（取现有学院第 2–3 位的最大值 +1），本级序号固定从 `001` 开始，因此新建学院不会与已有学院撞号，也不会占用已被下级号段使用的两位序号；专业与班级的 2 位号段**沿用上级编号的号段**，本级序号在同一个号段内递增。库中以该编号作主键（业务主键）；编号由「取现存编号的序号最大值 +1」生成，因此不会与现存对象撞号，但它不是数据库自增序列：删除序号最大的对象后，该编号会在下次新建时被再次分配，所以不能假设编号永不复用（历史引用以审计快照为准）。前端一律按名称操作：请求体中的组织字段既接受编号也接受名称，由 `OrganizationService.resolveOwn` 归一化成编号，响应再通过 `OrganizationService.names` 附加 `collegeName`/`majorName`/`className`。班级名称采用规范形式「年级-专业-班号」，例如 `2023级-软件工程-2301班`、`2024级-会计学-2401班`，由 `OrganizationService.className(gradeYear, majorName, number)` 生成；名称本身已是完整显示名，因此 `OrganizationService.displayName` 直接返回 `name`（不再拼接年级前缀）。
11. 组织维护用启用/停用而不是删除来表示“不再招生”。删除前先由 `OrganizationService.impact` 给出下级、人员与课程的影响面；仍有下级时必须 `force=true` 二次确认，仍有课程或仍被账号引用时**始终拒绝**（409），避免产生悬挂引用并保持成绩与审计引用稳定。
12. 人员的组织归属按角色分级要求：`TEACHER` 必须有学院与专业；`STUDENT` 必须有学院、专业与班级；`ADMIN` **不归属任何组织**（第二轮修正，见第 19 条）。三级之间必须一致（专业属于该学院、班级属于该专业与学院）。`department` 只是展示用派生字段，不是第二份真相。
13. 选课以「批次」为单位发布：一个批次对应 `course_selections` 一行，包含开放课程集合、可选范围（学院/专业/班级）、起止时间、最低开课人数、学分上限，以及 `allow_add`/`allow_drop`/`allow_retake` 三个开关。批次保存后即为 `OPEN`（**没有草稿态**），关闭与结算都落到 `CLOSED`，整批作废落到 `CANCELLED`，即 `Models.SELECTION_STATUS` 的全部取值。
14. 选课规则（全部在服务端校验，前端只做提示）：已有教师录入成绩的课程不允许发布选课；**同一学期允许开设多个使用相同课程代码的教学班**（不同教师分别开课），但同一学生不得选择不同教师的同一课程代码，即「重复修读」由选课环节拦截而不是由教务开课环节禁止；已通过的课程不允许重选；挂科重修需批次开启 `allow_retake`；累计学分不得超过批次 `max_credits`；选课人数低于 `min_enroll` 的课程在结算时自动退回。
15. 选课证据分两层：`enrollments` 保存「当前关系」（新增 `source`/`publish_id`/`selected_at`/`status` 四个字段），`enrollment_records` 保存「动作流水」（`SELECT`/`DROP`/`ADMIN_ASSIGN`/`ADMIN_REMOVE`/`AUTO_REFUND`，含原因与操作人）。所有写入走统一事务，因此同时进入独立审计账本。
16. 数据库初始化改为「结构版本驱动」：`SchemaCatalog.SCHEMA_VERSION` 与库中 `schema_meta` 记录不一致时整库重建，再由 `DemoInitializer` 灌入演示数据（第六轮起为 4 学院 / 8 专业 / 21 班级 / 205 账号 / 155 教学班 / 1354 条选课 / 1354 条成绩，见第 24 条）。选择整库重建而非增量迁移，是因为旧库的 H2 命名约束无法用补列修复，且演示数据本身就是合成数据。

## 第二轮需求解释（小问题修正）

本节记录第二轮修改的 8 条需求（前端 4 条 + 后端 4 条）与随之重构的初始化数据。本轮不新增角色与页面级功能，全部是对既有组织管理与选课系统的修正与入口整理；编号 17–24 继续沿用上节序号。

17. **组织编号就是主键 `id`**：学院 `C01001`、专业 `M01001`、班级 `B01001` 这种 6 位编号既是库内引用的主键，也是页面上展示与人工沟通时使用的编号，不再有第二套「显示编号」。新建时由 `OrganizationService.nextId` 自动生成，规则未变：学院取现有学院第 2–3 位号段的最大值 +1、本级序号从 `001` 开始；专业与班级沿用上级编号的号段，本级序号在同一号段内递增。编号一经生成不可修改：`POST /organizations/save` 的修改分支只用 `id` 定位对象，请求体里传入的 `code` 或伪造的 `id` 都不会改写主键，响应固定为 `{ok: true, id}`。前端组织管理表单因此**没有「编号」输入框**（新增时不填、编辑时也不显示），列表改为在名称下方展示主键编号。

    |编号|格式|作用|是否可改|
    |---|---|---|---|
    |主键 `id`|`C`/`M`/`B` + 2 位号段 + 3 位本级序号（`C01001`、`M01001`、`B01001`）|库内引用、审计快照、成绩与选课关系的引用键、页面展示与人工沟通|否（只在新建时由后端生成）|

    第三轮去掉了第二轮的「两位显示编号 `code`」：主键本身已经可读，再维护一层编号要多占前端一个只读字段、后端一套生成与复核逻辑，而两套编号的语义差别（同层唯一 vs 跨层前缀、两位序号 vs 号段加本级序号）反而增加了沟通成本。`colleges`/`majors`/`classes` 表里遗留的 `code` 列**没有删除**（避免再触发一次整库重建），但组织接口不再读写它：`/organizations` 与 `/organizations/options` 的响应里没有 `code` 键，`OrganizationService` 也不再提供 `nextCode`/`maxCode`/`codeOf`。

18. **班级去掉「辅导员」**：`classes` 不再有 `counselor` 列，`SchemaCatalog.SCHEMA_VERSION` 因此升到 `3`（结构版本不一致会触发整库重建）。班级表列为 `id, major_id, college_id, name, grade_year, code, enabled, version`（其中 `code` 是第三轮起不再被接口读写的遗留列，见第 17 条）；`OrganizeRoutes.save` 不再写入 `counselor`，`/organizations` 的班级项在返回前显式 `remove("counselor")`（兼容旧数据残留），`/organizations/options` 的 `classes` 也不输出它，前端组织管理页同步删除列与表单输入。
19. **管理员不归属任何组织**：管理员是全局教务角色，不属于某一个学院、专业或班级。`AdminService.saveUser` 对 `role=ADMIN` 的请求若带了任何一级组织一律 400「管理员不归属学院、专业或班级」，通过校验后三级字段一律写空串（库中用空串表示「不设置」）；`OrganizeRoutes.assign` 把管理员列进学生或教师名单时 400「管理员不归属学院/专业/班级」；种子数据里 `admin`/`jw001`/`jw002` 的三级组织字段都为空。因此 `GET /me` 对管理员返回的 `collegeName`/`majorName`/`className` 均为 `null`，前端身份行必须做空值过滤，只显示「管理员」而不是「管理员 · null」。
20. **选课入口合并**：管理员的「网上选课」与「课程与选课」合并为一个「选课管理」页面，页内分「课程与选课 / 选课批次 / 选课记录」三个标签页；管理员侧栏因此只保留一个选课入口。原先独立的「批量选课」界面（按发布批次 + 班级）**移除**，避免与课程界面的入口功能重复；`POST /selections/batch` 接口保留，作为批次维度的既有批量能力。学生视角不变，仍只有一个「网上选课」选课台。
21. **按课程批量选课并入每门课程的「选课」按钮弹窗**：新增 `POST /enrollments/batch`（`GRADE_ADMIN`），请求体 `{courseId, studentIds?, classId?/className?, remove?, publishId?}`，`studentIds` 与班级至少要有一个，两者可同时给出并取**并集去重**；`remove=true` 表示批量退课。返回 `{ok, added, skipped, removed, failed[], classStudents, total}`：已经选过的计入 `skipped`（不报错），个别学生不满足规则（账号停用、非学生、该课程已有成绩不能退课）只进入 `failed` 而不中断整批。**每处理一名学生都写一条 `enrollment_records`**（选课 `ADMIN_ASSIGN`、退课 `ADMIN_REMOVE`，含课程代码、学期、原因与操作人），并与 `enrollments` 关系行在**同一次** `RemoteRepository.mutate`（审计 action `ENROLLMENT_BATCH`）中提交，要么全成要么全滚；既有单人 `POST /enrollments` 也补写同两类流水，使「教务代选代退也应出现在选课记录里」对单人与批量都成立。
22. **前端导航与身份行**：管理员的左侧导航顺序固定为「课程成绩、统计分析、人员与权限、组织管理、选课管理、安全审计」——「安全审计」必须是最后一项；学生/教师看不到该项，顺序不受影响。左下角身份行由「姓名 + 角色」改为「角色 · 学院 · 专业 · 班级」并对空值逐项过滤（教师只有前两项、管理员只有角色）。因为文字变长，侧栏宽度改为**鼠标拖动调节**：侧栏右边缘是拖拽手柄，宽度范围 68–420px、默认 216px；拖到 118px 以下进入「仅图标档」（隐藏文字，导航按钮保留 `title`/`aria-label`），118–167px 为「紧凑档」（仍显示文字、收紧内边距）；宽度写入 `localStorage`（`campus.sidebarWidth`）在刷新后保持，双击手柄恢复默认并清除持久化值。小屏（≤760px）侧栏本来就是横向导航条，因此隐藏手柄。
23. **去掉硬编码学院名**：顶栏由「信息工程学院 / 页面名」改为只显示页面名，页脚由「知序 · 信息工程学院」改为「知序 · 高校成绩管理」，登录页标语也去掉具体学院名。学院信息一律来自后端组织数据（`/me`、`/organizations/options`），前端不再出现任何写死的学院名称。
24. **初始化数据规模与启动兜底**：`DemoInitializer` 重建为 **4 个学院**（`C01001` 信息工程学院、`C02001` 经济与管理学院、`C03001` 建筑工程学院、`C04001` 外国语学院）、**8 个专业**、**21 个班级**、**205 个账号**（3 名教务管理员 + 15 名教师 = 11 名演示教师 + 4 名不可登录的史料教师 + 187 名学生 = 144 名正课学生 + 40 名历史样本学生 + 3 名待分班）、**155 个教学班**（64 个正课 + 91 个历史样本教学班，2020-1 至 2026-1）、**1354 条选课**与 **1354 条成绩**。其中 2025-1 有两门课程代码同为 `CS401`、教师不同的教学班（`c19-cs401a` 由陈老师、`c20-cs401b` 由李老师开设），用于验证「不得选不同教师的同一课程代码」；重修的样本见第 25–27 条。另预置一个 2026-1 的进行中批次 `sel-2026-1-demo`（覆盖该学期全部教学班、范围为全校、最低开课人数 3、允许选课/退课/重修）。每次启动**先**执行 `DemoInitializer.purgeTestArtifacts()`：按「名称含『测试』或『低人数』」＋「学期/批次不在演示数据声明的集合内」清除端到端脚本残留的测试教学班与测试批次（级联清理选课、成绩、选课流水与分析），因为系统没有课程/批次的删除接口；灌数结束后依次执行 `verifyOrganizationIntegrity()`（三级归属与「管理员无组织」）、`verifyTranscriptIntegrity()`（重修轨迹）、`verifyPredictionCoverage()`（64 门正课的学业预警覆盖），任一不满足直接抛异常中止启动。

## 第四轮需求解释（重修语义）

本节记录第四轮对「重修」的澄清与实现口径。前三轮把它当成「同一学期同代码的另一个教学班」来验证，本轮明确：重修是**同一课程号在后续学年重新开设**，课程名本身与重修无关。

25. **重修的定义与展示口径**：某学生修读某课程号后挂科（正考与补考都不及格、有效分 < 60），需要在**后续学年**选择**同一课程号**的教学班重修（判定只要求「挂科学期早于本学期」，不要求紧邻下一个学年——演示数据里就有 2024-1 挂科、2026-1 重修的学生）。在系统里这两次修读是**同一门课**（同一 `code`，不同 `term` 的教学班），课程名称完全一致、不带任何重修后缀——例如 `CS102`「程序设计基础」在 2023-1 与 2024-1 各有一个教学班，学分与权重相同，只有开设学期不同；只有「这个学生这次是在重修」这一**状态**被单独标记。因此课程维护、成绩单、选课名单里的课程名都保持原样，前端用「重修」徽标表达状态。
26. **重修状态的下发字段**（口径一致：判定只来自历史成绩，课程实体上没有任何重修字段）：

    |端|接口|字段|
    |---|---|---|
    |学生|`GET /selections/my`|每条记录 `retake`(boolean)、`retakeLabel`（`"重修"` 或 `null`）|
    |学生|`GET /selections/available`|每门可选课程 `retake`、`retakeLabel`|
    |教师|`GET /roster?courseId=`|每条名单记录 `retake`、`retakeLabel`|
    |教务|`GET /selections`|批次课程列表里每门课程 `retakeCount`(int)|

    判定依据：该生在该课程号的**更早学期**存在一条成绩**已提交**（`state = SUBMITTED`）且**有效分 < 60** 的修读记录 → 本学期这条就是重修。成绩未出或未提交（正在修读）、以及已退课的记录都不算，避免把「第一次修读」误判成重修；判定由 `SelectionService.failedCodes`（学生端/教务端）与 `CourseService.failedCodesBefore`（教师端名单）完成，批量场景复用一次读入的成绩索引。
27. **「已通过不得重选」的重申**：同一课程号若在更早学期已经**通过**（成绩已提交且有效分 ≥ 60），该生此后任何学期再选同一课程号一律拒绝 409「该课程此前已通过，不能重复修读」；本学期已经选了同一课程号的另一个教学班同样拒绝 409「本学期已选择同一课程代码的其他教学班」（唯一例外是「该生此前挂科且本次批次 `allow_retake=1`」）。挂科重修必须由批次开启 `allow_retake`，否则 403「本次选课不允许重修」。三条规则的实现位置与文案见 [安全文档](security.md) 与 [API 文档](api.md)。

## 第五轮需求解释（学业记录重修状态 + 学业预警可用）

本节记录第五轮针对两条用户反馈的修正：学生端「我的成绩」看不到重修状态、学业预警始终提示「数据不足」。

28. **学业记录显示重修状态**：`GET /transcript` 只含**已提交**成绩，现在按**学期倒序**（同学期按课程代码升序）返回，并为每条记录附 `retake`(boolean) 与 `retakeLabel`（`"重修"` 或 `null`）。判定只看「同一课程代码在**更早学期**是否还有一条记录」——因为已通过的课程不允许再选、演示数据的 `verifyTranscriptIntegrity()` 也保证跨学期同代码的前一次必是挂科，所以同一代码出现第二次就必然是重修；暂存（`DRAFT`）成绩与已退课不在学业记录里，不参与判定。前端「我的成绩」在课程名后加「重修」徽标，**课程名本身不变**（与第四轮的口径一致，详见 [API 文档](api.md) 3.4 与 [模型文档](models.md) 的「学业记录里的重修状态」）。
29. **学业记录的统计口径按课程代码去重（重修友好）**：同一门课重修两次不应被当成两门课。因此「我的成绩」页面统计改为：已获课程数 = 按 `code` 去重后已通过的课程数；已获学分 = 按 `code` 去重、只取**通过的那一次**的学分（重修不重复计学分）；未通过课程数 = **至今仍未通过**的课程代码数（已重修通过的不再算未通过）。若不做去重，重修学生会看到「已获课程 0 门却拿到学分」「同一门课既已通过又显示未通过」这类自相矛盾的数字。
30. **学业预警必须可用（数据与筛选两处）**：预测对象是「**已有平时与实验、期末未录入**」的学生——也就是期末还没考的人；`AnalyticsService.predict` 原先的筛选条件写反，把「期末已录入」的学生跳过了，导致接口 200 但 `results` 为空、页面看不到任何预测行，本轮已修正。训练样本的口径是「同一课程代码、学期更早、成绩已提交、且平时/实验/期末三分项齐全」，门槛为 **≥3 个不同年份且 ≥24 条样本**，不足返回 422「至少需要 3 年、24 条完整历史成绩，当前数据不足，未生成预测」。为了让功能对**每一门有选课的课程**都可用，演示数据（由 `DemoInitializer` 提供）分两层构造：

    |层|做法|原因|
    |---|---|---|
    |历史样本教学班（2020-1 至 2023-2 共 91 个，任课教师为 `enabled=0` 不可登录的史料教师账号 `ht2020`/`ht2021`/`ht2022`/`ht2023`）|**有完整名单与成绩**：每班 8 条 `ACTIVE` 选课 + 8 条三分项齐全的已提交成绩（共 728 条）|施工时担心「样本班有选课就会自己需要 3 个更早年样本」而一度只登记成绩；但 2020-1 等本身就是最早期次、没有更早学期可查，顾虑不成立。样本学生的成绩会进入他们自己的学业记录|
    |缓考样本池（`B01006`）|为每门**已提交成绩的正课**补 1 名「有平时与实验、缺期末」的学生（暂存 `DRAFT` 成绩）|保证每门课都有预测对象，打开页面就有预测行|

    启动时 `verifyPredictionCoverage()` 对**每一门有 ACTIVE 选课的课程**校验「≥3 个更早年份 + ≥24 条三分项齐全的已提交成绩 + 本班至少 1 人缺期末」，不满足直接中止启动（当前启动日志：64 门有选课的正课全部可预测）；遍历时**显式排除历史样本教学班**——它们是最早期次，没有更早年份可查，纳入校验必然失败。**边界**：预测只对**进行中的课程**有意义——学生对自己已经出分的历史课程调用 `/predict` 会返回 200 但 `results` 为空（没有「期末未录入」的对象，这不是数据不足），前端会提示改选当前学期（2026-1）的课程。暂存成绩的边界见第 31 条。

31. **暂存（`DRAFT`）成绩的边界**：暂存成绩只是**教师的在途录入**与**预测输入**——它不算通过、不算挂科、不进入学业记录（`/transcript` 只取 `SUBMITTED`）、不参与重修判定（选课侧与教师名单侧的判定都要求成绩已提交），也不影响已获学分与未通过课程统计。它出现在教师端成绩表（可继续编辑与提交）与 `/predict` 的预测对象里。

## 第七轮需求解释（成绩录入辅助：本地 OCR + 语音录入）

本节记录本轮新增的两个**纯前端**录入辅助能力（轮次沿用 `docs/testing.md` 的记录口径：第一至第五轮为业务功能轮，第六轮为「初始化数据重建 + 启动清理 + 性能优化」）。原始需求（第 9 条）已把 OCR/语音定为「二选一，选浏览器本地 OCR，原图不上传」，本轮把这一条落成可用功能，并补上此前未实现的语音通道。本轮**不新增任何后端接口、不新增权限点**：识别与解析的结果都只是录入表单的草稿，最终仍走第四轮以前就存在的暂存/提交与审计链路，因此安全设计与审计证据不受影响。

功能落地后按三条用户反馈做了增补，需求口径同步更新在下面第 34–35 条：① 语音录入此前只能录第一行 → 增加"切换录入对象"的路径（「录入对象」下拉框 / 上一行下一行按钮，以及成绩表每行状态列的麦克风按钮，F54；**语音口令切行已按用户决定整个移除**）；② 用户问"图片识别是不是对格式有要求" → 用 8 种输入的对照实验定位到"深色截图"与"小字号截图"两类真实缺陷，补上深色自动反色与多尺度回退，并把支持/不支持的版式写进界面（F55）；③ 演示数据被端到端测试留下了测试课程 → 用 `-Dcampus.reset-db=true` 整库重建恢复初始状态（记录见 [testing.md](testing.md) 的「演示数据恢复」）。

32. **图片识别纸质成绩单（本地 OCR）**：教师在成绩录入页点「识别成绩单」选择图片（PNG/JPEG/WebP，≤10MB），浏览器内完成「深色自动反色 → 多策略预处理 → 行带切分 → 逐行识别 → 列锚点与列分配 → 学号匹配 → 结构化」全链路，结果进入预览确认界面；教师逐格可改、整行可跳过，点「确认填入」后才写进录入表单（仍提示「待暂存」）。**图片不上传任何字节**，tesseract.js 的 worker、WASM 核心与 `eng` 语言包都从本站 `/ocr/*` 静态资源加载；blob URL 在识别结束的 `finally` 里 `revokeObjectURL` 释放。之所以选本地 OCR 而不是服务端识别：原图是学生的个人信息载体，把识别放在浏览器内可以让"图片离开本机"这件事在架构上不可能发生，而不只是靠承诺。
33. **语音录入**：教师在工具栏「语音录入」或成绩表某行的麦克风按钮打开面板，可以口述（浏览器 `SpeechRecognition`，Chrome/Edge）或直接在文本框输入口述文本，两条通道共用同一套解析逻辑、**都只负责解析分数，不承担切行**。「平时八十五 实验九十二 期末八十七」「平时 85 分，实验 92」「八十五 九十二 八十七」都能解析；中文数字支持 0–999（含 `两`/`零`/`〇` 等写法）、小数（`八十五点五`、`85点5`）与混合写法。**具名的可以覆盖已有分数，裸数字只补空位**——口述「90 88 76」时无法确认说话人指的是哪一列，覆盖已有成绩是不可逆的误操作，因此用不掉的数字会在界面上明确提示「这些数字没有可用空位（该行已有成绩）」而不是静默丢弃。越界值（<0 或 >100）解析为 `rejected` 并提示，不写入。
34. **语音录入可切换录入对象**：面板顶部有「录入对象」下拉框（列出全部可录入学生，选项形如 `第 N 行 · 学号 姓名`）、「上一行」「下一行」按钮（`stepRow` 夹取到 `[0, rows.length - 1]`、**不循环**，越界时对应按钮禁用），并显示 `当前行：<学号 姓名>（第 i / N 行）`；成绩表每行状态列的麦克风按钮（`data-testid="voice-<学号>"`）由 `App.vue` 的 `openVoice(row)` 处理，点哪一行就切到哪一行。这三条可视化路径都只 `emit("select-target", row)` 改当前行（`App.vue` 用 `@select-target="voiceTargetRow = $event"` 写回），不产生任何接口调用。**语音与文本框通道都只把口述/输入解析成分数**，切行一律走上述界面控件——`voice.js` 不再有 `parseNavigation`，面板里也没有命令行提示文案。
35. **图片识别的输入格式与版式边界**：支持**一人一行**的成绩单（学号在最左列、成绩列在右边的数字表格），打印件、Excel/网页截图、手机拍照都可以；深色背景截图会自动反色（判据：平均灰度 < 110 且暗像素占比 > 55%），小字号截图有 1.5/2/3 倍的多尺度回退。**暂不支持**转置表（学生放在列、成绩放成行）、合并单元格跨多行的表头与手写分数（会尽力识别，但需人工核对）。界面上写明了同样的提示，并建议截图不要裁得太紧（保留表头与学号列）。**"识别不到"最常见的原因是版式不是"一人一行"**：本方案靠"学号定位行 + 列锚点定位列"，没有学号列的图无法把分数对应到学生（该行会被标成「未匹配到名册中的学号」）。
36. **传输加密与临时存储即销毁**：本轮新增的链路没有"传输"也没有"临时存储服务端副本"——图片只存在于页面内存与 blob URL 中，用完立即 `revokeObjectURL`；识别每个变体时创建的 tesseract worker 在 `finally` 里 `terminate()`。既有的 HTTPS 统一入口、Cookie/CSRF 与网关鉴权规则不变。**语音通道是唯一例外**：浏览器厂商的语音识别可能把音频送到厂商服务，界面要求教师点击「开始识别」逐次确认（不会自动开麦），原先那条固定隐私提示已按用户要求删除；成绩数据本身仍只在本页表单中处理。
37. **安全审计不受影响**：识别与语音都不直接写库（没有"导入即提交"的路径），确认填入后仍需点「暂存」触发 `POST /grades/save`，提交走 `POST /grades/transition`；因此审计动作、版本 CAS、补考规则与账本锚定全部沿用既有实现。可以验证的界面事实是「识别后表单尚未变化（需人工确认）」（实测 `69 → 69`）与「未匹配行默认不勾选（跳过）」（实测 `skipped=3/5`）。

## 安全需求解释（数据库持久存储安全与密钥管理）

本节把课程要求里两条分散在不同页码的安全约束落成可验收的工程需求，编号接在既有序列之后（第 38–39 条）：原文第 17 页「避免常见安全坑」明确写着**禁止硬编码密钥 / 密码**；第 19 页「（6）数据库持久存储安全」要求访问权限控制、成绩隐私（可结合加密实现）与操作权限控制。此前的实现满足了一部分（登录口令 BCrypt、成绩字段 AES-GCM、独立加密账本），但**密钥本身仍是写在 `application.yml` 里的字面量**（`GATEWAY_KEY: ${GATEWAY_KEY:KEY}`、`TLS_PASSWORD` 固定为 `campus-dev-tls-2024`），数据库文件也还是明文 H2 库——即文件被拷走时全部账号、密码哈希与选课成绩可读。本节的两条需求就是补上这两处缺口。

38. **数据库整库加密（静态加密）**：数据服务的默认 JDBC URL 开启 H2 的 `CIPHER=AES`，**整个库文件**是密文，而不是只加密若干敏感列。H2 在 `CIPHER=AES` 下的会话口令是「**文件口令 + 空格 + 用户口令**」两段式（H2 规定文件口令放在口令字段中、位于用户口令之前，用一个空格分隔，文件口令自身不得含空格）：文件口令用于加密整库，用户口令用于 `sa` 账号认证，两段都由 CSPRNG 随机生成、互不相同、不写进任何提交到版本库的文件。**整库加密口令是文件级/进程级的，与用户登录密码无关**——演示账号的登录密码仍然是 `passwd`，没有变化。开启加密后，H2 打不开此前生成的明文库文件（报 `File corrupted while reading record`），因此启动时按文件头识别并清理旧明文库（加密库 `H2encrypt`、明文库 `H2:`），在 Spring 之前完成，避免把「库格式不匹配」暴露成难以定位的连接异常。

39. **认证密钥与口令动态生成、集中注入、缺失即失败**：全部 9 项密钥（`GATEWAY_KEY`、`BUSINESS_KEY`、`DATA_KEY`、`AUDIT_KEY`、`LEDGER_KEY`、`AUDIT_DATA_KEY`、`TLS_PASSWORD`、`DB_PASSWORD`、`DB_CIPHER_KEY`）由 `scripts/setup.mjs` 用 `crypto.randomBytes(32)` 生成并写入 `.runtime/secrets.json`（0600、被 `.gitignore` 忽略）；`application.yml` 里一律是空占位 `${VAR:}`，`chain-worker` 的 `LEDGER_KEY`/`AUDIT_KEY` 也从环境变量或密钥文件读取并在缺失时直接退出。注入优先级为**环境变量 > `-D` 启动参数 > `.runtime/secrets.json`**，注入点在四个主类 `main` 第一行的 `DatabaseBootstrap.prepare()` 与配置绑定之前的 `ConfigEnvironmentPostProcessor`，因此 IDEA 里直接跑主类也能起来。生产档（`CAMPUS_PROFILE=prod` 或 `-Dcampus.profile=prod`）下缺密钥、仍是占位值（`KEY`/`passwd`/`password`/`changeme`/`campus-dev-tls-2024`）或长度 < 16 就**拒绝启动**并打印缺失清单。密钥轮换会改变 `schema_meta` 里的密钥指纹并触发整库重建——这是「旧密文解不开」的显式后果，不是静默降级。

## 功能追踪矩阵

|编号|原文要求 / 页码|实现位置|验证|
|---|---|---|---|
|F01|教师百分制、系数，6|`CourseService.weights`, `Models.total`|权重合计、负值、已提交禁止更改|
|F02|正考/补考录入，6–7|`GradeService.save/validateScores`|0/100 边界、超范围、非有限数、补考条件|
|F03|暂存、提交、撤销，6|`GradeService.transition`|未完成提交拒绝、状态变更、版本冲突|
|F04|教师查看/打印，6|`App.vue` 成绩表及打印 CSS|教师浏览器与打印媒体|
|F05|统计表、分析录入与展示，6|`AnalyticsService.statistics/analysis`|均值/分段/分析保存/XSS|
|F06|往年学期/课程搜索，7|`ApiController` 分页筛选|学期、中文名称、分页边界|
|F07|本人本学期/在校成绩，7|`GradeService.transcript`|本人过滤、暂存隐藏|
|F08|58/57、补考封顶，7|`Models.effective`, 成绩单页面|58/60、58/57 数值测试|
|F09|本人成绩排名，7|`GradeService.transcript`|课程内有效成绩竞争排名，不显示他人成绩|
|F10|小撤销/大撤销，7|`GradeService.transition`|管理员流程、重录、删除后证据保留|
|F11|组织库及权限，7–8|`AdminService`, `AuthService`|新增/修改/停用/重置密码/会话失效|
|F12|数据库外原始成绩，8|`AdminService.integrity`, `LedgerService`|篡改/删除识别，账本快照|
|F13|数据库与网络共同审计，8|`TransactionService`, `LedgerService`|事务发件箱、独立文件、交易回执|
|F14|学院—专业—班级三级组织模型，补充|`Models.Level`, `OrganizationService`, `SchemaCatalog` 的 `colleges`/`majors`/`classes`|三级层级、上级归属、跨学院数据|
|F15|组织编号作业务主键，补充|`Models.codePattern/validCode`, `OrganizationService.nextId/sequenceOf/itemOf`|`C01001`/`M01001`/`B01001` 格式、学院号段递增、专业班级沿用上级号段、序号取现存最大值 +1（删除最大号后该号会被再分配）|
|F16|组织新增与修改，补充|`OrganizeRoutes.save`, `OrganizationService.save/checkClassConsistency`|同名拒绝、缺上级拒绝、班级与学院不一致拒绝、版本递增|
|F17|组织删除保护与影响面，补充|`OrganizationService.impact/deleteOps`, `OrganizeRoutes.remove`|仍有课程/账号 409、下级需 `force`、级联删除|
|F18|人员组织归属与三级一致性，补充|`AdminService.saveUser`, `OrganizationService.resolveOwn`|专业属学院、班级属专业与学院、角色分级必填|
|F19|批量调整人员归属，补充|`OrganizeRoutes.assign/students`, `OrganizationService.displayName`|学生只进班级、教师只进专业、整班迁移、批量上限、`ORG_ADMIN` 可独立使用学生名册|
|F20|组织名称渲染与规范班级名，补充|`OrganizationService.names/displayName/className`, `OrganizeRoutes.options/list/members`, `CoreRoutes.me`|前端按名称操作、响应带名称、停用组织仍可显示、班级名为「年级-专业-班号」|
|F21|课程开设院系与面向班级，补充|`CourseService.saveCourse/catalog`, `courses.college_id/class_id`|必填开设院系、班级须属该院系、目录带 `collegeName`/`className`/`teacherName`、目录权限含 `SELECTION_ADMIN`|
|F22|选课批次发布，补充|`SelectionService.save`, `SelectionRoutes`, `course_selections`|批次字段校验、状态流转、发布人时间|
|F23|选课范围与时间窗口，补充|`OrganizationService.inScope`, `course_selections.scope_*_ids`|范围外 403、窗口未开始/已结束 400|
|F24|学生选课与退课，补充|`SelectionService.select/drop`, `enrollments.source/status`|重复选课 409、退课后移出本人列表、窗口与开关校验|
|F25|教务按批次批量选课与批量退课，补充|`SelectionService.batch`, `Models.MAX_BATCH_STUDENTS`|按批次 + 班级整班处理、返回新增/跳过/移除/失败明细；按**课程**维度的批量入口见 F36|
|F26|已有成绩的课程不得发布选课，补充|`SelectionService` 发布校验|存在 `grades` 行即 409|
|F27|同一课程代码冲突，补充|`CourseService.saveCourse`（允许同学期同代码多教学班）, `SelectionService.codeHistory`|同一学生不得选同学期同代码的其他教学班、跨教师同代码不得重选、更晚学期记录不构成修读历史|
|F28|已通过不得重选 / 挂科重修，补充|`SelectionService` 选课校验, `allow_retake`|已通过 409、挂科且批次允许重修可再选|
|F29|学分上限，补充|`course_selections.max_credits`, `Models.MAX_CREDITS_LIMIT`|累计学分超限 409、上限范围校验|
|F30|最低开课人数不足自动退回，补充|`SelectionService.settle/settleBatch/autoSettleExpired`, `AUTO_REFUND`|结算返回被取消课程与退回人次、退回后移出本人选课、定时任务每分钟自动结算已过期批次|
|F31|选课记录与审计动作，补充|`enrollment_records`, `SELECTION_*` 审计动作|记录含动作/原因/操作人，账本含发布、选、退、结算|
|F32|数据库初始化重构（整库重建 + 多学院演示数据），补充|`SchemaCatalog.SCHEMA_VERSION`, `DemoInitializer`, `LedgerService.reset`|版本不一致重建、重建时清空账本与链锚点；演示数据规模在第二轮扩充为 4 学院 / 8 专业 / 16 班级（见 F41）|
|F33|组织编号直接用主键 `id`（后端 `nextId` 自动生成、不可修改、前端无编号输入框），第三轮|`OrganizationService.nextId`（`nextCode`/`maxCode`/`codeOf` 已删除）, `OrganizeRoutes.save/list/options`, `OrganizationView.vue`|新建返回 `{ok,id}`（`C`/`M`/`B` + 5 位）、编号沿用「同层最大 + 1」的号段规则、同一父级下编号不重复、修改时请求体里的 `id`/`code` 不改写主键、列表与下拉不含 `code` 键、表单无「编号」字段|
|F34|班级不设「辅导员」（结构版本升到 3），第二轮|`SchemaCatalog.SCHEMA_VERSION`, `SchemaCatalog` 的 `classes` 列, `RemoteRepository.FIELDS`, `OrganizeRoutes.save/list/options`, `OrganizationView.vue`|班级列表字段不含 `counselor`、保存与下拉不写不读、组织管理页无「辅导员」列与表单|
|F35|管理员不归属任何组织，第二轮|`AdminService.saveUser`, `OrganizeRoutes.requireRole/assign`, `DemoInitializer.PEOPLE/verifyOrganizationIntegrity`, `CoreRoutes.me`|给 ADMIN 传组织 400「管理员不归属学院、专业或班级」、管理员进入 assign 名单 400、`/me` 三级名称为 `null`、种子数据三级字段为空且启动自检拦截|
|F36|按课程批量选课（1 人 / 多人 / 整班），第二轮|`SelectionService.batchByCourse`, `CoreRoutes` 的 `POST /enrollments/batch`, `Models.MAX_BATCH_STUDENTS`|`studentIds` 与班级取并集去重、已选学生计入 `skipped`、有成绩的学生退课进 `failed`、超过 300 人 400、返回 `added/skipped/removed/failed/classStudents`|
|F37|教务代选/代退写入选课记录，第二轮|`SelectionService.enrollmentRecord`, `CourseService.enroll`, `enrollment_records` 的 `ADMIN_ASSIGN`/`ADMIN_REMOVE`|单人与批量都产生流水，`/selections/records` 可查到教务动作、原因与操作人|
|F38|管理员选课入口合并为「选课管理」（3 个标签页）,第二轮|`App.vue` 的 `nav`/`adminSelectionTabs`/`selectionTab`/`switchSelectionTab`, `SelectionView.vue`, `CoursesView`|导航只有一个「选课管理」、页内「课程与选课 / 选课批次 / 选课记录」、不再有独立「网上选课」与「课程与选课」入口、独立批量选课界面移除|
|F39|管理员导航顺序、身份行与拖动调节侧栏宽度，第二 / 三轮|`App.vue` 的 `nav`/`identityParts`/`identityLine`/`sidebarWidth`/`startSidebarResize`/`resetSidebarWidth`, `style.css` 的 `--sidebar-width`/`.sidebar-resizer`/`.identity-meta`|「安全审计」排在最后、身份行为「角色 · 学院 · 专业 · 班级」、空组织不显示 `null`、侧栏可拖动到 68–420px 且持久化、仅图标档保留 `title`/`aria-label`、双击复位、无横向滚动|
|F40|去掉硬编码学院名，第二轮|`App.vue` 顶栏 / 页脚 / 登录页标语文案|顶栏只显示页面名、页脚「知序 · 高校成绩管理」、登录页无具体学院名、`frontend/src` 全文检索「信息工程学院」为 0|
|F41|初始化数据扩充到多学院规模，第二 / 四轮|`DemoInitializer` 的 `COLLEGES/MAJORS/CLASSES/PEOPLE/STUDENTS/COURSES/RETAKES/seedCourseSelection/verifyOrganizationIntegrity`|4 学院 / 8 专业 / 16 班级 / 65 账号 / 35 教学班、预置 2026-1 进行中批次、`CS401` 两个不同教师教学班、5 名挂科后重修的学生（3 个课程代码）|
|F42|重修不体现在课程名：同一课程号在后续学年重新开设，课程名与普通教学班一致，第四轮|`DemoInitializer.COURSES/RETAKES/RETAKE_ONLY_COURSES`, `verifyTranscriptIntegrity`, `SelectionService.FailedCodes`|课程名无「重修」字样、重修学位与挂科学位同 `code` 不同 `term`、`verifyTranscriptIntegrity` 在启动时拦截「已通过又重选」与样本不足|
|F43|三端下发重修状态，第四轮|`SelectionService.my/options/describe`（`retake`/`retakeLabel`/`retakeCount`）, `CourseService.roster/failedCodesBefore`（`retake`/`retakeLabel`）, `SelectionView.vue`/`App.vue` 的 `.badge.amber`|学生「我的选课」与选课台、教师名单、教务批次课程三处都带重修标记；课程名不变|
|F44|重修的判定依据只来自历史成绩，第四轮|`SelectionService.failedCodes`/`codeHistory`, `CourseService.failedCodesBefore`, `Models.effective`|更早学期 + 同 `code` + `state=SUBMITTED` + 有效分 < 60 才算重修；未出/未提交成绩与已退课不算；已通过 409、未开放重修 403|
|F45|学业记录显示重修状态，第五轮|`GradeService.transcript/markRetakes/blank`, `App.vue`「我的成绩」的 `.badge.amber`|`/transcript` 学期倒序、第二次同代码标 `retake=true`/`retakeLabel=重修`、单次修读不标、暂存与退课不出现|
|F46|学业记录统计口径按课程代码去重，第五轮|`App.vue` 的 `passedCourseCount`/`earnedCredits`/`pendingCourseCount`|已获课程与学分按 `code` 去重（重修不重复计学分）、已重修通过的课程不再计入未通过|
|F47|学业预警可用（预测对象与样本口径），第五轮|`AnalyticsService.predict` 的样本与对象筛选, `DemoInitializer.verifyPredictionCoverage`/历史样本层/缓考样本池|`results[]` 返回「已有平时与实验、期末未录入」的学生、`low`/`high`/`warning`/`risk` 齐全；年份 < 3 或样本 < 24 → 422；训练失败 → 422 `MODEL_FAILURE`；已出分课程返回 200 + 空 `results`；每一门有 ACTIVE 选课的课程都可预测（启动自检）|
|F48|图片识别纸质成绩单（本地链路 + 人工确认），第七轮|`App.vue` 的 `recognize`/`applyRecognized`/`ocrRows`/`ocrSummary`/`OCR_STAGES`/`ALLOW_DIGIT_CORRECTION`、`components/RecognizePreview.vue`、`ocr.js` 的 `loadCanvas`→`invertIfDark`→`recognizeBest`（含 `scaleSteps` 尺度回退）→`detectColumns`→`inferAnchors`→`assignColumns`→`mapColumns`→`buildPreview`|固定图集逐张走真实管线（最新一轮 `14:50:14`）：清晰打印体字段级 100%（30/30）、行匹配 9/9；阴影+噪点 100%；脏数据（缺列/超界/名册外学号/整行空格）均 100%；倾斜 2° 100%（最优变体 `gray-raw`）、倾斜 4° **91.7%**（11/12，`variants = 13`，最优变体 `gray`）；另有本轮 8 种输入的对照实验（深色截图反色、14px 小字、中文表头、倾斜 3°、透视等，见 [ocr-voice-design.md](ocr-voice-design.md) 4.12）；单元层 76/76；证据 `.runtime/logs/ocr-voice-check.json`、`ocr-voice-unit.json`（见 [ocr-voice-design.md](ocr-voice-design.md) 第九节）|
|F49|语音录入（语音/文本双通道 + 中文数字文法），第七轮|`App.vue` 的 `openVoice`/`applyVoice`/`voiceDefaults`/`voiceTargetRow`、`components/VoicePanel.vue`、`voice.js` 的 `chineseToNumber`/`toNumber`/`scanUtterance`/`parseUtterance`/`toCellUpdates`/`speechSupport`/`createVoiceSession`/`COMPONENT_ALIASES`/`buildVoiceComponents`|单元层 76 条断言覆盖中文数字、小数、混合写法、口语别名、越界拒绝、裸数字只补空位等（`ocr-voice-unit.json` 的 `total = 76`）；浏览器层「中文口述解析为三项分数」「超界分数被拒绝并提示」「只提到一项时其余列不动」「裸数字无空位时提示」「行内麦克风切到该学生」「`SpeechRecognition` 桩驱动真实接入路径」|
|F50|识别结果必须人工确认才进表单（预览网格），第七轮|`components/RecognizePreview.vue` 的 `draft`/`enabled`/`toggleRow`/`setValue`/`confirm`（`confirm` 只回传已勾选且有值的格子）、`App.vue` 的 `applyRecognized`|「识别后表单尚未变化（需人工确认）」实测 `69 → 69`；「未匹配行默认不勾选」实测 `skipped=3/5`；「坏行不影响其余行填入」；`data-testid` 统计项 `usable-count`/`filled-count`/`suspicious-count`|
|F51|识别与语音的隐私边界（图片不上行、blob 用完释放、worker 用完终止），第七轮|`App.vue` 的 `URL.createObjectURL`/`finally { URL.revokeObjectURL(url) }`、`ocr.js` 的 `recognizeVariants` 的 `finally { worker.terminate() }`、`VoicePanel.vue` 的**逐次点击「开始识别」**才开麦|「整轮识别没有任何图片上行请求」通过（捕获「POST 且请求体含 PNG」的请求数为 0）；语音侧界面**不再显示**固定隐私提示（那条黄色提示框已按用户要求删除），事实记录见 [ocr-voice-design.md](ocr-voice-design.md) 2 节与 7.4 节；CSP 仅对 OCR 的 WebAssembly 编译放宽（见 [security.md](security.md) 的威胁表）|
|F52|识别/语音仍走既有暂存、提交与审计，不新增接口，第七轮|`App.vue` 的 `saveGrades`→`POST /grades/save`、`transition`→`POST /grades/transition`；`ocr.js`/`voice.js` 不引用 `api.js`|确认填入后仅置 `dirty`，仍需点「暂存」；识别、语音与**切换录入对象**都没有独立写库路径（见 [api.md](api.md) 的「成绩录入辅助（前端本地能力）」）；既有浏览器回归 **57/57 已在演示数据重建后复跑通过**，端到端 201/201 仍是重建前 `2026-10-05 12:53:45` 的记录（本轮未复跑，见 [testing.md](testing.md) 的演示数据恢复一节）|
|F53|**学号安全匹配：不能被已知混淆解释的差异不自动认人**，第七轮|`ocr.js` 的 `correctId`（`confusionHits === 0` 时返回 `{username: null, unverified: true, candidate}`；`allowDigitCorrection` 只放行"仅一位数字"的差异；同距离同解释力时标 `ambiguous` + `alternatives`）、`confusionHits`、`compact`、`mapColumns` 的四条学号文案、`App.vue` 的 `ALLOW_DIGIT_CORRECTION`|`scripts/ocr-voice-unit.mjs` 的 4 条断言：「数字位差异不被自动认人」（`correctId("20231539", ["20231530","20241530"])` 的 `username === null`）、「数字位差异标记为待人工确认」（`unverified === true`）、「无法确认的学号不写入任何学生」（`structureRows` 后 `matched === false`）、「无法确认的学号给出人工核对提示」（`issues` 含「人工核对」）；另有「精确匹配优先于编辑距离为 1 的其它学号」「多候选同距离时优先混淆能解释的」。证据 `.runtime/logs/ocr-voice-unit.json`（`total = 76`、`passed = 76`）|
|F54|**语音录入可切换录入对象**（「录入对象」下拉框 / 上一行下一行按钮 / 成绩表行内麦克风按钮），第七轮|`VoicePanel.vue` 的 `rows` prop 与 `select-target` emit、`stepRow`/`gotoRow`/`rowKey`（`stepRow` 夹取到 `[0, rows.length - 1]`、不循环）、`App.vue` 的 `openVoice(row)` 与 `:rows="rows"` + `@select-target="voiceTargetRow = $event"`|单元 76 项不再含导航口令（`ocr-voice-unit.json`，`total = 76`、`passed = 76`）；浏览器 40 项含 4 条切行断言（下拉框列全部 / 下拉框选中 / 上一行 / 下一行）：「下一行」按钮（`20241530 → 20241531`）、「上一行」按钮（`20241531 → 20241530`）、下拉框列出全部学生（`options=9 rows=9`）、下拉框直接选中（`20241532 → 20241532`）（`ocr-voice-check.json`，`total = 40`、`passed = 40`）|
|F55|**图片识别的输入格式兼容**（深色截图自动反色 + 小字号多尺度回退 + 明确的版式边界），第七轮|`ocr.js` 的 `invertIfDark`（被 `loadCanvas` 调用，判据"平均灰度 < 110 且暗像素占比 > 55%"）、`recognizeBest` 的 `scaleSteps`（默认 `[2, 3, 1.5]`）与 `buildVariants` 的 `scale`（先放大再纠偏）、`App.vue` 识别弹窗的 `.ocr-tips` 版式提示与 10 MB 上限|本轮 8 种输入的对照实验：基准 3/3、14px 小字 3/3、11px 极小字 2/3（中间行 `80→8` 丢位）、中文表头 3/3、深色截图**修复后** 3/3（修复前仅 2 行且缺列）、表格线很浅 3/3、倾斜 3° 修复后 3/3、透视 3/3 基本对；固定图集侧深色/小字相关结论见 `ocr-voice-check.json` 的 `accuracy[]` 与 [ocr-voice-design.md](ocr-voice-design.md) 4.1.1、4.1.2、4.12|
|F56|**数据库整库加密（H2 `CIPHER=AES`）**：库文件整体为密文，会话口令为「文件口令 + 空格 + 用户口令」两段式，两段均由 CSPRNG 生成|`DbCredentials.url()`（默认 `jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE`）、`DbCredentials.password()`、`ConfigGuard.databasePassword()`、`data-service/.../DataSourceConfig.dataSource()`、`SchemaCatalog.dropLegacyPlaintextDatabase()`、`DatabaseBootstrap.prepare()`（反射调用前者）、`scripts/setup.mjs` 的 `DB_CIPHER_KEY`/`DB_PASSWORD`|库文件头为 `H2encrypt`；在库文件里检索 `password`/`$2a$`/`李老师`/`t1101`/`CS401`/`20241530`/`course_selections` 命中数**全部为 0**；正确两段式口令可打开（`users` 205 行）；只给单段报 `Wrong password format, must be: file password <space> user password [90050-224]`；文件口令错报 `Encryption error in file ... [90049-224]`；明文库被自动识别清理。证据见 [testing.md](testing.md) 8.3–8.4|
|F57|**认证密钥与口令动态化**：不再有硬编码 `KEY`、不再有固定 `campus-dev-tls-2024`；9 项密钥随机生成到 `.runtime/secrets.json`，按「环境变量 > `-D` > 密钥文件」注入；生产档缺密钥/占位值/长度不足即拒绝启动；密钥指纹落库用于识别轮换|`scripts/setup.mjs` 的 `SECRET_KEYS` 与 `crypto.randomBytes(32)`、`ConfigGuard`（`REQUIRED_KEYS`/`load`/`secret`/`dataFingerprint`/`acceptableInProduction`/`secretsFile`）、`DatabaseBootstrap.prepare()`、`ConfigEnvironmentPostProcessor` + `common/src/main/resources/META-INF/spring.factories`、`common/.../DbCredentials.describePassword()`、`common/.../RpcClient`（信任库口令取密钥表）、`chain-worker/server.mjs` 的 `loadSecrets()`、`scripts/start.ps1` / `start.sh` 的注入、`common` 的 `ConfigGuardTest`（7 条）与 `data-service` 的 `SchemaCatalogTest.rebuildsWhenSecretsFingerprintChanges`|`mvn -o test` 258 项全绿（`ConfigGuardTest` 断言密钥清单为 9 项、随机密钥为 64 位十六进制且不重复、指纹稳定且对任一密钥敏感且不泄漏密钥、生产档拒绝 `KEY`/`passwd`/`campus-dev-tls-2024`/长度不足、密钥文件路径可覆盖、两段式口令恰有一个空格）；`secrets.json` 实测 9 项且全部匹配 `[0-9a-f]{64}`；`chain-worker` 缺密钥直接退出；`.logs/jvm.args` 里 9 项 `-D` 与带引号的两段式 `-DDB_PASSWORD` 可直接核对。证据见 [testing.md](testing.md) 8.2、8.4|
|A01|均值±3σ、百分位、波动，8|`AnalyticsService.anomalies`|异常规则及管理员可见事件|
|A02|线性回归，8–10|Weka `LinearRegression`|三年校验、留后一年验证、RMSE|
|A03|决策树/树结构，9|Weka `REPTree`|树模型实际训练及文本输出|
|A04|预测私密/不落库，10|`AnalyticsService.predict`|学生仅本人、管理员拒绝、模型临时对象|
|A05|OCR/语音辅助，10|Tesseract.js 本地 OCR（原图不上传）|图片识别、输入范围、人工确认；本轮已实现并补上语音通道，见 `A08`–`A14` 与 [ocr-voice-design.md](ocr-voice-design.md)|
|A06|区块链关键操作，10|Ganache EVM + `LedgerService`|提交/撤回/大撤销快照哈希、回执校验|
|A07|LSTM/Transformer 日志分类，10|TensorFlow.js LSTM|合成训练集、独立验证集、实际推理|
|A08|本地 OCR 的识别管线（深色自动反色 / 按轮生成的预处理候选 / 投影法倾斜估计 / baseline 自校正 / 小字号多尺度回退 / 自适应行带切分 / 逐行 PSM 7），第七轮|`ocr.js` 的 `VARIANTS`（常量表，当前不再逐个消费）/`loadCanvas`/`invertIfDark`/`estimateSkew`/`estimateTiltFromRows`/`segmentBands`/`recognizeSheet`/`recognizeBest`/`rankPasses`/`scoreVariant`/`variantQuality` 与内部 `stretch`/`otsu`/`adaptiveThreshold`/`rotateCanvas`（双线性插值）/`cropBand`/`buildVariants`（`scale` 先放大再纠偏）|`segmentBands` 切带与顺序（3 带）、`__testRotate` 旋转一致性；10 张固定图集经真实浏览器管线逐张统计（`ocr-voice-check.json` 的 `accuracy[]`：`elapsedMs`/`skew`/`bestVariant`/`variants`）；自校正的收益见 `skew-2deg` 100%；尺度回退与深色反色见 `F55` 与 [ocr-voice-design.md](ocr-voice-design.md) 4.1.1/4.1.2|
|A09|学号匹配与列分配容错（精确优先、混淆纠正、编辑距离 ≤1、缺值不前移），第七轮|`ocr.js` 的 `fixConfusion`/`editDistance`/`correctId`/`idCandidates`/`assignColumns`/`rightEdge`/`singleDigit`/`inferAnchors`/`isRowUsable`|单元层：精确匹配优先于编辑距离为 1 的其它学号、多候选同距离时优先混淆可解释的、缺中间列留空不前移、个位残片不占用前一列、右对齐一位分数落在正确列；浏览器层 `confusable-id` 3/3 行匹配。安全侧见 `F53`|
|A10|语音解析的中文数字文法与裸数字补位语义，第七轮|`voice.js` 的 `chineseToNumber`/`toNumber`/`scanUtterance`/`parseUtterance`/`toCellUpdates`|单元层 76 项含中文数字、小数、混合写法、别名映射、越界拒绝、裸数字只补空位、未提到的项保留原值，以及 OCR 侧的行带切分、列锚点与列分配、结构化与统计（`ocr-voice-unit.json` 的 `total = 76`）；浏览器层面板实测失败见 `voice-unmatched` 文案。**已不含导航口令**（语音口令切行已移除，`voice.js` 无 `parseNavigation`）|
|A11|浏览器语音接入与降级，第七轮|`voice.js` 的 `speechSupport`/`createVoiceSession`（`lang=zh-CN`、`continuous`、`interimResults`、`maxAlternatives=1`）、`VoicePanel.vue` 的双通道与错误映射|浏览器层用 `SpeechRecognition` 桩验证「点击开始识别调用 `start`」与「识别回调文本进入解析」；不支持时渲染 `voice-unsupported` 并提示改用文本框（解析逻辑完全相同）|
|A12|学号安全匹配（不可解释的差异不自动认人），第七轮|`ocr.js` 的 `correctId`/`confusionHits` 与 `mapColumns` 的 `unverified`/`ambiguous` 分支，`App.vue` 的 `ALLOW_DIGIT_CORRECTION`|`scripts/ocr-voice-unit.mjs`：「数字位差异不被自动认人」「数字位差异标记为待人工确认」「无法确认的学号不写入任何学生」「无法确认的学号给出人工核对提示」；证据 `.runtime/logs/ocr-voice-unit.json`（76/76）。设计规则见 [ocr-voice-design.md](ocr-voice-design.md) 4.9|
|A14|深色背景自动反色，第七轮|`ocr.js` 的 `invertIfDark`（`loadCanvas` 内调用）|判据"平均灰度 < 110 且暗像素占比 > 55%"；本轮对照实验里暗色主题截图修复前只能认出 2 行且缺列，反色后 3/3 行全对（见 [ocr-voice-design.md](ocr-voice-design.md) 4.1.1 与 4.12）|
|A15|**数据库持久存储安全：整库静态加密**（原文 19 页「数据安全，成绩的隐私」）|H2 `CIPHER=AES` + `DataSourceConfig` 显式装配 + `DbCredentials` 的两段式口令 + `SchemaCatalog.dropLegacyPlaintextDatabase`|① 库文件头 `H2encrypt`；② 文件中检索用户名/课程代码/学号/BCrypt 前缀/表名均 0 命中；③ 正确两段式口令可打开且 `users` 表 205 行；④ 单段口令报 90050、文件口令错报 90049；⑤ 旧明文库被自动清理后以加密库重建（见 [testing.md](testing.md) 8.3、8.4）|
|A16|**禁止硬编码密钥 / 密码（原文 17 页）**：密钥全部动态生成并注入，生产档强校验|`scripts/setup.mjs`、`ConfigGuard`、`DatabaseBootstrap`、`ConfigEnvironmentPostProcessor`、`RpcClient`、`chain-worker/server.mjs`、`scripts/start.ps1` / `start.sh`|① `ConfigGuardTest` 7 条全绿（清单/随机性/长度/指纹/生产档判据/路径覆盖/两段式契约）；② 源码与 `application.yml` 里检索不到真实密钥，只有空占位；③ 生产档缺密钥或占位值拒绝启动并打印缺失清单；④ `.runtime/secrets.json` 为 9 项 64 位十六进制且被 `.gitignore` 忽略；⑤ 密钥指纹不一致时整库重建（`SchemaCatalogTest.rebuildsWhenSecretsFingerprintChanges`）；⑥ `chain-worker` 缺密钥直接退出（见 [testing.md](testing.md) 8.2、8.4、8.5）|
|R01|HTTPS 统一入口、统一收集，11|`GatewayController`, `ApiController`|全部 `/api` 经网关处理|
|R02|对象值传递/命名约定，11|`Protocol`, `RemoteRepository`|JSON 查询/操纵对象，主键与普通字段分离|
|R03|SelectInterface 二维字符串数组，12|`DataRpcController.select`|签名远程查询，`String[][]`|
|R04|ManipulationInterface 布尔返回，12|`DataRpcController.manipulate`|成功 true；失败明确 HTTP 错误|
|R05|自动 SQL 与多条事务，12|`SqlCompiler`, `TransactionService`|注入/全事务回滚/版本冲突|
|R06|数据库配置自适应，12|`DataApplication`, `SchemaCatalog`|H2 实测；其余待目标环境回归|
|S01|密码哈希、HTTPS、接口权限，18|BCrypt、TLS、认证/权限服务|登录、锁定、RBAC、课程归属|
|S02|SQL 注入、XSS，18|参数绑定、Vue 转义、CSP|恶意标识符、值注入、XSS 浏览器检查|
|S03|加密存储和完整性，19|H2 整库 AES + 字段级 AES-GCM + 独立账本（细节见 F56/F57）|密文绑定身份状态版本、恢复证据、库文件里搜不到明文|
|S04|多管理员或签，19|任何启用且持 `GRADE_ADMIN` 的管理员可审批撤销|第二管理员授权、撤销流程|
|S05|网络分区/应急/审计，14–18|默认回环地址、部署和安全文档|端口与权限配置、异常拒绝写入|
|S06|禁止硬编码密钥 / 密码，17|`setup.mjs` 随机生成 9 项密钥 → `.runtime/secrets.json` → 环境变量 / `-D` 注入；生产档拒绝启动（细节见 F57、A16）|源码与 yml 里没有真实密钥；生产档缺密钥或占位值启动失败；`chain-worker` 缺密钥退出|

## 非功能要求

- 数据访问模块单独部署，业务模块无 SQL/JDBC；参数化协议不暴露给浏览器。
- 网关维护 20 秒租约，服务每 5 秒心跳，多业务实例轮询，数据库会话共享。只读请求可由客户端刷新重试，写操作不做透明自动重试。
- 同一教学班的版本号保护跨实例写入，成绩行版本额外保护批量写入。
- 生产必须使用数据库权限、服务账号隔离、外部密钥管理和可信证书；本机统一 `.runtime` 是教学便利措施。
- 密钥与口令**没有默认值**：`application.yml` 里只有空占位 `${VAR:}`，唯一保留的字面量是 `server.ssl.key-store-password` 的开发证书兜底值 `campus-dev-tls-2024`，它只在 `TLS_PASSWORD` 完全缺失且非生产档时生效，且被 `ConfigGuard` 列为生产档的占位值（出现即拒绝启动）。占位值与长度 < 16 的判据**只在生产档生效**；开发档下手工注入的短值会被原样采用，因此手工注入示例都用 64 位十六进制。单元测试不需要密钥文件（用 H2 内存库），运行全套服务必须先执行 `node scripts/setup.mjs`。
- **密钥轮换等于整库重建**：`schema_meta.version_value` 存的是「结构版本:密钥指纹」，任一密钥变化即触发整库重建，因为成绩 payload、审计发件箱与账本区块都用当时那组密钥加密、换了就解不开。轮换前必须备份 `.runtime/database/`，并按部署文档处理账本、锚点与链数据（见 F57、[configuration.md](configuration.md) 3.3.5）。
- 数据库静态加密的信任边界要写清：它保护**静态文件**（拷走 `.mv.db`、备份卷或磁盘镜像的人读不到明文），不保护运行中的进程；库文件与 `secrets.json` 同机可读时，拥有本机管理员权限的攻击者仍可解密。H2 的 AES 实现是 AES-128。
- H2 文件数据库不能被多个独立数据服务同时打开；水平扩容数据服务需迁移共享事务数据库并完成部署章节的并发回归。
- 不声称任意规模下“绝对无误”；测试结论以报告中的实际执行证据和边界为准。
- 组织与选课的请求体同时接受编号与名称，服务端统一归一化成编号；前端不拼接、不推断内部编号，避免出现第二份组织真相。
- 选课写入全部组装成 `Protocol.Operation` 后交给 `RemoteRepository.mutate` 一次性提交，因此自动进入审计发件箱与独立账本，不绕过统一事务直接写库。
- 组织列表、成员列表、选课批次与选课记录均复用 `ApiController.page` 的统一分页（页从 1 开始，单页 1–300）；`/organizations/options` 为一次性全量返回，仅适用于演示规模，大库需改为按上级分页查询。
- 批量调整人员与批量选课沿用既有上限：`Models.MAX_BATCH_STUDENTS = 300` 个账号、单事务最多 500 条 `Protocol.Operation`，超限在进入事务前拒绝。
- `SchemaCatalog.SCHEMA_VERSION` 驱动的整库重建只适用于演示与教学环境：它会清空全部业务表并触发演示数据重灌。生产库必须改用增量迁移，并按部署章节完成并发回归。
- 组织只有一个编号：主键 `id`（`C`/`M`/`B` + 5 位），它同时承担库内引用、审计快照、接口寻址与页面展示。任何接口都不接受用名称以外的键冒充编号，`resolveOwn` 只按 `id` 与 `name` 解析组织；组织表里遗留的 `code` 列不参与任何读写，也不出现在响应里（第三轮已删除第二轮的两位显示编号）。
- 编号不可修改是服务端约束：`POST /organizations/save` 的修改分支只用 `id` 定位对象，请求体里额外的 `id`/`code` 不会改写主键；前端表单因此没有「编号」输入框，列表只把主键 `id` 作为只读文本展示。
- 侧栏宽度是纯前端的显示偏好（`localStorage.campus.sidebarWidth`，68–420px）：它不进后端、不影响权限，也不改变任何请求内容；拖动只调整 `--sidebar-width` 与主内容左边距。
- 管理员无组织使 `users` 表的组织字段出现「合法的全空」状态：所有按组织筛选的查询（组织成员、学生名册、按学院统计）都不会返回管理员，这是期望行为；管理员仍然通过角色权限参与组织维护与选课管理。
- `/organizations/students` 的权限口径仍是 `ORG_ADMIN`（本轮未放宽为 `GRADE_ADMIN`）：课程界面的按课程选课弹窗用它取学生名单，失败时前端降级提示「学生列表不可用，仍可按班级整班处理」，此时仍可通过班级名称完成整班选课。
- 重修是**状态**而不是课程属性：课程表 `courses` 没有任何重修字段，课程名也与重修无关；「谁在重修」由历史成绩派生（`SelectionService.failedCodes` / `CourseService.failedCodesBefore`），并以 `retake`/`retakeLabel`/`retakeCount` 下发。派生字段的代价是每次查询要读历史成绩，因此批量场景一次性读入成绩索引复用；若将来加入「重修报名」这类显式流程，应新增业务表而不是往课程名或课程行上挂标记。
- 图片识别是**浏览器本地能力**：识别结果不产生任何后端请求，tesseract.js 的 worker、WASM 核心与 `eng` 语言包都是本站 `/ocr/*` 静态资源；图片只经 `createObjectURL` 在本页读取并在 `finally` 里 `revokeObjectURL`。它的准确率结论只覆盖 10 张合成图集（Edge 本机渲染）与本轮 8 种输入的对照实验，**不代表真实手机拍照的整体水平**。实测边界：倾斜 2° 100%（最优变体是**不纠偏**的 `gray-raw`）、倾斜 4° **91.7%**（11/12，`variants = 13`，最优变体 `gray`；上一轮同为该图集是 75% 且最优变体是 `gray-raw`，两次不同说明"纠偏 vs 不纠偏"没有恒定赢家）；11px 极小字仍有 `80→8` 这类丢位；残余错误是个位残片与漏读。**"识别不到"最常见的原因是版式不是"一人一行"**（转置表、或只截分数列没带学号列——没有学号就无法把分数对应到学生）。这些都是已知边界而不是回归。
- 学号匹配遵循**安全优先**：OCR 结果只有在"归一化后精确命中名册"或"差异能被已知字形混淆解释"时才自动填入；`9→0`、`5→4` 这类纯数字位差异默认返回 `{username: null, unverified: true, candidate}`，既不写库也不写表单，只提示「学号与名册有差异但无法确认，请人工核对」；多个候选在"编辑距离 + 混淆解释力"上并列时标 `ambiguous`。自动认人需要显式传 `allowDigitCorrection: true` **且**差异仅一位数字；`App.vue` 用常量 `ALLOW_DIGIT_CORRECTION = true` **同时**传给 `recognizeBest`（选优）与 `buildPreview`（预览重建），因此两阶段口径一致，识别与预览都会自动认人并标「学号经自动纠正，请核对」（详见 [ocr-voice-design.md](ocr-voice-design.md) 4.9.3）。所有分支都只是预览界面的提示，不新增接口、不新增权限点。
- 语音录入的两个通道能力相同、来源不同：文本框通道完全本地（解析是纯函数），麦克风通道的音频由浏览器厂商的 `SpeechRecognition` 处理、**可能上行到厂商服务**，因此界面要求教师逐次点击「开始识别」（不会自动开麦）；**原先那条固定隐私提示已按用户要求从面板删除**，界面上不再有相关文案。**切行只走界面控件（「录入对象」下拉框 / 上一行下一行按钮 / 成绩表行内麦克风按钮），不发任何请求**；语音与文本框通道都只解析分数。无论走哪条通道，结果都需教师确认才写入表单，且写入后仍需暂存才落库。
- 识别与语音都**不新增权限点**：入口按钮与「暂存」按钮共用 `isTeacher && can('ENTRY') && !submitted` 这一个条件，成绩全部提交后按钮不渲染。
