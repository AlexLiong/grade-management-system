# 知序 · 高校成绩管理系统

依据《软件开发综合能力实践（网络软件与安全）要求说明》（长安大学信息工程学院，2026 年 8 月）实现的课程实践工程。采用 Java 17、Spring Boot、Vue 3，提供独立网关、业务服务、数据访问服务、审计服务，以及本机以太坊测试链与 LSTM 工具进程。

原始 PDF 和提示文件在 [`docs/requirements/`](docs/requirements/original-prompt.md)。本项目不包含答辩 PPT、个人报告或虚构的组员信息。课程报告、类说明和模型均为 Markdown。

本文按**角色与能力**组织：教师、学生、教务/管理员、安全与合规。所有成绩录入方式（手工、成绩单图片识别、语音口述）最终都汇入同一套「暂存 → 提交 → 审计」流程，因此在教师一节里一并说明。

---

## 1. 快速运行

### 1.1 环境依赖

|依赖|版本|用途|
|---|---|---|
|JDK|17（完整 JDK，含 `keytool`）|4 个 Java 服务与 TLS 证书生成|
|Maven|3.9+|构建打包与单元测试|
|Node.js|22 LTS（Node 26 亦可运行）|chain-worker、前端构建、`scripts/*.mjs`|
|Git|任意近期版本|获取源码|

`java`、`keytool`、`mvn`、`node`、`npm` 需在 `PATH` 上。首次构建需要联网下载依赖。

### 1.2 启动步骤

```bash
# 1) 安装 Node 依赖（chain-worker 与前端；frontend 的 node-forge 还被 setup 脚本复用）
npm --prefix chain-worker install
npm --prefix frontend install

# 2) 生成 TLS 证书、信任库，以及全部认证密钥与数据库口令（幂等，已存在则复用）
node scripts/setup.mjs

# 3) 构建四个 Java 服务（首次需联网）
mvn -DskipTests clean package

# 4) 构建前端静态资源（交付页面由网关直接提供）
npm --prefix frontend run build

# 5) 启动 chain-worker + 四个 Java 服务
./scripts/start.sh          # macOS / Linux
```

```powershell
.\scripts\start.ps1         # Windows PowerShell（推荐，见 1.3）
```

启动后访问 **https://localhost:8443**（网关直接托管 `frontend/dist`；若要用 Vite 调试服务器则是 `https://127.0.0.1:5173`）。浏览器须信任 `.runtime/localhost.crt` 或对本地测试站点确认例外。正式部署必须换用组织 CA 签发的证书，不能关闭证书验证。

各步骤的开关与产物：

