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

首次运行时由 `DemoInitializer` 自动向数据库注入演示账号（管理员 `admin` / `jw001` / `jw002`、教师 `t1101` 等、学生 `20231530` 等，密码统一为 `passwd`）及历史成绩，并记账、上链、签署合约。所有数据落在各模块的 `.runtime/database/` 中。


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

- 教师：六项百分制成绩系数、批量暂存、整课提交和撤回、正考与补考、往年课程搜索、统计与教学分析、OCR 辅助录入、打印；课程名单里标出哪些学生本次是在重修。
- 学生：本学期与在校成绩、正考/补考并列、补考最高 60 分、本人课程排名、未通过课程统计、本人学业预测；「我的成绩」与选课页都以「重修」徽标显示重修状态（课程名不变），已获课程与学分按课程代码去重（重修不重复计学分），已重修通过的课程不再计入未通过。
- 管理员：小撤销、大撤销、人员和组织维护、按角色分配功能权限、停用账号、重置密码、课程与选课管理（含按课程批量选课/退课）、审计复核、独立原始成绩查询。
- 智能功能：均值/3σ/百分位/历史波动检测，Weka 线性回归和决策树，真实 TensorFlow.js LSTM 序列分类。学业预警预测的是「已有平时与实验、期末未录入」的学生，演示数据已备好满足门槛的历史样本与暂存成绩；预测仅临时计算，不进入正式成绩表。

## IDEA 与前端开发

在 IDEA 打开根 `pom.xml`，选择 JDK 17。先执行初始化与构建，再运行四个 Application 主类。各 Run Configuration 的工作目录设为项目根目录，VM options 配置见 [部署说明](docs/deployment.md)。`chain-worker` 独立运行 `npm start`。
依次启动服务：

    Chain-Worker
    GateWay
    Budiness-Service
    Audit-Service
    Data-Service

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

## 新增功能（组织管理与网上选课）

- 管理员可在「组织管理」按名称维护学院—专业—班级三级组织，并批量把学生调入班级；教师能查看所带课程班级的学生，学生能看到自己所属的学院、专业与班级。组织**只有一个编号**——主键 `C01001` / `M01001` / `B01001`（`层前缀 + 2 位号段 + 3 位本级序号`），它同时用于库内引用、接口寻址与页面展示：新建时由后端自动生成、确定后不可修改，组织管理表单里没有「编号」输入框，列表在名称下方只读展示该编号。班级**不设辅导员**（结构版本 3 已删除该字段），管理员**不归属任何组织**（`/me` 返回的组织名称为空，身份行只显示「管理员」）。
- 侧栏宽度可**用鼠标拖动调节**：侧栏右边缘是拖拽手柄，宽度 68–420px（默认 216px），拖到 118px 以下显示为仅图标档（保留悬浮提示），宽度记在浏览器本地并在刷新后保持；双击手柄恢复默认。原来的「折叠/展开」按钮已移除。
- 管理员的选课入口已合并为侧栏的**一个「选课管理」页面**，页内分「课程与选课 / 选课批次 / 选课记录」三个标签页：在「课程与选课」里发布课程并直接维护名单，「选课批次」负责发布、关闭、取消与最低开课人数结算，「选课记录」查询全部选课/退课/自动退回流水。原独立的「批量选课」界面已移除。
- 每门课程行上的「选课」按钮打开**按课程选课弹窗**：可选择一个、多个学生或某个班级的全部学生（并集去重），支持整批退课，提交 `POST /enrollments/batch`；每处理一名学生都会写一条选课记录（选课 `ADMIN_ASSIGN` / 退课 `ADMIN_REMOVE`），单学生代选代退的 `POST /enrollments` 也写同样的流水。
- 教务可在「选课管理 → 选课批次」发布选课信息（选课名、起止时间、选课范围、最低开课人数、是否可选可退），并在窗口结束后自动结算最低开课人数；学生仍从「网上选课」按范围自助选课与退课。
- **重修不体现在课程名上**：挂科（正考与补考都不及格）的学生在**后续学年**选择**同一课程号**的教学班重修，两次修读在系统里是同一门课（同一课程代码、不同学期），课程名保持原样。学生端在「我的选课」与选课台、教师端在课程名单、教务端在批次课程列表都会看到「重修」徽标（接口字段 `retake`/`retakeLabel`/`retakeCount`），判定只依据历史成绩（更早学期、同一课程代码、成绩已提交且有效分 < 60）。演示数据含 5 名重修学生（3 个课程代码），其中 2 人正在当前学期重修、成绩未提交，便于教师现场录入。
- 组织与选课的全部敏感操作都经统一事务写入加密审计账本；选课系统实现「已有成绩不得发布选课」「不得选不同教师的同一课程代码」「不得重选此前学期已通过的课程（挂科重修除外）」「不满足最低开课人数自动退回」等鲁棒性规则。设计与实现细节见 `docs/api.md`、`docs/models.md`、`docs/security.md` 与 `docs/decisions.md`。

