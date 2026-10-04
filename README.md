# 知序 · 高校成绩管理系统

依据《软件开发综合能力实践（网络软件与安全）要求说明》（长安大学信息工程学院，2026 年 8 月）实现的课程实践工程。采用 Java 17、Spring Boot、Vue 3，提供独立网关、业务服务、数据访问服务、审计服务，以及本机以太坊测试链与 LSTM 工具进程。

原始 PDF 和提示文件在 `docs/requirements/`。本项目不包含答辩 PPT、个人报告或虚构的组员信息。课程报告、类说明和模型均为 Markdown。

## 快速运行

安装 JDK 17、Maven 3.9+、Node.js 22 LTS（也在 Node 26 上验证）。将 `java`、`keytool`、`mvn`、`node`、`npm` 加入 PATH。首次构建需要联网下载依赖。

首次启动先执行
```bash
node scripts/setup.mjs
```
在 .runtime 下生成证书

执行构建打包
```bash
mvn -q -DskipTests clean package
```
启动服务
```bash
./scripts/start.sh
```


浏览器须信任 `.runtime/localhost.crt` 或对本地测试站点确认例外。正式部署必须换用组织 CA 签发证书，不能关闭证书验证。

首次运行时由 `DemoInitializer` 自动向数据库注入演示账号（管理员 `admin` / `jw001` / `jw002`、教师 `t1101` 等、学生 `20231530` 等，密码统一为 `passwd`）及历史成绩，并记账、上链、签署合约。所有数据落在各模块的 `.runtime/database/` 中。要演示具体功能时用哪个账号，见 [演示账号与场景指南](docs/演示账号与场景指南.md)。


## 工程目录

|目录|职责|
|---|---|
|`common`|值传递协议、远程接口、安全签名、加密、注册心跳|
|`gateway`|HTTPS 统一入口、服务租约注册和轮询发现、静态页面|
|`business-service`|认证、权限、课程成绩流程、统计和预测；不依赖 JDBC|
|`data-service`|SQL 编译、参数绑定、事务、版本检查、成绩加密、审计发件箱|
|`audit-service`|独立加密账本、哈希链、EVM 锚定与原始证据|
|`chain-worker`|Ganache 本机 EVM、交易回执校验、TensorFlow.js LSTM|
|`frontend`|Vue 操作界面、浏览器本地 OCR、打印与浏览器测试|
|`scripts`|跨平台初始化、启停、集成测试、文档生成|
|`docs`|需求、架构、安全、类说明、部署、测试和模型|

## 已实现的业务

- 教师：六项百分制成绩系数、批量暂存、整课提交和撤回、正考与补考、往年课程搜索、统计与教学分析、OCR 辅助录入、打印。课程成绩页的课程名单里标出哪些学生本次是在**重修**，并为尚未录入期末的学生生成学业预测。
- 学生：本学期与在校成绩、正考/补考并列、补考最高 60 分、本人课程排名、未通过课程统计、本人学业预测。学业记录按学期倒序，**重修**在成绩单上以徽标标注（课程名不变），已获课程与学分按课程代码去重（重修不重复计学分），已重修通过的课程不再计入未通过。网上选课按选课范围自助选课与退课，选课台与「我的选课」同样带重修徽标。
- 教务：小撤销、大撤销、人员和组织维护、按角色分配功能权限、停用账号、重置密码、审计复核、独立原始成绩查询。课程与选课的维护集中在「组织管理」与「选课管理」两个页面：
  - **组织管理**按名称维护学院—专业—班级三级组织，可批量把学生调入班级。组织**只有一个编号**——主键 `C01001` / `M01001` / `B01001`（`层前缀 + 2 位号段 + 3 位本级序号`），它同时用于库内引用、接口寻址与页面展示：新建时由后端自动生成、确定后不可修改，表单里没有「编号」输入框，列表在名称下方只读展示该编号。班级**不设辅导员**，管理员**不归属任何组织**。删除前可查看影响面。
  - **选课管理**分「课程与选课 / 选课批次 / 选课记录」三个标签页：课程与选课页发布课程并直接维护名单（成绩未录入的课才允许开选），选课批次页发布、关闭、取消批次并在窗口结束后结算最低开课人数，选课记录页查询全部选课/退课/自动退回流水。批次包含选课名、起止时间、选课范围、最低开课人数、是否可选可退。
  - 每门课程行上的「选课」按钮打开**按课程选课弹窗**：可选择一个、多个学生或某个班级的全部学生（并集去重），也支持整批退课。每处理一名学生都写一条选课记录（代选 `ADMIN_ASSIGN` / 代退 `ADMIN_REMOVE`），已有成绩的学生会被拒绝并单独列出。
