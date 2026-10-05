# 测试报告与验证证据

本报告依据实际运行输出生成，不把尚未执行的平台或安全扫描写成通过。各轮（第一轮「组织管理与网上选课」、第二轮「8 条修正」、第三轮「侧栏拖动 + 编号回归主键 `id`」、第四轮「重修语义」、第五轮「学业记录重修状态 + 学业预警可用」、第六轮「初始化数据重建 + 启动清理 + 性能优化」、第七轮「成绩录入辅助：本地 OCR + 语音录入」、第八轮「整库加密与动态密钥」）的原始证据都保存在 `.runtime/logs/`（`junit-summary.json`、`feature-test.json`、`browser-check.json`、`sidebar-resize-check.json`、`ocr-voice-unit.json`、`ocr-voice-check.json`）、各模块 `target/surefire-reports/` 与 `test-results/browser/`（界面截图）；原始交付的摘要记录在 `docs/evidence/`（该目录在当前源码树中不存在，见「复现顺序」说明）。**本文档以第八轮冻结修订上的实测结果为当前值（258 / 201 / 57 / 28 / 76 / 40），前七轮的旧数字只在各自的小节里作历史对照。**

## 环境

本轮实测环境为 Windows、**JDK 17**（`JAVA_HOME=D:\JAVA\jdk-jb-17`，`mvn -o test` 离线运行，Maven 3.9.16 来自本机 `.m2\wrapper\dists`）、Node.js v26.3.0、HTTPS 本机证书、**整库加密（H2 `CIPHER=AES`）的 H2 文件库**、独立 Java 服务与 Ganache EVM；浏览器检查用本机 Edge 无头模式。演示库为 4 学院 / 8 专业 / 21 班级 / 205 账号 / 155 教学班 / 1354 条选课 / 1354 条成绩的合成数据，不含真实学生信息。原始交付记录的环境为 macOS ARM64、JDK 17.0.13。

## 汇总

|测试层|通过|失败|证据|
|---|---:|---:|---|
|Java 单元测试（第八轮实测 `mvn -o test`；第七轮为 250）|**258**|0|各模块 `target/surefire-reports/TEST-*.xml`；汇总脚本 `scripts/junit-summary.mjs` 写入 [junit-summary.json](../.runtime/logs/junit-summary.json)|
|feature 端到端（第八轮复跑；第五轮起为 201 条）|201|0|[feature-test.json](../.runtime/logs/feature-test.json)|
|browser 真实浏览器（第八轮复跑）|57|0|[browser-check.json](../.runtime/logs/browser-check.json)|
|OCR/语音纯函数单元（第七轮新增，第八轮复跑）|76|0|[ocr-voice-unit.json](../.runtime/logs/ocr-voice-unit.json)|
|OCR/语音浏览器端到端（第七轮新增，第八轮复跑）|40|0|[ocr-voice-check.json](../.runtime/logs/ocr-voice-check.json)|
|侧栏拖动专项检查（第四轮记录，未重跑）|28|0|[sidebar-resize-check.json](../.runtime/logs/sidebar-resize-check.json)|
|api|18|0|[api.json](evidence/api.json)|
|workflow|17|0|[workflow.json](evidence/workflow.json)|
|tamper|5|0|[tamper.json](evidence/tamper.json)|
|scale|4|0|[scale.json](evidence/scale.json)|
|Playwright 浏览器|6|0|[browser-summary.json](evidence/browser-summary.json)|

**第八轮实测的是前六行：Java 单元测试 258 条、feature 端到端 201/201、真实浏览器 57/57、OCR/语音单元 76/76、OCR/语音浏览器 40/40，全部 0 失败**（侧栏专项沿用第四轮记录 28/28）。证据文件时间：`ocr-voice-unit` `2026-10-05T16:18:50.973Z`（本机 `2026-10-06 00:18:50`）、`ocr-voice-check` `16:20:11.747Z`、`browser-check` `16:21:09.371Z`、`feature-test` `16:21:41.662Z`；Java 单元测试在 `2026-10-06 00:25` 由 `mvn -o test` 重跑，surefire 报告为本机 `0:25:09`–`0:25:14`。**前七轮的记录只在下方各节作历史对照，不代表当前值**：第一轮 204 / 114 / 15，第二轮 234 / 156 / 35，第三轮 230 / 164 / 44，第四轮 243 / 183 / 50，第五轮 248 / 201 / 57，第六轮 250 / 201 / 57，第七轮 Java 未改动（250）+ OCR/语音 76 / 40。其余五行是原始交付记录，本轮未重新执行，且其脚本在当前源码树中不存在（见文末「复现顺序」），不计入本轮结论。不同层级包含多条断言，这个数量不等同于穷举所有输入组合。

## 第三轮（R3）说明（历史记录）

第三轮只改两处：前端侧栏宽度由「折叠按钮」改为「鼠标拖动调节」，以及组织编号回归数据库主键 `id`（取消第二轮的显示编号 `code`）。按用户要求，第三轮**不为这两处改动新写测试用例**；但既有测试已按新契约更新并全部重跑通过——Java 侧删掉 7 条「两位显示编号」用例、新增 3 条「主键编号」用例（净减 4 条，234 → 230），端到端与浏览器脚本则扩充了编号字段与侧栏拖动的断言。各证据文件在第三轮冻结修订上的记录：

|证据文件|第三轮记录值|文件时间（本机）|
|---|---|---|
|`.runtime/logs/junit-summary.json`|230（0 失败）|2026-10-03 22:35:41|
|`.runtime/logs/feature-test.json`|164（0 失败）|2026-10-03 22:38:12|
|`.runtime/logs/browser-check.json`|44（0 失败）|2026-10-03 22:38:54|
|`.runtime/logs/sidebar-resize-check.json`|28（0 失败）|2026-10-03 22:39:10|

下文各节按修订标注：`## Java 单元测试（第四轮实测）` 与浏览器一节给出第四轮当前结果，第一至三轮的明细保留在各自的历史小节里作对照。

## 第四轮（R4）说明（历史记录）：重修语义

> 本节是第四轮冻结修订上的快照；当前结果见上一节第五轮。

第四轮改的是「重修」的语义与数据：重修不体现在课程名上（同一课程号在后续学年重新开设，课程名保持一致），并在学生端、教师端、教务端下发重修状态（`retake`/`retakeLabel`/`retakeCount`），同时给演示数据补上挂科后重修的学生与启动自检。

### 结果

|验证项|结果|证据文件|文件时间（本机）|
|---|---|---|---|
|Java 单元测试 `mvn -o clean package`|**243 / 0 失败**|`.runtime/logs/junit-summary.json`|2026-10-03 23:28:12|
|端到端功能测试 `node scripts/feature-test.mjs`|**183 / 183 通过**|`.runtime/logs/feature-test.json`|2026-10-03 23:30:37|
|浏览器验证 `node scripts/browser-check.mjs`|**50 / 50 通过**|`.runtime/logs/browser-check.json`|2026-10-03 23:31:12|
|侧栏拖动专项 `node scripts/verify-sidebar-resize.mjs`|**28 / 28 通过**|`.runtime/logs/sidebar-resize-check.json`|2026-10-03 23:31:28|
|生成式文档 `node scripts/generate-docs.mjs --check`|通过，**61 个 Java 命名类型**|—|—|

### Java 单元测试（243 条）

逐模块与逐测试类的明细、以及相对第三轮的增量见下文「Java 单元测试（第四轮实测）」一节；本轮净增 13 条，全部是重修判定相关的新用例（`SelectionServiceTest` +9、`CourseServiceTest` +4）。

### 端到端功能测试（183/183，新增 19 条）

第四轮脚本从 164 条扩到 183 条（+19）：新增 `[8b]` 重修语义段 15 条，并在 `[1]`/`[2]` 组织与初始化数据段补充若干条。核心断言：

- 初始化数据里 `CS102` 有多个学期的教学班，且**同一课程代码在不同学年使用完全相同的课程名**；
- 初始化数据的课程名**不含「重修」字样**；
- 学生端 `/selections/my` 把重修的课标记为 `retake=true` + `retakeLabel="重修"`，非重修课程不被误标；
- 教师端 `/roster` 名单标出重修学生，且**重修教学班名单里的学生全是重修生**；
- 重修判定依据是「更早学期已提交且不及格」的成绩；当前重修教学班成绩未提交，供教师现场录入。

### 浏览器验证（50/50，新增 6 条）

第四轮脚本从 44 条扩到 50 条（+6），新增断言：

- 学生端显示重修徽标；
- 课程名不含中文括号的「重修」后缀；
- 重修徽标出现在「程序设计基础（CS102）」那一行；
- 教师课程下拉能找到 2026-1 的重修班；
- 教师端名单标出重修学生；
- 教师端课程名同样无重修后缀。

### 演示数据与自检（启动日志原文）

> 下面是**第四轮冻结修订**上的启动日志快照；第五轮补充了学业预警样本、第六轮又重建了初始化数据，因此学院/班级/账号/教学班/选课/成绩条数已多次变化——**当前值见上文「第六轮（R6）说明」**（205 账号 / 187 学生 / 1354 选课 / 1354 成绩），本节保留原文仅作历史对照。

```text
[DemoInitializer] 数据已写入：4 个学院、8 个专业、16 个班级、65 个账号、35 个教学班、107 条选课、78 条成绩、1 个选课批次。
[DemoInitializer] 成绩单自检通过：5 名重修学生（3 个课程代码）、48 名学生，无「已通过重选」的跨学期重复课程代码。
[DemoInitializer] 账本已按新数据库重建并锚定 78 条成绩事件（课程 35 门）。
```

5 名重修学生：

|学号|姓名|课程代码|挂科学期与有效分|重修学期与结果|
|---|---|---|---|---|
|20231530|林知夏|CS102|2023-1，52 分|2024-1 重修，60 分通过|
|20231536|赵一诺|MG101|（同型）|2024-1 重修通过|
|20231542|崔明轩|CE101|（同型）|2024-1 重修通过|
|20241531|冯亦舟|CS102|2024-1，52 分|**2026-1 重修中，成绩未提交**|
|20241532|邓墨白|CS102|2024-1，52 分|**2026-1 重修中，成绩未提交**|

两名在读重修学生位于只接收重修学生的教学班 `c35-cs102c`（教师 `t1102`），教师登录即可在名单里看到他们并现场录入成绩。

### 第四轮修复的问题

`Models.total` 原先对**系数表缺项**会执行 `number(null)` 抛 NPE，而重修判定的两个入口（`SelectionService.failedCodes` 与 `CourseService.failedCodesBefore`）都 `catch (RuntimeException ignored)` 静默吞掉异常，后果是：课程行 `weights` 不全时，挂科学生**不会被判定为重修**（教师端名单与学生端徽标同时失效），且没有任何日志。现在改为「缺失系数按 0 权重处理」——缺项只是不参与计算，不会把整门课变成「无成绩」；`ModelsTest` 相应补充了用例。该修复消除了一个会影响重修判定的静默失败点，已记入 [decisions.md](decisions.md)。

## 第五轮（R5）说明：学业记录重修状态 + 学业预警可用

第五轮针对两条用户反馈：① 学生端「我的成绩」看不到某门课是否重修；② 学业预警一直提示「至少需要 3 年、24 条完整历史成绩，当前数据不足，未生成预测」。改动包括：`/transcript` 改为学期倒序并附 `retake`/`retakeLabel`；学业记录的已获课程/已获学分/未通过课程三个统计按课程代码去重；修正 `AnalyticsService.predict` 预测对象筛选条件写反的缺陷；为预测补历史样本层与缓考样本池，并把启动自检升级为 `verifyPredictionCoverage()`（逐门校验覆盖）。

### 结果