## 验证

```text
mvn -o clean package                       # Java 单元测试（第五轮实测 248 条）
node scripts/feature-test.mjs              # 组织管理与网上选课端到端功能测试（第五轮 201 项）
node scripts/browser-check.mjs             # 界面浏览器检查（第五轮 57 项，需 Vite 开发服务器）
node scripts/verify-sidebar-resize.mjs     # 侧栏拖动专项检查（28 项）
node scripts/junit-summary.mjs             # 汇总各模块 surefire 报告
npm --prefix frontend run build
node scripts/generate-docs.mjs --check     # 生成式文档与源码一致（63 个 Java 命名类型）
```

功能测试与浏览器检查都需要四个 Java 服务已启动；浏览器检查还需要
`npm --prefix frontend run dev` 在 5173 端口运行，默认使用本机 Edge，可用
`BROWSER_EXECUTABLE` 指定其他 Chromium 内核浏览器。结果分别写入
`.runtime/logs/feature-test.json`、`.runtime/logs/browser-check.json` 与
`.runtime/logs/sidebar-resize-check.json`。历史记录：第四轮 243 / 183 / 50、第三轮 230 / 164 / 44、第二轮 234 / 156 / 35，
详见 [测试报告](docs/testing.md)。

初始化的演示数据包含 4 个学院（信息工程学院 / 经济与管理学院 / 建筑工程学院 / 外国语学院）、8 个专业、21 个班级、206 个账号（3 名教务管理员 + 11 名教师 + 192 名学生，密码统一 `passwd`）、155 个教学班（64 个正课 + 91 个历史样本教学班）、626 条选课与 1445 条成绩，并预置一个 2026-1 的进行中选课批次（`sel-2026-1-demo`）；启动日志会打印实际条数（`[DemoInitializer] 数据已写入：…`）。其中包含「挂科 → 后续学年同一课程号重修」的学生（启动时由 `verifyTranscriptIntegrity()` 校验重修轨迹），以及为学业预警准备的两层样本：**只写成绩、不写选课**的历史样本教学班（避免它们自己又成为需要可预测的课程，形成无限回归）与「有平时与实验、缺期末」的缓考样本学生（暂存 `DRAFT` 成绩）；`verifyPredictionCoverage()` 逐门校验覆盖，当前 64 门有选课的课程全部可预测，因此教师与学生打开学业预警即可看到预测，不需要再补数据。注意**预测只对进行中的课程有意义**：对已出分的历史课程调用会返回空结果，页面会引导改选当前学期（2026-1）的课程。数据库结构版本变化时会在启动日志里说明「删除全部业务表后重建」，并同步重建独立审计账本与链锚点；需要手动重建时运行
`.\scripts\start.ps1 -ResetDb`（等价于 `-Dcampus.reset-db=true`）。

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