- 智能功能：均值/3σ/百分位/历史波动检测，Weka 线性回归和决策树，真实 TensorFlow.js LSTM 序列分类。学业预警预测的是「已有平时与实验、期末未录入」的学生，预测只临时计算、不进入正式成绩表；演示数据已按门槛备好历史样本与暂存成绩，教师与学生打开即可看到预测结果。
- 重修规则：挂科（正考与补考都不及格）的学生可在**后续学年**选择**同一课程号**的教学班重修——两次修读在系统里是同一门课（同一课程代码、不同学期），**课程名不出现「（重修）」后缀**。学生端在「我的成绩」「我的选课」与选课台、教师端在课程名单、教务端在批次课程列表都会看到「重修」徽标（接口字段 `retake` / `retakeLabel` / `retakeCount`），判定只依据历史成绩（更早学期、同一课程代码、成绩已提交且有效分 < 60）。系统拒绝重选此前学期已通过的课程，也拒绝在同一学期选择不同教师的同一课程代码。
- 鲁棒性与留痕：不满足最低开课人数自动退回全部选课并写入 `AUTO_REFUND` 流水；已有成绩的课程不得发布选课、不得退课；组织与选课的增删改、成绩流转全部经统一事务写入加密审计账本。设计与实现细节见 `docs/api.md`、`docs/models.md`、`docs/security.md`、`docs/decisions.md`。

## 界面

- 左侧导航按角色呈现，管理员导航最后一项是「安全审计」；左下角身份行显示「角色 · 学院 · 专业 · 班级」（管理员只显示角色）。
- 侧栏宽度可**用鼠标拖动调节**：侧栏右边缘是拖拽手柄，宽度 68–420px（默认 216px），拖到 118px 以下显示为仅图标档（保留悬浮提示），宽度记在浏览器本地并在刷新后保持；双击手柄恢复默认。

## IDEA 与前端开发

在 IDEA 打开根 `pom.xml`，选择 JDK 17。先执行初始化与构建，再运行四个 Application 主类。各 Run Configuration 的工作目录设为项目根目录，VM options 配置见 [部署说明](docs/deployment.md)。`chain-worker` 独立运行 `npm start`。
依次启动服务（前两个就绪后再启动后面的，`business-service` 依赖数据与审计服务）：

    Chain-Worker
    Gateway
    Data-Service
    Audit-Service
    Business-Service

前端开发服务器

```bash
npm run --prefix frontend dev
```
前端调试运行 `npm --prefix frontend run dev`，访问 https://localhost:5173；请求由 Vite 代理到 8443。最终交付页面由网关直接提供，无需同时启动 Vite。

## Windows 一键启动

Windows 下推荐使用 `scripts/start.ps1`：它会写入 JVM 参数文件并以项目根为工作目录启动
chain-worker 与四个 Java 服务，日志落在 `.logs/`。直接写 `java -D...` 时 PowerShell 会
把 `-D` 参数拆坏（典型报错 `ClassNotFoundException: /encoding=UTF-8`），脚本已规避该问题。

```powershell
.\scripts\start.ps1              # 启动全部
.\scripts\start.ps1 -NoChain     # 只启动四个 Java 服务
.\scripts\start.ps1 -ResetDb     # 先整库重建再启动
```

## 验证

```text
mvn -o clean package                       # Java 单元测试（当前 250 条）
node scripts/feature-test.mjs              # 端到端功能测试（当前 201 项）
node scripts/browser-check.mjs             # 界面浏览器检查（当前 57 项，需 Vite 开发服务器）
node scripts/verify-sidebar-resize.mjs     # 侧栏拖动专项检查（28 项）
node scripts/junit-summary.mjs             # 汇总各模块 surefire 报告
npm --prefix frontend run build
node scripts/generate-docs.mjs --check     # 生成式文档与源码一致（64 个 Java 命名类型）
```