|验证项|结果|证据文件|文件时间（本机）|
|---|---|---|---|
|Java 单元测试 `mvn -o clean package`|**248 / 0 失败**|`.runtime/logs/junit-summary.json`|2026-10-03 00:37:16|
|端到端功能测试 `node scripts/feature-test.mjs`|**201 / 201 通过**|`.runtime/logs/feature-test.json`|2026-10-03 00:35:09|
|浏览器验证 `node scripts/browser-check.mjs`|**57 / 57 通过**|`.runtime/logs/browser-check.json`|2026-10-03 00:36:25|
|侧栏拖动专项 `node scripts/verify-sidebar-resize.mjs`|**28 / 28 通过**（第四轮记录，本轮未重跑）|`.runtime/logs/sidebar-resize-check.json`|2026-10-03 23:31:28|
|生成式文档 `node scripts/generate-docs.mjs --check`|通过，**63 个 Java 命名类型**（第四轮为 61）|—|—|

### Java 单元测试（248 条）

相对第四轮（243 条）净增 **5 条**，全部是本轮两条反馈相关的新用例；逐模块与逐类明细见下文「Java 单元测试（第五轮实测）」一节。

|测试类|第四轮|第五轮|增量|
|---|---:|---:|---:|
|`GradeServiceTest`|11|14|+3（学业记录重修标注、单次修读不标重修、学期倒序）|
|`AnalyticsRulesTest`|4|6|+2（预测返回缺期末的学生、历史不足 422）|
|其余类|228|228|—|
|合计|243|248|+5|

### 端到端功能测试（201/201，新增 18 条）

脚本从 183 条扩到 201 条（+18）：新增 `[8c]`「我的成绩：学业记录标注重修状态」6 条与 `[8d]`「学业预警：学生与教师的课程都能预测」12 条。核心断言：

- 学业记录里同一课程代码出现在两个学期；**重修那条带 `retake=true` 与 `retakeLabel="重修"`**，第一次修读不标重修；
- 两行课程名一致、**不含任何重修后缀**；返回顺序为**学期倒序**；只修读过一次的课程不被误标；
- 学生**逐门课**调用 `/predict` 全部成功（不再提示「数据不足」）：当前学期课程返回 ≥3 个训练年份、≥24 条样本、≥1 行本人预测且字段完整；
- 教师**逐门课**调用 `/predict` 全部成功。

### 浏览器验证（57/57，新增 7 条）

脚本从 50 条扩到 57 条（+7），第 49–55 条为本轮新增：

|#|第五轮新增断言|结果|
|---:|---|---|
|49|「我的成绩」显示重修徽标|PASS|
|50|「我的成绩」里 CS102 的两行课程名一致且不含中文括号的「重修」后缀|PASS|
|51|「我的成绩」有 2 行程序设计基础（挂科 + 重修）|PASS|
|52|学业预警页有生成预测按钮|PASS|
|53|学业预警不再提示「数据不足」|PASS|
|54|学业预警给出训练年份与样本数|PASS|
|55|学业预警渲染出预测结果行|PASS|

（第 56、57 条仍是「无未捕获页面错误」「无致命控制台错误」。）

### 演示数据与自检（第五轮启动日志，历史记录）

> 下面是**第五轮**的快照；第六轮重建数据后规模已变（205 账号 / 187 学生 / 1354 选课 / 1354 成绩，样本班每班 8 条选课），当前值见上文「第六轮（R6）说明」。

```text
[DemoInitializer] 数据已写入：4 个学院、8 个专业、21 个班级、206 个账号、155 个教学班、626 条选课、1445 条成绩、1 个选课批次。
[DemoInitializer] 成绩单自检通过：5 名重修学生（3 个课程代码）、153 名学生，无「已通过重选」的跨学期重复课程代码。
[DemoInitializer] 学业预警覆盖自检通过：64 门有选课的课程全部可预测。
```

- **账号构成（第五轮）**：3 名教务管理员 + 11 名教师 + 192 名学生（189 名已分班 + 3 名待分班）= 206。
- **教学班构成**：155 = 64 个正课 + 91 个历史样本教学班（2020-1 至 2026-1）。
- **成绩构成（第五轮）**：1445 = 正课成绩 + 历史样本成绩 819 条（91 个样本班 × 9 人）+ 缓考样本的暂存成绩。

### 第五轮的两处数据设计（其中第 1 条已被第六轮修正）

1. ~~历史样本层只登记成绩、不建选课记录~~（**已在第六轮推翻**）：第五轮为了避免「样本班有选课就自己需要 3 个更早年样本」，把 `SAMPLE_COURSES`（2020-1 / 2021-1 / 2022-1 / 2023-2 共 91 个）做成只登记成绩、不建选课记录，任课教师写成非真实账号。第六轮确认该顾虑不成立——这些期次本身就是最早的，没有更早学期可查——因此现在**每个样本班都有 8 条 `ACTIVE` 选课 + 8 条成绩**，任课教师换成 4 个 `enabled=0` 不可登录的史料教师账号（`ht2020`/`ht2021`/`ht2022`/`ht2023`），样本学生的成绩会进入他们自己的学业记录。详见上文「第六轮（R6）说明 → 历史样本教学班为什么可以有名册」。
2. **缓考样本池保证每门课都有预测对象**：为每门已提交成绩的正课补 1 名「有平时与实验、缺期末」的学生（暂存 `DRAFT` 成绩），这样每门课打开学业预警都能看到预测行。`verifyPredictionCoverage()` 校验正课的覆盖情况（「≥3 个更早年份 + ≥24 条三分项齐全的已提交成绩 + 本班至少 1 人缺期末」，遍历时排除历史样本教学班），不满足即中止启动；当前启动日志显示 64 门有选课的正课全部可预测。

### 已知边界：预测只对进行中的课程有意义

学生对本人的**已出分历史课程**调用 `/predict` 会返回 200，但 `results` 是**空数组**——因为预测对象是「已有平时与实验、期末还没考」的学生，而他本人那门课的期末早已录入。这不是缺陷，而是接口语义的必然结果（训练样本与预测对象是两个集合）。前端为此给出明确提示：学业预警页顶部说明「学业预警针对**正在进行中**的课程：已录入平时与实验、期末尚未考试时，可以预估期末与总评成绩。请在上方课程选择里选一门当前学期（2026-1）的课程」；选中已出分课程时显示「本学期的期末成绩已经录入，预测对象为空……请在课程选择里选一门当前学期（2026-1）的课程」。因此**预测只对进行中的课程有意义**。

## 第六轮（R6）说明：初始化数据重建、启动清理与性能优化

第六轮按用户要求**重建初始化数据**（清除测试课程残留、给历史样本教学班补名单、缩小数据量），并顺带做了 5 处读写路径优化（其中一处是测试发现的真实完整性缺口）。

### 结果

|验证项|结果|证据文件|文件时间|
|---|---|---|---|
|Java 单元测试 `mvn -o clean package`|**250 / 0 失败**|`.runtime/logs/junit-summary.json`|2026-10-04 09:47:57|
|端到端功能测试 `node scripts/feature-test.mjs`|**201 / 201 通过**（数据重建后重跑）|`.runtime/logs/feature-test.json`|2026-10-04 13:25:30|
|浏览器验证 `node scripts/browser-check.mjs`|**57 / 57 通过**（数据重建后重跑）|`.runtime/logs/browser-check.json`|2026-10-04 13:26:43|
|侧栏拖动专项|**28 / 28 通过**（第四轮记录，未重跑）|`.runtime/logs/sidebar-resize-check.json`|2026-10-03 15:31:28|
|生成式文档 `node scripts/generate-docs.mjs --check`|通过，**64 个 Java 命名类型**|—|—|

单测从第五轮的 248 增加到 **250**：`Crypto.deriveKey` 的派生密钥缓存新增 2 条用例（`CryptoTest` 4 → 6），因此 common 模块 13 → 15；business-service 仍 195、data-service 24、gateway 10、audit-service 6。端到端（201）与浏览器（57）的断言数量不变，只是在重建后的数据上重跑通过——**数据规模变化不影响断言数量**，因为在断言里写死条数的地方都改成了按接口返回值判断。

### 数据重建后的实际规模（启动日志原文）

```text
[DemoInitializer] 数据已写入：4 个学院、8 个专业、21 个班级、205 个账号、155 个教学班、1354 条选课、1354 条成绩、1 个选课批次。
[DemoInitializer] 成绩单自检通过：5 名重修学生（3 个课程代码）、184 名学生，无「已通过重选」的跨学期重复课程代码。
[DemoInitializer] 学业预警覆盖自检通过：64 门有选课的正课全部可预测。
```

- **账号 205** = 3 名教务管理员 + 15 名教师（11 名演示教师 + 4 名不可登录的史料教师 `ht2020`/`ht2021`/`ht2022`/`ht2023`）+ 187 名学生（144 名正课学生 + 40 名历史样本学生 + 3 名待分班）。
- **教学班 155** = 64 个正课 + 91 个历史样本教学班（2020-1 / 2021-1 / 2022-1 / 2023-2，任课教师分别是上面 4 个史料教师账号，`enabled=0` 不可登录）。
- **选课 1354 = 成绩 1354**：历史样本教学班现在**每班 8 条 `ACTIVE` 选课 + 8 条成绩**（共 728 条），与「有成绩的人恰好就是选了这门课的人」一致；第五轮那版「样本班只登记成绩、不建选课记录」（91×9 = 819 条）已作废。
- 与第五轮对比：账号 206 → **205**、学生 192 → **187**、选课 626 → **1354**、成绩 1445 → **1354**；教学班 155 不变。

### 历史样本教学班为什么可以有名册

第五轮采用「样本班只登记成绩、不建选课记录」，理由是怕样本班自己又变成「有学生选课、需要可预测」的课程而形成无限回归。重建时确认这个顾虑**不成立**：2020-1 / 2021-1 / 2022-1 / 2023-2 本身就是数据里最早的期次，**没有更早学期可查**，因此它们永远不会成为需要自己 3 个更早年样本的课程。因此现在每个样本班都有完整名册与成绩，`verifyPredictionCoverage()` 也据此收敛为**只校验 64 门正课**（遍历时显式排除 `SAMPLE_COURSE_IDS`）。样本学生的成绩会进入他们自己的学业记录，这与真实学生一致；样本班处在最早期次，不会产生「跨学期重复修读」或重修误判。

### 启动即清理测试残留

端到端脚本会在 `2027-2` 这类一次性学期里新建教学班与选课批次，而系统没有课程/批次删除接口。第六轮新增 `DemoInitializer.purgeTestArtifacts()`，在**每次启动、早于数据写入**执行：按「名称含『测试』或『低人数』」＋「学期/批次不在演示数据声明的集合内」清除测试课程与测试批次，并按 `course_id` 级联删除选课、成绩、选课流水与分析；没有残留时打印「未发现测试课程/测试选课批次残留」。

### 5 处读写路径优化

|优化|效果|
|---|---|
|`Crypto.deriveKey` 派生密钥缓存|千级成绩解密从约 515ms 降到热态约 50ms；缓存按 `(password, salt)`、上限 4096、超限清空，不放宽安全边界（salt 在密文里、加密用随机盐永不命中）|
|`LedgerService` 两级校验缓存|`localVerified`/`fullVerified` 按「文件大小 + mtime」失效并在所有写入路径显式 `invalidate()`；`/audit` 只走本地校验层|
|账本尾部截断检测 `requireNotTruncated()`|补上一个真实缺口：`verifyLocal()` 只校验区块间链接，**尾部截断不破坏哈希链**，删掉最后 N 块链仍自洽、读取会静默少返回 N 条；现在用 `anchors.json` 的区块数要求「本地账本不得比链上锚点更短」（409），只查「更短」以兼容「已落盘未锚定」的中间态|
|`/integrity` 批量比对|从「逐条 `repo.find`」（1445 条账本约 2890 次 RPC 往返）改为一次取回全部成绩在内存比对；密文篡改导致批量读取失败时退化为 `verifyRowByRow()` 逐条定位，语义不变|
|`/roster`、`/courses` 消除 N+1|重修判定改为课程/选课/成绩各取一次后在内存 join；组织名称用 `OrganizationService.namesInto()` 一次取回整表复用|

两个默认关闭的排障开关：`-Dcampus.trace.repo=true`（打印每次 `repo.find` 耗时）与 `-Dcampus.trace.sql=true`（打印数据侧每次查询/解密耗时）。细节见 [decisions.md](decisions.md)、[security.md](security.md) 与 [configuration.md](configuration.md)。