|命令|作用|产物|
|---|---|---|
|`npm --prefix chain-worker install` / `--prefix frontend install`|安装 Node 依赖|两个 `node_modules/`|
|`node scripts/setup.mjs`|生成 TLS 证书、信任库与 9 项密钥；已存在则复用|`.runtime/localhost.p12`、`localhost.key`、`localhost.crt`、`truststore.p12`、`secrets.json`|
|`node scripts/setup.mjs --reset`|证书与密钥**全部重新生成**；轮换后果（整库重建）见 [5.3 密钥的生成与注入](#53-密钥的生成与注入)|同上，覆盖旧文件|
|`mvn -DskipTests clean package`|编译打包|各模块 `target/*-1.0.0.jar`|
|`mvn -o test`|离线跑全部 Java 单元测试（当前 **258** 项）|各模块 `target/surefire-reports/`|
|`npm --prefix frontend run build`|构建交付页面|`frontend/dist/`|

### 1.3 Windows 一键启动

```powershell
.\scripts\start.ps1              # 启动 chain-worker + 四个 Java 服务
.\scripts\start.ps1 -NoChain     # 只启动四个 Java 服务
.\scripts\start.ps1 -ResetDb     # 先整库重建再启动
```

脚本会：读取 `.runtime/secrets.json`，把 9 项密钥**同时**作为环境变量与 `-D` 启动参数注入；把 JVM 参数写进 `.logs/jvm.args`，以参数数组直接调用 `java`（PowerShell 会把 `-D` 参数拆坏，典型报错 `ClassNotFoundException: /encoding=UTF-8`）；以**独立隐藏窗口**启动各服务，因此**脚本本身可以退出**，服务继续运行。日志落在 `.logs/`。

`secrets.json` 不存在时脚本会自动先调用 `setup.mjs`。`-ResetDb` 等价于 `-Dcampus.reset-db=true`。

启动后访问 **https://localhost:8443** 即为完整系统（网关直接托管 `frontend/dist`），**不需要**另外启动前端；只有改前端代码要热更新时才用 `npm --prefix frontend run dev`（`https://127.0.0.1:5173`）。

### 1.3.1 停止服务

```powershell
.\scripts\stop.ps1                 # 停止四个 Java 服务 + chain-worker
.\scripts\stop.ps1 -KeepChain      # 只停四个 Java 服务
.\scripts\stop.ps1 -IncludeVite    # 同时停 Vite 开发服务器（5173）
.\scripts\stop.ps1 -List           # 只列出当前在跑的本项目进程，不停止
```

```bash
./scripts/stop.sh                  # macOS / Linux；参数同名：--keep-chain / --include-vite / --list / --force / --wait 30
bash scripts/stop.sh --list        # 若脚本没有可执行位，用 bash 显式调用
```

停止脚本**按命令行特征匹配**（四个 jar 名与 chain-worker 的 `server.mjs`），不会误杀 IDE 里跑的其它 Java 程序；停止后逐个探测 8443 / 9441 / 9442 / 9443 / 9545 / 5173 并报告是否已释放。`.runtime` 下的加密数据库、账本、链数据与密钥都会保留，重新 `start` 即可继续。

> `start.sh` 以前台方式运行并用 `trap` 管理子进程，因此在同一终端按 **Ctrl+C** 即可全部停止；`stop.sh` 用于终端被关闭、Shell 被强杀，或想在另一个终端停服务的场景。

### 1.4 IDEA 直接运行主类

在 IDEA 打开根 `pom.xml`，Project SDK 选 JDK 17。先执行 `setup.mjs` 与 `package`，再为四个主类各建一个 Application 运行配置：

```text
chain-worker        npm start（工作目录 chain-worker/）
GatewayApplication
DataApplication
AuditApplication
BusinessApplication
```

- 每个运行配置的 **Working directory 设为项目根目录**，否则找不到 `.runtime/localhost.p12` 与 H2 数据库文件。
- **密钥不需要手工填**：`ConfigEnvironmentPostProcessor`（注册在 `common/src/main/resources/META-INF/spring.factories`）在配置绑定之前把密钥与数据源凭据注入 Environment，环境变量 / `-D` 优先、`.runtime/secrets.json` 兜底，因此直接跑主类即可启动。
- 可选 VM options（用于服务间 HTTPS 调用与运行目录）：

```text
-Dfile.encoding=UTF-8
-Dcampus.runtime=项目绝对路径/.runtime
-Djavax.net.ssl.trustStore=项目绝对路径/.runtime/truststore.p12
```

信任库口令会自动从 `secrets.json` 的 `TLS_PASSWORD` 取值（`RpcClient` 读密钥表，不再有硬编码口令）。四项端口、监听地址与其它配置项的完整清单见 [环境安装与配置说明](docs/configuration.md)。

---

## 2. 教师

### 2.1 成绩录入：三种录入方式，一套流程

一门课的成绩可以**手工录入**、可以**上传成绩单图片识别**、可以**语音口述**。三种方式的产物都只是录入表单里的草稿：都要先点「暂存」（`POST /grades/save`）落库为 `DRAFT`，再由「提交」（`POST /grades/transition`，`action=SUBMIT`）整门课公开；识别与语音都**没有「导入即提交」的旁路**。

|录入方式|操作|落库时机|
|---|---|---|
|手工|在成绩表逐格输入平时 / 实验 / 期末等分项，失焦即写入表单草稿|点「暂存」|
|成绩单图片识别|点「识别成绩单」选择图片（PNG/JPEG/WebP，≤10 MB）→ 浏览器内识别 → 预览界面逐格核对、改列映射、勾选行 → 点「确认填入」|点「暂存」|
|语音录入|点「语音录入」（或成绩表某行的麦克风按钮）→ 口述或直接输入口述文本 → 解析结果按行填入 → 点「填入当前行」|点「暂存」|

三者的共同规则：

- **结果必须人工确认**。识别结果先进入预览组件（`components/RecognizePreview.vue`），只改本地副本，点「确认填入」才写进 `App.vue` 的录入表单；语音面板同理。填入后只置 `dirty = true` 并提示「已填入 N 人成绩，待暂存」。
- **走同一个 `score()`**。确认填入与手工敲键是同一条代码路径，因此校验、权重计算与暂存范围完全一致。
- **不新增接口、不新增权限点**。识别与解析全部在浏览器内完成（`frontend/src/ocr.js`、`frontend/src/voice.js`），后端接口一个都没有新增；入口按钮与「暂存」按钮共用 `isTeacher && can('ENTRY') && !submitted` 这一个条件，成绩全部提交后按钮不再渲染。
- **图片不上传**。图片只经 `URL.createObjectURL` 在本页 `Image` + `Canvas` 读取，用完在 `finally` 里 `revokeObjectURL`；tesseract.js 的 worker、WASM 核心与 `eng` 语言包是本站静态资源（`frontend/public/ocr/`），由浏览器直接 GET。`scripts/ocr-voice-check.mjs` 统计「POST 且请求体含 PNG 字节」的请求数，实测为 **0**。
- **学号不猜人**。OCR 给出的学号只有在「归一化后精确命中名册」或「差异能被已知字形混淆（`O→0`、`l→1`、`B→8`、`S→5` 等）解释」时才自动填入；`9→0`、`5→4` 这类纯数字位差异默认不认人，只提示「学号与名册有差异但无法确认，请人工核对」。中文姓名不作为匹配依据。
- **语音只解析分数，不承担切行**。中文数字支持 0–999（含 `两`/`零`/`〇`）、小数（`八十五点五`、`85点5`）与混合写法；**具名项可覆盖已有分数，裸数字只补空位**，用不掉的数字会明确提示「这些数字没有可用空位」。切换录入对象走界面控件：「录入对象」下拉框、上一行 / 下一行按钮（夹取边界、不循环）、成绩表每行的麦克风按钮。
- **语音通道是唯一的数据出站口**：麦克风的音频由浏览器厂商的 `SpeechRecognition` 处理（可能上行到厂商服务），因此界面要求逐次点击「开始识别」才开麦，不会自动录音。文本框通道完全本地。

### 2.2 权重、提交与撤销

- 六项百分制系数，总和必须为 100；已提交成绩后不可修改系数，需先撤销。
- 以**整门教学班**为提交 / 撤销单位，避免半个班级成绩公开；提交前必须全部选课学生都有完整的带权成绩。
- 教师可撤回自己提交的成绩（`WITHDRAW`，回到 `DRAFT`）；跨教师不可见，教师只能看到自己归属的课程。
- 正考不足 60 分才可录入补考，最终有效分取正考与封顶 60 后的补考的较大值（`Models.effective`）。

### 2.3 教学名单、统计与教学分析

- 课程名单标出哪些学生本次属于**重修**（`retake` / `retakeLabel`），判定只依据历史成绩（更早学期、同一课程代码、成绩已提交且有效分 < 60），课程名不变。
- 统计：均值、分段、异常提醒；教学分析文本可保存与展示。
- 学业预警：为「已有平时与实验、期末未录入」的学生生成预测，预测只临时计算、不进入正式成绩表。
- 打印：成绩表提供打印样式（`打印` 按钮）。

### 2.4 往年课程检索

教师可按学期与课程名搜索自己历年的教学班（`GET /courses` 分页筛选），用于对照历史成绩与历史样本。

---

## 3. 学生

- **学业记录**：「我的成绩」按**学期倒序**列出全部已提交成绩，包含课程代码、课程名、学期、学分、总评、有效分、班内排名与是否通过；正考 / 补考并列展示（补考最高 60 分）。
- **重修标注**：同一课程代码出现第二次即为重修，成绩单上以徽标标注，**课程名保持原样**（不出现「（重修）」后缀）。
- **统计口径按课程代码去重**：已获课程数、已获学分只取通过的那一次（重修不重复计学分），已重修通过的课程不再计入未通过。
- **学业预测**：对自己进行中的课程生成预测（只本人可见，管理员无权获取个人预测分）。
- **网上选课**：在已发布且处于窗口内的批次里自助选课与退课；选课台与「我的选课」同样带重修徽标；系统拒绝重选此前学期已通过的课程，也拒绝在同一学期选择不同教师的同一课程代码。

---

## 4. 教务 / 管理员

### 4.1 成绩与审计

- **小撤销**：单个成绩退回暂存，保留成绩与版本。
- **大撤销**：删除全部业务成绩行（需输入课程编号与原因二次确认），独立审计账本保留前后快照。
- **安全审计页**：读取独立加密账本（`GET /audit`），执行成绩核查（`GET /integrity`），复核异常事件，查看待同步审计数量，并可运行 LSTM 日志检测。
- 管理员可读到维护前后的原始分数，但系统不会自动覆盖数据库——避免把调查变成新的无授权修改。

### 4.2 人员与权限

- 新增 / 修改人员、按角色分配功能权限、停用账号、重置密码；角色建立后不允许直接转换，以保持成绩、授课与审计引用稳定。
- 权限变更会删除该账号的全部会话，旧会话立即失效。
- 管理员是全局角色，**不归属任何学院 / 专业 / 班级**（三级字段为空是合法状态）。

### 4.3 组织管理

按名称维护**学院—专业—班级**三级组织，可批量把学生调入班级：

- 组织**只有一个编号**——主键 `C01001` / `M01001` / `B01001`（`层前缀 + 2 位号段 + 3 位本级序号`），同时用于库内引用、接口寻址与页面展示；新建时由后端自动生成、确定后不可修改，表单里没有「编号」输入框，列表在名称下方只读展示。
- 班级**不设辅导员**；组织维护用启用 / 停用表示「不再招生」。
- 删除前可查看影响面：仍有下级需 `force` 二次确认，仍有课程或仍被账号引用则始终 409 拒绝。

### 4.4 选课管理

分「课程与选课 / 选课批次 / 选课记录」三个标签页：

- **课程与选课**：发布课程并直接维护名单（成绩未录入的课才允许开选）。新建/编辑课程的对话框要填课程名称、代码、学年学期、学分、授课教师与**开设院系**；开设院系默认按授课教师所在院系自动带出，管理员可自行改选（人工改过之后不再被教师覆盖）。课程必须归属一个开设院系——它决定选课范围与统计口径，缺失会被后端以「必须指定开设院系」拒绝。
- **选课批次**：发布、关闭、取消批次，并在窗口结束后结算最低开课人数。
- **选课记录**：查询全部选课 / 退课 / 自动退回流水。
- 每门课程行上的「选课」按钮打开**按课程选课弹窗**：可选择一名、多名学生或某个班级的全部学生（并集去重），也支持整批退课；每处理一名学生都写一条选课记录（代选 `ADMIN_ASSIGN` / 代退 `ADMIN_REMOVE`），已有成绩的学生会被拒绝并单独列出。

### 4.5 智能分析与教务留痕

- 统计检测：均值 / 3σ / 百分位 / 历史波动检测；Weka 线性回归与决策树。
- 真实 TensorFlow.js LSTM 序列分类用于操作序列风险检测（结果需人工复核，无自动处分）。
- 组织与选课的增删改、成绩流转全部经统一事务写入加密审计账本；不满足最低开课人数时自动退回全部选课并写入 `AUTO_REFUND` 流水。

---

## 5. 安全与合规

### 5.1 加密与凭据总览

|层次|做法|
|---|---|
|传输|所有进程（含本机内部调用）走 HTTPS，校验服务器证书；正式部署只公开网关门 |
|服务间调用|独立调用者密钥 + HMAC-SHA256 + 30 秒时间窗 + Nonce 去重|
|成绩字段|AES-GCM，AAD 绑定 `id\|course_id\|student_id\|state\|version`，改状态必须重新加密|
|审计发件箱|同库加密；审计服务再用独立密钥加密快照|
|独立账本|AES-GCM 密文 + 前序哈希 + HMAC + EVM 交易锚定|
|**数据库静态加密**|H2 整库加密（`CIPHER=AES`）：整个库文件为密文，见 5.2|
|登录口令|BCrypt cost 12；失败 5 次锁定 5 分钟|

### 5.2 数据库整库加密

数据服务默认使用 H2 文件库并**开启整库加密**：

```text
jdbc:h2:file:./.runtime/database/campus;CIPHER=AES;AUTO_SERVER=TRUE
```

- 库文件头为 `H2encrypt`（明文库是 `H:2,block:...`）。工程实测：在该文件里检索 `password`、`$2a$`、`t1101`、`CS401`、`20241530`、`course_selections` 等字符串，命中数**全部为 0**。
- H2 在 `CIPHER=AES` 下的会话口令是**两段式**：「文件口令 + 空格 + 用户口令」。这两段都由 `setup.mjs` 随机生成，用户不需要也无法手输。少给一段会报 `Wrong password format, must be: file password <space> user password [90050-224]`；文件口令错会报 `Encryption error in file ... [90049-224]`。
- **数据库口令与用户登录密码无关**：加密口令是文件级 / 进程级的，演示账号的登录密码仍然是 `passwd`，**没有变化**。
- 启动时 `DatabaseBootstrap.prepare()`（四个主类的 `main` 第一行）会检查旧的**明文库**并按文件头识别清理：加密库 `H2encrypt`、明文库 `H2:`，命中明文库就删除并以加密库重建（演示数据由初始化器重新灌入）。

### 5.3 密钥的生成与注入

全部 9 项密钥与口令由 `scripts/setup.mjs` 用 `crypto.randomBytes(32)` 生成（64 位十六进制），写入 `.runtime/secrets.json`（0600；Windows 上用 NTFS ACL 限制服务账户可读；该目录已被 `.gitignore` 忽略，不进版本库）：

|密钥|用途|
|---|---|
|`GATEWAY_KEY`|网关内部调用签名|
|`BUSINESS_KEY`|业务服务签名|
|`DATA_KEY`|数据服务签名 + 成绩字段加密|
|`AUDIT_KEY`|审计服务签名；chain-worker 校验请求来源|
|`LEDGER_KEY`|账本密钥；其 SHA-256 派生 Ganache 测试链账户种子|
|`AUDIT_DATA_KEY`|审计数据加密|
|`TLS_PASSWORD`|`localhost.p12` 与 `truststore.p12` 口令|
|`DB_CIPHER_KEY`|H2 整库加密的**文件口令**|
|`DB_PASSWORD`|H2 整库加密的**用户口令**|

**注入优先级：环境变量 > `-D` 启动参数 > `.runtime/secrets.json`。** 具体路径有三条：

1. `scripts/start.ps1` / `scripts/start.sh`：读 `secrets.json`，把 9 项**同时**导出为环境变量与 `-D` 启动参数。两段式口令 `-DDB_PASSWORD` 在 `.logs/jvm.args` 里**必须加引号**——JVM 的 argfile 解析器按空白拆分，不加引号会变成两个参数（第二个被当作主类，报 `ClassNotFoundException`）。脚本本身以独立隐藏窗口启动服务，可以退出。
2. IDEA 直接跑主类：`ConfigEnvironmentPostProcessor` 在配置绑定之前把密钥与数据源凭据注入 Environment，`.runtime/secrets.json` 兜底，因此无需手工填密钥。
3. `chain-worker`：`LEDGER_KEY` / `AUDIT_KEY` 从环境变量或 `secrets.json` 读取；缺失、长度不足 16 或仍是占位值（`KEY` / `passwd` / `changeme`）就**直接退出**。

密钥文件路径可用 `CAMPUS_SECRETS` / `-Dcampus.secrets` 覆盖（默认 `.runtime/secrets.json`）。

### 5.4 生产档拒绝启动与密钥轮换

- **生产档校验**：设置 `CAMPUS_PROFILE=prod` 或 `-Dcampus.profile=prod` 后，`ConfigGuard` 不再兜底生成。缺密钥、仍是占位值（`KEY` / `passwd` / `password` / `changeme` / `campus-dev-tls-2024`）或长度 < 16，都会**拒绝启动**并打印缺失清单与处理方式。开发档下缺密钥会就地生成并落盘，保证「clone 下来直接跑」。**占位值与长度判据只在生产档生效**：开发档若手工注入了短值会被原样采用，所以手工注入示例一律用 64 位十六进制。
- **唯一保留字面量的口令**是各服务 `application.yml` 里的 `server.ssl.key-store-password: ${TLS_PASSWORD:campus-dev-tls-2024}`——它只是本机演示证书的开发兜底值，`TLS_PASSWORD` 一旦缺失且非生产档才会用到，且该值在生产档会被判为占位值（见 [配置说明 3.2](docs/configuration.md#2-配置加载机制)）。
- **密钥轮换的后果是整库重建**：`schema_meta.version_value` 存的是「结构版本 `:` 密钥指纹」，指纹不一致（换了密钥）时启动即整库重建，避免运行到读某一行时才报完整性失败。轮换前必须备份 `.runtime/database/` 并按部署文档的备份恢复流程操作。
- **启动顺序**：四个 Java 服务的 `main` 第一行都是 `DatabaseBootstrap.prepare()`（加载密钥 → 注入系统属性 → 检查明文库），之后才 `SpringApplication.run`。推荐顺序为 chain-worker → gateway → data-service → audit-service → business-service。

### 5.5 合规边界

- 生产必须使用数据库权限隔离、服务账号隔离、外部密钥管理（KMS / Vault 或秘密挂载）与组织 CA 签发的证书；本机统一 `.runtime` 是教学便利措施，不是生产秘密隔离方案。
- `POST /reset`（清空账本）与整库重建只适用于本机演示与教学环境，生产不得暴露或开启。
- 未实现 mTLS、MFA、多管理员双签；Ganache 是单机测试链，不能描述成不可被本机管理员重写的公有链。
- 完整的威胁表、判定规则与应急流程见 [安全设计与应急处理](docs/security.md)。

---

## 6. 界面

- 左侧导航按角色呈现，管理员导航顺序固定，最后一项是「安全审计」；左下角身份行显示「角色 · 学院 · 专业 · 班级」（管理员只显示角色，空值逐项过滤）。
- 侧栏宽度可用鼠标**拖动调节**：右边缘是拖拽手柄，宽度 68–420 px（默认 216 px），拖到 118 px 以下显示为仅图标档（保留悬浮提示）；宽度记在 `localStorage.campus.sidebarWidth` 并在刷新后保持，双击手柄恢复默认。
- 成绩表、课程表在窄屏下横向滚动，页面本身不出现横向溢出。

前端调试：

```bash
npm run --prefix frontend dev    # https://127.0.0.1:5173，/api 代理到 8443
```

最终交付页面由网关直接提供 `frontend/dist`，无需同时启动 Vite。

---

## 7. 工程目录

|目录|职责|
|---|---|
|`common`|值传递协议、远程接口、安全签名、加密、密钥与数据源凭据引导（`ConfigGuard` / `DatabaseBootstrap` / `DbCredentials` / `ConfigEnvironmentPostProcessor`）、注册心跳|
|`gateway`|HTTPS 统一入口、服务租约注册和轮询发现、静态页面|
|`business-service`|认证、权限、课程成绩流程、统计和预测；不依赖 JDBC|
|`data-service`|SQL 编译、参数绑定、事务、版本检查、成绩加密、审计发件箱、库结构与整库加密装配|
|`audit-service`|独立加密账本、哈希链、EVM 锚定与原始证据|
|`chain-worker`|Ganache 本机 EVM、交易回执校验、TensorFlow.js LSTM|
|`frontend`|Vue 操作界面、浏览器本地 OCR 与语音解析、打印与浏览器测试|
|`scripts`|跨平台初始化、启停、集成与浏览器测试、文档生成|
|`docs`|需求、架构、安全、类说明、部署、测试和模型|

---

## 8. 初始演示数据

首次运行（或结构版本 / 密钥指纹变化、显式 `-ResetDb`、业务表为空）时，`DemoInitializer` 自动灌入演示数据：

- **4 个学院 / 8 个专业 / 21 个班级 / 205 个账号 / 155 个教学班 / 1354 条选课 / 1354 条成绩**，并预置一个 2026-1 的进行中选课批次 `sel-2026-1-demo`。
- 账号构成：3 名教务管理员（`admin` / `jw001` / `jw002`，不归属任何组织）、11 名演示教师（`t1101` 等）、4 名不可登录的史料教师（`ht2020`–`ht2023`，`enabled=0`）、187 名学生。**密码统一为 `passwd`**。
- **每个教学班都有名单、有成绩**，不存在「没人选课却有成绩」的空壳课程；5 名「挂科 → 后续学年同一课程号重修」的学生、为学业预警准备的历史样本教学班与缓考样本学生均已备好，启动时由 `verifyOrganizationIntegrity()`、`verifyTranscriptIntegrity()`、`verifyPredictionCoverage()` 逐项自检，不通过即中止启动。
- 启动日志会打印实际条数与自检结果（`[DemoInitializer] 数据已写入：…`）。**整库重建需要 1–2 分钟**（205 个账号逐个计算 BCrypt 密码哈希），请等到日志出现「数据已写入」再操作。
- 要演示具体功能时用哪个账号，见 [演示账号与场景指南](docs/演示账号与场景指南.md)。

**测试残留会自动清理**：系统没有「删除课程 / 删除选课批次」的接口，端到端脚本留下的测试课程与测试批次由 `DemoInitializer.purgeTestArtifacts()` 在**每次启动时**自动清理（早于数据写入，不会误删演示数据），日志形如 `已清除 N 门测试课程、…`。因此**跑完测试重启一次服务即可**，不必 `-ResetDb`。

---

## 9. 验证

```text
mvn -o test                                # Java 单元测试（当前 258 项，6 个模块）
node scripts/junit-summary.mjs             # 汇总各模块 surefire 报告
node scripts/feature-test.mjs              # 端到端功能测试（当前 201 项）
node scripts/browser-check.mjs             # 界面浏览器检查（当前 57 项，需 Vite 开发服务器）
node scripts/verify-sidebar-resize.mjs     # 侧栏拖动专项检查（28 项）
node scripts/ocr-voice-unit.mjs            # OCR/语音纯函数单元测试（76 项）
node scripts/ocr-voice-check.mjs           # OCR/语音浏览器端到端 + 逐图准确率（40 项）
node scripts/generate-ocr-fixtures.mjs     # 生成 OCR 固定测试图集（10 张）
node scripts/generate-docs.mjs --check     # 生成式文档与源码一致（当前 69 个 Java 命名类型；见 docs/testing.md 的说明）
npm --prefix frontend run build            # 构建交付页面
```

- 端到端与浏览器检查需要四个 Java 服务已启动；浏览器检查还需要 `npm --prefix frontend run dev` 运行在 5173 端口，默认使用本机 Edge，可用 `BROWSER_EXECUTABLE` 指定其他 Chromium 内核浏览器。
- 结果写入 `.runtime/logs/`：`junit-summary.json`、`feature-test.json`、`browser-check.json`、`sidebar-resize-check.json`、`ocr-voice-unit.json`、`ocr-voice-check.json`。
- 当前实测：Java 单元测试 **258 / 0 失败**（common 22、gateway 10、data 25、business 195、audit 6）；OCR/语音单元 76 / 76、OCR/语音浏览器 40 / 40、既有浏览器回归 57 / 57、端到端功能 201 / 201。逐项证据与历史轮次对照见 [测试报告](docs/testing.md)。

---

## 10. 文档入口

### 系统与实现

1. [需求分析与逐项追踪](docs/requirements.md) — 需求解释、功能矩阵 F01–F57、验收项 A01–A16 与安全需求
2. [整体架构与分布式设计](docs/architecture.md) — 运行组件、加密与密钥注入位置、服务启动顺序
3. [类职责与方法说明](docs/classes.md) — 各 Java 类型的职责与方法
4. [UML、用例、状态、时序、数据库模型](docs/models.md) — 图与代码类型一一对应

### 安全、部署与运维

5. [安全设计与应急处理](docs/security.md) — 威胁表、密钥生命周期、数据库静态加密、判定规则与应急流程
6. [运行部署与扩缩容](docs/deployment.md) — 端口、工作目录、数据库切换、备份与恢复、故障排查
7. [环境安装与配置说明](docs/configuration.md) — 密钥与整库加密、配置项清单、IDEA / 测试 / 生产环境

### 接口与前端能力

8. [API 与命名约定](docs/api.md) — Web 接口、内部 RPC、签名协议与错误语义
9. [成绩录入辅助（图片识别 / 语音录入）设计](docs/ocr-voice-design.md) — 本地识别的管线、参数、逐图准确率与已知边界（成绩录入的两种辅助方式，见本文第 2.1 节）

### 过程与验证

10. [测试报告与验证证据](docs/testing.md) — 各轮实测数字、证据文件与复现顺序
11. [开发过程与设计决策](docs/decisions.md) — 取舍记录与备选方案
12. [演示账号与场景指南](docs/演示账号与场景指南.md) — 演示用哪个账号、按场景的点击路径
13. [类图与关系图](docs/class-diagrams.md) — Mermaid 类图与关系
14. [第三方组件与许可](docs/third-party.md) — 依赖清单、版本与许可

### 原始需求

15. [原始需求提取稿](docs/requirements/original-requirements.md) — 按 PDF 物理页码提取的 22 页原文
16. [原始任务提示](docs/requirements/original-prompt.md) — 用户补充要求原文

> `docs/requirements/original-requirements.pdf` 是原始 PDF 本体，参考架构图应查看 PDF 第 4 页，文字提取不替代原图。