功能测试与浏览器检查都需要四个 Java 服务已启动；浏览器检查还需要
`npm --prefix frontend run dev` 在 5173 端口运行，默认使用本机 Edge，可用
`BROWSER_EXECUTABLE` 指定其他 Chromium 内核浏览器。结果分别写入
`.runtime/logs/feature-test.json`、`.runtime/logs/browser-check.json` 与
`.runtime/logs/sidebar-resize-check.json`。历史记录：第四轮 243 / 183 / 50、第三轮 230 / 164 / 44、第二轮 234 / 156 / 35，
详见 [测试报告](docs/testing.md)。

初始化的演示数据包含 4 个学院（信息工程学院 / 经济与管理学院 / 建筑工程学院 / 外国语学院）、8 个专业、21 个班级、205 个账号（3 名教务管理员 + 11 名演示教师 + 4 名不可登录的史料教师 + 187 名学生，密码统一 `passwd`）、155 个教学班（64 个正课 + 91 个历史样本教学班）、1354 条选课与 1354 条成绩，并预置一个 2026-1 的进行中选课批次（`sel-2026-1-demo`）；启动日志会打印实际条数（`[DemoInitializer] 数据已写入：…`）。

**每个教学班都有名单、有成绩**，不存在「没人选课却有成绩」的空壳课程。数据中包含：

- 「挂科 → 后续学年同一课程号重修」的学生 5 名（3 个课程代码），启动时由 `verifyTranscriptIntegrity()` 校验重修轨迹，其中 2 人正在当前学期重修、成绩为暂存，便于教师现场录入；
- 为学业预警准备的**历史样本教学班**（2020-1 / 2021-1 / 2022-1 / 2023-2 的更早年份开课）与「有平时与实验、缺期末」的缓考样本学生（暂存 `DRAFT` 成绩）。`verifyPredictionCoverage()` 逐门校验覆盖，当前 64 门有选课的正课全部可预测，因此教师与学生打开学业预警即可看到结果，不需要再补数据。

注意**预测只对进行中的课程有意义**：对已出分的历史课程调用会返回空结果，页面会引导改选当前学期（2026-1）的课程。

数据库结构版本变化时会在启动日志里说明「删除全部业务表后重建」，并同步重建独立审计账本与链锚点；需要手动重建时运行 `.\scripts\start.ps1 -ResetDb`（等价于 `-Dcampus.reset-db=true`）。**整库重建需要 1–2 分钟**（205 个账号逐个计算 BCrypt 密码哈希），请等到日志出现「数据已写入」再操作。

系统没有「删除课程 / 删除选课批次」的接口，因此端到端测试脚本留下的测试课程与测试批次由 `DemoInitializer.purgeTestArtifacts()` **在每次启动时自动清理**（早于数据写入，不会误删演示数据），日志会打印 `已清除 N 门测试课程、…`。要正式演示前重启一次服务即可。

运行集成测试需要后台已启动，依次执行，不要并发。Playwright 首次在 frontend 目录执行 `npx playwright install chromium`。测试新增的数据使用专用测试课程与账号，不对真实校园数据运行这些脚本。具体结果和未验证项见 [测试报告](docs/testing.md)。

## 文档入口

1. [需求分析与逐项追踪](docs/requirements.md)
2. [整体架构与分布式设计](docs/architecture.md)
3. [类职责与方法说明](docs/classes.md)
4. [UML、用例、状态、时序、数据库模型](docs/models.md)
5. [安全设计与应急处理](docs/security.md)
6. [运行部署与扩缩容](docs/deployment.md)
7. [环境安装与配置说明（多系统/多环境）](docs/configuration.md)
8. [API 与命名约定](docs/api.md)
9. [测试报告](docs/testing.md)
10. [开发过程与设计决策](docs/decisions.md)
11. [演示账号与场景指南](docs/演示账号与场景指南.md)