## Java 单元测试（第六轮实测）

命令：`mvn -o clean package` → **BUILD SUCCESS，250 条测试、0 失败 0 错误 0 跳过**。逐模块统计（由各模块 `target/surefire-reports/TEST-*.xml` 汇总为 `.runtime/logs/junit-summary.json`，脚本 `scripts/junit-summary.mjs`，`generatedAt = 2026-10-04T09:47:57.861Z`）：

|模块|用例数|失败|按测试类的明细|
|---|---:|---:|---|
|common|15|0|`CryptoTest` 6、`ProtocolTest` 5、`SettingsTest` 4|
|gateway|10|0|`RegistryControllerTest` 10|
|data-service|24|0|`SchemaCatalogTest` 8、`DataRpcControllerTest` 10、`SqlCompilerTest` 6|
|business-service|195|0|`SelectionServiceTest` 48、`OrganizeRoutesTest` 36、`CourseServiceTest` 34、`OrganizationServiceTest` 23、`GradeServiceTest` 14、`ModelsTest` 12、`AdminServiceTest` 8、`AuthServiceTest` 8、`GradeRulesTest` 6、`AnalyticsRulesTest` 6|
|audit-service|6|0|`AuditControllerTest` 3、`LedgerServiceTest` 3|
|**合计**|**250**|**0**|15 + 10 + 24 + 195 + 6 = 250；business-service 的 195 与其测试类明细（48+36+34+23+14+12+8+8+6+6）一致|

第六轮相对第五轮（248 条）净增 **2 条**，来自本轮的性能优化：

|测试类|第五轮|第六轮|增量|新增用例覆盖|
|---|---:|---:|---:|---|
|`CryptoTest`|4|6|+2|`Crypto.deriveKey` 派生密钥缓存的命中与上限清空（同一 `(password, salt)` 复用、超限整体清空后仍能正确派生）|
|其余类|244|244|—|—|
|合计|248|250|+2|—|

全部用例均不需要外部服务即可运行，属于纯单元/规则级验证；跨服务的端到端行为见下文 feature 与 browser 两层。

### 第五轮用例变化（历史记录）

第五轮相对第四轮（243 条）净增 **5 条**，全部是第五轮两条反馈相关的新用例：

|测试类|第四轮|第五轮|增量|新增用例覆盖|
|---|---:|---:|---:|---|
|`GradeServiceTest`|11|14|+3|学业记录的重修标注、只修读一次不标重修、返回顺序为学期倒序|
|`AnalyticsRulesTest`|4|6|+2|`/predict` 返回「缺期末」的学生、历史不足时 422|
|其余类|228|228|—|—|
|合计|243|248|+5|—|

### 第四轮用例变化（历史记录）

第四轮相对第三轮（230 条）净增 **13 条**，全部是重修判定相关的新用例：

|测试类|第三轮|第四轮|增量|
|---|---:|---:|---:|
|`SelectionServiceTest`|39|48|+9|
|`CourseServiceTest`|30|34|+4|
|其余类|161|161|—|
|合计|230|243|+13|

新增用例覆盖：`/selections/my` 与选课台的 `retake`/`retakeLabel` 标注（含「未出成绩不算重修」「已退课不算」的负例）、教务批次课程的 `retakeCount`、教师名单的 `retake` 标记、重修判定只取「更早学期 + 同一课程代码 + 已提交 + 有效分 < 60」，以及系数表缺项时 `Models.total` 不再抛异常（见上文「第四轮修复的问题」）。

### 第三轮用例变化（历史记录）

第三轮相对第二轮（234 条）**净减 4 条**，变化只落在两个直接相关的测试类：

|测试类|第一轮|第二轮|第三轮|第三轮变化|
|---|---:|---:|---:|---|
|`OrganizationServiceTest`|21|28|23|删掉 7 条「两位显示编号」用例（`nextCode*` 与 `codeOf*`），新增 2 条「主键编号」用例（`nextIdFollowsDemoDataShape`、`nextIdNeverCollidesWithExistingIds`）|
|`OrganizeRoutesTest`|31|35|36|新增 `listItemsDoNotExposeCodeKey`、`optionsItemsDoNotExposeCodeKey` 等「响应不含 `code` 键」用例，替换掉旧的显示编号断言|
|`CourseServiceTest`|12|30|30|不变|
|`SchemaCatalogTest`|7|8|8|不变|
|合计|71|101|97|净减 4 条（234 → 230）|

第二轮相对第一轮曾净增 30 条（71 → 101）；上表把三轮的数字并列，第三轮删掉 7 条、新增 3 条，因此当轮总数回到 230（第四轮再在此基础上净增 13 条到 243）。

### 第二轮用例增量（历史记录）

|测试类|第一轮|第二轮|增量|第二轮相关用例覆盖|
|---|---:|---:|---:|---|
|`OrganizationServiceTest`|21|28|+7|（这批用例已在第三轮随 `nextCode` 删除）`nextCodeStartsAt01AndIncrementsFromMax`、`nextCodeSkipsOccupiedSlotInsteadOfOverwriting`、`nextCodeIgnoresNonTwoDigitHistoricalCodes`、`nextCodeRejectsExhaustedLevel`、`nextCodeRejectsWhenLastSlotIsTaken`、`saveIgnoresBodyCodeAndNeverFailsOnIt`、`codeOfReturnsStoredCodeOrEmptyString`|
|`OrganizeRoutesTest`|31|35|+4|（第三轮已改为「不含 `code` 键」用例）`listAndOptionsExposeTwoDigitCodeWithoutCounselor`、`listWithoutStoredCodeOutputsEmptyString`、`saveCreatesCollegeWithGeneratedIdAndCode`、`saveUpdateIgnoresBodyCodeAndCounselor`；另调整 `assignRejectsAdminAccountInEitherList`（管理员不能被列入学生或教师名单）|
|`CourseServiceTest`|12|30|+18|`batchByCourse*` 15 条（按 `studentIds` 选课并跳过已选、按班级名称整班选课、名单与班级并集去重、两者都不给 400、课程/班级不存在、退课时有成绩的学生进 `failed` 其余成功、退课后重选复用 `DROPPED` 行走 `UPDATE`、超过 300 人 400、`publishId` 自动解析 / 回退 `CLOSED` / 显式指定 / 无匹配时留空、`GRADE_ADMIN` 权限、非学生与未知学生、退课允许停用学生）、`enrollWritesAdminAssignRecordAndSelectedAt`、`enrollRemoveWritesAdminRemoveRecord`、`coreRoutesForwardsEnrollmentBatchToSelectionService`|
|`SchemaCatalogTest`|7|8|+1|结构版本过期触发整库重建，以及补列迁移路径的回归用例|
|合计|71|101|+30|—|

## 第七轮（R7）说明：成绩录入辅助（本地 OCR + 语音录入）

第七轮新增两个**纯前端**录入辅助能力（设计见 [ocr-voice-design.md](ocr-voice-design.md)）：成绩单图片识别（`frontend/src/ocr.js` + `components/RecognizePreview.vue`）与语音录入（`frontend/src/voice.js` + `components/VoicePanel.vue`）。本轮**不新增后端接口、不新增权限点**，因此 Java 单元测试无需改动；新增两层前端测试，并在同一批改动后重跑了既有的两层前端回归。

|验证项|结果|证据文件|文件时间（UTC）|
|---|---|---|---|
|OCR/语音纯函数单元 `node scripts/ocr-voice-unit.mjs`|**76 / 76 通过**（本轮移除语音口令切行："行切换口令""混合语句剥离"两节删除）|`.runtime/logs/ocr-voice-unit.json`|2026-10-05 14:48:56|
|OCR/语音浏览器端到端 `node scripts/ocr-voice-check.mjs`|**40 / 40 通过**（本轮删除语音口令切行断言，保留 4 条可视化切行断言：下拉框列出全部 / 下拉框选中 / 上一行 / 下一行）|`.runtime/logs/ocr-voice-check.json`|2026-10-05 14:50:14|
|既有端到端 `node scripts/feature-test.mjs`|**201 / 201 通过**（`12:53:45` 的记录，**演示数据重建前**；本轮未复跑，原因见下）|`.runtime/logs/feature-test.json`|2026-10-05 12:53:45|
|既有浏览器 `node scripts/browser-check.mjs`（在**重建后的演示数据**上按本轮复跑）|**57 / 57 通过**|`.runtime/logs/browser-check.json`|2026-10-05 14:51:13|
|固定测试图集 `node scripts/generate-ocr-fixtures.mjs`|**10 张 PNG** + 标注（比上一批多一张 `skew-4deg`）|`test-results/ocr/manifest.json`|2026-10-05 11:55:17|

三层测试的分工：

|层|测什么|为什么不放在别的层|
|---|---|---|
|单元（76 条）|中文数字文法、口语解析与裸数字补位、学号混淆纠正与编辑距离、**学号安全匹配（不可解释的数字位差异不自动认人、无法确认的学号不写入任何学生）**、行带切分、列锚点与列分配、结构化与统计（**不含导航口令**——语音口令切行已移除）|`voice.js` 的解析是纯函数；`ocr.js` 的纯函数部分不碰 DOM。放在 Node 里跑最快、最细，且能在没有浏览器与后端时回归|
|浏览器端到端（40 条）|真实 Edge + 真实识别管线 + 真实页面数据：`[2]` 段用**固定图集**逐张统计字段级准确率；`[3]`/`[4]`/`[5]` 段用 `renderSheet(...)` **按当前课程真实学生动态渲染**的成绩单验证预览「未确认不入表单」、坏行不阻塞、语音面板与 `SpeechRecognition` 桩、**切换录入对象的可视化路径（下拉框 / 上一行 / 下一行）**、图片不上行|只有真浏览器里才会同时出现 Canvas、tesseract worker、Vue 响应式与网络请求，界面契约（`data-testid` 与统计数字）也只能在这里断言|
|既有回归（201 + 57）|组织、选课、成绩、审计、打印等全部既有功能|证明"新增两个纯前端能力"没有破坏既有链路。**其中浏览器层是在演示数据重建之后复跑的 57 / 57**；`feature-test` 本轮未复跑（它会再次在 `2027-2` 留下测试课程与测试批次），因此引用 201 / 201 时必须带上 `2026-10-05 12:53:45` 与"重建前"这两个限定|

**两层对"图"的分工必须说清楚**：**准确率用固定图集**（期望值冻结在 `test-results/ocr/manifest.json` 里，逐格比对才有意义；该段的名册用图集里出现过的 8 位学号临时构造，不绑定演示账号）；**交互断言用动态渲染图**（`ocr-voice-check.mjs` 的 `renderSheet(rows)` 在浏览器里用当前课程真实学生渲染成绩单再截图，因为固定图集的演示学号与课程名册不一定重合，用固定图做交互断言会因为"匹配不上名册"而假失败）。

本轮**实测的准确率结论**（明细与逐图数据见 [ocr-voice-design.md](ocr-voice-design.md) 第九节，证据文件 `ocr-voice-check.json` 的 `accuracy[]`，`generatedAt = 2026-10-05T14:50:14.835Z`）：

|图集类别|字段级准确率|备注|
|---|---|---|
|清晰打印体（`clean-2col`/`3col`/`6col` 合并）|**100%**（30/30）|单图 927–1406 ms，`variants = 1`（第一轮第一候选就收工，均为 `gray`）；行匹配 9/9|
|阴影 + 噪点（`shadow-noise`）|**100%**（12/12）|1294 ms；最优候选是 `gray`，且 `variants = 1` 说明自适应阈值候选**根本没被跑到**——结论边界见设计文档 9.4 第 4 点|
|脏数据（`dirty-missing-and-range` / `dirty-unknown-student` / `dirty-blank-row`）|**100%**（9/9 × 3）|缺列、超界 120、名册外学号、整行空分数都按设计留空并给出问题提示；`dirty-missing-and-range` 耗时 29956 ms、`variants = 16`（"完整"条件永远不成立，尺度回退与角度微调被反复试到穷尽）|
|学号混淆（`confusable-id`）|66.7%（6/9）|962 ms；学号 `2023153O` 被纠正并匹配（行匹配 3/3，断言「学号混淆（0→O）能被纠正并匹配」实测 3/3）；丢的 3 格来自该行本身没读出的分数列|
|倾斜 2°（`skew-2deg`）|**100%**（12/12）|2499 ms；投影法只估到 `skew = 0.41`，`estimateTiltFromRows` 用 tesseract baseline 量出残余倾斜后清空重跑一轮（自校正），行匹配 4/4；本轮最优变体是 **`gray-raw`（不纠偏的原始图）**|
|倾斜 4°（`skew-4deg`）|**91.7%**（11/12）|16780 ms、`variants = 13`；行匹配 4/4，`missed` 只有一条「`20231530#2: 期望 87 实际 37`」（个位残片）。**上一轮同一图集为 75%（9/12）、最优变体是 `gray-raw`，本轮最优变体是 `gray`**——两次运行的最优变体不同，说明"纠偏 vs 不纠偏"没有恒定赢家，引用这条数字要带上 `variants` 与最优变体|

隐私断言：`ocr-voice-check.mjs` 统计「POST 且请求体含 PNG 字节」的请求数，实测为 **0**，即整轮识别没有任何图片上行请求。语音通道是唯一的数据出站口（浏览器厂商的 `SpeechRecognition` 可能上行音频），界面要求教师**逐次点击「开始识别」**才开麦；**面板里那条固定隐私提示已按用户要求删除**，因此界面上不再有相关文案（事实记录见 [ocr-voice-design.md](ocr-voice-design.md) 2 节与 7.4 节）。

### 演示数据恢复（本轮：显式重建开关 `-Dcampus.reset-db=true`）

用户要求把演示数据恢复到初始状态。做法是**停掉 data-service，再用显式重建开关重启**（等价于 `scripts/start.ps1 -ResetDb`，脚本里 `if ($ResetDb) { $arguments += '-Dcampus.reset-db=true' }`）：`DemoInitializer` 先执行 `purgeTestArtifacts()` 清掉测试残留，再 `clearAll()` 删掉全部业务表并整库重建，最后跑三项自检。启动日志原文（本轮实测，五条）：

```text
[DemoInitializer] 已清除 20 门测试课程、48 条选课、0 条成绩、0 个测试选课批次（另有 96 条选课流水）。
[DemoInitializer] 收到显式重建开关，删除全部业务表并重新灌入数据。
[DemoInitializer] 数据已写入：4 个学院、8 个专业、21 个班级、205 个账号、155 个教学班、1354 条选课、1354 条成绩、1 个选课批次。
[DemoInitializer] 成绩单自检通过：5 名重修学生（3 个课程代码）、184 名学生，无「已通过重选」的跨学期重复课程代码。
[DemoInitializer] 学业预警覆盖自检通过：64 门有选课的正课全部可预测。
```

重建后复核：教师 `t1101` 的课程列表回到 **7 门**——`2026-1 CS301 网络软件与安全`、`2025-1 CS401 软件工程`、`2025-1 CS301c`、`2024-1 CS201 数据结构`、`2024-1 CS301b`、`2023-1 CS101 计算机导论`、`2023-1 CS301a`，**不再有 `2027-2` 的测试课程**。（这 7 门与源码里 `DemoInitializer.COURSES` 中 `teacher_id = "t1101"` 的条目一一对应：`c24-cs301` / `c19-cs401a` / `c38-cs301c` / `c10-cs201` / `c37-cs301b` / `c1-cs101` / `c36-cs301a`。）规模与第六轮的 205 账号 / 155 教学班 / 1354 选课 / 1354 成绩一致（见上文「数据重建后的实际规模」），因此这不是"换了一套数据"，只是把测试残留清掉后按同一套种子重建。

> **跑完测试后请重启一次服务**：`scripts/feature-test.mjs` 每次运行都会在测试学期 `2027-2` 新建教学班与选课批次，而系统没有课程/批次的删除接口。**不需要 `-ResetDb`**——只要用 `scripts/start.ps1` 重启一次，`DemoInitializer.purgeTestArtifacts()` 就会在灌数前把它们连同选课、成绩、选课流水与分析一起清掉（日志形如上面第一条）。这也正是本轮 `feature-test.json` 停在 `12:53:45`（重建前）而没有复跑的原因：复跑会再次留下测试课程。详见 [演示账号与场景指南.md](演示账号与场景指南.md) 的「两个必须知道的行为」。

## 第八轮（R8）说明：整库加密与动态密钥

第八轮改的是**凭据与数据库文件**这一层：删除全部硬编码密钥，改为启动时动态生成并注入；数据库开启 H2 整库加密（`CIPHER=AES`）。**本轮不新增、不修改任何 HTTP 接口**，业务规则、权限模型与前端交互都没有变化，因此测试的重点是「配置与存储层的契约」加上「既有四层回归没有被打坏」。

### 8.1 结果

|验证项|结果|证据|时间（本机 / UTC）|
|---|---|---|---|
|Java 单元测试 `mvn -o test`|**258 / 0 失败**（6 个模块）|各模块 `target/surefire-reports/TEST-*.xml`|2026-10-06 00:25（`0:25:09`–`0:25:14`）|
|端到端功能 `node scripts/feature-test.mjs`|**201 / 201 通过**|`.runtime/logs/feature-test.json`|2026-10-06 00:21:41 / `16:21:41.662Z`|
|既有浏览器 `node scripts/browser-check.mjs`|**57 / 57 通过**|`.runtime/logs/browser-check.json`|2026-10-06 00:21:09 / `16:21:09.371Z`|
|OCR/语音纯函数单元 `node scripts/ocr-voice-unit.mjs`|**76 / 76 通过**|`.runtime/logs/ocr-voice-unit.json`|2026-10-06 00:18:50 / `16:18:50.973Z`|
|OCR/语音浏览器端到端 `node scripts/ocr-voice-check.mjs`|**40 / 40 通过**|`.runtime/logs/ocr-voice-check.json`|2026-10-06 00:20:11 / `16:20:11.747Z`|
|侧栏拖动专项 `node scripts/verify-sidebar-resize.mjs`|28 / 28（沿用第四轮记录，本轮未重跑）|`.runtime/logs/sidebar-resize-check.json`|2026-10-03 / `15:31:28.982Z`|

四层前端（76 / 40 / 57 / 201）与 Java 单元测试全部跑在**同一套整库加密的演示数据**上，顺序为 OCR/语音单元 → OCR/语音浏览器 → 既有浏览器 → 端到端功能，说明「数据库改成加密文件 + 密钥全部换成随机值」没有打破任何既有断言。

### 8.2 Java 单元测试（258 条，逐模块）

```text
C:\Users\AlexLiong\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd -o test
（JAVA_HOME=D:\JAVA\jdk-jb-17）
```

|模块|用例数|失败|按测试类的明细|
|---|---:|---:|---|
|common|**22**|0|`ConfigGuardTest` **7（本轮新增）**、`CryptoTest` 6、`ProtocolTest` 5、`SettingsTest` 4|
|gateway|10|0|`RegistryControllerTest` 10|
|data-service|**25**|0|`DataRpcControllerTest` 10、`SchemaCatalogTest` **9（本轮 +1）**、`SqlCompilerTest` 6|
|business-service|195|0|`SelectionServiceTest` 48、`OrganizeRoutesTest` 36、`CourseServiceTest` 34、`OrganizationServiceTest` 23、`GradeServiceTest` 14、`ModelsTest` 12、`AdminServiceTest` 8、`AuthServiceTest` 8、`GradeRulesTest` 6、`AnalyticsRulesTest` 6|
|audit-service|6|0|`AuditControllerTest` 3、`LedgerServiceTest` 3|
|**合计**|**258**|**0**|22 + 10 + 25 + 195 + 6 = 258|

本轮净增 **8 条**（250 → 258）：`ConfigGuardTest` +7、`SchemaCatalogTest` +1（`rebuildsWhenSecretsFingerprintChanges`），business/gateway/audit 三个模块的用例数不变——这正是「本轮没有触碰业务规则」的可验证证据。

`ConfigGuardTest` 的 7 条覆盖：

|用例|断言要点|
|---|---|
|`everyCredentialUsedByTheStackIsRequired`|密钥清单包含 9 项且数量恰为 9（增删密钥必须同步 `setup.mjs` 与启动脚本）|
|`generatedKeyIsStrongAndUnique`|随机密钥为 64 字符 `[0-9a-f]`（32 字节十六进制），连续 8 次不重复|
|`fingerprintIsStableAndSensitiveToEveryKey`|同一组密钥指纹稳定、长度 16；**任一**密钥变化都会改变指纹|
|`fingerprintDoesNotLeakKeyMaterial`|指纹串里不出现密钥内容|
|`productionRejectsPlaceholdersAndShortValues`|生产档判据：`KEY` / `passwd` / `campus-dev-tls-2024` / 长度不足 / `null` 全部拒绝，64 位随机值通过|
|`secretsFileLocationIsAbsoluteAndOverridable`|密钥文件路径为绝对路径、文件名以 `secrets.json` 结尾，`-Dcampus.secrets` 可覆盖|
|`databasePasswordFormatMatchesH2Contract`|两段式口令恰有一个空格；`describePassword()` 含「长度」且不含 32 位以上十六进制串（诊断不泄露口令）|

`SchemaCatalogTest` 本轮新增的 `rebuildsWhenSecretsFingerprintChanges` 断言：结构版本相同、只把 `schema_meta.version_value` 改成 `3:0000000000000000`（模拟换密钥）时，`wasRebuilt()` 为 true 且旧数据被清空；原有的「结构版本落后」用例也改为比对整串 `SCHEMA_VERSION + ":" + ConfigGuard.dataFingerprint()`。

### 8.3 加密实证（整库加密，H2 `CIPHER=AES`）

以下都在本机对 `.runtime/database/campus.mv.db` 实际执行：

|验证|命令 / 做法|结果|
|---|---|---|
|库文件头是密文标记|读取文件前 64 字节|`H2encrypt`（明文库为 `H:2,block:...`）|
|库里没有明文|按 ISO-8859-1 读全文件后检索 `password`、`$2a$`、`李老师`、`t1101`、`CS401`、`20241530`、`course_selections`、`schema_meta`、`users`、`grades`|**命中数全部为 0**（同一文件里 `H2encrypt` 命中 1 次，即文件头）|
|正确两段式口令可打开|用 `secrets.json` 的 `DB_CIPHER_KEY + " " + DB_PASSWORD` 连接|成功，`users` 表 205 行（与演示数据一致）|
|只给单段口令|只传用户口令|`Wrong password format, must be: file password <space> user password [90050-224]`|
|文件口令错|两段式的第一段换错|`Encryption error in file ... [90049-224]`|
|密钥文件形态|`node -e` 读取 `.runtime/secrets.json`|9 项、每项 64 字符、全部匹配 `[0-9a-f]{64}`|
|启动日志的口令形态|服务日志首行|`[ConfigGuard] 数据源口令来源：环境变量，长度 129，空格数 1` 与 `[DataSourceConfig] 数据库 jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE；会话口令：环境变量，长度 129，空格数 1`|

> 复核方式：`campus.mv.db` 在被运行中的 data-service 打开时会被加锁，此时无法用普通读打开——先停服务再读文件头/做明文检索。文件头与「明文检索 0 命中」这两条与环境无关，比库文件体积更可靠。

口径说明：这两条启动日志来自本机用 `scripts/start.ps1` 启动的四个服务（脚本同时注入环境变量与 `-D`，环境变量优先级更高，所以来源显示「环境变量」）；在 IDEA 里直接跑主类、不设任何环境变量时同样两行会显示「密钥文件（两段式）」。`长度 129` = 64 + 1 个空格 + 64，可作为「两段式有没有被拆开」的第一手判据。

**库文件体积**：本机同一个加密库在两次观测中为 **2,224,128 字节（约 2.1 MB，data-service 刚写完演示数据时）** 与 **1,118,208 字节（约 1.07 MB，H2 关闭/重新打开后自动整理过一次）**；同目录另有 `campus.trace.db`（H2 跟踪日志，与业务数据无关）。H2 会在打开/关闭时做有限的自动整理，因此**库文件大小会随打开次数波动，不能当作数据量或加密开销的指标**；整库加密也不承诺压缩体积（密文与明文同量级）。本文因此不引用任何单一「库文件应为 N KB」的结论；要复核加密是否生效，请用上表的**文件头与明文检索**两条判据。

### 8.4 负向验证（必须实际失败才算通过）

|场景|做法|预期与实测|
|---|---|---|
|生产档缺密钥拒绝启动|设 `CAMPUS_PROFILE=prod`（或 `-Dcampus.profile=prod`）并让部分密钥缺失|`ConfigGuard.load()` 抛 `配置校验未通过，拒绝启动（CAMPUS_PROFILE=prod）。` + 「缺少密钥：…」清单；进程在 `SpringApplication.run` 之前中止。单元层由 `productionRejectsPlaceholdersAndShortValues` 覆盖判据本身|
|生产档仍是占位值|把某项设成 `KEY` / `passwd` / `campus-dev-tls-2024`|同上，清单里的条目带「（长度 N）」|
|单段口令|只给 `DB_PASSWORD` 一段|H2 报 `Wrong password format, must be: file password <space> user password [90050-224]`|
|文件口令错|两段式的第一段错误|H2 报 `Encryption error in file ... [90049-224]`|
|chain-worker 缺密钥|不设 `LEDGER_KEY` 且没有 `secrets.json`|`[chain-worker] 缺少可用密钥 LEDGER_KEY：请先运行 node scripts/setup.mjs 生成 .runtime/secrets.json…` 后 `process.exit(1)`|
|明文库迁移|把旧的未加密 `campus.mv.db` 放回目录后启动|`[SchemaCatalog] 检测到未加密的 H2 明文库，已删除并改为加密库重建：…` + `[DatabaseBootstrap] 明文库已清理，将以加密库重新初始化演示数据。`|

### 8.5 密钥轮换的后果（实测路径，需谨慎执行）

任何一项密钥变化都会让 `schema_meta.version_value` 里的「结构版本:密钥指纹」不匹配，启动即整库重建（`SchemaCatalogTest.rebuildsWhenSecretsFingerprintChanges` 覆盖）。`node scripts/setup.mjs --reset` 会重新生成全部 9 项密钥与 TLS 证书：

```text
[SchemaCatalog] 结构版本 3:1a2b3c4d5e6f7788 与目标 3（密钥指纹 9f8e7d6c5b4a3210）不一致：删除全部业务表后重建（原有数据：有，将被清空）。
[DemoInitializer] 收到显式重建开关，删除全部业务表并重新灌入数据。
```

因此**轮换前必须备份 `.runtime/database/`**，并接受「旧成绩密文、审计发件箱与账本区块不可解密」这一事实；轮换的完整处置见 [配置说明 3.3.5](configuration.md#335-密钥轮换的后果整库重建)。

### 8.6 本轮改动没有影响的东西（回归口径）

- **接口清单没变**：`docs/api.md` 的接口总数、请求体与响应体一个都没动；整库加密在 JDBC 连接层完成，网关、业务服务与浏览器感知不到（见 [api.md](api.md) 开头的说明）。
- **登录密码没变**：数据库口令是文件级/进程级的，演示账号密码仍是 `passwd`；上述 201 / 57 / 40 三层浏览器与接口测试全部用 `passwd` 登录成功，本身就是这条结论的证据。
- **业务规则没变**：business-service 的 195 条用例一条未改且全绿。
- **测试残留清理逻辑没变**：`feature-test.mjs` 仍会在 `2027-2` 留下测试课程与批次，跑完**重启一次服务**由 `DemoInitializer.purgeTestArtifacts()` 清掉（**不需要** `-ResetDb`）；这一条在第八轮同样适用，因为本轮没有新增课程/批次的删除接口。


## api · 2026-09-08T16:40:00.312Z

|检查|结果|耗时 ms|
|---|---|---:|
|HTTPS and registered independent services|PASS|23|
|Anonymous access is rejected|PASS|110|
|Three roles can authenticate; secure cookies|PASS|824|
|CSRF and cross-origin writes are rejected|PASS|10|
|Teachers cannot read other teachers courses|PASS|120|
|Students see only own submitted grades and rank|PASS|82|
|History filtering and pagination|PASS|38|
|ML predicts with three years and respects privacy|PASS|156|
|Grades validation, optimistic concurrency and full transaction|PASS|229|
|Incomplete submissions are rejected|PASS|31|
|Analysis is safely saved as text|PASS|82|
|Internal services reject unsigned, expired and replayed requests|PASS|24|
|SQL identifier injection rejected at remote server|PASS|1|
|Registry blocks SSRF targets|PASS|1|
|Independent ledger verifies original grades and EVM receipts|PASS|144|
|LSTM operation sequence classifier runs|PASS|18|
|Administrator review is independently recorded|PASS|23|
|Repeated login failures lock the account key|PASS|52|

## workflow · 2026-09-08T16:46:36.789Z

|检查|结果|耗时 ms|
|---|---|---:|
|Create student and second administrator with assigned permissions|PASS|1235|
|Administrator creates course and enrollment|PASS|160|
|Invalid coefficient total is rejected; valid six component weights saved|PASS|122|
|Partial draft is hidden from student|PASS|131|
|Batch validation rejects unknown student without partial changes|PASS|64|
|Complete grades submit; student sees 58/57 and class rank|PASS|140|
|Submitted grades and coefficients cannot be edited|PASS|55|
|Teacher withdrawal enables change and caps makeup at 60|PASS|239|
|Other authorized administrator can perform small revocation|PASS|120|
|Large revocation requires explicit confirmation and preserves evidence|PASS|123|
|After large revocation teacher can fully re-enter grades|PASS|138|
|Statistics and analysis version conflicts|PASS|83|
|Permissions updates invalidate sessions and take effect on next login|PASS|305|
|Password change revokes all prior sessions|PASS|923|
|Disabled accounts cannot log in and users cannot remove own management access|PASS|59|
|All workflow changes verify against independent encrypted ledger|PASS|135|
|Cleanup leaves explicit disabled test accounts and removes test course grades|PASS|131|

## tamper · 2026-09-08T16:49:40.448Z

|检查|结果|耗时 ms|
|---|---|---:|
|Database ciphertext corruption is detected and original score recovered from independent ledger|PASS|8484|
|Restoring the exact original ciphertext restores database integrity|PASS|8196|
|Ledger content modification is rejected|PASS|19|
|Ledger tail deletion is detected by independently persisted EVM anchors|PASS|20|
|Tamper fixtures fully restored; audit and outbox healthy|PASS|194|

## scale · 2026-09-08T16:50:43.724Z

|检查|结果|耗时 ms|
|---|---|---:|
|Second business instance registers independently|PASS|3019|
|Registry round-robin discovers two endpoints|PASS|9|
|Shared session remains valid across business replicas|PASS|247|
|Stopped replica lease expires and traffic remains available|PASS|21437|

## 浏览器与视觉验证

- course filters load matching history and saved drafts survive reload：通过
- teacher desktop grades, weights, analysis, prediction and print：通过
- student mobile private transcript and prediction fit viewport：通过
- administrator audit verification and XSS escape：通过
- local OCR recognizes numeric grade sheet without uploading image：通过
- login page desktop and mobile image renders：通过

截图经人工查看：桌面成绩表、移动端成绩单均无页面级横向溢出；宽表在自身容器内滚动。登录校园图片正常加载；图表来自真实 API 数据；教师分析 PDF 使用打印媒体生成。

- [teacher-desktop.png](evidence/teacher-desktop.png)
- [teacher-analysis.png](evidence/teacher-analysis.png)
- [student-mobile.png](evidence/student-mobile.png)
- [prediction-mobile.png](evidence/prediction-mobile.png)
- [admin-users.png](evidence/admin-users.png)
- [admin-audit.png](evidence/admin-audit.png)
- [login-desktop.png](evidence/login-desktop.png)
- [login-mobile.png](evidence/login-mobile.png)
- [analysis-print.pdf](evidence/analysis-print.pdf)

## 安全审计结果

- frontend：0 个已知漏洞（critical 0 / high 0 / moderate 0 / low 0），详见 [原始审计](evidence/frontend-audit.json)。
- chain-worker：33 个已知漏洞（critical 5 / high 22 / moderate 5 / low 1），详见 [原始审计](evidence/chain-worker-audit.json)。

Ganache 7.9.2 捆绑部分依赖，兼容修复后仍有 elliptic、secp256k1、ws 等上游风险。节点仅作为回环地址上的教学 EVM，通过 HMAC 端点访问，不连接真实资产；不能视为生产安全验收通过。Java 依赖未完成 OWASP Dependency-Check/NVD 扫描，未运行 ZAP、SonarQube 或 OpenVAS，未声称已经执行这些扫描。

## 已发现并修复的问题

### 第一轮修复的问题

- 端到端脚本的班级命名断言会受**自身测试数据**影响：`[1]` 的「班级采用 `XXXX级-XX专业-XX班` 的形式」在同一次运行中会看到 `[2]` 刚建的测试班级，而它当时用的是非规范名称，因此第二次运行时该断言失败；同时测试结束后新建的测试学院/专业/班级会残留在演示库里。修复方式是把测试班级改名成规范形式 `2026级-测试专业-<stamp>班`，并在 `[12]` 按「班级 → 专业 → 学院」顺序删除本次新建的三个组织对象（顺序不能颠倒，后端会拒绝删除仍有下级的对象）。`[12]` 因此由 2 条变为 5 条，脚本总计 114/114 通过（第一轮演示库规模为 2 学院 / 4 专业 / 6 班级，第二轮已扩充）。
- 同一学期只允许一个使用某课程代码的教学班（`CourseService.saveCourse` 的 `(code, term)` 唯一性校验）使「不同教师开设同一门课、由学生选择」无法验收：端到端脚本第一次运行 108/112，4 条失败全部落在该场景。移除该限制后，重复修读改由 `SelectionService.codeHistory` 在选课环节拦截，该场景 4 条全部通过（当时的脚本总数为 111/111，后续补充清理断言后总数为 114/114）。
- 演示库重建后，旧 EVM 锚点会让重新锚定因 `Anchor conflict`（同一下标出现不同哈希）失败：`LedgerService.reset()` 现在先调用 chain-worker 的 `POST /reset` 清空锚点再清空账本；业务库、独立账本与链锚点因此一起重建。
- 注册发现 URL 序列化、TLS 信任库、EVM 重启账户、OCR WebAssembly CSP、停用学生退选、加载期间课程切换竞态，均在回归前修复。开发过程详见 decisions.md。

### 第二轮修复的三个缺陷

第二轮在实现 8 条修正的过程中发现并修复了 3 个真实缺陷，三者都不是「测试没过就改断言」，而是先定位到实现本身的错误：

|#|缺陷|现象与影响|修复|
|---:|---|---|---|
|1|48 名学生的 `college_id` 被写成**专业编号**|`DemoInitializer` 用 `find(CLASSES, classId).parent()` 取学院，而 `Org.parent()` 返回上一级编号——班级的 parent 是专业，于是学生的学院字段全是 `M01xxx`。列表页看不出异常，但选课范围校验 `inScope` 比较的是 `college_id`，导致学生选课一律 403「你不在此次选课范围内」|学院一律走「专业 → 学院」反推（`major.parent()`）；新增 `DemoInitializer.verifyOrganizationIntegrity()`，灌数结束时逐行校验三级归属与「管理员无组织」，不合法直接抛异常中止启动|
|2|登录后的身份行**没有组织信息**|`App.vue` 的 `login()` 使用 `POST /login` 返回的 user（只含 `id`/`username`/`name`/`role`/`permissions`），而 `collegeName`/`majorName`/`className` 只有 `GET /me` 才返回；于是「登录后立即看」与「刷新页面后看」身份行不一致|登录成功后立即补一次 `api("/me")` 再进入应用初始化；浏览器检查新增教师/学生身份含三级组织、管理员不含组织且不出现 `null` 的断言|
|3|`SchemaCatalog.wasRebuilt()` **语义错误**|空库首次建表时它也返回 `true`，把「首次建表」误报成「丢弃了已有数据」，使初始化日志与 `DemoInitializer` 的判断产生误导|仅当「库里原有业务数据、且因结构版本不匹配被删除」时返回 `true`，新增 `hasBusinessRows()` 判定；`SchemaCatalogTest` 增加结构版本过期重建与补列迁移两条回归用例|

这三处修复后（第二轮修订上）重跑：Java 单元测试 234/234、端到端 156/156、浏览器 35/35；第三轮在同一批用例上更新契约后重跑为 230/164/44（见上文汇总表）。无论哪一轮，下一次整库重建时都会由启动自检拦住同类「层级写错」的问题。

## 覆盖边界

- Windows、MySQL、SQL Server、Oracle、真实多机 TLS、防火墙和外部生产链未在本机实测。
- 未进行大规模负载/长稳/网络分区压力测试；测试对象是教学规模。
- 控制篡改测试修改本项目合成数据后完整恢复，当前业务库、独立账本和链摘要重新校验通过。
- 预测和日志模型实测包括训练与推理，不代表真实学校数据上的泛化准确率。
- 当前未实现分布式全局 nonce/限流、审计单写故障自动恢复、生产自动密钥轮换。
- 组织编号只有主键 `id`（第三轮起）：`colleges`/`majors`/`classes` 表里遗留的 `code` 列不再被接口读写，也没有唯一索引；端到端脚本在第三轮改为断言「列表/下拉不含 `code` 字段」「新建响应只含 `ok` 与 `id`」。
- 重修判定只依据**已提交的历史成绩**（更早学期 + 同一课程代码 + `SUBMITTED` + 有效分 < 60）：正在修读（成绩未提交）与已退课都不算；当前学期（2026-1）重修班的成绩未提交属于预期状态，由教师登录后现场录入。判定是派生值而非持久化字段，因此没有任何「重修报名/审批」流程被测试覆盖（该流程本就不存在）。
- 系数表缺项的处理（第四轮修复）：`Models.total` 现在把缺失系数当 0 权重，不再抛异常让整门课变成「无成绩」；单测覆盖了该分支，端到端脚本未单独构造「weights 不全」的课程行（那属于脏数据场景）。
- 学业记录的重修判定口径（第五轮）：只看「同一课程代码在更早学期是否还有记录」，不再重复判断上一次是否挂科——它依赖「已通过不得重选」的选课规则与 `verifyTranscriptIntegrity()` 自检保证数据里不存在「通过后又重修」的脏数据；若有人绕过业务规则直接写库造出这种数据，记录会被标成重修而不报错（边界已在 [decisions.md](decisions.md) 说明）。
- 预测只对**进行中的课程**有意义（第五轮）：对本人的已出分历史课程调用 `/predict` 会返回 200 但 `results` 为空（预测对象是「期末未录入」的学生）。这是接口语义而非缺陷，前端已给出明确引导文案；端到端脚本因此**逐门课**调用 `/predict` 校验「有选课的课程都可预测」，而不是只抽查一门。
- 演示数据的历史样本教学班（`SAMPLE_COURSES`，2020-1 至 2023-2 共 91 个，任课教师是 4 个 `enabled=0` 不可登录的史料教师账号）**有完整名单与成绩**，也会出现在对应史料教师的课程列表里；它们不会成为「需要自己 3 个更早年样本」的课程，因为 2020-1 等本身就是最早期次、没有更早学期可查，`verifyPredictionCoverage()` 也显式排除了它们（只校验 64 门正课）。样本学生的成绩会进入他们自己的学业记录。
- 「按课程批量选课」不校验批次时间窗口与 `allow_add`/`allow_drop`，因此它不是「学生自助选课」的等价路径；其权限边界只有 `GRADE_ADMIN` 与审计账本两层，未做限流或频率控制。

## 组织管理与网上选课功能测试（第六轮 201 条；第五轮 201 条、第四轮 183 条、第三轮 164 条、第二轮 156 条记录）

「学院—专业—班级三级组织管理」与「网上选课系统」由 `scripts/feature-test.mjs` 端到端验证。脚本通过网关 `https://localhost:8443/api` 发起与浏览器完全相同的请求（GET 走 query 串，POST 走 JSON + `X-CSRF-Token`），并在结束时把逐条结果写入 `.runtime/logs/feature-test.json`。第二轮给脚本补了编号规则、管理员无组织、班级无辅导员与按课程批量选课的断言，第三轮换成「编号即主键」口径，第四轮再补 19 条重修语义断言（见上文「第四轮（R4）说明」）。第一轮在同一脚本上的记录（114/114）见本节末尾的「第一轮历史记录」。

**前置条件**：四个 Java 服务与 chain-worker 已按 README 启动；数据库由 `DemoInitializer` 按 `SchemaCatalog.SCHEMA_VERSION = 3` 整库重建为 4 学院 / 8 专业 / 16 班级 / 65 账号 / 32 教学班；网关地址可用环境变量 `GATEWAY_URL` 覆盖。
**执行命令**：`node scripts/feature-test.mjs`（失败条数不为 0 时进程以非零码退出）

### 结果（第二轮记录）

**第二轮实际执行结果：156 / 156 通过，0 失败**，当时的证据文件记录 `total=156`、`passed=156`、`failed=0`、`generatedAt = 2026-10-03T13:36:28.679Z`（本机 21:36:28），网关 `https://localhost:8443`，在第二轮冻结修订 + 全新重建的演示库上运行。**该文件随后被第三、四、五、六轮的运行依次覆盖**：当前 `.runtime/logs/feature-test.json` 记录的是**第六轮在重建后的演示数据上重跑 201 条**的结果（`total=201`、`passed=201`、`failed=0`、`generatedAt = 2026-10-04T13:25:30.914Z`；第五轮 201 条 `2026-10-03T16:35:09.260Z`、第四轮 183 条、第三轮 164 条），下表是第二轮 156 条的分组快照（历史记录，不代表当前值）。

|分组|断言数|通过|失败|
|---|---:|---:|---:|
|[0] 服务可达性与登录|9|9|0|
|[1] 组织数据初始化（多学院、多专业、多班级）|20|20|0|
|[2] 组织管理：新建 → 层级校验 → 编号规则 → 删除保护|22|22|0|
|[3] 编号确定后不可修改 + 组织敏感操作进入审计|4|4|0|
|[4] 课程开设院系与学期唯一性|6|6|0|
|[5] 教务发布选课信息|8|8|0|
|[6] 选课鲁棒性校验（发布阶段）|6|6|0|
|[7] 学生选课：窗口与范围校验|13|13|0|
|[8] 鲁棒性：不同教师的同一门课 / 已通过课程不得重选|10|10|0|
|[9] 教务按课程批量选课（1 人 / 多人 / 整班）与退课保护|18|18|0|
|[10] 最低开课人数不满足时自动退回|18|18|0|
|[11] 权限边界与跨域保护|6|6|0|
|[12] 清理测试数据|16|16|0|
|**合计**|**156**|**156**|**0**|

> 分组标题取自 `scripts/feature-test.mjs` 里的 `console.log("\n[n] …")` 输出标记，断言数为该次运行的实际结果条数；总数 156 与证据文件 `total`/`passed` 一致（9+20+22+4+6+8+6+13+10+18+18+6+16 = 156），可逐项复算。
>
> 本轮同样不做任何结果推演：通过与失败数直接取自 `.runtime/logs/feature-test.json` 的逐条 `results[].ok`（该文件不记录分组字段，分组归属按脚本执行顺序与分组标题对应）。未覆盖的场景见本节末尾「未由本脚本覆盖的部分」。

### 第二轮新增断言的证据样例

脚本把关键响应写入每条结果的 `detail`，可从证据文件直接核对第二轮的新行为（编号相关的三行已按第三轮的实现更新，见括注）：

|断言|证据里的实际值|
|---|---|
|班级列表不含辅导员字段|`["id","major_id","college_id","name","grade_year","code","enabled","version","shortName","short_name","gradeYear","collegeName","majorName","studentCount"]`|
|管理员没有被分配组织|`status=400 message=管理员不归属学院/专业/班级`|
|单学生按课程批量选课|`{"ok":true,"added":1,"skipped":0,"removed":0,"failed":[],"total":1}`|
|整班按课程批量选课|`{"ok":true,"added":2,"skipped":1,"removed":0,"failed":[],"classStudents":3,"total":3}`|
|重复批量选课全部跳过|`{"ok":true,"added":0,"skipped":3,"removed":0,"failed":[],"classStudents":3,"total":3}`|
|批量选课后名单人数增加|`before=2 after=4`，名单项带 `className`（`2023级-软件工程-2301班`）|
|已有成绩的学生批量退课进 `failed`|`{"failed":[{"studentId":"20231539","reason":"教师已录入成绩，不能退课","name":"吴星野"}]}`|
|既不给学生也不给班级被拒绝|`status=400 message=必须指定学生名单或班级`|
|学生调用批量选课被拒绝|`status=403 message=没有此操作权限`|

第三轮把脚本扩充到 164 条后，编号相关的断言换成「编号即主键」的口径，可从 `.runtime/logs/feature-test.json` 里直接读到：学院/专业/班级列表与三级下拉**都不含 `code` 字段**；新建学院/专业/班级返回的是 `C`/`M`/`B` + 5 位主键；新建响应**只含 `ok` 与 `id`**；请求体里带 `code` 或不符的 `id` 不影响生成与主键；修改后主键保持不变、仍能按原 `id` 找到该对象。

### 文档核对时的只读 API 抽查

在冻结修订 + 重建后的演示库上，另做了一次**只读**抽查（`POST /api/login` 取会话后只用 GET，不写入任何数据），用于核对本文档中与种子数据规模相关的数字：

|抽查|实际返回|
|---|---|
|`GET /users?size=300`|65 个账号：`STUDENT` 51、`ADMIN` 3、`TEACHER` 11|
|`GET /organizations/options`|4 学院 / 8 专业 / 16 班级；学院 `code` = `01,02,03,04`|
|班级项是否含辅导员字段|不含（`classes[0]` 没有 `counselor` 键）|
|管理员 `GET /me`|`collegeId`/`majorId`/`classId` 与 `collegeName`/`majorName`/`className` 均为 `null`|

这四组结果与当时（第三轮）的 feature 脚本断言一致，也与当时文档、README 里写的 4 学院 / 8 专业 / 16 班级 / 65 账号 / 32 教学班相符。这是**第三轮的抽查快照**，未在第四轮重跑；第四轮把教学班扩到 35 个并新增重修样本，`DemoInitializer` 的类注释也已同步更新（不再是旧规模），种子数据的当前口径见下文「第四轮（R4）说明」与 [配置说明](configuration.md)。

### 第一轮历史记录

第一轮脚本共 13 个分组、114 条断言，全部通过。第一轮曾出现一次 4 条失败：第一次执行（`2026-10-03T09:23:01.344Z`）结果是 **108/112**，4 条全部集中在「不同教师的同一门课」——当时 `CourseService.saveCourse` 强制 `(code, term)` 唯一，同一学期只允许一个使用该课程代码的教学班，导致「必修课由不同教师分别开课、学生选择其中一位」这一场景无法构造：

|失败条目|当时的现象|
|---|---|
|创建同课程代码不同教师的教学班|409「该学期已存在相同课程代码的教学班」|
|同代码不同教师教学班已创建|拿到空对象|
|把两个同代码教学班放进同一批次|因上一条失败而缺少批次编号|
|不允许选择不同教师开设的同一门课（按课程代码判定）|未能构造出该场景|

随后 `CourseService.saveCourse` 移除该唯一性校验，重复修读改由 `SelectionService.codeHistory` 在选课环节拦截（同一学期同代码的其他教学班 409、此前学期已通过 409），重跑后该场景全部通过。该取舍已记入 [decisions.md](decisions.md)。第二轮初始化数据进一步把它固化成可复现的样本：2025-1 的两门 `CS401` 教学班分别由陈老师与李老师开设。

### 覆盖范围

|分组|覆盖内容|
|---|---|
|[0] 服务可达性与登录|admin、t1101、t2101、20241530、20241536、20231530 六个演示账号登录；`/me` 返回学院/专业/班级的编号与名称；学生归属到具体班级（`classId=B01002`）；**管理员的三级组织编号全为 `null`**|
|[1] 组织数据初始化|`/organizations/options` 返回 4 学院 / 8 专业 / 16 班级；`/organizations` 三级列表；学院带专业/班级/学生计数，专业带学院名，班级带学院与专业名；主键格式 `C\d{5}`/`M\d{5}`/`B\d{5}`；班级项**不含 `counselor`**；班级名形如 `2023级-软件工程-2301班`（第三轮起列表与下拉**都不含 `code` 字段**）|
|[2] 组织管理|按名称新建学院/专业/班级并返回主键 `id`（`C`/`M`/`B` + 5 位）；新建编号沿用「同层最大 + 1」的号段规则；同级编号无重复；**新建响应只含 `ok` 与 `id`**；同级重名 400；缺上级 400；班级与专业不匹配 400；`/organizations/impact` 返回影响面；有账号的班级与有课程的学院删除 409；**把管理员调整到组织被 400 拒绝**|
|[3] 编号不可修改与审计|修改学院时请求体带另一个 `id` 与 `code`，修改后主键不变、仍能按原 `id` 找到该对象、名称已生效；账本中存在组织管理的审计事件且带 `before`/`after` 快照|
|[4] 课程与学期|课程目录（第二轮快照为 32 门）全部带 `collegeName`；至少两个学院开设课程；**初始化数据**中同一学期没有重复课程代码；初始化数据中同一学生不在两个学期修读相同课程代码|
|[5] 发布选课|`/courses/save` 建测试教学班；`/selections/save` 发布批次并返回批次编号；列表含 `courseCount`/`selectedCount`/`status=OPEN`/`statusName`|
|[6] 发布阶段鲁棒性|已有成绩的课程 409「该课程已有教师录入成绩，不能发布选课」；同一课程不能出现在两个进行中发布 409；结束早于开始 400；`minEnroll` 越界 400；学生发布 403|
|[7] 学生选课|窗口未开始 400；范围外学生 403「你不在此次选课范围内」；`/selections/available` 的 `courses[]` 带教师/学院名与 `eligible`/`reason`；选课成功后出现在 `/selections/my` 且带 `source`；重复选课 409|
|[8] 课程代码规则|同一批次内选择另一位教师的同代码课程被 409 拒绝；初始化数据含 `20231530` 的挂科与重修两条记录（第四轮起重修样本扩为 5 名学生 / 3 个课程代码，见「第四轮（R4）」小节）；此前学期已通过的同一门课不得重选 409|
|[9] 按课程批量选课|`POST /enrollments/batch` 单人（`added=1`）、整班（`classStudents=3`）、并集去重；批量后 `/roster` 人数增加且带 `className`；重复批量全部进 `skipped`；两者都不给 400；学生调用 403；**已有成绩的学生批量退课进 `failed` 而其他人成功**；教务代退有成绩的学生 409；学生自助退课后从 `/selections/my` 消失|
|[10] 自动退回|为低人数场景单独建课并发布高 `minEnroll` 批次；学生选入；`/selections/settle` 返回被取消课程与 `refunded`；退回后从 `/selections/my` 移除；`/selections/records` 含 `SELECT`/`DROP`/`AUTO_REFUND`/**`ADMIN_ASSIGN`** 且带学生姓名与课程名称；账本含 `SELECTION_PUBLISH`/`SELECTION_SELECT`/`SELECTION_DROP`/`SELECTION_SETTLE`|
|[11] 权限与跨域|学生读 `/selections` 403、批量选课 403、新建学院 403；教师读 `/organizations` 403；未登录 401；伪造 `Origin` 被网关 403|
|[12] 清理|关闭并取消测试批次；按「清理测试班级 → 清理测试专业 → 清理测试学院」顺序删除本轮新建的组织对象（顺序不能颠倒，后端会拒绝删除仍有下级的对象）；逐个清理测试教学班（`TL573887`/`CS102`/`TB573887`×2/`TA573887`）的选课名单，保证测试结束后演示数据仍是干净的 4 学院 / 8 专业 / 16 班级|

### 未由本脚本覆盖的部分

- **浏览器交互**：本脚本只走 HTTP 接口，不点击页面。另有 `scripts/browser-check.mjs` 做真实浏览器渲染验证（见下一节），以及第三轮新增的 `scripts/verify-sidebar-resize.mjs`（结果写入 `.runtime/logs/sidebar-resize-check.json`）做侧栏拖动的专项检查；它们覆盖导航顺序、身份行、侧栏拖动与选课弹窗的可见性，但不提交弹窗表单。
- **定时自动结算**：`SelectionService.autoSettleExpired` 的调度周期是 60 秒，脚本执行期间通常不会触发，因此脚本验证的是显式 `POST /selections/settle`；定时任务只通过源码审查确认，未做实际计时验证。
- **编号耗尽（号段 99 / 本级 999）**：构造 99 个组织会污染演示库，端到端脚本未做该场景，只在 `OrganizationServiceTest.nextIdRejectsExhaustedLevel` 用 mock 覆盖（第三轮已随 `nextCode` 一起删除对应的两位数用尽用例）。
- **并发与规模**：同一学生的并发选课、数百人同时选课、跨批次并发结算均未测试；`enrollments(course_id,student_id)` 唯一索引是唯一的并发兜底。
- **组织与选课的 RPC 层**：脚本只走浏览器入口，内部 `Selection`/`Mutation` 协议的新表字段没有单独的注入或异常用例。
- **管理员无组织的完整路径**：脚本覆盖了「管理员不能被分配到组织」（`/organizations/assign` 400）与「`/me` 返回 `null`」，`POST /users/save` 给管理员传组织的 400 分支由 `AdminServiceTest` 覆盖。
## 真实浏览器检查（第六轮 57 条；附第五轮 57 条、第四轮 50 条、第三轮 44 条与第二轮 35 条对照）

`scripts/browser-check.mjs` 用 Playwright 驱动真实浏览器，验证登录页、按角色的导航、身份行、侧栏宽度调节、合并后的选课管理页、按课程选课弹窗、重修徽标、学业记录的「我的成绩」与学业预警页的渲染，结果写入 `.runtime/logs/browser-check.json`，截图落在 `test-results/browser/`。

**第六轮在重建后的演示数据上实测 57 / 57 通过、0 失败**（`generatedAt = 2026-10-04T13:26:43.751Z`，本机 21:26:43）。**本轮（R7 后续）又在演示数据按 `-Dcampus.reset-db=true` 重建之后复跑了一次，仍是 57 / 57 通过、0 失败**（`generatedAt = 2026-10-05T14:51:13.142Z`；同一次会话里的 `ocr-voice-check` 是 `14:50:14`）。历史对照：第五轮 57 / 57（`2026-10-03T16:36:25.085Z`）、第四轮 50 / 50（`15:31:12.652Z`）、第三轮 44 / 44（`14:38:54.625Z`）、第二轮 35 / 35（`13:37:05.939Z`）。基址都是 `https://127.0.0.1:5173`（前端 Vite 开发服务器，不是网关 8443），三种身份（管理员 / 教师 / 学生）全部覆盖。断言数量与第五轮相同（重建数据不影响断言数），第 49–55 条仍是第五轮新增的那 7 条。

第五轮新增 7 条断言（第 49–55 条，逐条见上文「第五轮（R5）说明」的浏览器小节）：`「我的成绩」显示重修徽标`、`两行课程名一致且不含重修后缀`、`有 2 行程序设计基础（挂科 + 重修）`、`学业预警页有生成预测按钮`、`学业预警不再提示「数据不足」`、`学业预警给出训练年份与样本数`、`学业预警渲染出预测结果行`；第 56、57 条仍是「无未捕获页面错误」「无致命控制台错误」。

以下为更早轮次的断言记录（历史对照）：

第四轮新增 6 条断言（在第三轮 44 条的基础上）：

|#|第四轮新增断言|结果|
|---:|---|---|
|45|学生端显示重修徽标|PASS|
|46|课程名不含中文括号的「重修」后缀|PASS|
|47|重修徽标出现在「程序设计基础（CS102）」那一行|PASS|
|48|教师课程下拉能找到 2026-1 的重修班|PASS|
|49|教师端名单标出重修学生|PASS|
|50|教师端课程名同样无重修后缀|PASS|

下表是**第二轮 35 条**的逐条记录（历史对照）：其中已被第三轮替换的条目（侧栏第 9–13 条、组织编号第 25 条）**以本节下方的第三轮断言为准**；第三轮与第四轮的新增断言见下方列表，均不代表 35 条是当前状态。

|#|检查|结果|
|---:|---|---|
|1|登录页不再出现硬编码学院名|PASS|
|2|管理员登录成功|PASS|
|3|管理员导航顺序正确且「安全审计」在最后|PASS|
|4|管理员导航不再有「课程与选课」|PASS|
|5|管理员导航不再有独立的「网上选课」|PASS|
|6|顶栏/页脚不再出现硬编码学院名|PASS|
|7|管理员身份只显示角色、不含组织|PASS|
|8|身份行为空组织时不显示 null|PASS|
|9|存在侧栏宽度调节按钮（第二轮为折叠按钮，第三轮改为拖拽手柄）|PASS|
|10|侧栏宽度可调窄（第二轮实测 `216 -> 68`）|PASS|
|11|调窄后无横向滚动条（`overflow=0px`）|PASS|
|12|窄栏时导航按钮仍有悬浮提示|PASS|
|13|可恢复到原宽度（`68 -> 216`）|PASS|
|14|选课管理页含「课程与选课 / 选课批次 / 选课记录」三个入口|PASS|
|15|选课管理页能看到课程列表|PASS|
|16|课程列表每行有「选课」按钮（`count=37`）|PASS|
|17|「选课」按钮打开按课程选课弹窗|PASS|
|18|弹窗可同时按学生与整班处理|PASS|
|19|按课程选课弹窗可正常关闭|PASS|
|20|组织管理页加载学院数据|PASS|
|21|组织管理页不出现「辅导员」|PASS|
|22|班级标签显示规范班名|PASS|
|23|班级标签不出现「辅导员」|PASS|
|24|组织管理页存在「新建班级」按钮|PASS|
|25|新建班级表单的组织编号字段只读展示（第三轮已删除该输入框）|PASS|
|26|教师身份显示「教师 · 学院 · 专业」|PASS|
|27|教师身份不含班级、不含 null|PASS|
|28|学生导航只有「网上选课」入口|PASS|
|29|学生导航不出现「组织管理」|PASS|
|30|学生身份显示「学生 · 学院 · 专业 · 班级」|PASS|
|31|学生身份不含 null|PASS|
|32|学生选课台渲染（含「我的选课」）|PASS|
|33|学生选课页无页面级横向溢出（`overflow=0px`）|PASS|
|34|浏览器无未捕获的页面错误|PASS|
|35|浏览器无致命控制台错误|PASS|

第三轮替换/新增的断言（当前证据文件里的实际名称与关键 detail）：侧栏部分为「原来的折叠按钮已移除」「存在侧栏宽度拖拽手柄」「向右拖动可加宽侧栏并实时生效（`216 -> 326`）」「主内容区左边距同步跟随（`margin=326 w=326`）」「向左拖动可收窄到最小 68px（仅图标档）」「仅图标档隐藏导航文字」「仅图标档导航按钮仍有悬浮提示」「收窄后无横向滚动条（`overflow=0px`）」「拖过的宽度写入 localStorage」「刷新后宽度保持（`w=68`）」「双击手柄恢复默认 216px」；编号部分为「学院列表展示主键编号（C + 5 位）」「班级列表展示主键编号（B + 5 位）」「新建班级表单不再有『编号』输入框」「新建班级表单只剩班名/年级等业务字段」。

其中导航顺序的实测值直接来自页面文本：管理员为 `["课程成绩","统计分析","人员与权限","组织管理","选课管理","安全审计"]`（第 3 条断言「安全审计」在最后，第 4、5 条断言旧的「课程与选课」「网上选课」两个入口都已消失）；教师身份为 `教师 · 信息工程学院 · 软件工程`；学生身份为 `学生 · 信息工程学院 · 软件工程 · 2024级-软件工程-2401班`；管理员身份为 `管理员`（不含 `·`，也不含 `null`/`undefined`）。

**覆盖与边界**：该检查覆盖登录页文案、按权限的导航可见性与顺序、身份行文本（含空组织过滤）、侧栏宽度调节（拖动实时生效、主内容左边距跟随、仅图标档的 `title` 提示、localStorage 持久化与刷新保持、双击复位、`overflow=0px`）、合并后「选课管理」的三标签页与课程列表、按课程选课弹窗的打开/关闭与「学生 + 整班」两种处理方式、组织管理页（无辅导员、列表显示主键编号、表单无编号字段、规范班名）、学生选课台渲染，以及页面级错误与控制台致命错误。它**不覆盖**写操作的实际提交（选课弹窗只验证打开与关闭，不点击提交；组织的新增/删除、批次发布与结算也不在浏览器层点击），这些由 `scripts/feature-test.mjs` 从接口层覆盖；分页交互与表单校验文案仍需人工验证。基址为 Vite 开发服务器，网关 8443 上的打包页面只有接口层被 feature 脚本覆盖。

同一组修订上还生成了界面截图供人工查看，保存在 `test-results/browser/`：

- [org-colleges.png](../test-results/browser/org-colleges.png)、[org-classes.png](../test-results/browser/org-classes.png)（组织管理学院/班级页，第二轮截图；第三轮刷新时间为 22:33:04）
- [selection-admin.png](../test-results/browser/selection-admin.png)、[selection-student.png](../test-results/browser/selection-student.png)（合并后的「选课管理」页、学生选课台）
- [enroll-dialog.png](../test-results/browser/enroll-dialog.png)（每门课程「选课」按钮打开的按课程选课弹窗）
- [sidebar-admin.png](../test-results/browser/sidebar-admin.png)、[sidebar-teacher.png](../test-results/browser/sidebar-teacher.png)、[sidebar-student.png](../test-results/browser/sidebar-student.png)（三种身份的身份行文本）
- [sidebar-collapsed.png](../test-results/browser/sidebar-collapsed.png)（第二轮：折叠后的窄栏，历史截图）
- [sidebar-resized-wide.png](../test-results/browser/sidebar-resized-wide.png)、[sidebar-resized-compact.png](../test-results/browser/sidebar-resized-compact.png)、[sidebar-resized-icononly.png](../test-results/browser/sidebar-resized-icononly.png)、[sidebar-resized-icon-only.png](../test-results/browser/sidebar-resized-icon-only.png)、[sidebar-resized-persisted.png](../test-results/browser/sidebar-resized-persisted.png)（第三轮：拖动到宽栏 / 紧凑档 / 仅图标档 / 刷新后保持宽度）
- [org-form-no-code.png](../test-results/browser/org-form-no-code.png)、[org-created-by-id.png](../test-results/browser/org-created-by-id.png)（第三轮：新建表单已无编号输入框、新建回执给出主键编号）
- [selection-student-live.png](../test-results/browser/selection-student-live.png)（由 `scripts/capture-selection.mjs` 生成：教务临时发布一个生效批次 → 学生打开选课台 → 截图 → 清理批次）
- [ocr-preview.png](../test-results/browser/ocr-preview.png)（第七轮：`scripts/ocr-voice-check.mjs` 在识别 `clean-3col` 后截取的识别结果预览界面，含列对应下拉、逐格编辑、行置信度与问题说明）

## 复现顺序

本轮实际执行的命令与顺序如下（离线 Maven 与 Node 均可直接运行）：

```text
mvn -o test                           # 258 条 Java 单元测试，第八轮实测全绿
# 由 scripts/start.ps1（或 scripts/start.sh）启动四个 Java 服务与 chain-worker
node scripts/feature-test.mjs         # 端到端断言 201 条（第八轮复跑 201/201）
# 另开前端开发服务器（默认 https://127.0.0.1:5173）后：
node scripts/browser-check.mjs        # 真实浏览器检查 57 条（第八轮复跑 57/57）
node scripts/verify-sidebar-resize.mjs # 侧栏拖动专项检查 28 条（第四轮记录，未重跑）
node scripts/ocr-voice-unit.mjs       # OCR/语音纯函数单元测试 76 条（第八轮复跑 76/76）
node scripts/ocr-voice-check.mjs      # OCR/语音浏览器端到端 40 条（第八轮复跑 40/40）
node scripts/generate-ocr-fixtures.mjs # 生成 OCR 固定测试图集 10 张到 test-results/ocr/（可选，--force 重新生成）
node scripts/junit-summary.mjs        # 汇总各模块 surefire 报告为 .runtime/logs/junit-summary.json
node scripts/generate-docs.mjs --check # 生成式文档与源码一致（见下方说明：当前源码树共 69 个 Java 命名类型，该检查尚未通过）
```

**生成式文档的当前状态要说清楚**：`scripts/SourceInventory.java` 用 JDK `JavacTask` 解析真实语法树，第八轮实测源码树共 **69 个 Java 命名类型**（第六轮及以前为 64，新增 `ConfigGuard`、`DatabaseBootstrap`、`DbCredentials`、`ConfigEnvironmentPostProcessor`、`DataSourceConfig` 五个）。本轮的改动只允许写 Markdown，因此 `docs/classes.md` 与 `docs/class-diagrams.md` 已按生成格式**手工补齐这五个类型的条目**（成员清单来自实际运行 `java scripts/SourceInventory.java .` 的输出，逐个核对），但 `docs/source-inventory.json` 仍是旧快照，`node scripts/generate-docs.mjs --check` 会因 `docs/classes.md` 的内容与脚本重新生成的文本不完全相同而报 `Outdated generated documentation`。要让它通过，需在允许写非 Markdown 文件时执行一次 `node scripts/generate-docs.mjs`（会重写 `docs/classes.md`、`docs/class-diagrams.md`、`docs/source-inventory.json`），并在脚本的 `descriptions` 表里为新类型补上职责说明，否则新条目会退化成默认文案「协议或组件的内部类型。」。历史记录：第六轮 64 个类型时该检查通过，第五轮 63、第四轮 61。

单测汇总证据 `.runtime/logs/junit-summary.json` 由各模块 `target/surefire-reports/TEST-*.xml` 汇总而来（`mvn -o test` 之后运行 `scripts/junit-summary.mjs` 生成）。**该文件当前仍是第六轮的记录（250 条、`generatedAt = 2026-10-04T09:47:57.861Z`），因为第八轮只重跑了 `mvn -o test` 而没有重跑汇总脚本**；第八轮 258 条的权威证据是各模块 `target/surefire-reports/`（本机 `2026-10-06 0:25:09`–`0:25:14`）与本文 8.2 的逐模块表格，跑一次 `node scripts/junit-summary.mjs` 即可把 JSON 更新到 258。历史记录：第七轮 250、第六轮 250、第五轮 248、第四轮 243、第三轮 230、第二轮 234。

**跑完端到端脚本后的演示库清理**：`feature-test.mjs` 会在测试学期 `2027-2` 新建教学班并在 `[12]` 把学生全部退回，
但**不会删除这些教学班**（系统没有删除课程的接口，教务用「取消教学班」下架课程）。因此反复运行后，课程下拉里会堆积
学期为 `2027-2` 的测试课程——它们不影响任何断言（演示数据集中在 `2023-1`–`2026-1`），但会让界面变乱。

**现在不需要 `-ResetDb`**：第六轮起 `DemoInitializer.purgeTestArtifacts()` 会在**每次启动时**（早于灌数）自动清掉这些测试课程与测试批次，
并按 `course_id` 级联删除对应的选课、成绩、选课流水与分析，日志形如 `[DemoInitializer] 已清除 20 门测试课程、48 条选课、0 条成绩、0 个测试选课批次（另有 96 条选课流水）。`
（无残留时打印「未发现测试课程/测试选课批次残留」）。因此跑完测试**重启一次服务即可**恢复干净界面：

```powershell
.\scripts\start.ps1              # 重启即自动清理测试残留（不需要 -ResetDb）
.\scripts\start.ps1 -ResetDb     # 需要彻底重建演示数据时才用：4 学院 / 8 专业 / 21 班级 / 205 账号 / 155 教学班 / 1354 选课 / 1354 成绩 + 5 名重修学生 + 64 门可预测正课
```

> 本轮就是按 `-ResetDb`（等价 `-Dcampus.reset-db=true`）把演示数据恢复到初始状态的：先打印上面那条清理日志，再 `[DemoInitializer] 收到显式重建开关，删除全部业务表并重新灌入数据。`，然后重灌并自检。完整五条日志与复核结果见上文「第七轮（R7）说明 → 演示数据恢复」。

重建或清理后可通过 data-service 启动日志确认（`[DemoInitializer] 数据已写入：…`、`成绩单自检通过：…`、`学业预警覆盖自检通过：64 门有选课的正课全部可预测。`）。<br>
原始交付记录的完整回归流程为：

```text
mvn verify
node scripts/api-test.mjs
node scripts/workflow-test.mjs
node scripts/tamper-test.mjs
node scripts/scale-test.mjs
npm --prefix frontend run test:e2e
```

> 需要说明的是：当前源码树的 `scripts/` 目录包含 `browser-check.mjs`、`capture-selection.mjs`、`feature-test.mjs`、`generate-docs.mjs`、`generate-ocr-fixtures.mjs`（第七轮）、`junit-summary.mjs`、`ocr-voice-check.mjs`（第七轮）、`ocr-voice-unit.mjs`（第七轮）、`SourceInventory.java`、`setup.mjs`、`start.sh`、`start.ps1` 与 `verify-sidebar-resize.mjs`。上面第二段里的 `api-test.mjs`、`workflow-test.mjs`、`tamper-test.mjs`、`scale-test.mjs` 在本次交付的源码树中**不存在**，`docs/evidence/` 目录也不存在；`npm --prefix frontend run test:e2e` 所需的 Playwright 用例亦未随源码提供（浏览器验证由 `scripts/browser-check.mjs` 与 `scripts/ocr-voice-check.mjs` 承担，截图在 `test-results/browser/`）。因此当前的验证证据以 `mvn -o test` 的 surefire 报告、`.runtime/logs/` 下的六份 JSON（`feature-test`、`browser-check`、`ocr-voice-unit`、`ocr-voice-check`、`sidebar-resize-check`、`junit-summary`）与本文 8.3 的加密实证为准；第二段描述的是历史回归流程，不是本次实际执行的命令清单。
>
> Windows 下启动服务请使用 `scripts/start.ps1`：它会读取 `.runtime/secrets.json` 把密钥同时注入为环境变量与 `-D` 启动参数，把参数写进 `.logs/jvm.args`（两段式 `-DDB_PASSWORD` 加引号，否则 JVM 的 argfile 解析器会按空白把它拆成两个参数），并以独立隐藏窗口启动各服务，脚本本身可以退出。需要整库重建时用 `-ResetDb`（等价 `-Dcampus.reset-db=true` 或 `CAMPUS_RESET_DB=true`）。

测试共享同一演示数据库，应按顺序执行。tamper-test 会停止并重新启动服务，scale-test 会临时启动第二业务副本，不能与写入或浏览器测试并发。Playwright 首次需安装浏览器：在 frontend 目录执行 `npx playwright install chromium`，或设置 BROWSER_EXECUTABLE 指向已安装的 Chromium（本机实测使用 `BROWSER_EXECUTABLE` 指向已安装的 Edge）。
